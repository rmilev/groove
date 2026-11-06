package mk.lyrics

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max

/** The song being heard and how to tell where in it we are at any moment. */
data class NowPlaying(
    val match: Match,
    val lyrics: Lyrics? = null,
    val lyricsLoading: Boolean = true,
    val art: ImageBitmap? = null,
    /** At [anchorNanos] (System.nanoTime) the song was at [anchorMs]. */
    val anchorNanos: Long,
    val anchorMs: Long,
    /** Playback speed relative to the original; 1.0 except for a turntable running slightly fast or slow. */
    val rate: Double = 1.0,
) {
    fun positionAt(nanos: Long) = anchorMs + ((nanos - anchorNanos) / 1_000_000.0 * rate).toLong()
    val durationMs get() = lyrics?.durationMs
}

data class UiState(
    val now: NowPlaying? = null,
    /** Looking for a (new) song. */
    val searching: Boolean = true,
    /** The song shown is over (or none yet): show the listening screen. */
    val ended: Boolean = true,
    /** A recognition is in flight. */
    val busy: Boolean = false,
    val micError: String? = null,
    val offline: Boolean = false,
)

/**
 * Listens all the time, recognizes, and keeps the lyrics in time:
 * - while searching, tries growing clips (4, 7, 10 s) then every 8 s;
 * - once a song is known, re-checks every 8 s, so a skip, a seek or a new song shows within seconds;
 * - a break in the sound (a skip, the groove between tracks) brings the next check forward to 3.5 s after it.
 */
class Engine(testFile: java.io.File? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mic = Mic(testFile)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val lyricsCache = HashMap<String, Lyrics?>()
    private val artCache = HashMap<String, ImageBitmap?>()

    @Volatile private var resyncRequested = false

    // Song currently shown: points (clip start, position) seen for it, newest last
    private var points = mutableListOf<Pair<Long, Long>>()
    private var nextCheckNanos = 0L
    private var misses = 0
    private var quietSince = 0L

    // Search from this mic mark on, so audio from the previous song isn't mixed in
    private var searchMark = 0L
    private var searchAttempts = 0
    private var lastRequestNanos = 0L
    private var backoffUntil = 0L
    private var slowUntil = 0L
    private var lastLevelLog = 0L

    fun start() {
        mic.start()
        scope.launch { loop() }
    }

    fun stop() {
        scope.cancel()
        mic.stop()
    }

    /** OK on the remote: check the song and position right now. */
    fun resync() { resyncRequested = true }

    private suspend fun loop() {
        delay(300)
        searchMark = mic.mark()
        while (scope.isActive) {
            _state.update { it.copy(micError = mic.error) }
            val now = System.nanoTime()
            if (now - lastLevelLog > 5_000_000_000L) {
                lastLevelLog = now
                Log.d(TAG, "level ${"%.1f".format(mic.level(1_000))} dB, floor ${"%.1f".format(mic.floor())} dB, searching=${_state.value.searching}")
            }
            try {
                if (_state.value.now == null || _state.value.searching) search(now) else follow(now)
            } catch (e: Shazam.RateLimited) {
                Log.w(TAG, "rate limited")
                backoffUntil = now + 30_000_000_000L
                slowUntil = now + 600_000_000_000L
            } catch (e: Exception) {
                Log.w(TAG, "step failed", e)
                _state.update { it.copy(offline = e is java.io.IOException) }
                delay(3_000)
            }
            delay(250)
        }
    }

    // ----- searching

    private suspend fun search(now: Long) {
        if (now < backoffUntil) return
        // Sound coming back after silence: start over with only the new audio
        gapJustEnded(minQuietMs = 600)?.let { searchMark = it; searchAttempts = 0 }
        val recorded = mic.recordedSince(searchMark)
        val wanted = when (searchAttempts) { 0 -> 4_000L; 1 -> 7_000L; 2 -> 10_000L; else -> 10_000L }
        val spacing = when {
            searchAttempts < 3 -> 0L
            searchAttempts < 20 -> 8_000L
            else -> 20_000L // nothing recognizable for a few minutes: ask less often
        }.let { if (now < slowUntil) max(it, 20_000L) else it }
        val forced = resyncRequested.also { resyncRequested = false }
        if (!forced && (recorded < wanted || now - lastRequestNanos < spacing * 1_000_000)) return
        if (!forced && mic.level(3_000) < SILENT_DB) return // nothing playing
        val clip = mic.clip(minOf(recorded, 10_000L), since = if (searchAttempts < 3) searchMark else 0) ?: return
        val match = recognize(clip)
        searchAttempts++
        if (match != null) show(match, clip)
        else if (searchAttempts >= 2 && !_state.value.ended) _state.update { it.copy(ended = true) } // changed to something unknown
    }

    // ----- following a known song: keep listening so skips, seeks and song changes are caught within seconds

    private var checkSince = 0L

    private suspend fun follow(now: Long) {
        val np = _state.value.now ?: return
        val pos = np.positionAt(now)
        val duration = np.durationMs

        // Music stopped
        if (mic.level(1_000) < max(mic.floor() + 4, SILENT_DB)) {
            if (quietSince == 0L) quietSince = now
            if (now - quietSince > 12_000_000_000L) return startSearching()
            return
        } else quietSince = 0L

        // Song ran out: the next one is starting
        if (duration != null && pos > duration + 1_000) return startSearching()

        // A break in the sound (a skip, or the groove between tracks): check again as soon as there's enough new audio
        gapJustEnded(minQuietMs = 250)?.let { mark ->
            checkSince = mark
            nextCheckNanos = now + (GAP_CHECK_MS - mic.recordedSince(mark)).coerceAtLeast(0) * 1_000_000
        }

        val forced = resyncRequested.also { resyncRequested = false }
        if (!forced && now < nextCheckNanos) return
        if (!forced && now < backoffUntil) return
        val since = checkSince.also { checkSince = 0L }
        val clip = mic.clip(CHECK_CLIP_MS, since = since) ?: return
        if (clip.durationMs < 3_000) return
        val match = recognize(clip)
        val interval = if (now < slowUntil) 20_000L else CHECK_EVERY_MS
        when {
            match == null -> {
                misses++
                nextCheckNanos = now + 5_000_000_000L
                // Two misses in a row while music plays: something we can't identify replaced the song
                if (misses >= 2) startSearching()
            }
            match.key != np.match.key -> show(match, clip)
            else -> {
                misses = 0
                points += clip.startNanos to match.offsetMs
                refine(np)
                nextCheckNanos = now + interval * 1_000_000
            }
        }
    }

    /** Fit position against time over this song's matches, so a slightly fast or slow turntable stays in sync. */
    private fun refine(np: NowPlaying) {
        val (t1, p1) = points.last()
        val drift = np.positionAt(t1) - p1
        if (abs(drift) > 1_500) {
            // Jumped within the song (seek, or the needle moved): start the fit over
            Log.i(TAG, "seek in ${np.match.title}: ${drift} ms")
            points = mutableListOf(t1 to p1)
            _state.update { s -> s.copy(now = s.now?.copy(anchorNanos = t1, anchorMs = p1, rate = 1.0)) }
            return
        }
        if (points.size > 8) points = points.takeLast(8).toMutableList()
        var rate = 1.0
        val first = points.first()
        val spanMs = (t1 - first.first) / 1_000_000.0
        if (points.size >= 3 && spanMs > 20_000) {
            val r = (p1 - first.second) / spanMs
            if (r in 0.97..1.03) rate = r
        }
        Log.i(TAG, "resync ${np.match.title}: drift ${drift} ms, rate ${"%.4f".format(rate)}")
        // Ignore tiny corrections so the lyrics don't twitch
        if (abs(drift) < 150 && abs(rate - np.rate) < 0.001) return
        _state.update { s -> s.copy(now = s.now?.copy(anchorNanos = t1, anchorMs = p1, rate = rate)) }
    }

    private fun startSearching() {
        searchMark = mic.mark()
        searchAttempts = 0
        quietSince = 0L
        _state.update { it.copy(searching = true, ended = true) }
    }

    private suspend fun show(match: Match, clip: Clip) {
        val previous = _state.value.now
        if (previous != null && previous.match.key == match.key) {
            // Same song again (e.g. after a pause or a false gap): keep it, just re-anchor
            points += clip.startNanos to match.offsetMs
            _state.update { it.copy(searching = false, ended = false, now = previous.copy(anchorNanos = clip.startNanos, anchorMs = match.offsetMs)) }
        } else {
            points = mutableListOf(clip.startNanos to match.offsetMs)
            val cachedLyrics = lyricsCache[match.key]
            _state.update {
                it.copy(
                    searching = false,
                    ended = false,
                    now = NowPlaying(
                        match, lyrics = cachedLyrics, lyricsLoading = !lyricsCache.containsKey(match.key),
                        art = artCache[match.key], anchorNanos = clip.startNanos, anchorMs = match.offsetMs,
                    ),
                )
            }
            Log.i(TAG, "now playing ${match.artist} – ${match.title} at ${match.offsetMs} ms")
            if (!lyricsCache.containsKey(match.key)) scope.launch { loadLyrics(match) }
            if (!artCache.containsKey(match.key)) scope.launch { loadArt(match) }
        }
        misses = 0
        quietSince = 0L
        nextCheckNanos = System.nanoTime() + CHECK_EVERY_MS * 1_000_000
    }

    private suspend fun recognize(clip: Clip): Match? {
        lastRequestNanos = System.nanoTime()
        _state.update { it.copy(busy = true) }
        try {
            val pcm = normalize(clip.samples)
            val json = withContext(Dispatchers.IO) { Shazam.request(Signature.fromPcm(pcm), clip.durationMs) }
            _state.update { it.copy(offline = false) }
            return Shazam.parse(json).also { Log.i(TAG, "recognize ${clip.durationMs} ms → ${it?.title ?: "no match"}") }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun loadLyrics(match: Match) {
        val lyrics = runCatching { withContext(Dispatchers.IO) { Lrclib.find(match.title, match.artist, match.album) } }
            .onFailure { Log.w(TAG, "lyrics failed", it) }
        if (lyrics.isSuccess) lyricsCache[match.key] = lyrics.getOrNull()
        _state.update { s ->
            if (s.now?.match?.key != match.key) s else s.copy(now = s.now.copy(lyrics = lyrics.getOrNull(), lyricsLoading = false))
        }
    }

    private suspend fun loadArt(match: Match) {
        val url = match.artwork ?: return
        fun fetch(u: String) = (URL(u).openConnection() as HttpURLConnection).let { conn ->
            try { conn.connectTimeout = 8_000; conn.readTimeout = 10_000; conn.inputStream.use { BitmapFactory.decodeStream(it) } }
            finally { conn.disconnect() }
        }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { fetch(url) }
                .recoverCatching { if (it is javax.net.ssl.SSLException) fetch(url.replaceFirst("https://", "http://")) else throw it }
                .onFailure { Log.w(TAG, "art failed for $url", it) }.getOrNull()
        }?.asImageBitmap()
        Log.i(TAG, "art ${if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "missing"}")
        artCache[match.key] = bitmap
        _state.update { s -> if (s.now?.match?.key != match.key) s else s.copy(now = s.now.copy(art = bitmap)) }
    }

    /**
     * Looks for a dip of at least [GAP_DROP_DB] below the music around it lasting 0.8–6 s that has just ended,
     * with [GAP_RESUME_MS] of sound since. Returns the mic mark where the sound came back.
     */
    private fun gapJustEnded(minQuietMs: Long): Long? {
        val window = mic.recentLevels(16_000)
        val chunk = Mic.LEVEL_WINDOW_MS
        val after = (GAP_RESUME_MS / chunk).toInt()
        if (window.size < after + 60) return null
        val loud = window.sortedDescending().let { it[it.size / 4] } // the music's typical level
        val threshold = loud - GAP_DROP_DB
        // Walking back from now: a run of sound (the new song so far), then the quiet run before it
        var i = window.size - 1
        var soundChunks = 0
        while (i >= 0 && window[i] > threshold) { soundChunks++; i-- }
        if (soundChunks < after || soundChunks > after + 12) return null
        var quiet = 0
        while (i >= 0 && window[i] <= threshold) { quiet++; i-- }
        if (i < 0) return null // the quiet started before our history: not a gap between songs
        val quietMs = quiet * chunk
        if (quietMs < minQuietMs || quietMs > 8_000) return null
        // Only report each gap once: it must have ended just now
        val endedAgoMs = soundChunks * chunk
        if (lastGapReportedNanos != 0L && System.nanoTime() - lastGapReportedNanos < 4_000_000_000L) return null
        lastGapReportedNanos = System.nanoTime()
        Log.i(TAG, "gap of $quietMs ms (music ${"%.0f".format(loud)} dB)")
        return mic.mark() - endedAgoMs * Signature.SAMPLE_RATE / 1000
    }

    private var lastGapReportedNanos = 0L

    /** Bring quiet microphone audio up to a healthy level so the fingerprint finds enough peaks. */
    private fun normalize(s: ShortArray): ShortArray {
        var peak = 1
        for (v in s) { val a = abs(v.toInt()); if (a > peak) peak = a }
        val gain = (20_000.0 / peak).coerceIn(1.0, 24.0)
        if (gain < 1.05) return s
        return ShortArray(s.size) { (s[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }

    companion object {
        private const val TAG = "Lyrics"
        private const val SILENT_DB = -70f
        private const val GAP_DROP_DB = 14f
        private const val GAP_RESUME_MS = 600L
        /** Re-check a known song this often. */
        private const val CHECK_EVERY_MS = 8_000L
        private const val CHECK_CLIP_MS = 6_000L
        /** After a break in the sound, check once this much new audio is in. */
        private const val GAP_CHECK_MS = 3_500L
    }
}
