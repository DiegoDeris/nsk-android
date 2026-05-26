package com.neuroshield.nsk
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            val prefs = applicationContext.getSharedPreferences("nsk", Context.MODE_PRIVATE)
            val token = prefs.getString("token", "") ?: return Result.failure()
            val lastSync = prefs.getLong("last_sync", System.currentTimeMillis() - 15 * 60 * 1000)
            val events = UsageHelper.getEventsSince(applicationContext, lastSync)
            if (events.isEmpty()) return Result.success()
            val arr = JSONArray()
            events.forEach { ev ->
                arr.put(JSONObject().apply {
                    put("app_name", ev.appName)
                    put("duration_seconds", ev.durationSeconds)
                    put("occurred_at", ev.occurredAt)
                    put("event_type", ev.eventType)
                })
            }
            val body = JSONObject().apply { put("events", arr) }.toString()
            val client = OkHttpClient()
            val req = Request.Builder()
                .url("https://lqvgspmjfkfdurdnejzs.supabase.co/functions/v1/ingest-usage")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                prefs.edit().putLong("last_sync", System.currentTimeMillis()).apply()
                Result.success()
            } else Result.retry()
        } catch (e: Exception) { Result.retry() }
    }
}
