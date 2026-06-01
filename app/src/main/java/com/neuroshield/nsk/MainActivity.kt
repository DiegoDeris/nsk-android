package com.neuroshield.nsk

import android.app.AppOpsManager
import android.content.Context
import com.neuroshield.nsk.R
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        const val SUPABASE_URL = "https://lqvgspmjfkfdurdnejzs.supabase.co"
        const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImxxdmdzcG1qZmtmZHVyZG5lanpzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzkzOTQxMzcsImV4cCI6MjA5NDk3MDEzN30.wocGhw9oj96-GAKNNFYai_KciAuZfs4jO_oMqbOkXuo"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Handle nsk://setup/[token] deep link (from camera scanning InstallToken QR)
        val deepLinkToken = intent?.data?.let { uri ->
            if (uri.scheme == "nsk" && uri.host == "setup") uri.lastPathSegment else null
        }
        if (deepLinkToken != null) {
            registerDevice(deepLinkToken)
            return
        }

        refresh()

        findViewById<View>(R.id.btnScanQr).setOnClickListener {
            IntentIntegrator(this).apply {
                setPrompt("Escanea el QR de configuración de NSK")
                setBeepEnabled(false)
                setOrientationLocked(true)
                initiateScan()
            }
        }
        findViewById<View>(R.id.btnPermission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<View>(R.id.btnDisconnect).setOnClickListener {
            WorkManager.getInstance(this).cancelAllWorkByTag("nsk_sync")
            getSharedPreferences("nsk", Context.MODE_PRIVATE).edit().remove("ingest_token").apply()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val deepLinkToken = intent?.data?.let { uri ->
            if (uri.scheme == "nsk" && uri.host == "setup") uri.lastPathSegment else null
        }
        if (deepLinkToken != null) registerDevice(deepLinkToken)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result?.contents != null) {
            val content = result.contents.trim()
            when {
                content.startsWith("nsk://setup/") -> registerDevice(content.removePrefix("nsk://setup/"))
                content.length >= 16 -> {
                    getSharedPreferences("nsk", Context.MODE_PRIVATE).edit().putString("ingest_token", content).apply()
                    refresh()
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun refresh() {
        val prefs = getSharedPreferences("nsk", Context.MODE_PRIVATE)
        val token = prefs.getString("ingest_token", null)
        when {
            token == null -> showStep(Step.SCAN)
            !hasUsagePermission() -> showStep(Step.PERMISSION)
            else -> { showStep(Step.ACTIVE); scheduleSync() }
        }
    }

    private fun showStep(step: Step) {
        findViewById<View>(R.id.layoutScan).visibility = if (step == Step.SCAN) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutPermission).visibility = if (step == Step.PERMISSION) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutActive).visibility = if (step == Step.ACTIVE) View.VISIBLE else View.GONE
    }

    private fun registerDevice(installToken: String) {
        showStep(Step.SCAN)
        findViewById<View>(R.id.btnScanQr).isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bodyJson = JSONObject().apply {
                    put("install_token", installToken)
                    put("device_info", JSONObject().apply {
                        put("model", Build.MODEL)
                        put("android_version", Build.VERSION.RELEASE)
                        put("fingerprint", Build.FINGERPRINT)
                    })
                }.toString()
                val request = Request.Builder()
                    .url("$SUPABASE_URL/functions/v1/register-device")
                    .addHeader("apikey", ANON_KEY)
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()
                val response = httpClient.newCall(request).execute()
                val json = JSONObject(response.body?.string() ?: "{}")
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && json.optBoolean("success")) {
                        getSharedPreferences("nsk", Context.MODE_PRIVATE)
                            .edit().putString("ingest_token", json.getString("ingest_token")).apply()
                        refresh()
                    } else {
                        findViewById<View>(R.id.btnScanQr).isEnabled = true
                        Toast.makeText(this@MainActivity, "Error: ${json.optString("error", "inténtalo de nuevo")}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    findViewById<View>(R.id.btnScanQr).isEnabled = true
                    Toast.makeText(this@MainActivity, "Error de red: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun scheduleSync() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .addTag("nsk_sync")
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("nsk_sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    enum class Step { SCAN, PERMISSION, ACTIVE }
}
