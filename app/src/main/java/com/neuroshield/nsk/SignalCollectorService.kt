package com.neuroshield.nsk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Servicio en primer plano que recoge señales conductuales de forma continua.
 *
 * Es la diferencia central respecto a la versión web: aquí el sistema operativo
 * nos entrega eventos en el momento en que ocurren, esté la app abierta o no.
 *
 * Se captura:
 *   - Encendidos de pantalla y desbloqueos reales → índice de compulsividad
 *   - Nivel de luz ambiental en el momento del desbloqueo → uso a oscuras
 *   - Uso nocturno por franja horaria
 *   - Mayor intervalo sin uso → proxy de ventana de sueño
 *
 * Consumo: el sensor de luz solo se activa una fracción de segundo en cada
 * desbloqueo, y los receptores de pantalla son pasivos. El impacto en batería
 * es despreciable.
 */
class SignalCollectorService : Service() {

    companion object {
        private const val CHANNEL_ID = "nsk_protection"
        private const val NOTIF_ID = 1
        private const val LIGHT_SAMPLE_MS = 1200L

        fun start(ctx: Context) {
            val intent = Intent(ctx, SignalCollectorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null

    /** Última lectura de lux conocida; se refresca en cada desbloqueo. */
    @Volatile private var lastLux: Float? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    SignalStore.recordScreenOn(context)
                    // Tomamos una muestra de luz mientras la pantalla se enciende,
                    // para tenerla lista cuando llegue el desbloqueo.
                    sampleLight()
                }
                Intent.ACTION_USER_PRESENT -> {
                    SignalStore.recordUnlock(context, lastLux)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    SignalStore.recordScreenOff(context)
                }
            }
        }
    }

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_LIGHT) {
                lastLux = event.values.firstOrNull()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
    }

    /** Activa el sensor de luz brevemente y lo apaga: coste de batería mínimo. */
    private fun sampleLight() {
        val sm = sensorManager ?: return
        val sensor = lightSensor ?: return
        sm.registerListener(lightListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        android.os.Handler(mainLooper).postDelayed({
            try { sm.unregisterListener(lightListener) } catch (_: Exception) { }
        }, LIGHT_SAMPLE_MS)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

        // Los eventos de pantalla no pueden declararse en el manifest desde API 26:
        // hay que registrarlos dinámicamente desde un componente vivo.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: si el sistema mata el servicio por memoria, lo recrea.
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        try { sensorManager?.unregisterListener(lightListener) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Protección NeuroShield",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "Indica que la protección está activa"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NeuroShield Kids")
            .setContentText("Protección activa")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .build()
    }
}
