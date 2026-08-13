package com.neuroshield.nsk

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val SUPABASE_URL = "https://lqvgspmjfkfdurdnejzs.supabase.co"
        const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImxxdmdzcG1qZmtmZHVyZG5lanpzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzkzOTQxMzcsImV4cCI6MjA5NDk3MDEzN30.wocGhw9oj96-GAKNNFYai_KciAuZfs4jO_oMqbOkXuo"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs = applicationContext.getSharedPreferences("nsk", Context.MODE_PRIVATE)
            val ingestToken = prefs.getString("ingest_token", null) ?: return@withContext Result.success()
            val lastSync = prefs.getLong("last_sync_ms", System.currentTimeMillis() - 90 * 60 * 1000)

            val events = UsageHelper.getEventsSince(applicationContext, lastSync)
            val metrics = UsageHelper.computeMetrics(applicationContext, lastSync)
            val signals = SignalStore.read(applicationContext)

            // Bloque de señales conductuales nativas. Viaja en el metadata del
            // primer evento del lote para no requerir cambios de esquema.
            val nativeSignals = JSONObject().apply {
                put("source", "android_native")
                put("schema_version", 2)

                // Compulsividad y patrón de acceso
                put("unlocks", signals.unlocks)
                put("screen_ons", signals.screenOns)
                put("night_unlocks", signals.nightUnlocks)

                // Uso a oscuras → conducta de ocultación / móvil en la cama
                put("dark_unlocks", signals.darkUnlocks)
                signals.avgLux?.let { put("avg_lux", it.toDouble()) }

                // Ventana de sueño (proxy: mayor intervalo sin desbloquear)
                put("longest_idle_gap_minutes", signals.longestGapMinutes)
                if (signals.firstUseMs > 0) put("first_use_ms", signals.firstUseMs)
                if (signals.lastUseMs > 0) put("last_use_ms", signals.lastUseMs)

                // Atención y diversidad de actividad
                put("app_switches", metrics.appSwitches)
                put("switches_per_minute", metrics.switchesPerMinute)
                put("distinct_apps", metrics.distinctApps)
                put("session_entropy", metrics.sessionEntropy)
                put("avg_session_seconds", metrics.avgSessionSeconds)
                put("longest_session_seconds", metrics.longestSessionSeconds)
                put("night_minutes", metrics.nightMinutes)

                // Fase 2: solo presentes si el padre concedió el permiso de
                // notificaciones. El motor las marca como no disponibles si no.
                if (signals.notificationListenerActive) {
                    put("notifications_total", signals.notificationsTotal)
                    put("notifications_social", signals.notificationsSocial)
                    signals.avgResponseSeconds?.let { put("avg_response_seconds", it) }
                    signals.fastResponseRatio?.let { put("fast_response_ratio", it) }
                    put("phantom_pickups", signals.phantomPickups)
                }
            }

            if (events.isEmpty()) {
                // Aun sin sesiones de app puede haber señales relevantes
                // (desbloqueos nocturnos, uso a oscuras). Se envía un latido.
                if (signals.unlocks > 0) {
                    val heartbeat = JSONArray().put(JSONObject().apply {
                        put("app_name", "__signals__")
                        put("duration_seconds", 0)
                        put("occurred_at", nowIso())
                        put("event_type", "signals")
                        put("metadata", nativeSignals)
                    })
                    if (!postBatch(ingestToken, heartbeat)) return@withContext Result.retry()
                }
                prefs.edit().putLong("last_sync_ms", System.currentTimeMillis()).apply()
                return@withContext Result.success()
            }

            var first = true
            for (chunk in events.chunked(500)) {
                val arr = JSONArray()
                for (e in chunk) {
                    arr.put(JSONObject().apply {
                        put("app_name", e.appName)
                        put("duration_seconds", e.durationSeconds)
                        put("occurred_at", e.occurredAt)
                        put("event_type", e.eventType)
                        if (first) {
                            put("metadata", nativeSignals)
                            first = false
                        }
                    })
                }
                if (!postBatch(ingestToken, arr)) return@withContext Result.retry()
            }

            prefs.edit().putLong("last_sync_ms", System.currentTimeMillis()).apply()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun nowIso(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

    private fun postBatch(token: String, events: JSONArray): Boolean {
        val body = JSONObject().apply {
            put("token", token)
            put("events", events)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$SUPABASE_URL/functions/v1/ingest-usage")
            .addHeader("apikey", ANON_KEY)
            .addHeader("Authorization", "Bearer $ANON_KEY")
            .post(body)
            .build()

        return client.newCall(request).execute().use { it.isSuccessful }
    }
}
