package dev.walker.tvhome

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Default home row, in display order, until it is rearranged from the remote. */
val DEFAULT_PINNED_PACKAGES = listOf(
    "ar.tvplayer.tv", // TiviMate
    "com.google.android.youtube.tv",
    "org.xbmc.kodi",
    "com.spotify.tv.android",
    "com.amazon.amazonvideo.livingroom",
)

private const val PINNED_KEY = "pinned"

data class TvApp(
    val packageName: String,
    val label: String,
    /** Full-bleed square art for a circular tile, like the stock "Your apps" row. */
    val circleArt: ImageBitmap,
)

class AppRepository(private val context: Context) {

    private val pm = context.packageManager
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    suspend fun pinned(): List<TvApp> = withContext(Dispatchers.IO) {
        pinnedPackages().mapNotNull { load(it) }
    }

    fun savePinned(apps: List<TvApp>) {
        prefs.edit().putString(PINNED_KEY, apps.joinToString(",") { it.packageName }).apply()
    }

    suspend fun app(packageName: String): TvApp? = withContext(Dispatchers.IO) { load(packageName) }

    fun openAppInfo(app: TvApp) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun launch(packageName: String) {
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: return
        ForegroundApp.launchedFromHome = packageName
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun pinnedPackages(): List<String> =
        prefs.getString(PINNED_KEY, null)?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_PINNED_PACKAGES

    /** Every launchable TV app except this launcher, alphabetically. */
    suspend fun all(): List<TvApp> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .mapNotNull { load(it) }
            .sortedBy { it.label.lowercase() }
    }

    fun launch(app: TvApp) = launch(app.packageName)

    private fun load(packageName: String): TvApp? = runCatching {
        val info = pm.getApplicationInfo(packageName, 0)
        TvApp(
            packageName = packageName,
            label = pm.getApplicationLabel(info).toString(),
            circleArt = highResIcon(info).toCircleArt(320),
        )
    }.getOrNull()

    /**
     * getApplicationIcon returns the icon for this TV's density, which blurs once scaled up to the
     * home row's circles. Ask the app's resources for its largest (xxxhdpi) variant instead.
     */
    private fun highResIcon(info: ApplicationInfo): Drawable =
        info.icon.takeIf { it != 0 }?.let { id ->
            runCatching {
                pm.getResourcesForApplication(info).getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null)
            }.getOrNull()
        } ?: pm.getApplicationIcon(info)
}

/**
 * Adaptive icon layers span 108 units with only the central 72 visible, so draw both layers
 * unmasked at 1.5x and let the circular clip reveal the full-bleed art. Legacy square icons are
 * trimmed to their visible pixels and scaled to fill, over their own edge colour, so a
 * white-backed icon becomes a white circle rather than a small square on a disc.
 */
private fun Drawable.toCircleArt(size: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    if (this is AdaptiveIconDrawable) {
        val inset = size / 4
        for (layer in listOfNotNull(background, foreground)) {
            layer.setBounds(-inset, -inset, size + inset, size + inset)
            layer.draw(canvas)
        }
        return bitmap.asImageBitmap()
    }

    val raw = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, size, size)
    draw(Canvas(raw))
    val trimmed = raw.opaqueBounds()?.let { Bitmap.createBitmap(raw, it.left, it.top, it.width(), it.height()) } ?: raw
    // A transparent corner means a bare logo rather than a square tile: pad it inside the circle
    // instead of scaling it to the edge, where the clip would crop it.
    val isBareLogo = android.graphics.Color.alpha(trimmed.getPixel(trimmed.width / 20, trimmed.height / 20)) < 200
    // Sample just inside the top edge, where the circle meets the icon's own background.
    val edge = trimmed.getPixel(trimmed.width / 2, trimmed.height / 25)
    canvas.drawColor(if (!isBareLogo && android.graphics.Color.alpha(edge) > 200) edge else 0xFFECECEC.toInt())
    val pad = if (isBareLogo) size * 15 / 100 else 0
    canvas.drawBitmap(
        trimmed,
        null,
        android.graphics.Rect(pad, pad, size - pad, size - pad),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
    )
    return bitmap.asImageBitmap()
}

/** Square bounds of the visibly opaque pixels, centred on their content; null if none. */
private fun Bitmap.opaqueBounds(): android.graphics.Rect? {
    val pixels = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
    var left = width; var top = height; var right = -1; var bottom = -1
    for (y in 0 until height) for (x in 0 until width) {
        if (android.graphics.Color.alpha(pixels[y * width + x]) > 16) {
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    if (right < 0) return null
    val side = maxOf(right - left + 1, bottom - top + 1)
    val cx = (left + right) / 2
    val cy = (top + bottom) / 2
    val l = (cx - side / 2).coerceIn(0, width - side.coerceAtMost(width))
    val t = (cy - side / 2).coerceIn(0, height - side.coerceAtMost(height))
    return android.graphics.Rect(l, t, l + side.coerceAtMost(width), t + side.coerceAtMost(height))
}
