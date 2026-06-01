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
            if (events.isEmpty()) return@withContext Result.success()

            events.chunked(500).forEach { chunk ->
                val arr = JSONArray().apply {
                    chunk.forEach { e ->
                        put(JSONObject().apply {
                            put("app_name", e.appName)
                            put("duration_seconds", e.durationSeconds)
                            put("occurred_at", e.occurredAt)
                            put("event_type", e.eventType)
                        })
                    }
                }
                val body = JSONObject().apply {
                    put("token", ingestToken)
                    put("events", arr)
                }.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$SUPABASE_URL/functions/v1/ingest-usage")
                    .addHeader("apikey", ANON_KEY)
                    .addHeader("Authorization", "Bearer $ANON_KEY")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext Result.retry()
                response.close()
            }

            prefs.edit().putLong("last_sync_ms", System.currentTimeMillis()).apply()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
