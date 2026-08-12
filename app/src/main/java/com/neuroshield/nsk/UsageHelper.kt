package com.neuroshield.nsk

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ln

data class AppUsageEvent(
    val appName: String,
    val durationSeconds: Int,
    val occurredAt: String,
    val eventType: String,
)

/**
 * Métricas derivadas que se calculan en el propio dispositivo a partir del
 * flujo de eventos del sistema. Son deterministas: los mismos eventos producen
 * siempre los mismos números.
 */
data class UsageMetrics(
    /** Cambios de app: base de la fragmentación de atención. */
    val appSwitches: Int,
    /** Nº de apps distintas usadas: diversidad de actividad. */
    val distinctApps: Int,
    /** Duración media de sesión en segundos. */
    val avgSessionSeconds: Int,
    /** Sesión más larga en segundos. */
    val longestSessionSeconds: Int,
    /** Minutos de uso entre las 23:00 y las 06:00 (hora local). */
    val nightMinutes: Int,
    /**
     * Entropía de Shannon normalizada sobre el reparto de tiempo entre apps.
     * 0 = todo el tiempo en una sola app; 1 = tiempo repartido por igual.
     * Valores altos con sesiones cortas indican fragmentación atencional.
     */
    val sessionEntropy: Double,
    /** Cambios de app por minuto de uso: indicador directo de fragmentación. */
    val switchesPerMinute: Double,
)

object UsageHelper {

    private val iso: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private fun isNight(ms: Long): Boolean {
        val h = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)
        return h >= 23 || h < 6
    }

    /**
     * Extrae las sesiones de app cerradas dentro de la ventana indicada.
     * Una sesión es un par primer-plano → segundo-plano del mismo paquete.
     */
    fun getEventsSince(ctx: Context, sinceMs: Long): List<AppUsageEvent> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = mutableListOf<AppUsageEvent>()
        val starts = mutableMapOf<String, Long>()
        val ue = usm.queryEvents(sinceMs, now)
        val e = UsageEvents.Event()

        while (ue.hasNextEvent()) {
            ue.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> starts[pkg] = e.timeStamp
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val start = starts.remove(pkg) ?: continue
                    val dur = ((e.timeStamp - start) / 1000).toInt()
                    if (dur > 0) {
                        events.add(
                            AppUsageEvent(
                                appName = pkg,
                                durationSeconds = dur,
                                occurredAt = iso.format(Date(start)),
                                eventType = "session",
                            )
                        )
                    }
                }
            }
        }
        return events
    }

    /**
     * Calcula las métricas derivadas recorriendo el flujo de eventos crudo.
     *
     * Esto es lo que la versión web nunca pudo hacer: el navegador solo veía
     * su propia pestaña, mientras que aquí vemos la secuencia completa de apps
     * del dispositivo con marca de tiempo exacta.
     */
    fun computeMetrics(ctx: Context, sinceMs: Long): UsageMetrics {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val ue = usm.queryEvents(sinceMs, now)
        val e = UsageEvents.Event()

        val starts = mutableMapOf<String, Long>()
        val perAppSeconds = mutableMapOf<String, Long>()
        val sessionDurations = mutableListOf<Int>()
        var appSwitches = 0
        var nightSeconds = 0L
        var lastForegroundPkg: String? = null

        while (ue.hasNextEvent()) {
            ue.getNextEvent(e)
            val pkg = e.packageName ?: continue

            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    starts[pkg] = e.timeStamp
                    // Un cambio de app se cuenta cuando el paquete en primer
                    // plano es distinto del anterior.
                    if (lastForegroundPkg != null && lastForegroundPkg != pkg) appSwitches++
                    lastForegroundPkg = pkg
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val start = starts.remove(pkg) ?: continue
                    val durSec = (e.timeStamp - start) / 1000
                    if (durSec <= 0) continue

                    perAppSeconds[pkg] = (perAppSeconds[pkg] ?: 0L) + durSec
                    sessionDurations.add(durSec.toInt())
                    if (isNight(start)) nightSeconds += durSec
                }
            }
        }

        val totalSeconds = perAppSeconds.values.sum()
        val totalMinutes = totalSeconds / 60.0

        // Entropía de Shannon normalizada sobre el reparto de tiempo por app
        val entropy: Double = if (totalSeconds > 0 && perAppSeconds.size > 1) {
            val h = perAppSeconds.values.sumOf { sec ->
                val p = sec.toDouble() / totalSeconds
                if (p > 0) -p * ln(p) else 0.0
            }
            h / ln(perAppSeconds.size.toDouble())
        } else 0.0

        return UsageMetrics(
            appSwitches = appSwitches,
            distinctApps = perAppSeconds.size,
            avgSessionSeconds = if (sessionDurations.isNotEmpty()) sessionDurations.average().toInt() else 0,
            longestSessionSeconds = sessionDurations.maxOrNull() ?: 0,
            nightMinutes = (nightSeconds / 60).toInt(),
            sessionEntropy = if (entropy.isNaN()) 0.0 else entropy,
            switchesPerMinute = if (totalMinutes > 0) appSwitches / totalMinutes else 0.0,
        )
    }
}
