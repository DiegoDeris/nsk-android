package com.neuroshield.nsk
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
data class AppUsageEvent(val appName: String, val durationSeconds: Int, val occurredAt: String, val eventType: String)
object UsageHelper {
    fun getEventsSince(ctx: Context, sinceMs: Long): List<AppUsageEvent> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = mutableListOf<AppUsageEvent>()
        val starts = mutableMapOf<String, Long>()
        val ue = usm.queryEvents(sinceMs, now)
        val e = UsageEvents.Event()
        while (ue.hasNextEvent()) {
            ue.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> starts[pkg] = e.timeStamp
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val start = starts.remove(pkg) ?: continue
                    val dur = ((e.timeStamp - start) / 1000).toInt()
                    if (dur > 0) events.add(AppUsageEvent(pkg, dur, java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(start)), "session"))
                }
            }
        }
        return events
    }
}
