package dev.walker.tvhome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV's launcher declares its HOME filter with a priority third-party launchers can't match,
 * so the default-home setting is ignored. Instead, bring this launcher forward whenever the stock
 * home activity is in front.
 *
 * Window-state events alone miss Home presses made while the stock home is already showing, and
 * Home key presses are not delivered to accessibility key filters. So track which launcherx window
 * is in front and treat any activity from the stock home as a cue to redirect. The long-press Home
 * dashboard is a separate launcherx activity started by SystemUI; while it is in front nothing is
 * redirected, so it keeps working unchanged.
 */
class HomeRedirectService : AccessibilityService() {

    private var stockHomeInFront = false
    private var lastRedirectAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Only activity windows report a class we care about; dialogs and toasts keep the current state.
            val windowClass = event.className?.toString() ?: return
            if (windowClass.startsWith(LAUNCHERX)) stockHomeInFront = windowClass == STOCK_HOME_ACTIVITY
        }
        if (!stockHomeInFront) return

        val now = SystemClock.uptimeMillis()
        if (now - lastRedirectAt < REDIRECT_DEBOUNCE_MS) return
        lastRedirectAt = now
        stockHomeInFront = false
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    override fun onInterrupt() {}

    private companion object {
        const val LAUNCHERX = "com.google.android.apps.tv.launcherx."
        const val STOCK_HOME_ACTIVITY = "com.google.android.apps.tv.launcherx.home.HomeActivity"
        const val REDIRECT_DEBOUNCE_MS = 1_000L
    }
}
