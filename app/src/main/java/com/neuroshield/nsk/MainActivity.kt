package com.neuroshield.nsk
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.*
import java.util.concurrent.TimeUnit
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.data?.getQueryParameter("token")
            ?: getSharedPreferences("nsk", Context.MODE_PRIVATE).getString("token", null)
        if (token != null && token.length >= 16) {
            getSharedPreferences("nsk", Context.MODE_PRIVATE).edit().putString("token", token).apply()
            schedulSync()
            if (!hasUsagePermission()) requestUsagePermission()
            else finish()
        } else {
            AlertDialog.Builder(this).setTitle("NeuroShield Kids").setMessage("Token inválido. Escanea el QR desde la app.").setPositiveButton("OK") { _, _ -> finish() }.show()
        }
    }
    private fun hasUsagePermission(): Boolean {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val now = System.currentTimeMillis()
        return usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 1000, now).isNotEmpty()
    }
    private fun requestUsagePermission() {
        AlertDialog.Builder(this).setTitle("Permiso necesario").setMessage("Concede acceso al uso de apps para que NeuroShield pueda monitorizar.").setPositiveButton("Ajustes") { _, _ -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }.show()
    }
    private fun schedulSync() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("nsk_sync", ExistingPeriodicWorkPolicy.REPLACE, req)
    }
}
