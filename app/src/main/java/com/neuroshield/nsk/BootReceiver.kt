package com.neuroshield.nsk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Rearranca la recogida tras un reinicio del dispositivo o una actualización
 * de la app. Sin esto, apagar y encender el móvil deja la protección muerta
 * sin que el padre se entere.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val token = context.getSharedPreferences("nsk", Context.MODE_PRIVATE)
            .getString("ingest_token", null) ?: return

        // Android restringe el arranque de servicios en primer plano desde
        // segundo plano. BOOT_COMPLETED está exento, pero algunos fabricantes
        // lo aplican de forma agresiva: si falla, WorkManager reactiva el
        // servicio en la siguiente sincronización.
        try {
            SignalCollectorService.start(context)
        } catch (_: Exception) { }

        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .addTag("nsk_sync")
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("nsk_sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
