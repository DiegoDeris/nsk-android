package com.neuroshield.nsk

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.google.zxing.integration.android.IntentIntegrator
import com.neuroshield.nsk.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        const val SUPABASE_URL = "https://lqvgspmjfkfdurdnejzs.supabase.co"
        const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImxxdmdzcG1qZmtmZHVyZG5lanpzIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzkzOTQxMzcsImV4cCI6MjA5NDk3MDEzN30.wocGhw9oj96-GAKNNFYai_KciAuZfs4jO_oMqbOkXuo"
    }

    private lateinit var binding: ActivityMainBinding
    private var tts: TextToSpeech? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Narración por voz: un padre no técnico no busca en una lista de ajustes,
        // hace lo que se le va diciendo mientras mira la pantalla real.
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("es", "ES")
        }

        extractToken(intent)?.let { registerDevice(it); return }

        refresh()

        binding.btnScanQr.setOnClickListener {
            IntentIntegrator(this).apply {
                setPrompt("Escanea el QR de configuración de NSK")
                setBeepEnabled(false)
                setOrientationLocked(true)
                initiateScan()
            }
        }

        binding.btnPermission.setOnClickListener {
            speak("Busca NeuroShield Kids en la lista y activa el interruptor.")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        binding.btnBattery.setOnClickListener { requestBatteryExemption() }

        binding.btnDisconnect.setOnClickListener {
            WorkManager.getInstance(this).cancelAllWorkByTag("nsk_sync")
            stopService(Intent(this, SignalCollectorService::class.java))
            getSharedPreferences("nsk", Context.MODE_PRIVATE).edit().remove("ingest_token").apply()
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        extractToken(intent)?.let { registerDevice(it) }
    }

    /** Acepta tanto el esquema propio nsk://setup/TOKEN como el App Link https://nsk.app/s/TOKEN */
    private fun extractToken(intent: Intent?): String? {
        val uri: Uri = intent?.data ?: return null
        return when {
            uri.scheme == "nsk" && uri.host == "setup" -> uri.lastPathSegment
            uri.scheme == "https" && uri.host == "nsk.app" &&
                uri.pathSegments.firstOrNull() == "s" -> uri.lastPathSegment
            else -> null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result?.contents != null) {
            val content = result.contents.trim()
            when {
                content.startsWith("nsk://setup/") -> registerDevice(content.removePrefix("nsk://setup/"))
                content.startsWith("https://nsk.app/s/") -> registerDevice(content.substringAfterLast('/'))
                content.length >= 16 -> {
                    getSharedPreferences("nsk", Context.MODE_PRIVATE).edit()
                        .putString("ingest_token", content).apply()
                    refresh()
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // ── Flujo de pasos ────────────────────────────────────────────────────────

    private fun refresh() {
        val token = getSharedPreferences("nsk", Context.MODE_PRIVATE).getString("ingest_token", null)
        when {
            token == null -> showStep(Step.SCAN)
            !hasUsagePermission() -> showStep(Step.PERMISSION)
            !isBatteryExempt() -> showStep(Step.BATTERY)
            else -> {
                showStep(Step.ACTIVE)
                ensureNotificationPermission()
                SignalCollectorService.start(this)
                scheduleSync()
            }
        }
    }

    private fun showStep(step: Step) {
        binding.layoutScan.visibility = if (step == Step.SCAN) View.VISIBLE else View.GONE
        binding.layoutPermission.visibility = if (step == Step.PERMISSION) View.VISIBLE else View.GONE
        binding.layoutBattery.visibility = if (step == Step.BATTERY) View.VISIBLE else View.GONE
        binding.layoutActive.visibility = if (step == Step.ACTIVE) View.VISIBLE else View.GONE
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nsk")
    }

    // ── Permisos ──────────────────────────────────────────────────────────────

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

    /**
     * Sin exención de optimización de batería, Android acaba estrangulando el
     * servicio y la app deja de reportar a los pocos días. Es la causa número
     * uno de fallo silencioso en apps de monitorización.
     */
    private fun isBatteryExempt(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        speak("Toca Permitir para que la protección no se detenga.")
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ── Alta del dispositivo ──────────────────────────────────────────────────

    private fun registerDevice(installToken: String) {
        showStep(Step.SCAN)
        binding.btnScanQr.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bodyJson = JSONObject().apply {
                    put("install_token", installToken)
                    put("device_info", JSONObject().apply {
                        put("model", Build.MODEL)
                        put("manufacturer", Build.MANUFACTURER)
                        put("android_version", Build.VERSION.RELEASE)
                        put("fingerprint", Build.FINGERPRINT)
                        put("app_kind", "native")
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
                        binding.btnScanQr.isEnabled = true
                        Toast.makeText(
                            this@MainActivity,
                            "Error: ${json.optString("error", "inténtalo de nuevo")}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnScanQr.isEnabled = true
                    Toast.makeText(this@MainActivity, "Error de red: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun scheduleSync() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .addTag("nsk_sync")
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("nsk_sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    enum class Step { SCAN, PERMISSION, BATTERY, ACTIVE }
}
