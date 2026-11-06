package mk.lyrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** What the listening screen shows while it waits for a song it knows (Up/Down on the remote). */
enum class Visual(val label: String) {
    Aurora("Aurora"), Horizon("Horizon"), Halo("Halo"), Drift("Drift"), Record("Just the record")
}

// The Groove palette: mint and teal, cooling into blue and lavender
private val Mint = Color(0xFF5DCAA5)
private val Foam = Color(0xFFC8F7E6)
private val Teal = Color(0xFF2FA4A9)
private val Blue = Color(0xFF4B7BE5)
private val Lavender = Color(0xFF9B7BEA)
private val Deep = Color(0xFF0E3B33)

private fun palette(t: Float): Color {
    val stops = listOf(Mint, Teal, Blue, Lavender, Mint)
    val x = ((t % 1f) + 1f) % 1f * (stops.size - 1)
    val i = x.toInt().coerceAtMost(stops.size - 2)
    return lerp(stops[i], stops[i + 1], x - i)
}

/**
 * Turns the newest microphone audio into what the visuals draw: [bands] across the spectrum (0–1, low to high),
 * overall [level], [bass], and a short [wave]. Levels follow the music's own loudness, so a quiet room still moves.
 */
class Analyzer {
    companion object {
        const val BANDS = 48
        private const val N = 2048
        private const val RATE = Signature.SAMPLE_RATE
    }

    val bands = FloatArray(BANDS)
    val peaks = FloatArray(BANDS)
    val wave = FloatArray(160)
    var level = 0f; private set
    var bass = 0f; private set
    /** A sudden rise in the bass (a kick drum): 1 right after, fading out. */
    var kick = 0f; private set
    /** Loudness of the newest audio in dBFS. */
    var db = -99f; private set
    /** Seconds since the visuals started, sped up a little by the music. */
    var time = 0f; private set

    private val buf = ShortArray(N)
    private val re = DoubleArray(N)
    private val im = DoubleArray(N)
    private val window = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }
    private val target = FloatArray(BANDS)
    private val edges = IntArray(BANDS + 1).also { e ->
        // Log-spaced from 45 Hz to 7 kHz, each band at least one FFT bin wide
        val lo = ln(45.0); val hi = ln(7_000.0)
        var prev = 0
        for (i in 0..BANDS) {
            val bin = (exp(lo + (hi - lo) * i / BANDS) * N / RATE).toInt()
            e[i] = max(bin, prev + 1).also { prev = it }
        }
    }
    private var ref = -40.0
    private var slowBass = 0f
    private var lastTotal = -1L

    fun update(read: (ShortArray) -> Long, dt: Float) {
        val total = read(buf)
        // The mic delivers audio in 64 ms chunks: only analyse when there's new audio, ease towards it every frame
        if (total > 0 && total != lastTotal) { lastTotal = total; analyse() }
        val attack = 1f - exp(-dt * 28f); val release = 1f - exp(-dt * 7f)
        var sum = 0f; var lows = 0f
        for (i in 0 until BANDS) {
            val k = if (target[i] > bands[i]) attack else release
            bands[i] += (target[i] - bands[i]) * k
            peaks[i] = if (bands[i] > peaks[i]) bands[i] else max(0f, peaks[i] - dt * 0.35f)
            sum += bands[i]
            if (i < 8) lows += bands[i]
        }
        level = sum / BANDS
        bass = lows / 8
        slowBass += (bass - slowBass) * (1f - exp(-dt * 1.5f))
        kick = if (bass > slowBass * 1.35f + 0.06f) 1f else max(0f, kick - dt * 3f)
        time += dt * (0.6f + level * 1.6f)
    }

    private fun analyse() {
        var power = 0.0
        for (i in 0 until N) {
            val v = buf[i] / 32768.0
            power += v * v
            re[i] = v * window[i]; im[i] = 0.0
        }
        db = (10 * log10(max(power / N, 1e-10))).toFloat()
        Fft.transform(re, im)
        var loudest = -200.0
        val bandDb = DoubleArray(BANDS)
        for (b in 0 until BANDS) {
            var p = 0.0
            for (k in edges[b] until min(edges[b + 1], N / 2)) p += re[k] * re[k] + im[k] * im[k]
            p /= max(1, edges[b + 1] - edges[b])
            // Tilt up the highs (+3 dB per octave) so cymbals and voices show next to the bass
            val d = 10 * log10(max(p, 1e-14)) + 3 * log2((edges[b] + 1).toDouble())
            bandDb[b] = d
            if (d > loudest) loudest = d
        }
        // Follow the music's loudness: jump up at once, ease down over a few seconds
        ref = if (loudest > ref) loudest else ref - 0.25
        val silent = db < -72
        for (b in 0 until BANDS) {
            target[b] = if (silent) 0f else ((bandDb[b] - (ref - 42)) / 42).toFloat().coerceIn(0f, 1f).pow(1.6f)
        }
        // A short, evenly spaced waveform for the ribbons
        var peak = 1e-4
        for (i in N - 1024 until N) peak = max(peak, kotlin.math.abs(buf[i] / 32768.0))
        for (i in wave.indices) {
            val j = N - 1024 + i * 1024 / wave.size
            wave[i] = if (silent) 0f else (buf[j] / 32768.0 / peak).toFloat()
        }
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)
}

/** Full-screen music visual, redrawn every frame from the microphone. */
@Composable
fun Visualizer(kind: Visual, audio: (ShortArray) -> Long, modifier: Modifier = Modifier.fillMaxSize()) {
    val analyzer = remember { Analyzer() }
    val stars = remember { Stars() }
    val bars = remember { BarBrushes() }
    var frame by remember { mutableLongStateOf(0L) }
    val current by rememberUpdatedState(kind)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { t ->
            val dt = if (last == 0L) 0.016f else ((t - last) / 1e9f).coerceIn(0.001f, 0.1f)
            last = t
            analyzer.update(audio, dt)
            if (current == Visual.Drift) stars.step(analyzer, dt)
            frame = t
        }
    }
    Canvas(modifier) {
        frame // redraw every frame
        when (kind) {
            Visual.Aurora -> aurora(analyzer)
            Visual.Horizon -> horizon(analyzer, bars)
            Visual.Halo -> halo(analyzer)
            Visual.Drift -> stars.draw(this, analyzer)
            Visual.Record -> Unit
        }
    }
}

// ----- Aurora: soft ribbons of light drifting across the screen, swelling with the music

private fun DrawScope.aurora(a: Analyzer) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF050A0E), Color(0xFF081814), Color(0xFF07070B))))
    val ribbons = 4
    for (r in 0 until ribbons) {
        // Each ribbon follows its own part of the spectrum
        val from = r * Analyzer.BANDS / ribbons; val to = from + Analyzer.BANDS / ribbons
        var e = 0f
        for (i in from until to) e += a.bands[i]
        e /= (to - from)
        val base = h * (0.38f + 0.1f * r)
        val amp = h * (0.03f + 0.22f * e)
        val thick = h * (0.05f + 0.16f * e)
        val t = a.time * (0.35f + 0.12f * r) + r * 1.7f
        val top = Path(); val bottom = Path()
        val steps = 96
        val ys = FloatArray(steps + 1)
        for (s in 0..steps) {
            val x = s / steps.toFloat()
            val env = sin(PI.toFloat() * x).coerceAtLeast(0f).pow(0.8f)
            val wv = a.wave[(x * (a.wave.size - 1)).toInt()] * 0.03f
            val y = base + amp * env * (sin(x * (5.5f + r) + t * 2f) * 0.65f + sin(x * (11f - r * 1.3f) - t * 1.3f) * 0.35f + wv)
            ys[s] = y
            if (s == 0) top.moveTo(0f, y) else top.lineTo(x * w, y)
        }
        // The ribbon hangs down from its bright edge, like an aurora curtain
        val fill = Path().apply {
            addPath(top)
            for (s in steps downTo 0) {
                val x = s / steps.toFloat()
                lineTo(x * w, ys[s] + thick * (0.6f + 0.4f * sin(x * 7f + t)))
            }
            close()
        }
        val c = palette(r / ribbons.toFloat() * 0.9f + a.time * 0.02f)
        drawPath(
            fill,
            Brush.verticalGradient(listOf(c.copy(alpha = 0.16f + 0.4f * e), c.copy(alpha = 0.05f), Color.Transparent), startY = base - amp, endY = base + amp + thick),
        )
        drawPath(top, c.copy(alpha = 0.06f + 0.12f * e), style = Stroke(h * 0.04f, cap = StrokeCap.Round))
        drawPath(top, c.copy(alpha = 0.12f + 0.25f * e), style = Stroke(h * 0.014f, cap = StrokeCap.Round))
        drawPath(top, lerp(c, Color.White, 0.55f).copy(alpha = 0.45f + 0.5f * e), style = Stroke(h * 0.0035f, cap = StrokeCap.Round))
    }
    // Let the ribbons melt into the dark at the sides
    val bg = Color(0xFF07070B)
    drawRect(Brush.horizontalGradient(0f to bg, 0.16f to Color.Transparent, 0.84f to Color.Transparent, 1f to bg))
}

// ----- Horizon: a mirrored spectrum rising from a glowing horizon line, with a reflection below

/** Each band's bar and reflection gradients, made once per screen size rather than every frame. */
private class BarBrushes {
    private var forHeight = -1f
    var bar = emptyList<Brush>(); private set
    var reflection = emptyList<Brush>(); private set
    fun prepare(h: Float, baseY: Float) {
        if (h == forHeight) return
        forHeight = h
        val colors = List(Analyzer.BANDS) { lerp(Mint, Lavender, it / Analyzer.BANDS.toFloat()) }
        // Spans the tallest bar, so a bar's tip shows how high it reaches: brighter towards the top
        bar = colors.map { Brush.verticalGradient(listOf(lerp(it, Foam, 0.55f), it, Deep), startY = baseY - h * 0.42f, endY = baseY) }
        reflection = colors.map { Brush.verticalGradient(listOf(it.copy(alpha = 0.28f), Color.Transparent), startY = baseY, endY = baseY + h * 0.19f) }
    }
}

private fun DrawScope.horizon(a: Analyzer, brushes: BarBrushes) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF07070B), Color(0xFF071512), Color(0xFF07070B))))
    val baseY = h * 0.66f
    val bars = Analyzer.BANDS * 2
    val margin = w * 0.08f
    val slot = (w - 2 * margin) / bars
    val barW = slot * 0.62f
    brushes.prepare(h, baseY)
    // Soft light along the horizon, brighter with the music
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, Mint.copy(alpha = 0.10f + 0.25f * a.level), Color.Transparent), startY = baseY - h * 0.12f, endY = baseY + h * 0.12f),
        topLeft = Offset(0f, baseY - h * 0.12f), size = Size(w, h * 0.24f),
    )
    for (j in 0 until bars) {
        // Lows in the middle, highs out to both sides
        val band = if (j < Analyzer.BANDS) Analyzer.BANDS - 1 - j else j - Analyzer.BANDS
        val v = a.bands[band]
        val x = margin + j * slot + (slot - barW) / 2
        val bh = max(h * 0.006f, v * h * 0.42f)
        drawRoundRect(brushes.bar[band], topLeft = Offset(x, baseY - bh), size = Size(barW, bh), cornerRadius = CornerRadius(barW / 2))
        // Reflection
        drawRoundRect(brushes.reflection[band], topLeft = Offset(x, baseY + h * 0.008f), size = Size(barW, bh * 0.45f), cornerRadius = CornerRadius(barW / 2))
        // Peak marker, falling slowly
        val py = baseY - max(a.peaks[band] * h * 0.42f, h * 0.006f) - h * 0.016f
        drawRoundRect(Foam.copy(alpha = 0.75f), topLeft = Offset(x, py), size = Size(barW, h * 0.006f), cornerRadius = CornerRadius(barW / 2))
    }
    drawLine(Foam.copy(alpha = 0.35f), Offset(margin, baseY + h * 0.004f), Offset(w - margin, baseY + h * 0.004f), strokeWidth = 1.5f)
}

// ----- Halo: a ring of light around a record's grooves, rays pulsing with the spectrum, breathing on the bass

private fun DrawScope.halo(a: Analyzer) {
    val w = size.width; val h = size.height
    val c = Offset(w / 2, h / 2)
    drawRect(Brush.radialGradient(listOf(Color(0xFF0B1B17), Color(0xFF07070B)), c, w * 0.6f))
    val r = h * (0.2f + 0.025f * a.bass + 0.02f * a.kick)
    // Glow behind the ring
    drawCircle(Brush.radialGradient(listOf(Mint.copy(alpha = 0.10f + 0.25f * a.level), Color.Transparent), c, r * 2.2f), r * 2.2f, c)
    // Grooves inside, like the Groove record
    for (k in 1..7) drawCircle(Color(0xFF1C3A32).copy(alpha = 0.7f), r * (0.35f + 0.08f * k), c, style = Stroke(1.5f))
    val rays = Analyzer.BANDS * 2
    val spin = a.time * 0.08f
    for (j in 0 until rays) {
        // Mirrored left and right so the ring is balanced
        val band = if (j < Analyzer.BANDS) j else rays - 1 - j
        val v = a.bands[band]
        val ang = spin + 2 * PI.toFloat() * j / rays - PI.toFloat() / 2
        val dir = Offset(cos(ang), sin(ang))
        val len = h * (0.012f + 0.2f * v)
        val col = palette(band / Analyzer.BANDS.toFloat() * 0.75f)
        val start = c + dir * (r + h * 0.012f)
        drawLine(col.copy(alpha = 0.25f), start, start + dir * len, strokeWidth = h * 0.016f, cap = StrokeCap.Round)
        drawLine(lerp(col, Foam, 0.4f), start, start + dir * len, strokeWidth = h * 0.006f, cap = StrokeCap.Round)
    }
    drawCircle(lerp(Mint, Foam, a.kick * 0.6f).copy(alpha = 0.85f), r, c, style = Stroke(h * 0.006f))
    drawCircle(Mint, r * 0.16f, c)
    drawCircle(Color(0xFF09120F), r * 0.04f, c)
}

// ----- Drift: flying through a field of soft lights, faster when the music swells, a burst on every kick

private class Stars {
    private class Star(var angle: Float, var z: Float, var hue: Float, var spin: Float)
    private val rnd = Random(7)
    private val stars = List(260) { Star(rnd.nextFloat() * 2 * PI.toFloat(), rnd.nextFloat(), rnd.nextFloat(), (rnd.nextFloat() - 0.5f) * 0.2f) }

    fun step(a: Analyzer, dt: Float) {
        val speed = 0.06f + 0.5f * a.level + 0.9f * a.kick
        for (s in stars) {
            s.z += dt * speed * (0.4f + s.z)
            s.angle += dt * s.spin
            if (s.z > 1.2f) { s.z = rnd.nextFloat() * 0.05f; s.angle = rnd.nextFloat() * 2 * PI.toFloat(); s.hue = rnd.nextFloat() }
        }
    }

    fun draw(d: DrawScope, a: Analyzer) = with(d) {
        val w = size.width; val h = size.height
        val c = Offset(w / 2, h / 2)
        drawRect(Color(0xFF06080B))
        drawCircle(Brush.radialGradient(listOf(Teal.copy(alpha = 0.08f + 0.22f * a.bass), Color.Transparent), c, h * 0.6f), h * 0.6f, c)
        val far = sqrt(w * w + h * h) / 2
        for (s in stars) {
            val dist = s.z.pow(2.2f) * far
            val p = c + Offset(cos(s.angle), sin(s.angle)) * dist
            val radius = h * (0.0015f + 0.007f * s.z) * (1f + a.kick * 0.6f)
            val col = palette(s.hue * 0.8f)
            val alpha = (s.z * 1.4f).coerceAtMost(1f) * (0.45f + 0.55f * a.level)
            // A short streak behind each light when moving fast
            val tail = c + Offset(cos(s.angle), sin(s.angle)) * (dist * (1f - 0.06f - 0.25f * a.kick))
            drawLine(col.copy(alpha = alpha * 0.35f), tail, p, strokeWidth = radius, cap = StrokeCap.Round)
            drawCircle(lerp(col, Color.White, 0.3f).copy(alpha = alpha), radius, p)
        }
    }
}
