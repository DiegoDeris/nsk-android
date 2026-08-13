package com.neuroshield.nsk

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Almacén local de señales conductuales acumuladas en el dispositivo.
 *
 * Todo se cuenta en el propio móvil y solo se sube un resumen. Esto evita
 * enviar un evento por cada desbloqueo (gasto de batería y de datos) y permite
 * que el análisis funcione aunque el dispositivo esté horas sin conexión.
 *
 * Los contadores se resetean solos al cambiar el día natural.
 */
object SignalStore {

    private const val PREFS = "nsk_signals"

    // Claves de contadores diarios
    private const val K_DAY = "day"
    private const val K_UNLOCKS = "unlocks"                 // desbloqueos (USER_PRESENT)
    private const val K_SCREEN_ONS = "screen_ons"           // encendidos de pantalla
    private const val K_DARK_UNLOCKS = "dark_unlocks"       // desbloqueos con luz ambiental baja
    private const val K_NIGHT_UNLOCKS = "night_unlocks"     // desbloqueos entre 23:00 y 06:00
    private const val K_LIGHT_SUM = "light_sum"             // suma de lecturas de lux
    private const val K_LIGHT_N = "light_n"                 // nº de lecturas de lux
    private const val K_FIRST_USE = "first_use_ms"          // primer uso del día
    private const val K_LAST_USE = "last_use_ms"            // último uso del día
    private const val K_LAST_SCREEN_OFF = "last_screen_off" // para calcular ventana de sueño
    private const val K_LONGEST_GAP = "longest_gap_min"     // mayor intervalo sin uso → proxy de sueño

    // Señales de notificaciones (fase 2: requiere permiso adicional)
    private const val K_NOTIF_TOTAL = "notif_total"
    private const val K_NOTIF_SOCIAL = "notif_social"
    private const val K_RESP_SUM_MS = "resp_sum_ms"          // suma de latencias
    private const val K_RESP_N = "resp_n"                    // nº de respuestas medidas
    private const val K_RESP_UNDER_30 = "resp_under_30"      // respuestas en menos de 30 s
    private const val K_LAST_NOTIF_MS = "last_notif_ms"      // para detectar desbloqueo espontáneo
    private const val K_PHANTOM_PICKUPS = "phantom_pickups"  // desbloqueos sin notificación previa
    private const val K_NOTIF_ACTIVE = "notif_listener_active"

    /** Ventana tras una notificación en la que un desbloqueo se considera provocado por ella. */
    private const val NOTIF_ATTRIBUTION_WINDOW_MS = 3 * 60 * 1000L

    /** Umbral de lux por debajo del cual consideramos "a oscuras" (habitación con luz apagada). */
    private const val DARK_LUX_THRESHOLD = 12f

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Resetea los contadores si ha cambiado el día natural. */
    private fun rollover(ctx: Context) {
        val p = prefs(ctx)
        val stored = p.getString(K_DAY, null)
        val now = today()
        if (stored != now) {
            p.edit().clear().putString(K_DAY, now).apply()
        }
    }

    private fun isNightHour(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 23 || h < 6
    }

    // ── Registro de eventos ───────────────────────────────────────────────────

    /** Pantalla encendida (todavía puede estar bloqueada). */
    fun recordScreenOn(ctx: Context) {
        rollover(ctx)
        val p = prefs(ctx)
        p.edit().putInt(K_SCREEN_ONS, p.getInt(K_SCREEN_ONS, 0) + 1).apply()
    }

    /** Desbloqueo real del dispositivo: el niño va a usarlo. */
    fun recordUnlock(ctx: Context, lux: Float?) {
        rollover(ctx)
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val e = p.edit()

        e.putInt(K_UNLOCKS, p.getInt(K_UNLOCKS, 0) + 1)

        if (isNightHour()) {
            e.putInt(K_NIGHT_UNLOCKS, p.getInt(K_NIGHT_UNLOCKS, 0) + 1)
        }

        if (lux != null) {
            e.putFloat(K_LIGHT_SUM, p.getFloat(K_LIGHT_SUM, 0f) + lux)
            e.putInt(K_LIGHT_N, p.getInt(K_LIGHT_N, 0) + 1)
            if (lux <= DARK_LUX_THRESHOLD) {
                e.putInt(K_DARK_UNLOCKS, p.getInt(K_DARK_UNLOCKS, 0) + 1)
            }
        }

        if (p.getLong(K_FIRST_USE, 0L) == 0L) e.putLong(K_FIRST_USE, now)
        e.putLong(K_LAST_USE, now)

        // Desbloqueo espontáneo: coger el móvil sin que haya llegado nada.
        // Es la medida más limpia de compulsión, porque no hay estímulo externo.
        val lastNotif = p.getLong(K_LAST_NOTIF_MS, 0L)
        val provoked = lastNotif > 0L && (now - lastNotif) <= NOTIF_ATTRIBUTION_WINDOW_MS
        if (!provoked && p.getBoolean(K_NOTIF_ACTIVE, false)) {
            e.putInt(K_PHANTOM_PICKUPS, p.getInt(K_PHANTOM_PICKUPS, 0) + 1)
        }

        // Intervalo desde el último apagado de pantalla → candidato a ventana de sueño
        val lastOff = p.getLong(K_LAST_SCREEN_OFF, 0L)
        if (lastOff > 0L) {
            val gapMin = ((now - lastOff) / 60000L).toInt()
            if (gapMin > p.getInt(K_LONGEST_GAP, 0)) e.putInt(K_LONGEST_GAP, gapMin)
        }

        e.apply()
    }

    /** Pantalla apagada: marca el inicio de un posible periodo de inactividad. */
    fun recordScreenOff(ctx: Context) {
        rollover(ctx)
        prefs(ctx).edit().putLong(K_LAST_SCREEN_OFF, System.currentTimeMillis()).apply()
    }

    // ── Notificaciones (fase 2) ───────────────────────────────────────────────

    fun recordNotificationPosted(ctx: Context, social: Boolean) {
        rollover(ctx)
        val p = prefs(ctx)
        val e = p.edit()
        e.putInt(K_NOTIF_TOTAL, p.getInt(K_NOTIF_TOTAL, 0) + 1)
        if (social) {
            e.putInt(K_NOTIF_SOCIAL, p.getInt(K_NOTIF_SOCIAL, 0) + 1)
            e.putLong(K_LAST_NOTIF_MS, System.currentTimeMillis())
        }
        e.apply()
    }

    fun recordNotificationResponse(ctx: Context, latencyMs: Long) {
        rollover(ctx)
        val p = prefs(ctx)
        val e = p.edit()
        e.putLong(K_RESP_SUM_MS, p.getLong(K_RESP_SUM_MS, 0L) + latencyMs)
        e.putInt(K_RESP_N, p.getInt(K_RESP_N, 0) + 1)
        if (latencyMs <= 30_000L) {
            e.putInt(K_RESP_UNDER_30, p.getInt(K_RESP_UNDER_30, 0) + 1)
        }
        e.apply()
    }

    fun setNotificationListenerActive(ctx: Context, active: Boolean) {
        prefs(ctx).edit().putBoolean(K_NOTIF_ACTIVE, active).apply()
    }

    // ── Lectura ───────────────────────────────────────────────────────────────

    data class DailySignals(
        val day: String,
        val unlocks: Int,
        val screenOns: Int,
        val darkUnlocks: Int,
        val nightUnlocks: Int,
        val avgLux: Float?,
        val firstUseMs: Long,
        val lastUseMs: Long,
        val longestGapMinutes: Int,
        // Fase 2
        val notificationsTotal: Int,
        val notificationsSocial: Int,
        /** Segundos medios hasta abrir una notificación social. */
        val avgResponseSeconds: Double?,
        /** Proporción 0-1 de respuestas en menos de 30 s. */
        val fastResponseRatio: Double?,
        /** Desbloqueos sin notificación previa: compulsión pura. */
        val phantomPickups: Int,
        val notificationListenerActive: Boolean,
    )

    fun read(ctx: Context): DailySignals {
        rollover(ctx)
        val p = prefs(ctx)
        val n = p.getInt(K_LIGHT_N, 0)
        val respN = p.getInt(K_RESP_N, 0)
        return DailySignals(
            day = p.getString(K_DAY, today())!!,
            unlocks = p.getInt(K_UNLOCKS, 0),
            screenOns = p.getInt(K_SCREEN_ONS, 0),
            darkUnlocks = p.getInt(K_DARK_UNLOCKS, 0),
            nightUnlocks = p.getInt(K_NIGHT_UNLOCKS, 0),
            avgLux = if (n > 0) p.getFloat(K_LIGHT_SUM, 0f) / n else null,
            firstUseMs = p.getLong(K_FIRST_USE, 0L),
            lastUseMs = p.getLong(K_LAST_USE, 0L),
            longestGapMinutes = p.getInt(K_LONGEST_GAP, 0),
            notificationsTotal = p.getInt(K_NOTIF_TOTAL, 0),
            notificationsSocial = p.getInt(K_NOTIF_SOCIAL, 0),
            avgResponseSeconds = if (respN > 0) (p.getLong(K_RESP_SUM_MS, 0L) / respN) / 1000.0 else null,
            fastResponseRatio = if (respN > 0) p.getInt(K_RESP_UNDER_30, 0).toDouble() / respN else null,
            phantomPickups = p.getInt(K_PHANTOM_PICKUPS, 0),
            notificationListenerActive = p.getBoolean(K_NOTIF_ACTIVE, false),
        )
    }
}
