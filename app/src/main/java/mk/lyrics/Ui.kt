package mk.lyrics

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlin.math.abs

private val Ink = Color(0xFF07070B)

/** The looks to choose from (Up/Down on the remote). */
enum class Design(val label: String) { Stage("Stage"), Glass("Glass"), Cinema("Cinema") }

/** Colours taken from Shazam's analysis of the cover ("b:…p:…s:…"), with sensible fallbacks. */
private data class Theme(val background: Color, val primary: Color, val secondary: Color) {
    /** A colour from the cover that reads well as an accent on dark backgrounds. */
    val accent: Color get() = listOf(primary, secondary, background).maxBy { vivid(it) }.let { lerp(it, Color.White, 0.15f) }
    private fun vivid(c: Color): Float {
        val max = maxOf(c.red, c.green, c.blue); val min = minOf(c.red, c.green, c.blue)
        val lum = (max + min) / 2
        return (max - min) - abs(lum - 0.6f) * 0.5f
    }
}

private fun themeOf(colors: String?): Theme {
    val map = colors?.let { s -> Regex("""([a-z]):([0-9a-fA-F]{6})""").findAll(s).associate { it.groupValues[1] to it.groupValues[2] } }.orEmpty()
    fun c(k: String) = map[k]?.let { Color(0xFF000000 or it.toLong(16)) }
    return Theme(c("b") ?: Color(0xFF22202A), c("p") ?: Color.White, c("s") ?: Color(0xFF5DCAA5))
}

@Composable
fun LyricsApp(
    state: UiState,
    permission: Boolean?,
    initialOffsetMs: Long,
    onOffset: (Long) -> Unit,
    initialDesign: Design,
    onDesign: (Design) -> Unit,
    onResync: () -> Unit,
    onAskPermission: () -> Unit,
) {
    var offsetMs by remember { mutableLongStateOf(initialOffsetMs) }
    var design by remember { mutableStateOf(initialDesign) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastId by remember { mutableLongStateOf(0L) }
    fun flash(s: String) { toast = s; toastId++ }
    LaunchedEffect(toastId) { if (toast != null) { delay(2_000); toast = null } }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val np = state.now
    val showing = np != null && !state.ended

    Box(
        Modifier.fillMaxSize().background(Ink)
            .focusRequester(focus).focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (val code = e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        offsetMs = (offsetMs + if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 200 else -200).coerceIn(-5_000, 5_000)
                        onOffset(offsetMs)
                        flash(offsetLabel(offsetMs))
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val all = Design.entries
                        design = all[(design.ordinal + if (code == KeyEvent.KEYCODE_DPAD_DOWN) 1 else all.size - 1) % all.size]
                        onDesign(design)
                        flash("Look: ${design.label}")
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (permission == false) onAskPermission() else { onResync(); flash("Listening…") }
                        true
                    }
                    else -> false
                }
            },
    ) {
        AnimatedContent(
            targetState = (if (showing) np!!.match.key else "") to design,
            transitionSpec = { fadeIn(tween(900)) togetherWith fadeOut(tween(700)) },
            label = "screen",
        ) { (key, d) ->
            if (key.isEmpty() || np == null) Listening(state, permission)
            else NowPlayingScreen(np, d, offsetMs, state.searching || state.busy)
        }

        AnimatedVisibility(toast != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp), enter = fadeIn(), exit = fadeOut()) {
            Text(
                toast.orEmpty(), color = Color.White, fontSize = 16.sp,
                modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(50)).padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
        if (state.offline) Text(
            "No internet connection", color = Color(0xFFFFB4A8), fontSize = 14.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(28.dp),
        )
    }
}

private fun offsetLabel(ms: Long) = when {
    ms == 0L -> "Lyrics timing: exact"
    ms > 0 -> "Lyrics %.1f s earlier".format(ms / 1000.0)
    else -> "Lyrics %.1f s later".format(-ms / 1000.0)
}

// ---------------------------------------------------------------- listening

@Composable
private fun Listening(state: UiState, permission: Boolean?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Pulse(active = permission != false && state.micError == null, fast = state.busy)
            Spacer(Modifier.height(36.dp))
            val (title, sub) = when {
                permission == false -> "Microphone access needed" to "Press OK to allow Groove to listen to the music in the room."
                state.micError != null -> "Can't use the microphone" to state.micError
                else -> "Listening…" to null
            }
            Text(title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            if (sub != null) {
                Spacer(Modifier.height(10.dp))
                Text(sub, color = Color(0xFFA9A9BA), fontSize = 17.sp)
            }
            val last = state.now
            if (last != null && state.ended) {
                Spacer(Modifier.height(44.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(Color(0x22FFFFFF), RoundedCornerShape(14.dp)).padding(10.dp)) {
                    last.art?.let { Image(it, null, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Last played", color = Color(0xFF8A8A9A), fontSize = 11.sp)
                        Text("${last.match.title} · ${last.match.artist}", color = Color(0xFFDADAE6), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

/** The Groove record turning slowly while it listens; still and dimmed when it can't. */
@Composable
private fun Pulse(active: Boolean, fast: Boolean) {
    val turn by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(4_000, easing = LinearEasing)), label = "turn",
    )
    val grooveColor = Color(0xFF1C3A32)
    val dim = Color(0xFF1E6B55); val bright = Color(0xFFC8F7E6)
    Canvas(Modifier.size(200.dp).graphicsLayer { rotationZ = if (active) turn else 0f; alpha = if (active) 1f else 0.4f }) {
        val c = Offset(size.width / 2, size.height / 2)
        val unit = size.minDimension / 2 / 134f // the logo is drawn on a 134-radius disc
        drawCircle(Color(0xFF09120F), 134 * unit, c)
        var i = 0
        for (r in 36 until 132 step 7) {
            drawCircle(grooveColor, r * unit, c, style = Stroke((if (i++ % 3 == 0) 2.4f else 1.6f) * unit))
        }
        // The lit groove brightens towards the needle point
        val r = 92 * unit
        val start = -90f; val sweep = 290f; val n = 48
        for (k in 0 until n) {
            val t = ((k + 0.5f) / n).let { it * it.coerceAtLeast(0f) }
            drawArc(
                lerp(dim, bright, t), start + sweep * k / n, sweep / n + 0.6f, useCenter = false,
                topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
                style = Stroke(7 * unit, cap = if (k == 0 || k == n - 1) androidx.compose.ui.graphics.StrokeCap.Round else androidx.compose.ui.graphics.StrokeCap.Butt),
            )
        }
        val end = Math.toRadians((start + sweep).toDouble())
        drawCircle(Color(0xFFF2FFFA), 8 * unit, Offset(c.x + r * kotlin.math.cos(end).toFloat(), c.y + r * kotlin.math.sin(end).toFloat()))
        drawCircle(Color(0xFF5DCAA5), 22 * unit, c)
        drawCircle(Color(0xFF09120F), 5 * unit, c)
    }
}

// ---------------------------------------------------------------- now playing

@Composable
private fun NowPlayingScreen(np: NowPlaying, design: Design, offsetMs: Long, listening: Boolean) {
    // Song position, refreshed every frame (frame time is on the System.nanoTime clock)
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(np) { while (true) withFrameNanos { position = np.positionAt(it) + offsetMs } }
    val theme = themeOf(np.match.colors)
    val pos = { position }
    // Move on to the next artist photo now and then
    var photoTurn by remember { mutableLongStateOf(0L) }
    LaunchedEffect(np.match.key) { while (true) { delay(PHOTO_EVERY_MS); photoTurn++ } }
    val photos = np.artistImages
    val photo = if (photos.isEmpty()) null else photos[(photoTurn % photos.size).toInt()]
    CompositionLocalProvider(LocalPhoto provides photo) { Designs(np, design, theme, pos, listening) }
}

private const val PHOTO_EVERY_MS = 40_000L
private val LocalPhoto = staticCompositionLocalOf<ImageBitmap?> { null }

@Composable
private fun Designs(np: NowPlaying, design: Design, theme: Theme, pos: () -> Long, listening: Boolean) {
    when (design) {
        Design.Stage -> Stage(np, theme, pos, listening)
        Design.Glass -> Glass(np, theme, pos, listening)
        Design.Cinema -> Cinema(np, theme, pos, listening)
    }
}

/** The artist's photo; without one, the cover blown up and blurred so it reads as colour and texture. */
@Composable
private fun Backdrop(np: NowPlaying, modifier: Modifier, blur: Dp = 0.dp, alignment: Alignment = Alignment.TopCenter, scale: Float = 1f) {
    val artist = LocalPhoto.current
    Crossfade(artist ?: np.art, modifier, animationSpec = tween(2_000), label = "backdrop") { img ->
        if (img != null) {
            val photo = img !== np.art
            Image(
                img, null, contentScale = ContentScale.Crop, alignment = alignment,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { val s = if (photo) scale else 1.4f * scale; scaleX = s; scaleY = s }
                    .blur(if (photo) blur else maxOf(blur, 70.dp)),
            )
        }
    }
}

// ----- Stage: the artist on the left, lyrics large on the right over a dark fade, small album tag

@Composable
private fun Stage(np: NowPlaying, theme: Theme, position: () -> Long, listening: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = with(LocalDensity.current) { maxWidth.toPx() }
        Backdrop(np, Modifier.fillMaxHeight().fillMaxWidth(0.68f).align(Alignment.CenterStart), alignment = Alignment.TopCenter)
        // The photo dissolves into the dark on the right and bottom
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Ink.copy(alpha = 0.1f), 0.38f to Ink.copy(alpha = 0.55f), 0.66f to Ink.copy(alpha = 0.96f), 1f to Ink)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Ink.copy(alpha = 0.85f))))
        // A wash of the album's colour from the corner
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(theme.accent.copy(alpha = 0.18f), Color.Transparent), Offset(w, 0f), 1400f)))

        Box(Modifier.fillMaxHeight().fillMaxWidth(0.6f).align(Alignment.CenterEnd).padding(end = 72.dp, top = 24.dp, bottom = 120.dp)) {
            LyricsArea(np, position, LyricStyle(size = 42.sp, align = TextAlign.Start, anchor = 0.36f))
        }
        Box(Modifier.fillMaxWidth(0.6f).align(Alignment.BottomEnd).padding(end = 72.dp, bottom = 44.dp)) {
            AlbumTag(np, position, theme, listening, Modifier)
        }
    }
}

// ----- Glass: a softened photo behind a frosted panel of centred lyrics

@Composable
private fun Glass(np: NowPlaying, theme: Theme, position: () -> Long, listening: Boolean) {
    Box(Modifier.fillMaxSize()) {
        Backdrop(np, Modifier.fillMaxSize(), blur = 10.dp, alignment = Alignment.TopCenter, scale = 1.05f)
        Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.42f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(theme.background.copy(alpha = 0.25f), Ink.copy(alpha = 0.6f)))))

        // Song header
        Row(Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(np, 64.dp, 10.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(np.match.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 520.dp))
                Text(listOfNotNull(np.match.artist, np.match.album).joinToString("  ·  "), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 520.dp))
            }
            if (listening) { Spacer(Modifier.width(18.dp)); ListeningDot() }
        }

        // The frosted panel
        val shape = RoundedCornerShape(32.dp)
        Box(
            Modifier.align(Alignment.Center).padding(top = 70.dp).fillMaxWidth(0.66f).fillMaxHeight(0.72f)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.05f))))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.06f))), shape),
        ) {
            Box(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 14.dp, bottom = 64.dp)) {
                LyricsArea(np, position, LyricStyle(size = 36.sp, align = TextAlign.Center, anchor = 0.44f))
            }
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 48.dp, vertical = 22.dp)) {
                ProgressLine(position, np.durationMs, Color.White.copy(alpha = 0.9f))
            }
        }
    }
}

// ----- Cinema: a slowly drifting photo, three lines of lyrics in the middle, a tiny tag in the corner

@Composable
private fun Cinema(np: NowPlaying, theme: Theme, position: () -> Long, listening: Boolean) {
    val drift by rememberInfiniteTransition(label = "kenburns").animateFloat(0f, 1f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Reverse), label = "d")
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val s = 1.08f + 0.1f * drift
            scaleX = s; scaleY = s; translationX = (drift - 0.5f) * 60.dp.toPx()
        }) {
            Backdrop(np, Modifier.fillMaxSize(), blur = 2.dp, alignment = Alignment.TopCenter)
        }
        Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.5f)))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(0.35f to Color.Transparent, 1f to Ink.copy(alpha = 0.85f), center = Offset.Unspecified, radius = 1500f)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Ink.copy(alpha = 0.7f))))

        Box(Modifier.align(Alignment.Center).fillMaxWidth(0.8f).fillMaxHeight().padding(vertical = 40.dp)) {
            LyricsArea(np, position, LyricStyle(size = 48.sp, align = TextAlign.Center, anchor = 0.40f, cinematic = true))
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (listening) { ListeningDot(); Spacer(Modifier.width(14.dp)) }
            Column(horizontalAlignment = Alignment.End) {
                Text(np.match.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.dp))
                Text(np.match.artist, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.dp))
                np.match.album?.let {
                    Text(it, color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Cover(np, 60.dp, 8.dp)
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            val d = np.durationMs
            if (d != null && d > 0) Box(Modifier.fillMaxWidth((position().toFloat() / d).coerceIn(0f, 1f)).height(3.dp).background(theme.accent.copy(alpha = 0.9f)))
        }
    }
}

// ---------------------------------------------------------------- shared pieces

@Composable
private fun Cover(np: NowPlaying, size: Dp, corner: Dp) {
    Box(Modifier.size(size).shadow(14.dp, RoundedCornerShape(corner)).clip(RoundedCornerShape(corner)).background(Color(0x33FFFFFF))) {
        np.art?.let { Image(it, np.match.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
private fun ListeningDot() {
    val a by rememberInfiniteTransition(label = "dot").animateFloat(0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(8.dp).graphicsLayer { alpha = a }.background(Color(0xFF5DCAA5), CircleShape))
}

/** Small cover with title, artist and a slim progress bar. */
@Composable
private fun AlbumTag(np: NowPlaying, position: () -> Long, theme: Theme, listening: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Cover(np, 72.dp, 10.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.width(300.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(np.match.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (listening) { Spacer(Modifier.width(10.dp)); ListeningDot() }
            }
            Text(listOfNotNull(np.match.artist, np.match.album).joinToString("  ·  "), color = Color.White.copy(alpha = 0.62f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            ProgressLine(position, np.durationMs, theme.accent)
        }
    }
}

@Composable
private fun ProgressLine(position: () -> Long, duration: Long?, color: Color) {
    if (duration == null || duration <= 0) return
    val pos = position()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(clock(pos), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxWidth((pos.toFloat() / duration).coerceIn(0f, 1f)).fillMaxHeight().background(color))
        }
        Spacer(Modifier.width(10.dp))
        Text(clock(duration), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
    }
}

private fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(s / 60, s % 60)
}

private data class LyricStyle(val size: TextUnit, val align: TextAlign, val anchor: Float, val cinematic: Boolean = false)

private val TextShadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 18f)

@Composable
private fun BoxScope.LyricsArea(np: NowPlaying, position: () -> Long, style: LyricStyle) {
    val lyrics = np.lyrics
    when {
        lyrics != null && lyrics.synced && lyrics.lines.isNotEmpty() -> SyncedLyrics(lyrics, position, style)
        lyrics != null && !lyrics.synced && lyrics.lines.isNotEmpty() -> PlainLyrics(lyrics, position, style)
        np.lyricsLoading -> Message("Finding lyrics…", style)
        lyrics?.instrumental == true -> Message("♪  Instrumental", style)
        else -> Message("No lyrics for this song", style)
    }
}

@Composable
private fun Message(text: String, style: LyricStyle) {
    Box(Modifier.fillMaxSize(), contentAlignment = if (style.align == TextAlign.Center) Alignment.Center else Alignment.CenterStart) {
        Text(text, color = Color.White.copy(alpha = 0.5f), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, style = TextStyle(shadow = TextShadow))
    }
}

/**
 * Time-synced lyrics: the line being sung sits at [LyricStyle.anchor] of the height, bright; the rest are dimmed and
 * slightly blurred with distance. Instrumental breaks (blank lines, the intro) show three filling dots.
 */
@Composable
private fun SyncedLyrics(lyrics: Lyrics, position: () -> Long, style: LyricStyle) {
    val rows = remember(lyrics) {
        buildList {
            if (lyrics.lines.first().timeMs > 3_000) add(Line(0, ""))
            addAll(lyrics.lines)
        }
    }
    val pos = position()
    var current = -1
    run { var lo = 0; var hi = rows.size - 1; while (lo <= hi) { val m = (lo + hi) / 2; if (rows[m].timeMs <= pos) { current = m; lo = m + 1 } else hi = m - 1 } }
    // A short pause between lines (a blank too brief for the dots): keep the last sung line lit
    if (current > 0 && rows[current].text.isBlank()) {
        val end = rows.getOrNull(current + 1)?.timeMs ?: (rows[current].timeMs + 5_000)
        if (end - rows[current].timeMs <= 2_500) {
            var k = current - 1
            while (k > 0 && rows[k].text.isBlank()) k--
            if (rows[k].text.isNotBlank()) current = k
        }
    }
    val tops = remember(lyrics) { mutableStateMapOf<Int, Float>() }
    val heights = remember(lyrics) { mutableStateMapOf<Int, Int>() }
    val scroll = remember(lyrics) { Animatable(0f) }
    val centered = style.align == TextAlign.Center

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val stops = if (style.cinematic) arrayOf(0f to Color.Transparent, 0.26f to Color.Black, 0.62f to Color.Black, 0.9f to Color.Transparent)
                else arrayOf(0f to Color.Transparent, 0.14f to Color.Black, 0.74f to Color.Black, 1f to Color.Transparent)
                drawRect(Brush.verticalGradient(*stops), blendMode = BlendMode.DstIn)
            },
    ) {
        val anchor = with(LocalDensity.current) { (maxHeight * style.anchor).toPx() }
        val c = current.coerceAtLeast(0)
        // Centre the current line on the anchor
        val target = anchor - (tops[c] ?: 0f) - (heights[c] ?: 0) / 2f
        LaunchedEffect(target) { scroll.animateTo(target, tween(700, easing = FastOutSlowInEasing)) }
        Column(
            Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer { translationY = scroll.value },
            verticalArrangement = Arrangement.spacedBy(if (style.cinematic) 26.dp else 20.dp),
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            rows.forEachIndexed { i, line ->
                val d = i - current
                Box(Modifier.onGloballyPositioned { tops[i] = it.positionInParent().y; heights[i] = it.size.height }) {
                    if (line.text.isBlank()) {
                        val end = rows.getOrNull(i + 1)?.timeMs ?: (line.timeMs + 5_000)
                        Break(active = d == 0 && end - line.timeMs > 2_500, centered = centered, progress = { ((position() - line.timeMs).toFloat() / (end - line.timeMs)).coerceIn(0f, 1f) })
                    } else LyricLine(line.text, d, style)
                }
            }
            Spacer(Modifier.height(600.dp))
        }
    }
}

@Composable
private fun LyricLine(text: String, distance: Int, style: LyricStyle) {
    val alpha by animateFloatAsState(
        when {
            distance == 0 -> 1f
            style.cinematic -> if (abs(distance) == 1) 0.4f else 0.16f
            distance < 0 -> 0.3f
            distance == 1 -> 0.55f
            else -> 0.38f
        },
        tween(500), label = "alpha",
    )
    val scale by animateFloatAsState(if (distance == 0) 1f else if (style.cinematic) 0.72f else 0.93f, tween(500), label = "scale")
    val blur = when (abs(distance)) { 0, 1 -> 0.dp; 2 -> 1.5.dp; else -> 3.dp }
    val centered = style.align == TextAlign.Center
    Text(
        text, color = Color.White, fontSize = style.size, fontWeight = FontWeight.Bold,
        lineHeight = style.size * 1.18f, textAlign = style.align,
        style = TextStyle(shadow = TextShadow),
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha; scaleX = scale; scaleY = scale
            transformOrigin = if (centered) TransformOrigin(0.5f, 0.5f) else TransformOrigin(0f, 0.5f)
        }.blur(blur),
    )
}

/** Three dots that fill in over an instrumental break, collapsing when it isn't the current row. */
@Composable
private fun Break(active: Boolean, centered: Boolean, progress: () -> Float) {
    val h by animateFloatAsState(if (active) 1f else 0f, tween(400), label = "h")
    if (h <= 0.01f) return
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(1_100), RepeatMode.Reverse), label = "b")
    Row(
        Modifier.height((46 * h).dp).graphicsLayer {
            alpha = h; scaleX = breathe; scaleY = breathe
            transformOrigin = if (centered) TransformOrigin(0.5f, 0.5f) else TransformOrigin(0f, 0.5f)
        },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val p = progress()
        for (k in 0 until 3) {
            val fill = ((p * 3) - k).coerceIn(0f, 1f)
            Box(Modifier.size(14.dp).background(Color.White.copy(alpha = 0.25f + 0.75f * fill), CircleShape))
        }
    }
}

/** Unsynced lyrics: scrolled along with the song when its length is known. */
@Composable
private fun PlainLyrics(lyrics: Lyrics, position: () -> Long, style: LyricStyle) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        var contentHeight by remember { mutableStateOf(0f) }
        val viewport = with(LocalDensity.current) { maxHeight.toPx() }
        val duration = lyrics.durationMs
        val p = if (duration != null && duration > 0) (position().toFloat() / duration).coerceIn(0f, 1f) else 0f
        val offset by animateFloatAsState(-(contentHeight - viewport * 0.6f).coerceAtLeast(0f) * p, tween(900), label = "plain")
        val centered = style.align == TextAlign.Center
        Column(
            Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = offset }
                .onGloballyPositioned { contentHeight = it.size.height.toFloat() },
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Text("Lyrics (not synced)", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            for (l in lyrics.lines) Text(
                l.text, color = Color.White.copy(alpha = 0.85f), fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold,
                textAlign = style.align, style = TextStyle(shadow = TextShadow),
            )
        }
    }
}
