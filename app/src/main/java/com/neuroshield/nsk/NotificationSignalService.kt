package com.neuroshield.nsk

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Mide dos señales que ninguna app de control parental del mercado ofrece hoy,
 * y que son imposibles desde una web.
 *
 * 1. LATENCIA DE RESPUESTA SOCIAL
 *    Cuántos segundos tarda el menor en abrir un mensaje desde que llega.
 *    Responder de forma sistemática en menos de 30 segundos es un indicador
 *    objetivo de ansiedad por aprobación social. El patrón inverso —acumular
 *    notificaciones sin abrirlas— apunta a evitación.
 *
 * 2. DESBLOQUEOS SIN NOTIFICACIÓN PREVIA
 *    Coger el móvil cuando no ha pasado nada. Es la medida más limpia de
 *    compulsión que existe: no hay estímulo externo, solo el impulso.
 *
 * Privacidad: no se lee el contenido de ninguna notificación. Solo se registra
 * de qué aplicación viene, cuándo llega y cuándo se abre. Ni texto, ni
 * remitente, ni asunto.
 */
class NotificationSignalService : NotificationListenerService() {

    companion object {
        /** Apps cuyo tiempo de respuesta tiene valor clínico. */
        private val SOCIAL_PACKAGES = setOf(
            "com.whatsapp",
            "org.telegram.messenger",
            "com.instagram.android",
            "com.snapchat.android",
            "com.facebook.orca",
            "com.discord",
            "com.zhiliaoapp.musically",
            "com.twitter.android",
            "com.google.android.apps.messaging",
        )

        /** Se descarta cualquier latencia mayor: el menor no estaba con el móvil. */
        private const val MAX_PLAUSIBLE_LATENCY_MS = 15 * 60 * 1000L

        fun isEnabled(ctx: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            return enabled.contains(ctx.packageName)
        }
    }

    /** Notificaciones sociales pendientes de ser abiertas: paquete → instante. */
    private val pending = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg == packageName) return

        SignalStore.recordNotificationPosted(applicationContext, isSocial(pkg))

        if (isSocial(pkg)) {
            // Solo guardamos la primera pendiente por app: si llegan cinco
            // mensajes seguidos, lo que importa es cuánto tarda en atender la
            // conversación, no cada mensaje por separado.
            pending.putIfAbsent(pkg, System.currentTimeMillis())
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val postedAt = pending.remove(pkg) ?: return
        if (!isSocial(pkg)) return

        val latency = System.currentTimeMillis() - postedAt
        if (latency in 0..MAX_PLAUSIBLE_LATENCY_MS) {
            SignalStore.recordNotificationResponse(applicationContext, latency)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        pending.clear()
        SignalStore.setNotificationListenerActive(applicationContext, true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        SignalStore.setNotificationListenerActive(applicationContext, false)
    }

    private fun isSocial(pkg: String): Boolean {
        if (pkg in SOCIAL_PACKAGES) return true
        val p = pkg.lowercase()
        return p.contains("whatsapp") || p.contains("telegram") ||
            p.contains("messenger") || p.contains("instagram") || p.contains("snapchat")
    }
}
