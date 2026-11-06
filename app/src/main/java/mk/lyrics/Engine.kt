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
    /** Photos of the artist for the background, shown in turn; filled in as they load. */
    val artistImages: List<ImageBitmap> = emptyList(),
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
 * - a break in the sound (a skip, the groove between tracks) brings the next check forward to 3.5 s after it;
 * - talk over the music or a repeated chorus can't knock it off: misses keep the lyrics running, and a jump within
 *   the song must be confirmed by a second check.
 */
class Engine(testFile: java.io.File? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mic = Mic(testFile)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val lyricsCache = HashMap<String, Lyrics?>()
    private val artCache = HashMap<String, ImageBitmap?>()
    /** Artist photos by artist, so the next song by the same artist starts with them. Only a few artists are kept. */
    private val photoCache = object : LinkedHashMap<String, MutableList<ImageBitmap>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<ImageBitmap>>?) = size > 3
    }
    private val photoLoads = HashSet<String>()

    @Volatile private var resyncRequested = false

    // Song currently shown: points (clip start, position) seen for it, newest last
    private var points = mutableListOf<Pair<Long, Long>>()
    private var nextCheckNanos = 0L
    private var misses = 0

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
        val shown = _state.value.now
        if (shown != null && !_state.value.ended) {
            // Still showing the last song while looking: give up on it once it should be over, or nothing has matched for a while
            val over = shown.durationMs?.let { shown.positionAt(now) > it + 1_000 } == true
            if (over || now - lastMatchNanos > GIVE_UP_MS * 1_000_000) _state.update { it.copy(ended = true) }
        }
        // Sound coming back after silence: start over with only the new audio
        gapJustEnded(minQuietMs = 600)?.let { searchMark = it; searchAttempts = 0 }
        val recorded = mic.recordedSince(searchMark)
        val wanted = when (searchAttempts) { 0 -> 4_000L; 1 -> 7_000L; else -> 10_000L }
        val spacing = when {
            searchAttempts < 3 -> 0L
            searchAttempts < 20 -> 8_000L
            searchAttempts < 60 -> 20_000L // nothing recognizable for a few minutes: ask less often
            else -> 60_000L
        }.let { if (now < slowUntil) max(it, 20_000L) else it }
        val forced = resyncRequested.also { resyncRequested = false }
        if (!forced && (recorded < wanted || now - lastRequestNanos < spacing * 1_000_000)) return
        if (!forced && mic.level(3_000) < SILENT_DB) return // nothing playing
        val clip = mic.clip(minOf(recorded, 10_000L), since = if (searchAttempts < 3) searchMark else 0) ?: return
        val match = recognize(clip)
        searchAttempts++
        if (match != null) show(match, clip)
    }

    // ----- following a known song: keep listening so skips, seeks and song changes are caught within seconds

    private var checkSince = 0L
    private var gapCheck = false
    private var lastMatchNanos = 0L
    /** A match that put us somewhere else in the same song; acted on only once a second check agrees. */
    private var pendingJump: Pair<Long, Long>? = null

    private suspend fun follow(now: Long) {
        val np = _state.value.now ?: return
        val duration = np.durationMs

        // Song ran out: the next one is starting
        if (duration != null && np.positionAt(now) > duration + 1_000) return beginSearch(ended = true)

        // A break in the sound (a skip, or the groove between tracks): check again as soon as there's enough new audio
        gapJustEnded(minQuietMs = 250)?.let { mark ->
            checkSince = mark
            gapCheck = true
            nextCheckNanos = now + (GAP_CHECK_MS - mic.recordedSince(mark)).coerceAtLeast(0) * 1_000_000
        }

        val forced = resyncRequested.also { resyncRequested = false }
        if (!forced && now < nextCheckNanos) return
        if (!forced && now < backoffUntil) return
        val since = checkSince.also { checkSince = 0L }
        val wasGapCheck = gapCheck.also { gapCheck = false }
        val clip = mic.clip(CHECK_CLIP_MS, since = since) ?: return
        if (clip.durationMs < 3_000) return
        val match = recognize(clip)
        val interval = if (now < slowUntil) 20_000L else CHECK_EVERY_MS
        nextCheckNanos = now + interval * 1_000_000
        when {
            match == null -> {
                // Talking over the music or a quiet passage can spoil a check; keep the lyrics going.
                // Only after several misses in a row look for a new song (still showing this one meanwhile).
                if (!wasGapCheck) misses++
                nextCheckNanos = now + 5_000_000_000L
                if (misses >= 3) beginSearch(ended = false)
            }
            match.key != np.match.key -> show(match, clip)
            else -> sameSong(np, clip, match)
        }
    }

    /** A check heard the song we're showing: keep the timing, or move it if a jump is confirmed. */
    private fun sameSong(np: NowPlaying, clip: Clip, match: Match) {
        misses = 0
        lastMatchNanos = System.nanoTime()
        _state.update { it.copy(searching = false, ended = false) }
        val drift = np.positionAt(clip.startNanos) - match.offsetMs
        if (abs(drift) <= JUMP_MS) {
            pendingJump = null
            points += clip.startNanos to match.offsetMs
            refine(np)
            return
        }
        // Somewhere else in the song: a real seek, or just a repeated chorus matching another spot. Confirm first.
        val pj = pendingJump
        val elapsed = (clip.startNanos - (pj?.first ?: 0)) / 1_000_000
        if (pj != null && abs((match.offsetMs - pj.second) - elapsed) <= JUMP_MS) {
            Log.i(TAG, "seek in ${np.match.title}: ${-drift} ms (confirmed)")
            pendingJump = null
            points = mutableListOf(pj, clip.startNanos to match.offsetMs)
            _state.update { s -> s.copy(now = s.now?.copy(anchorNanos = clip.startNanos, anchorMs = match.offsetMs, rate = 1.0)) }
        } else {
            Log.i(TAG, "possible seek in ${np.match.title}: ${-drift} ms, checking again")
            pendingJump = clip.startNanos to match.offsetMs
            nextCheckNanos = System.nanoTime() + 3_000_000_000L
        }
    }

    /** Fit position against time over this song's matches, so a slightly fast or slow turntable stays in sync. */
    private fun refine(np: NowPlaying) {
        val (t1, p1) = points.last()
        val drift = np.positionAt(t1) - p1
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

    /** Look for a (new) song. [ended]: the shown song is over, so show the listening screen meanwhile. */
    private fun beginSearch(ended: Boolean) {
        searchMark = mic.mark()
        searchAttempts = 0
        misses = 0
        pendingJump = null
        _state.update { it.copy(searching = true, ended = it.ended || ended) }
    }

    private suspend fun show(match: Match, clip: Clip) {
        val previous = _state.value.now
        if (previous != null && previous.match.key == match.key) return sameSong(previous, clip, match)
        points = mutableListOf(clip.startNanos to match.offsetMs)
        pendingJump = null
        val cachedLyrics = lyricsCache[match.key]
        _state.update {
            it.copy(
                searching = false,
                ended = false,
                now = NowPlaying(
                    match, lyrics = cachedLyrics, lyricsLoading = !lyricsCache.containsKey(match.key),
                    art = artCache[match.key], artistImages = photoCache[artistKey(match)]?.toList().orEmpty(), anchorNanos = clip.startNanos, anchorMs = match.offsetMs,
                ),
            )
        }
        Log.i(TAG, "now playing ${match.artist} – ${match.title} at ${match.offsetMs} ms")
        if (!lyricsCache.containsKey(match.key)) scope.launch { loadLyrics(match) }
        if (!artCache.containsKey(match.key)) scope.launch { loadArt(match) }
        if (photoLoads.add(artistKey(match))) scope.launch { loadPhotos(match) }
        misses = 0
        lastMatchNanos = System.nanoTime()
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
        val bitmap = match.artwork?.let { fetchImage(it) }
        artCache[match.key] = bitmap
        _state.update { s -> if (s.now?.match?.key != match.key) s else s.copy(now = s.now.copy(art = bitmap)) }
    }

    private fun artistKey(match: Match) = AudioDb.key(Lrclib.mainArtist(match.artist))

    /** Shazam's artist photo first (quick), then TheAudioDB's widescreen ones as they arrive. */
    private suspend fun loadPhotos(match: Match) {
        val key = artistKey(match)
        val list = mutableListOf<ImageBitmap>()
        fun publish() {
            photoCache[key] = list
            _state.update { s ->
                val np = s.now
                if (np == null || artistKey(np.match) != key) s else s.copy(now = np.copy(artistImages = list.toList()))
            }
        }
        match.artistImage?.let { fetchImage(it) }?.let { list += it; publish() }
        val more = runCatching { withContext(Dispatchers.IO) { AudioDb.photos(match.artist) } }
            .onFailure { Log.w(TAG, "artist photos failed", it) }.getOrDefault(emptyList())
        Log.i(TAG, "${more.size} more photos of ${match.artist}")
        for (url in more) fetchImage(url)?.let { list += it; publish() }
        if (list.isEmpty()) photoLoads.remove(key) // try again next time
    }

    private suspend fun fetchImage(url: String): ImageBitmap? {
        fun fetch(u: String) = (URL(u).openConnection() as HttpURLConnection).let { conn ->
            try { conn.connectTimeout = 8_000; conn.readTimeout = 10_000; conn.inputStream.use { BitmapFactory.decodeStream(it) } }
            finally { conn.disconnect() }
        }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { fetch(url) }
                .recoverCatching { if (it is javax.net.ssl.SSLException) fetch(url.replaceFirst("https://", "http://")) else throw it }
                .onFailure { Log.w(TAG, "image failed for $url", it) }.getOrNull()
        }?.asImageBitmap()
        Log.i(TAG, "image ${if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "missing"} ${url.substringAfterLast("/v4/").take(40)}")
        return bitmap
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
        private const val SILENT_DB = -66f
        /** Positions further apart than this are a jump, not drift. */
        private const val JUMP_MS = 1_500L
        /** Drop to the listening screen after this long without hearing the shown song. */
        private const val GIVE_UP_MS = 40_000L
        private const val GAP_DROP_DB = 14f
        private const val GAP_RESUME_MS = 600L
        /** Re-check a known song this often. */
        private const val CHECK_EVERY_MS = 8_000L
        private const val CHECK_CLIP_MS = 6_000L
        /** After a break in the sound, check once this much new audio is in. */
        private const val GAP_CHECK_MS = 3_500L
    }
}
