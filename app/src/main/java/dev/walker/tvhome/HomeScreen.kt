package dev.walker.tvhome

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.LocalTime

private const val PHOTO_DURATION_MS = 90_000L
private const val CROSSFADE_MS = 2_500
private const val IDLE_AFTER_MS = 3 * 60_000L
private const val UNSELECTED_SCALE = 0.6f

private val NIGHT_STARTS = LocalTime.of(22, 30)
private val NIGHT_ENDS = LocalTime.of(6, 30)

/** Margins from the screen edge; the Streamer outputs without overscan, so these can sit close. */
private val EdgeX = 32.dp
private val EdgeY = 22.dp

/** Room inside the now-playing badge for its halo to spread without being clipped. */
private val BadgeInset = 18.dp

private val Ink = Color(0xFF0A0A0C)
private val TextPrimary = Color(0xFFF2F2F2)
private val TextSecondary = Color(0xB3F2F2F2)
private val Accent = Color(0xFF8AB4F8)

/** Dims and warms the photo at night; a dark amber rather than black keeps it from looking grey. */
private val NightTint = Color(0xFF1C1006)

private enum class Overlay { None, AllApps, Menu, Moving, Picking }

@Composable
fun HomeScreen(resumeCount: Int, startCount: Int, visible: Boolean, homeFocus: HomeFocus, idle: IdleState) {
    val context = LocalContext.current
    val apps = remember { AppRepository(context) }
    var pinned by remember { mutableStateOf(emptyList<TvApp>()) }
    LaunchedEffect(resumeCount) { pinned = apps.pinned() }

    var overlay by remember { mutableStateOf(Overlay.None) }
    var selectedSlot by remember { mutableIntStateOf(0) }
    val now = rememberNow(resumeCount)
    val nowPlaying = rememberNowPlaying(resumeCount)
    val nowPlayingRequester = remember { FocusRequester() }
    val latestPlaying by rememberUpdatedState(nowPlaying)
    DisposableEffect(idle) {
        idle.idleKeyHandler = { keyCode ->
            val track = latestPlaying
            when {
                track == null -> false
                keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || keyCode == AndroidKeyEvent.KEYCODE_ENTER -> {
                    track.togglePlayPause()
                    true
                }
                keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                    track.skipNext()
                    true
                }
                else -> false
            }
        }
        onDispose { idle.idleKeyHandler = { false } }
    }

    LaunchedEffect(idle.lastInputAt, overlay) {
        if (overlay == Overlay.None) {
            delay(IDLE_AFTER_MS)
            idle.isIdle = true
        }
    }

    // Fade the foreground in whenever the screen becomes visible (wake, or back from an app).
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(startCount) {
        entrance.snapTo(0f)
        entrance.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    // Idle fades slowly so it reads as settling, and wakes quickly.
    val chrome by animateFloatAsState(if (idle.isIdle) 0f else 1f, tween(if (idle.isIdle) 1_500 else 250), label = "chrome")
    val night by animateFloatAsState(if (isNight(now)) 1f else 0f, tween(4_000), label = "night")

    Box(Modifier.fillMaxSize().background(Ink)) {
        // Each static region is cached as its own offscreen layer, so a focus animation only
        // re-renders the tiles rather than the photo, scrim and blurred text shadows.
        Box(Modifier.fillMaxSize().cached()) {
            RotatingBackground()
            Scrims()
            if (night > 0f) Box(Modifier.fillMaxSize().background(NightTint.copy(alpha = 0.5f * night)))
        }

        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * 12.dp.toPx()
            },
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = chrome }) {
                ClockBlock(Modifier.align(Alignment.TopStart).padding(start = EdgeX, top = EdgeY - 8.dp).cached(), now)
                WeatherBlock(Modifier.align(Alignment.TopEnd).padding(end = EdgeX, top = EdgeY + 4.dp).cached(), resumeCount)
            }
            SmallClock(
                Modifier.align(Alignment.BottomEnd).padding(end = EdgeX, bottom = EdgeY + 18.dp).graphicsLayer { alpha = 1f - chrome },
                now,
            )
            NowPlayingBadge(
                playing = nowPlaying,
                listening = visible,
                focusRequester = nowPlayingRequester,
                focusEnabled = overlay == Overlay.None,
                onOpen = { apps.launch(it.packageName) },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = EdgeX - BadgeInset, bottom = EdgeY - BadgeInset),
            )
            HomeRow(
                apps = pinned,
                nowPlayingRequester = if (nowPlaying != null) nowPlayingRequester else null,
                homeFocus = homeFocus,
                resumeCount = resumeCount,
                rowAlpha = chrome,
                focusEnabled = overlay == Overlay.None || overlay == Overlay.Moving,
                movingSlot = if (overlay == Overlay.Moving) selectedSlot else null,
                onLaunch = apps::launch,
                onLongPress = { slot -> selectedSlot = slot; overlay = Overlay.Menu },
                onMove = { from, to ->
                    pinned = pinned.toMutableList().apply { add(to, removeAt(from)) }
                    selectedSlot = to
                    apps.savePinned(pinned)
                },
                onMoveDone = { overlay = Overlay.None },
                onAllApps = { overlay = Overlay.AllApps },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = EdgeY),
            )
        }

        AnimatedVisibility(overlay == Overlay.AllApps || overlay == Overlay.Picking, enter = fadeIn(), exit = fadeOut()) {
            val picking = overlay == Overlay.Picking
            AllApps(
                repository = apps,
                title = if (picking) "Choose an app to replace ${pinned.getOrNull(selectedSlot)?.label.orEmpty()}" else null,
                onSelect = { app ->
                    if (picking) {
                        pinned = pinned.toMutableList().apply { if (selectedSlot in indices) set(selectedSlot, app) }
                        apps.savePinned(pinned)
                    } else {
                        apps.launch(app)
                    }
                    overlay = Overlay.None
                },
                onDismiss = { overlay = Overlay.None },
            )
        }

        AnimatedVisibility(overlay == Overlay.Menu, enter = fadeIn(), exit = fadeOut()) {
            pinned.getOrNull(selectedSlot)?.let { app ->
                SlotMenu(
                    app = app,
                    onMove = { overlay = Overlay.Moving },
                    onReplace = { overlay = Overlay.Picking },
                    onAppInfo = { overlay = Overlay.None; apps.openAppInfo(app) },
                    onDismiss = { overlay = Overlay.None },
                )
            }
        }
    }
}

private fun isNight(now: LocalDateTime): Boolean {
    val time = now.toLocalTime()
    return time >= NIGHT_STARTS || time < NIGHT_ENDS
}

private fun Modifier.cached(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }

@Composable
private fun RotatingBackground() {
    val context = LocalContext.current
    val source = remember { PhotoSource(context) }
    // Start on the last photo shown; it is already in the image cache, so wake-up isn't black.
    var current by remember { mutableStateOf(source.lastShown()) }

    LaunchedEffect(Unit) {
        val loader = SingletonImageLoader.get(context)
        if (current != null) delay(PHOTO_DURATION_MS)
        while (true) {
            val batch = runCatching { source.nextBatch() }.getOrDefault(emptyList())
            if (batch.isEmpty()) { delay(30_000); continue }
            for (photo in batch) {
                // Fully download before swapping so the crossfade never reveals a half-loaded image.
                val result = loader.execute(ImageRequest.Builder(context).data(photo.url).build())
                if (result !is SuccessResult) continue
                current = photo
                source.reportUsed(photo)
                delay(PHOTO_DURATION_MS)
            }
        }
    }

    Crossfade(current, animationSpec = tween(CROSSFADE_MS), label = "background") { photo ->
        if (photo == null) return@Crossfade
        // Static between crossfades: a continuous zoom redraws the whole screen every frame,
        // which this GPU can't sustain alongside focus animations.
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = photo.url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            BasicText(
                photo.caption,
                style = TextStyle(color = TextSecondary, fontSize = 12.sp, letterSpacing = 0.5.sp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = EdgeX, bottom = EdgeY - 6.dp),
            )
        }
    }
}

@Composable
private fun Scrims() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.7f),
                0.2f to Color.Black.copy(alpha = 0.3f),
                0.4f to Color.Transparent,
                0.55f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.85f),
            ),
        ),
    )
}

@Composable
private fun HomeRow(
    apps: List<TvApp>,
    nowPlayingRequester: FocusRequester?,
    homeFocus: HomeFocus,
    resumeCount: Int,
    rowAlpha: Float,
    focusEnabled: Boolean,
    movingSlot: Int?,
    onLaunch: (TvApp) -> Unit,
    onLongPress: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onMoveDone: () -> Unit,
    onAllApps: () -> Unit,
    modifier: Modifier,
) {
    val requesters = remember(apps.size) { List(apps.size) { FocusRequester() } }
    val allAppsRequester = remember { FocusRequester() }
    var lastFocused by remember { mutableIntStateOf(0) }
    var appliedFocus by remember { mutableIntStateOf(-1) }

    // Each time home becomes visible: highlight the app the user just left, or the middle app
    // (first launch, an app not on the row, or Home pressed while already home).
    LaunchedEffect(homeFocus.sequence, apps.size) {
        if (apps.isEmpty() || appliedFocus == homeFocus.sequence) return@LaunchedEffect
        appliedFocus = homeFocus.sequence
        lastFocused = apps.indexOfFirst { it.packageName == homeFocus.packageName }.takeIf { it >= 0 } ?: (apps.size / 2)
        if (focusEnabled) runCatching { requesters[lastFocused].requestFocus() }
    }

    LaunchedEffect(apps.size, resumeCount, focusEnabled, movingSlot) {
        if (focusEnabled && apps.isNotEmpty()) {
            val target = movingSlot ?: lastFocused
            runCatching { requesters[target.coerceIn(0, requesters.lastIndex)].requestFocus() }
        }
    }
    BackHandler(enabled = movingSlot != null, onBack = onMoveDone)

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.graphicsLayer { alpha = rowAlpha }, horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                modifier = Modifier.onPreviewKeyEvent { event ->
                    // In move mode, Left/Right carry the selected app and OK finishes; nothing else moves focus.
                    val slot = movingSlot ?: return@onPreviewKeyEvent false
                    val key = event.nativeKeyEvent
                    if (!key.keyCode.isMoveKey()) return@onPreviewKeyEvent false
                    if (key.action == AndroidKeyEvent.ACTION_DOWN) {
                        when (key.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> if (slot > 0) onMove(slot, slot - 1)
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> if (slot < apps.lastIndex) onMove(slot, slot + 1)
                            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> onMoveDone()
                        }
                    }
                    true
                },
            ) {
                apps.forEachIndexed { index, app ->
                    AppCircle(
                        app = app,
                        diameter = 92.dp,
                        onClick = { onLaunch(app) },
                        onLongClick = { onLongPress(index) },
                        onFocused = { lastFocused = index },
                        labelOnlyWhenFocused = true,
                        moving = movingSlot == index,
                        // Unselected apps shrink well back so the photo shows through the row.
                        restingScale = UNSELECTED_SCALE,
                        modifier = Modifier
                            .focusRequester(requesters[index])
                            .focusProperties {
                                canFocus = focusEnabled
                                down = allAppsRequester
                                if (index == 0 && nowPlayingRequester != null) left = nowPlayingRequester
                            },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Tile(
                shape = CircleShape,
                onClick = onAllApps,
                onFocused = {},
                modifier = Modifier
                    .height(40.dp)
                    .focusRequester(allAppsRequester)
                    // Up returns to the app you came from rather than whichever circle is nearest.
                    .focusProperties {
                        canFocus = focusEnabled && movingSlot == null
                        if (requesters.isNotEmpty()) up = requesters[lastFocused]
                    },
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GridGlyph(12.dp)
                    BasicText("All apps", style = TextStyle(color = TextPrimary, fontSize = 14.sp, letterSpacing = 0.5.sp))
                }
            }
        }
    }
}

private fun Int.isMoveKey() = this == AndroidKeyEvent.KEYCODE_DPAD_LEFT ||
    this == AndroidKeyEvent.KEYCODE_DPAD_RIGHT ||
    this == AndroidKeyEvent.KEYCODE_DPAD_UP ||
    this == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
    this == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
    this == AndroidKeyEvent.KEYCODE_ENTER

/** A circular app tile with its name centred underneath. */
@Composable
private fun AppCircle(
    app: TvApp,
    diameter: Dp,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    labelOnlyWhenFocused: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    moving: Boolean = false,
    restingScale: Float = 1f,
) {
    var focused by remember { mutableStateOf(false) }
    val labelAlpha by animateFloatAsState(
        when {
            focused || moving -> 1f
            labelOnlyWhenFocused -> 0f
            else -> 0.6f
        },
        tween(150),
        label = "labelAlpha",
    )
    // Fixed width keeps circles evenly spaced however long the name is; the label may overhang.
    Column(Modifier.width(diameter), horizontalAlignment = Alignment.CenterHorizontally) {
        Tile(
            shape = CircleShape,
            onClick = onClick,
            onLongClick = onLongClick,
            onFocused = onFocused,
            onFocusChange = { focused = it },
            ringColor = if (moving) Accent else TextPrimary,
            restingScale = if (moving) 1f else restingScale,
            pinBottom = restingScale < 1f,
            modifier = modifier.size(diameter),
        ) {
            Image(app.circleArt, app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(14.dp))
        BasicText(
            if (moving) "‹  ${app.label}  ›" else app.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = if (moving) Accent else TextPrimary,
                fontSize = 14.sp,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier
                .wrapContentWidth(unbounded = true)
                .widthIn(max = diameter * 2)
                .graphicsLayer { alpha = labelAlpha },
        )
    }
}

/**
 * Focusable surface for the remote. OK is handled here rather than by clickable so that holding
 * it (the remote sends key repeats) can open a menu without also launching on release.
 */
@Composable
private fun Tile(
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocusChange: (Boolean) -> Unit = {},
    ringColor: Color = TextPrimary,
    shape: Shape = RoundedCornerShape(10.dp),
    plain: Boolean = false,
    restingScale: Float = 1f,
    pinBottom: Boolean = false,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        if (focused) 1.12f else restingScale,
        tween(if (restingScale < 1f) 220 else 150, easing = FastOutSlowInEasing),
        label = "tileScale",
    )
    val shade by animateFloatAsState(if (focused) 0f else 0.4f, tween(150), label = "tileShade")
    // Only a release that follows a press on this tile counts: holding OK opens a menu, and the
    // release then lands on the menu's first button, which must not treat it as a click.
    var pressed by remember { mutableStateOf(false) }
    var longPressed by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        onFocusChange(focused)
        if (focused) onFocused()
    }

    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                if (pinBottom) transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .then(if (plain) Modifier else Modifier.clip(shape).background(Color(0xFF1C1C20)))
            .onPreviewKeyEvent { event ->
                val key = event.nativeKeyEvent
                if (!key.isSelectKey()) return@onPreviewKeyEvent false
                when (key.action) {
                    AndroidKeyEvent.ACTION_DOWN -> when {
                        key.repeatCount == 0 -> pressed = true
                        key.repeatCount == 1 && pressed && onLongClick != null -> {
                            longPressed = true
                            onLongClick()
                        }
                    }
                    AndroidKeyEvent.ACTION_UP -> {
                        if (pressed && !longPressed && !key.isCanceled) onClick()
                        pressed = false
                        longPressed = false
                    }
                }
                true
            }
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (!plain) {
            // Shade unfocused tiles rather than fading them, so the background never bleeds through.
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = shade)))
            if (focused) Box(Modifier.matchParentSize().border(3.dp, ringColor, shape))
        }
    }
}

private fun AndroidKeyEvent.isSelectKey() = keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
    keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
    keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER

/**
 * Minimal now-playing badge: cover art with a soft halo in the cover's own colour that swells with
 * the bass line, the title and artist, and play/pause and next buttons that control the session
 * directly. Keeps the last track while fading out.
 */
@Composable
private fun NowPlayingBadge(
    playing: NowPlaying?,
    listening: Boolean,
    focusRequester: FocusRequester,
    focusEnabled: Boolean,
    onOpen: (NowPlaying) -> Unit,
    modifier: Modifier,
) {
    var shown by remember { mutableStateOf(playing) }
    if (playing != null) shown = playing
    val bass = rememberBassLevel(active = listening && playing?.isPlaying == true)
    val pulse by animateFloatAsState(bass, tween(90), label = "bass")
    val halo by animateColorAsState(rememberArtColor(shown) ?: Accent, tween(800), label = "halo")
    val pausedShade by animateFloatAsState(if (shown?.isPlaying == false) 1f else 0f, tween(250), label = "paused")

    AnimatedVisibility(playing != null, enter = fadeIn(tween(600)), exit = fadeOut(tween(600)), modifier = modifier) {
        val track = shown ?: return@AnimatedVisibility
        val strength = if (track.isPlaying) pulse else 0f
        val glow = lerp(halo, Color.White, 0.15f * strength)
        Row(
            Modifier
                .padding(BadgeInset)
                .drawBehind {
                    // A wide, faint wash behind art, text and controls together, so the whole
                    // badge breathes with the bass rather than just the cover.
                    val wash = size.width / 2 + BadgeInset.toPx()
                    withTransform({ scale(scaleX = wash / (size.height * 0.9f), scaleY = 1f, pivot = center) }) {
                        drawCircle(
                            Brush.radialGradient(
                                0f to glow.copy(alpha = 0.05f + 0.16f * strength),
                                1f to Color.Transparent,
                                center = center,
                                radius = size.height * 0.9f,
                            ),
                            radius = size.height * 0.9f,
                        )
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tile(
                onClick = { onOpen(track) },
                onFocused = {},
                plain = true,
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.focusRequester(focusRequester).focusProperties { canFocus = focusEnabled },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .drawBehind {
                                // Brighter core around the cover: a flat centre then a soft falloff,
                                // so it reads as light around the art rather than a blob.
                                val radius = size.minDimension * (0.85f + 0.3f * strength)
                                drawCircle(
                                    Brush.radialGradient(
                                        0f to glow.copy(alpha = 0.14f + 0.5f * strength),
                                        0.45f to glow.copy(alpha = 0.10f + 0.38f * strength),
                                        1f to Color.Transparent,
                                        center = center,
                                        radius = radius,
                                    ),
                                    radius = radius,
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val art = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2E))
                        Crossfade(track.art ?: track.artUri, animationSpec = tween(350), label = "cover") { cover ->
                            when (cover) {
                                is ImageBitmap -> Image(cover, null, contentScale = ContentScale.Crop, modifier = art)
                                is String -> AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = art)
                                else -> Box(art)
                            }
                        }
                        if (pausedShade > 0f) {
                            Box(
                                Modifier.size(44.dp).graphicsLayer { alpha = pausedShade }.clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center,
                            ) { PauseGlyph(TextSecondary) }
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.widthIn(max = 240.dp)) {
                        BasicText(
                            track.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = lerp(TextPrimary, glow, 0.2f + 0.25f * strength), fontSize = 13.sp),
                        )
                        if (track.subtitle.isNotEmpty()) {
                            BasicText(
                                track.subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(
                                    color = lerp(TextSecondary, glow.copy(alpha = 0.8f), 0.25f + 0.3f * strength),
                                    fontSize = 12.sp,
                                ),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            MediaButton(focusEnabled, onClick = track::togglePlayPause) {
                if (track.isPlaying) PauseGlyph(TextPrimary) else PlayGlyph()
            }
            Spacer(Modifier.width(4.dp))
            MediaButton(focusEnabled, onClick = track::skipNext) { NextGlyph() }
        }
    }
}

/** Small round transport button; a faint disc marks focus. */
@Composable
private fun MediaButton(focusEnabled: Boolean, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Tile(
        onClick = onClick,
        onFocused = {},
        onFocusChange = { focused = it },
        plain = true,
        shape = CircleShape,
        modifier = Modifier.size(32.dp).focusProperties { canFocus = focusEnabled },
    ) {
        Box(
            Modifier.fillMaxSize().background(if (focused) Color.White.copy(alpha = 0.18f) else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) { glyph() }
    }
}

@Composable
private fun PlayGlyph() {
    Canvas(Modifier.size(12.dp)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.15f, 0f)
            lineTo(size.width, size.height / 2)
            lineTo(size.width * 0.15f, size.height)
            close()
        }
        drawPath(path, TextPrimary)
    }
}

@Composable
private fun NextGlyph() {
    Canvas(Modifier.size(12.dp)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width * 0.75f, size.height / 2)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, TextPrimary)
        drawRect(TextPrimary, topLeft = Offset(size.width * 0.8f, 0f), size = Size(size.width * 0.2f, size.height))
    }
}

@Composable
private fun PauseGlyph(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val barWidth = size.width / 4
        val barSize = Size(barWidth, size.height * 0.8f)
        drawRect(color, topLeft = Offset(size.width * 0.2f, size.height * 0.1f), size = barSize)
        drawRect(color, topLeft = Offset(size.width * 0.8f - barWidth, size.height * 0.1f), size = barSize)
    }
}

@Composable
private fun SlotMenu(
    app: TvApp,
    onMove: () -> Unit,
    onReplace: () -> Unit,
    onAppInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(app.circleArt, null, contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp).clip(CircleShape))
            Spacer(Modifier.height(14.dp))
            BasicText(app.label, style = TextStyle(color = TextPrimary, fontSize = 18.sp))
            Spacer(Modifier.height(24.dp))
            listOf(
                "Move" to onMove,
                "Replace with another app" to onReplace,
                "App info" to onAppInfo,
            ).forEachIndexed { index, (label, action) ->
                Tile(
                    shape = CircleShape,
                    onClick = action,
                    onFocused = {},
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .height(44.dp)
                        .widthIn(min = 260.dp)
                        .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                ) {
                    BasicText(
                        label,
                        style = TextStyle(color = TextPrimary, fontSize = 15.sp, textAlign = TextAlign.Center),
                        modifier = Modifier.padding(horizontal = 28.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GridGlyph(glyphSize: Dp = 22.dp) {
    Canvas(Modifier.size(glyphSize)) {
        val step = size.width / 2
        for (x in 0..2) for (y in 0..2) {
            drawCircle(TextPrimary, radius = size.width / 14, center = Offset(x * step, y * step))
        }
    }
}

@Composable
private fun AllApps(repository: AppRepository, title: String?, onSelect: (TvApp) -> Unit, onDismiss: () -> Unit) {
    var apps by remember { mutableStateOf(emptyList<TvApp>()) }
    LaunchedEffect(Unit) { apps = repository.all() }
    val first = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)
    LaunchedEffect(apps.isNotEmpty()) { if (apps.isNotEmpty()) runCatching { first.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.97f))) {
        if (title != null) {
            BasicText(
                title,
                style = TextStyle(color = TextSecondary, fontSize = 16.sp, letterSpacing = 0.5.sp),
                modifier = Modifier.padding(start = 72.dp, top = 40.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            contentPadding = PaddingValues(horizontal = 72.dp, vertical = if (title != null) 28.dp else 56.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                Box(contentAlignment = Alignment.Center) {
                    AppCircle(
                        app = app,
                        diameter = 84.dp,
                        onClick = { onSelect(app) },
                        onFocused = {},
                        labelOnlyWhenFocused = false,
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
    }
}
