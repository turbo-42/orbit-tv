package dev.walker.tvhome

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

private val Primary = Color(0xFFF5F5F2)
private val Secondary = Color(0xC7F5F5F2)
private val Tertiary = Color(0x8CF5F5F2)

/** A soft drop shadow keeps light text legible over bright photos without outlining it. */
private val Lift = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), blurRadius = 18f)

private const val WEATHER_REFRESH_MS = 30 * 60_000L

/** The current time, updated on each minute boundary and whenever the screen returns. */
@Composable
fun rememberNow(resumeCount: Int): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(resumeCount) {
        while (true) {
            now = LocalDateTime.now()
            delay(60_000L - now.second * 1_000L - now.nano / 1_000_000L)
        }
    }
    return now
}

/** Compact clock for idle mode, when the main header has faded out. */
@Composable
fun SmallClock(modifier: Modifier, now: LocalDateTime) {
    val is24h = DateFormat.is24HourFormat(LocalContext.current)
    BasicText(
        now.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm")),
        style = TextStyle(color = Secondary, fontSize = 34.sp, fontWeight = FontWeight.ExtraLight, shadow = Lift),
        modifier = modifier,
    )
}

@Composable
fun ClockBlock(modifier: Modifier, now: LocalDateTime) {
    val is24h = DateFormat.is24HourFormat(LocalContext.current)

    Column(modifier) {
        BasicText(
            buildAnnotatedString {
                append(now.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm")))
                if (!is24h) {
                    withStyle(SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp)) {
                        append(" " + now.format(DateTimeFormatter.ofPattern("a")).uppercase())
                    }
                }
            },
            style = TextStyle(
                color = Primary,
                fontSize = 96.sp,
                fontWeight = FontWeight.ExtraLight,
                letterSpacing = (-2).sp,
                shadow = Lift,
            ),
        )
        BasicText(
            now.format(DateTimeFormatter.ofPattern("EEEE  ·  d MMMM")).uppercase(),
            style = TextStyle(
                color = Secondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 3.sp,
                shadow = Lift,
            ),
            // Large thin numerals carry side bearing; indent the date to line up with the glyphs.
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
fun WeatherBlock(modifier: Modifier, resumeCount: Int) {
    val is24h = DateFormat.is24HourFormat(LocalContext.current)
    val source = remember { WeatherSource(is24h) }
    var weather by remember { mutableStateOf<Weather?>(null) }
    LaunchedEffect(resumeCount) {
        while (true) {
            runCatching { source.current() }.onSuccess { weather = it }
            delay(WEATHER_REFRESH_MS)
        }
    }
    val w = weather ?: return

    Column(modifier, horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(w.sky, w.isDay, Modifier.size(52.dp))
            Spacer(Modifier.width(18.dp))
            BasicText(
                "${w.temperature}°",
                style = TextStyle(color = Primary, fontSize = 72.sp, fontWeight = FontWeight.ExtraLight, shadow = Lift),
            )
        }
        Spacer(Modifier.height(2.dp))
        BasicText(
            w.description.uppercase(),
            style = TextStyle(color = Secondary, fontSize = 15.sp, letterSpacing = 3.sp, textAlign = TextAlign.End, shadow = Lift),
        )
        if (w.outlook.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            BasicText(
                w.outlook,
                style = TextStyle(color = Secondary, fontSize = 14.sp, letterSpacing = 0.5.sp, textAlign = TextAlign.End, shadow = Lift),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (w.place.isNotEmpty()) {
                BasicText(w.place, style = TextStyle(color = Tertiary, fontSize = 14.sp, letterSpacing = 0.5.sp, shadow = Lift))
            }
            BasicText(
                "H ${w.high}°   L ${w.low}°",
                style = TextStyle(color = Tertiary, fontSize = 14.sp, letterSpacing = 0.5.sp, shadow = Lift),
            )
        }
    }
}

/** Minimal line-drawn weather glyphs, so they match the thin type. */
@Composable
private fun WeatherIcon(sky: Sky, isDay: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = size.minDimension * 0.045f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val shadow = Color.Black.copy(alpha = 0.25f)
        // Draw once offset in black for the same lift as the text, then in white.
        for ((color, dy) in listOf(shadow to size.minDimension * 0.03f, Primary to 0f)) {
            translateY(dy) {
                when (sky) {
                    Sky.Clear -> if (isDay) sun(color, stroke, Offset(0.5f, 0.5f), 0.2f) else moon(color, stroke)
                    Sky.PartlyCloudy -> {
                        if (isDay) sun(color, stroke, Offset(0.36f, 0.36f), 0.14f) else moon(color, stroke, scale = 0.7f, at = Offset(0.36f, 0.34f))
                        cloud(color, stroke, top = 0.42f)
                    }
                    Sky.Cloudy -> cloud(color, stroke, top = 0.28f)
                    Sky.Fog -> for (i in 0..3) {
                        val y = size.height * (0.3f + i * 0.14f)
                        val inset = if (i % 2 == 0) 0.12f else 0.22f
                        drawLine(color, Offset(size.width * inset, y), Offset(size.width * (1 - inset), y), stroke.width, StrokeCap.Round)
                    }
                    Sky.Drizzle -> { cloud(color, stroke, top = 0.18f); drops(color, stroke, length = 0.07f) }
                    Sky.Rain -> { cloud(color, stroke, top = 0.18f); drops(color, stroke, length = 0.15f) }
                    Sky.Snow -> {
                        cloud(color, stroke, top = 0.18f)
                        for (x in listOf(0.34f, 0.5f, 0.66f)) {
                            drawCircle(color, size.minDimension * 0.035f, Offset(size.width * x, size.height * (if (x == 0.5f) 0.88f else 0.8f)))
                        }
                    }
                    Sky.Thunder -> {
                        cloud(color, stroke, top = 0.18f)
                        val bolt = Path().apply {
                            moveTo(size.width * 0.54f, size.height * 0.66f)
                            lineTo(size.width * 0.44f, size.height * 0.82f)
                            lineTo(size.width * 0.56f, size.height * 0.82f)
                            lineTo(size.width * 0.46f, size.height * 0.98f)
                        }
                        drawPath(bolt, color, style = stroke)
                    }
                }
            }
        }
    }
}

private inline fun DrawScope.translateY(dy: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(0f, dy)
    block()
    drawContext.transform.translate(0f, -dy)
}

private fun DrawScope.sun(color: Color, stroke: Stroke, center: Offset, radius: Float) {
    val c = Offset(size.width * center.x, size.height * center.y)
    val r = size.minDimension * radius
    drawCircle(color, r, c, style = stroke)
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val inner = r * 1.45f
        val outer = r * 1.95f
        drawLine(
            color,
            Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
            Offset(c.x + outer * cos(a).toFloat(), c.y + outer * sin(a).toFloat()),
            stroke.width,
            StrokeCap.Round,
        )
    }
}

private fun DrawScope.moon(color: Color, stroke: Stroke, scale: Float = 1f, at: Offset = Offset(0.5f, 0.5f)) {
    val r = size.minDimension * 0.26f * scale
    val c = Offset(size.width * at.x, size.height * at.y)
    // Crescent: an arc from the full circle minus a bite on the upper right.
    drawArc(color, 60f, 270f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = stroke)
    drawArc(color, 140f, -110f, false, Offset(c.x - r * 0.35f, c.y - r * 1.15f), Size(r * 1.6f, r * 1.6f), style = stroke)
}

private fun DrawScope.cloud(color: Color, stroke: Stroke, top: Float) {
    val w = size.width
    val h = size.height
    val base = h * (top + 0.42f)
    val path = Path().apply {
        moveTo(w * 0.24f, base)
        // Left lobe, big top lobe, right lobe, flat base.
        cubicTo(w * 0.08f, base, w * 0.08f, h * (top + 0.2f), w * 0.28f, h * (top + 0.2f))
        cubicTo(w * 0.32f, h * top, w * 0.62f, h * (top - 0.04f), w * 0.68f, h * (top + 0.18f))
        cubicTo(w * 0.9f, h * (top + 0.16f), w * 0.94f, base, w * 0.76f, base)
        close()
    }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.drops(color: Color, stroke: Stroke, length: Float) {
    for ((i, x) in listOf(0.36f, 0.52f, 0.68f).withIndex()) {
        val y0 = size.height * (0.74f + if (i == 1) 0.04f else 0f)
        drawLine(
            color,
            Offset(size.width * x, y0),
            Offset(size.width * (x - 0.04f), y0 + size.height * length),
            stroke.width,
            StrokeCap.Round,
        )
    }
}
