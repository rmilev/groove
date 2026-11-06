package mk.lyrics

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

data class Line(val timeMs: Long, val text: String)

/** Lyrics for one song: [lines] are time-synced when [synced], otherwise plain text with no times. */
data class Lyrics(val lines: List<Line>, val synced: Boolean, val durationMs: Long?, val instrumental: Boolean = false) {
    /** Index of the line being sung at [positionMs], or -1 before the first one. */
    fun indexAt(positionMs: Long): Int {
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/** LRCLIB (lrclib.net): a free, open database of time-synced lyrics. */
object Lrclib {
    private const val AGENT = "Groove 1.2 (https://github.com/rmilev/mk-tv)"

    fun find(title: String, artist: String, album: String?): Lyrics? {
        val t = cleanTitle(title)
        val a = mainArtist(artist)
        val results = search("track_name=${enc(t)}&artist_name=${enc(a)}")
            .ifEmpty { search("q=${enc("$a $t")}") }
        if (results.isEmpty()) return null
        // Same song by the same artist; synced lyrics first, then the album we heard
        val candidates = results.filter { same(it.title, t) && (same(it.artist, a) || norm(it.artist).contains(norm(a))) }
            .ifEmpty { results.filter { same(it.title, t) } }
        val best = candidates.sortedWith(
            compareByDescending<Record> { it.synced != null }
                .thenByDescending { album != null && same(it.album, album) }
                .thenByDescending { it.plain != null },
        ).firstOrNull() ?: return null
        val duration = best.duration?.let { (it * 1000).toLong() }
        return when {
            best.synced != null -> Lyrics(parseLrc(best.synced), true, duration)
            best.plain != null -> Lyrics(best.plain.lines().map { Line(0, it.trim()) }, false, duration)
            best.instrumental -> Lyrics(emptyList(), false, duration, instrumental = true)
            else -> null
        }
    }

    private class Record(val title: String, val artist: String, val album: String, val duration: Double?, val synced: String?, val plain: String?, val instrumental: Boolean)

    private fun search(query: String): List<Record> {
        val conn = URL("https://lrclib.net/api/search?$query").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000; conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", AGENT)
            if (conn.responseCode != 200) return emptyList()
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Record(
                    o.optString("trackName"), o.optString("artistName"), o.optString("albumName"),
                    o.optDouble("duration").takeIf { !it.isNaN() },
                    o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" },
                    o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" },
                    o.optBoolean("instrumental"),
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    private val stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun parseLrc(lrc: String): List<Line> {
        var offset = 0L
        val out = mutableListOf<Line>()
        for (raw in lrc.lines()) {
            Regex("""^\[offset:\s*([+-]?\d+)]""").find(raw.trim())?.let { offset = it.groupValues[1].toLong(); return@let }
            val stamps = stamp.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val text = raw.substring(stamps.last().range.last + 1).trim()
            for (s in stamps) {
                val (m, sec, frac) = s.destructured
                val ms = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.take(3).toInt() }
                out += Line(m.toLong() * 60_000 + sec.toLong() * 1000 + ms - offset, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** "Song (Remastered 2011) - Live" → "Song" */
    fun cleanTitle(t: String) = t.replace(Regex("""\s*[(\[][^)\]]*(remaster|version|edit|mix|live|mono|stereo|feat|with|deluxe|bonus)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+-\s+.*(remaster|version|edit|mix|live|mono|stereo).*$""", RegexOption.IGNORE_CASE), "")
        .trim()

    /** "A feat. B" / "A & B" → "A" */
    fun mainArtist(a: String) = a.split(Regex("""\s+(feat\.?|ft\.?|featuring|x|with)\s+|\s*[,&]\s*""", RegexOption.IGNORE_CASE)).first().trim()

    private fun same(a: String, b: String) = norm(a) == norm(b) || norm(cleanTitle(a)) == norm(cleanTitle(b))

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("""\p{M}"""), "").replace(Regex("""[^\p{L}\p{N}]"""), "")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
