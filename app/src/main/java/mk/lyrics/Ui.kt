package mk.lyrics

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlin.math.abs

private val Ink = Color(0xFF07070B)

/** Colours taken from Shazam's analysis of the cover ("b:…p:…s:…"), with sensible fallbacks. */
private data class Theme(val background: Color, val primary: Color, val secondary: Color)

private fun themeOf(colors: String?): Theme {
    val map = colors?.let { s -> Regex("""([a-z]):([0-9a-fA-F]{6})""").findAll(s).associate { it.groupValues[1] to it.groupValues[2] } }.orEmpty()
    fun c(k: String) = map[k]?.let { Color(0xFF000000 or it.toLong(16)) }
    val bg = c("b") ?: Color(0xFF22202A)
    return Theme(bg, c("p") ?: Color.White, c("s") ?: Color(0xFFB8B8C8))
}

@Composable
fun LyricsApp(
    state: UiState,
    permission: Boolean?,
    initialOffsetMs: Long,
    onOffset: (Long) -> Unit,
    onResync: () -> Unit,
    onAskPermission: () -> Unit,
) {
    var offsetMs by remember { mutableLongStateOf(initialOffsetMs) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastId by remember { mutableLongStateOf(0L) }
    fun flash(s: String) { toast = s; toastId++ }
    LaunchedEffect(toastId) { if (toast != null) { delay(2_000); toast = null } }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val np = state.now
    val showing = np != null && !state.ended
    val theme = themeOf(np?.match?.colors)
    val bg by animateColorAsState(if (showing) theme.background else Color(0xFF1A1A24), tween(1_200), label = "bg")

    Box(
        Modifier.fillMaxSize().background(Ink)
            .focusRequester(focus).focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val step = if (e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 200 else -200
                        offsetMs = (offsetMs + step).coerceIn(-5_000, 5_000)
                        onOffset(offsetMs)
                        flash(offsetLabel(offsetMs))
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
        // Backdrop: the cover, huge and blurred, washed with its own colour
        Crossfade(targetState = np?.art.takeIf { showing }, animationSpec = tween(1_200), label = "backdrop") { art ->
            Box(Modifier.fillMaxSize()) {
                if (art != null) Image(
                    art, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f; alpha = 0.7f }.blur(90.dp),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(bg.copy(alpha = 0.55f).compositeOver(Ink, 0.35f), Ink.copy(alpha = 0.55f)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.6f)))))

        AnimatedContent(
            targetState = if (showing) np!!.match.key else "",
            transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(500)) },
            label = "screen",
        ) { key ->
            if (key.isEmpty() || np == null) Listening(state, permission, onAskPermission)
            else NowPlayingScreen(np, theme, offsetMs, state.searching || state.busy)
        }

        // Small notices
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

private fun Color.compositeOver(base: Color, amount: Float) = lerp(base, this, amount)

// ---------------------------------------------------------------- listening

@Composable
private fun Listening(state: UiState, permission: Boolean?, onAskPermission: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Pulse(active = permission != false && state.micError == null, fast = state.busy)
            Spacer(Modifier.height(36.dp))
            val (title, sub) = when {
                permission == false -> "Microphone access needed" to "Press OK to allow Lyrics to listen to the music in the room."
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

/** Rings spreading out from a small disc, like sound. */
@Composable
private fun Pulse(active: Boolean, fast: Boolean) {
    val t by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (fast) 1_400 else 2_600, easing = LinearEasing)), label = "t",
    )
    Canvas(Modifier.size(180.dp)) {
        val c = Offset(size.width / 2, size.height / 2)
        val maxR = size.minDimension / 2
        if (active) for (k in 0 until 3) {
            val p = (t + k / 3f) % 1f
            drawCircle(Color(0xFFFF5A7A).copy(alpha = (1 - p) * 0.55f), radius = maxR * (0.25f + 0.75f * p), center = c, style = Stroke(2.dp.toPx()))
        }
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFF7A93), Color(0xFFE23B62)), c, maxR * 0.22f), maxR * 0.22f, c)
        drawCircle(Ink, maxR * 0.05f, c)
    }
}

// ---------------------------------------------------------------- now playing

@Composable
private fun NowPlayingScreen(np: NowPlaying, theme: Theme, offsetMs: Long, listening: Boolean) {
    // Current song position, refreshed every frame
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(np) {
        while (true) withFrameNanos { position = np.positionAt(it) + offsetMs }
    }
    // withFrameNanos gives frame time on the same clock as System.nanoTime
    Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 44.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).shadow(28.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp)).background(Color(0x33FFFFFF)),
            ) {
                np.art?.let { Image(it, np.match.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Spacer(Modifier.height(22.dp))
            Text(np.match.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 30.sp)
            Spacer(Modifier.height(4.dp))
            Text(np.match.artist, color = Color.White.copy(alpha = 0.78f), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            np.match.album?.let {
                Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(20.dp))
            Progress(position, np.durationMs)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(18.dp)) {
                if (listening) {
                    val a by rememberInfiniteTransition(label = "dot").animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
                    Box(Modifier.size(7.dp).graphicsLayer { alpha = a }.background(Color(0xFFFF5A7A), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text("Listening", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.width(64.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val lyrics = np.lyrics
            when {
                lyrics != null && lyrics.synced && lyrics.lines.isNotEmpty() -> SyncedLyrics(lyrics, { position })
                lyrics != null && !lyrics.synced && lyrics.lines.isNotEmpty() -> PlainLyrics(lyrics, { position })
                np.lyricsLoading -> Message("Finding lyrics…")
                lyrics?.instrumental == true -> Message("♪  Instrumental")
                else -> Message("No lyrics found for this song")
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        Text(text, color = Color.White.copy(alpha = 0.45f), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Progress(position: Long, duration: Long?) {
    if (duration == null || duration <= 0) return
    val p = (position.toFloat() / duration).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(Color.White.copy(alpha = 0.85f)))
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        Text(clock(position), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text("-" + clock(duration - position), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
    }
}

private fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Time-synced lyrics: the line being sung sits a third of the way down, bright; the rest are dimmed and
 * slightly blurred with distance. Instrumental breaks (blank lines, the intro) show three filling dots.
 */
@Composable
private fun SyncedLyrics(lyrics: Lyrics, position: () -> Long) {
    // Shown rows: the intro (before the first line) as a break, then every line
    val rows = remember(lyrics) {
        buildList {
            val first = lyrics.lines.first().timeMs
            if (first > 3_000) add(Line(0, ""))
            addAll(lyrics.lines)
        }
    }
    val pos = position()
    var current = -1
    run { var lo = 0; var hi = rows.size - 1; while (lo <= hi) { val m = (lo + hi) / 2; if (rows[m].timeMs <= pos) { current = m; lo = m + 1 } else hi = m - 1 } }
    val tops = remember(lyrics) { mutableStateMapOf<Int, Float>() }
    val scroll = remember(lyrics) { Animatable(0f) }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // Fade the lyrics out towards the top and bottom edges
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.14f to Color.Black, 0.72f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
    ) {
        val anchor = with(LocalDensity.current) { (maxHeight * 0.34f).toPx() }
        val target = anchor - (tops[current.coerceAtLeast(0)] ?: 0f)
        LaunchedEffect(target) { scroll.animateTo(target, tween(650, easing = FastOutSlowInEasing)) }
        Column(
            Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer { translationY = scroll.value },
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            rows.forEachIndexed { i, line ->
                val d = i - current
                Box(Modifier.onGloballyPositioned { tops[i] = it.positionInParent().y }) {
                    if (line.text.isBlank()) {
                        val end = rows.getOrNull(i + 1)?.timeMs ?: (line.timeMs + 5_000)
                        Break(active = d == 0 && end - line.timeMs > 2_500, progress = { ((position() - line.timeMs).toFloat() / (end - line.timeMs)).coerceIn(0f, 1f) })
                    } else LyricLine(line.text, d)
                }
            }
            Spacer(Modifier.height(600.dp))
        }
    }
}

@Composable
private fun LyricLine(text: String, distance: Int) {
    val alpha by animateFloatAsState(
        when { distance == 0 -> 1f; distance < 0 -> 0.28f; distance == 1 -> 0.5f; else -> 0.36f }, tween(450), label = "alpha",
    )
    val scale by animateFloatAsState(if (distance == 0) 1f else 0.94f, tween(450), label = "scale")
    val blur = when (abs(distance)) { 0, 1 -> 0.dp; 2 -> 1.5.dp; else -> 3.dp }
    Text(
        text, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, lineHeight = 46.sp,
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha; scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0.5f)
        }.blur(blur),
    )
}

/** Three dots that fill in over an instrumental break, collapsing when it isn't the current row. */
@Composable
private fun Break(active: Boolean, progress: () -> Float) {
    val h by animateFloatAsState(if (active) 1f else 0f, tween(400), label = "h")
    if (h <= 0.01f) return
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(1_100), RepeatMode.Reverse), label = "b")
    Row(
        Modifier.height((46 * h).dp).graphicsLayer { alpha = h; scaleX = breathe; scaleY = breathe; transformOrigin = TransformOrigin(0f, 0.5f) },
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
private fun PlainLyrics(lyrics: Lyrics, position: () -> Long) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        var contentHeight by remember { mutableStateOf(0f) }
        val viewport = with(LocalDensity.current) { maxHeight.toPx() }
        val duration = lyrics.durationMs
        val p = if (duration != null && duration > 0) (position().toFloat() / duration).coerceIn(0f, 1f) else 0f
        val offset by animateFloatAsState(-(contentHeight - viewport * 0.6f).coerceAtLeast(0f) * p, tween(900), label = "plain")
        Column(
            Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = offset }
                .onGloballyPositioned { contentHeight = it.size.height.toFloat() },
        ) {
            Text("Lyrics (not synced)", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            for (l in lyrics.lines) Text(l.text, color = Color.White.copy(alpha = 0.8f), fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
