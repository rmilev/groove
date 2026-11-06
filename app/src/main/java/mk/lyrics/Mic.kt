package mk.lyrics

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.log10
import kotlin.math.sqrt

/** A stretch of recorded audio and the moment (System.nanoTime) its first sample was heard. */
class Clip(val samples: ShortArray, val startNanos: Long) {
    val durationMs get() = samples.size * 1000L / Signature.SAMPLE_RATE
}

/**
 * Records the TV's microphone continuously into a ring buffer of the last [SECONDS] seconds,
 * with the time each sample was heard, plus a loudness history for spotting gaps between songs.
 */
class Mic(
    /** Test hook: play this raw 16 kHz mono PCM file in real time instead of recording (then silence). */
    private val testFile: java.io.File? = null,
) {
    companion object {
        private const val RATE = Signature.SAMPLE_RATE
        private const val SECONDS = 16
        private const val CHUNK = 1024 // 64 ms
        const val LEVEL_WINDOW_MS = CHUNK * 1000L / RATE
    }

    private val ring = ShortArray(RATE * SECONDS)
    private var written = 0L // total frames recorded
    private val lock = Object()
    private var thread: Thread? = null
    @Volatile private var running = false

    // Clock mapping: frame [anchorFrame] was heard at [anchorNanos]
    private var anchorFrame = 0L
    private var anchorNanos = 0L

    /** Loudness of each 64 ms chunk in dBFS, newest last (about 30 s). */
    private val levels = ArrayDeque<Float>()

    @Volatile var error: String? = null
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "mic").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun feed(samples: ShortArray, n: Int, now: Long) {
        var sum = 0.0
        for (i in 0 until n) sum += samples[i] * samples[i].toDouble()
        val db = (20 * log10(maxOf(sqrt(sum / maxOf(n, 1)), 1.0) / 32768)).toFloat()
        synchronized(lock) {
            for (i in 0 until n) ring[((written + i) % ring.size).toInt()] = samples[i]
            written += n
            anchorFrame = written; anchorNanos = now
            levels.addLast(db)
            while (levels.size > 30_000 / LEVEL_WINDOW_MS) levels.removeFirst()
        }
    }

    private fun playTestFile(file: java.io.File) {
        val bytes = file.readBytes()
        val all = ShortArray(bytes.size / 2).also { java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        val start = System.nanoTime()
        var pos = 0
        val buf = ShortArray(CHUNK)
        while (running) {
            val due = start + (pos + CHUNK).toLong() * 1_000_000_000L / RATE
            val wait = (due - System.nanoTime()) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
            for (i in 0 until CHUNK) buf[i] = if (pos + i < all.size) all[pos + i] else 0
            feed(buf, CHUNK, due)
            pos += CHUNK
        }
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        testFile?.let { Log.i("Mic", "playing test file $it"); playTestFile(it); return }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // UNPROCESSED skips the noise suppression meant for voice, which would also eat music
        val rec = listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC).firstNotNullOfOrNull { src ->
            runCatching { AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE)) }
                .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED || run { it.release(); false } }
        }
        if (rec == null) { error = "The microphone isn't available"; running = false; return }
        error = null
        val buf = ShortArray(CHUNK)
        val ts = AudioTimestamp()
        try {
            rec.startRecording()
            while (running) {
                var n = 0
                while (n < CHUNK && running) {
                    val r = rec.read(buf, n, CHUNK - n)
                    if (r < 0) { error = "Microphone error $r"; return }
                    n += r
                }
                val now = System.nanoTime()
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                val db = (20 * log10(maxOf(sqrt(sum / maxOf(n, 1)), 1.0) / 32768)).toFloat()
                synchronized(lock) {
                    for (i in 0 until n) ring[((written + i) % ring.size).toInt()] = buf[i]
                    written += n
                    // Prefer the audio system's own record of when frames were captured
                    if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        anchorFrame = ts.framePosition; anchorNanos = ts.nanoTime
                    } else {
                        anchorFrame = written; anchorNanos = now
                    }
                    levels.addLast(db)
                    while (levels.size > 30_000 / LEVEL_WINDOW_MS) levels.removeFirst()
                }
            }
        } catch (e: Exception) {
            Log.w("Mic", "recording stopped", e); error = e.message
        } finally {
            runCatching { rec.stop() }; rec.release()
        }
    }

    /** Frames recorded so far; a position that [since] can be measured from. */
    fun mark(): Long = synchronized(lock) { written }

    /** The newest [ms] of audio, but nothing recorded before [since] (a mark). */
    fun clip(ms: Long, since: Long = 0): Clip? = synchronized(lock) {
        val want = (ms * RATE / 1000).coerceAtMost(ring.size.toLong())
        val from = maxOf(written - want, since, written - ring.size)
        val count = (written - from).toInt()
        if (count <= 0) return null
        val out = ShortArray(count) { ring[((from + it) % ring.size).toInt()] }
        Clip(out, anchorNanos + (from - anchorFrame) * 1_000_000_000L / RATE)
    }

    /** Milliseconds of audio recorded since [mark]. */
    fun recordedSince(mark: Long): Long = synchronized(lock) { (written - mark) * 1000 / RATE }

    /** Average loudness (dBFS) over the last [ms]. */
    fun level(ms: Long): Float = synchronized(lock) {
        val n = (ms / LEVEL_WINDOW_MS).toInt().coerceIn(1, maxOf(levels.size, 1))
        if (levels.isEmpty()) -99f else levels.takeLast(n).average().toFloat()
    }

    /** Quietest second in the last 30 s: the room's background noise. */
    fun floor(): Float = synchronized(lock) {
        if (levels.size < 16) -99f else levels.windowed(16, 8).minOf { it.average() }.toFloat()
    }

    /** Loudness history (oldest first), for the gap detector. */
    fun recentLevels(ms: Long): List<Float> = synchronized(lock) { levels.takeLast((ms / LEVEL_WINDOW_MS).toInt()) }
}
