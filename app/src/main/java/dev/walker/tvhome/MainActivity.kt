package dev.walker.tvhome

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

/** Tracks remote input so the home screen can fade to a photo frame when left alone. */
class IdleState {
    var isIdle by mutableStateOf(false)
    var lastInputAt by mutableLongStateOf(SystemClock.uptimeMillis())
        private set

    /** Set by the home screen: handles a key while idle (e.g. media control) and returns true to stay idle. */
    var idleKeyHandler: (keyCode: Int) -> Boolean = { false }

    fun touch() {
        lastInputAt = SystemClock.uptimeMillis()
        isIdle = false
    }
}

class MainActivity : ComponentActivity() {

    /** Bumped on every resume so the home row can reclaim focus after returning from an app. */
    private val resumeCount = mutableIntStateOf(0)

    /** Bumped when the screen becomes visible, but not when an overlay such as the dashboard closes. */
    private val startCount = mutableIntStateOf(0)

    private val idle = IdleState()

    /** The app to highlight when home returns: the one the user just left, or null for the middle. */
    private val homeFocus = mutableStateOf(HomeFocus(packageName = null, sequence = 0))

    /** Hidden by an app since the last resume (as opposed to briefly covered by the stock home). */
    private var leftForApp = false
    private var hiddenAt = 0L
    private var pausedAt = 0L

    /** Brought forward by the Home redirect, i.e. the user pressed Home. */
    private var homePressed = false

    private val background = Executors.newSingleThreadExecutor()

    /** Whether the home screen is on screen; audio analysis only runs while it is. */
    private val visible = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back on the home screen goes nowhere; overlays register their own handlers above this one.
        onBackPressedDispatcher.addCallback(this) {}
        setContent {
            HomeScreen(
                resumeCount = resumeCount.intValue,
                startCount = startCount.intValue,
                visible = visible.value,
                homeFocus = homeFocus.value,
                idle = idle,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        startCount.intValue++
        visible.value = true
        idle.touch()
    }

    override fun onStop() {
        visible.value = false
        leftForApp = true
        // The next app resumes before this screen stops, so measure from the pause.
        hiddenAt = pausedAt
        super.onStop()
    }

    override fun onPause() {
        pausedAt = System.currentTimeMillis()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homePressed = true
    }

    override fun onResume() {
        super.onResume()
        resumeCount.intValue++
        // Re-pick the highlight on return from an app, or on Home while already home (which only
        // pauses this screen behind the stock home for a moment). Closing the dashboard does neither.
        if (leftForApp) {
            // The usage lookup can take a while; keep it off the thread drawing the first frame.
            val since = hiddenAt
            background.execute {
                val app = ForegroundApp.beforeHome(this, since)
                runOnUiThread { homeFocus.value = HomeFocus(app, homeFocus.value.sequence + 1) }
            }
        } else if (homePressed) {
            homeFocus.value = HomeFocus(null, homeFocus.value.sequence + 1)
        }
        leftForApp = false
        homePressed = false
    }

    override fun onDestroy() {
        background.shutdown()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (idle.isIdle) {
            // While idle, OK and Right control the music without waking; any other press wakes,
            // and only wakes: it shouldn't also move focus or launch.
            if (event.action == KeyEvent.ACTION_UP && !idle.idleKeyHandler(event.keyCode)) idle.touch()
            return true
        }
        idle.touch()
        return super.dispatchKeyEvent(event)
    }
}
