package dev.walker.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.media.audiofx.Visualizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

private const val CAPTURE_SIZE = 1024
private const val BASS_LOW_HZ = 30f
private const val BASS_HIGH_HZ = 160f

/**
 * Smoothed 0..1 bass energy of the TV's output mix while [active], via the global Visualizer
 * session. Stays 0 when the permission is missing or the platform returns no data (for example
 * when audio is offloaded straight to HDMI), so callers get a static look rather than an error.
 */
@Composable
fun rememberBassLevel(active: Boolean): Float {
    var level by remember { mutableFloatStateOf(0f) }
    DisposableEffect(active) {
        level = 0f
        val visualizer = if (active) runCatching { Visualizer(0) }.getOrNull() else null
        if (visualizer != null) {
            runCatching {
                val (minSize, maxSize) = Visualizer.getCaptureSizeRange().let { it[0] to it[1] }
                visualizer.captureSize = CAPTURE_SIZE.coerceIn(minSize, maxSize)
                val meter = BassMeter(visualizer.samplingRate, visualizer.captureSize)
                visualizer.setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) {}
                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray, rate: Int) {
                            level = meter.update(fft)
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    false,
                    true,
                )
                visualizer.enabled = true
            }
        }
        onDispose {
            visualizer?.runCatching { enabled = false }
            visualizer?.release()
        }
    }
    return level
}

/** Turns FFT frames into a bass level with automatic gain and a fast-attack, slow-release envelope. */
private class BassMeter(samplingRateMilliHz: Int, captureSize: Int) {

    // Visualizer FFT layout: [Re0, Re(n/2), Re1, Im1, Re2, Im2, ...], bins spaced rate/n apart.
    private val bins = run {
        val binHz = samplingRateMilliHz / 1000f / captureSize
        (1 until captureSize / 2).filter { it * binHz in BASS_LOW_HZ..BASS_HIGH_HZ }.ifEmpty { listOf(1) }
    }
    private var peak = 1f
    private var smoothed = 0f

    fun update(fft: ByteArray): Float {
        var energy = 0f
        for (k in bins) {
            val re = fft[2 * k].toFloat()
            val im = fft[2 * k + 1].toFloat()
            energy += sqrt(re * re + im * im)
        }
        energy /= bins.size
        // Track the recent loudest bass so quiet and loud tracks both use the full range.
        peak = max(energy, peak * 0.995f).coerceAtLeast(4f)
        val target = (energy / peak).coerceIn(0f, 1f)
        smoothed += (target - smoothed) * if (target > smoothed) 0.55f else 0.12f
        return smoothed
    }
}

/** A light, saturated colour taken from the album art, for the glow; null until known. */
@Composable
fun rememberArtColor(playing: NowPlaying?): Color? {
    val context = LocalContext.current
    var color by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(playing?.title, playing?.artUri, playing?.art) {
        color = playing?.let { artColor(context, it) }
    }
    return color
}

private suspend fun artColor(context: Context, playing: NowPlaying): Color? = withContext(Dispatchers.Default) {
    val bitmap = playing.art?.asAndroidBitmap() ?: playing.artUri?.let { uri ->
        val request = ImageRequest.Builder(context).data(uri).allowHardware(false).size(64).build()
        (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    } ?: return@withContext null
    runCatching { vividColor(bitmap) }.getOrNull()
}

/** Picks the most vivid pixel of a tiny thumbnail, then lifts it so it reads as light, not mud. */
private fun vividColor(source: Bitmap): Color {
    val software = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
    val thumb = Bitmap.createScaledBitmap(software, 24, 24, true)
    val hsv = FloatArray(3)
    var best = floatArrayOf(220f, 0.5f, 1f)
    var bestScore = -1f
    for (y in 0 until thumb.height) for (x in 0 until thumb.width) {
        android.graphics.Color.colorToHSV(thumb.getPixel(x, y), hsv)
        val score = hsv[1] * hsv[2]
        if (score > bestScore) { bestScore = score; best = hsv.copyOf() }
    }
    best[1] = best[1].coerceIn(0.35f, 0.75f)
    best[2] = max(best[2], 0.9f)
    return Color(android.graphics.Color.HSVToColor(best))
}
