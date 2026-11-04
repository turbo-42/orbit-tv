package dev.walker.tvhome

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/** Which row app to highlight when the home screen comes back; null means the middle app. */
data class HomeFocus(val packageName: String?, val sequence: Int)

/**
 * Works out the app the user was in before returning home, so the row can highlight it.
 */
object ForegroundApp {

    /** Fallback when usage access isn't granted: the app this launcher last opened. */
    @Volatile
    var launchedFromHome: String? = null

    // Screens that sit between an app and this launcher without being "where the user was".
    private val TRANSIENT = setOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.apps.tv.dreamx",
        "com.android.systemui",
    )

    /**
     * The app the user was in while the home screen was hidden since [hiddenAt] (wall-clock ms).
     * Only apps resumed after that point count, and never this launcher: Android may already have
     * logged this launcher's own return by the time the lookup runs.
     */
    fun beforeHome(context: Context, hiddenAt: Long): String? {
        val fallback = launchedFromHome
        launchedFromHome = null
        return runCatching { lastAppResumedSince(context, hiddenAt) }.getOrNull() ?: fallback
    }

    private fun lastAppResumedSince(context: Context, since: Long): String? {
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val events = usage.queryEvents(since - CLOCK_SLACK_MS, System.currentTimeMillis())
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                event.packageName !in TRANSIENT &&
                event.packageName != context.packageName
            ) {
                last = event.packageName
            }
        }
        return last
    }

    private const val CLOCK_SLACK_MS = 200L
}
