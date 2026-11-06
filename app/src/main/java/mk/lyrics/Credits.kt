package mk.lyrics

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** MusicBrainz (musicbrainz.org): full artist credits, for songs where Shazam names only the main artist. */
object MusicBrainz {
    private const val AGENT = "Groove/1.3 (https://github.com/rmilev/mk-tv)"

    /**
     * The fullest credit MusicBrainz has for this recording, e.g. "Vince Staples feat. Kendrick Lamar & KUČKA",
     * or null when it adds nobody to [artist].
     */
    fun fullCredit(title: String, artist: String, durationMs: Long?): String? {
        val t = Lrclib.cleanTitle(title)
        val main = Lrclib.withoutGuests(artist)
        val q = "recording:\"${t.replace("\"", "")}\" AND artist:\"${main.replace("\"", "")}\""
        val conn = URL("https://musicbrainz.org/ws/2/recording?fmt=json&limit=15&query=${URLEncoder.encode(q, "UTF-8")}").openConnection() as HttpURLConnection
        val arr = try {
            conn.connectTimeout = 8_000; conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", AGENT)
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("recordings") ?: return null
        } finally {
            conn.disconnect()
        }
        val key = AudioDb.key(main)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            // The same song (not a remix or bootleg), led by the same artist, about the same length
            .filter { AudioDb.key(it.optString("title")) == AudioDb.key(t) }
            .filter { durationMs == null || !it.has("length") || abs(it.optLong("length") - durationMs) < 5_000 }
            .mapNotNull { rec ->
                val credits = rec.optJSONArray("artist-credit") ?: return@mapNotNull null
                val names = (0 until credits.length()).map { credits.getJSONObject(it) }
                if (names.isEmpty() || AudioDb.key(names.first().optString("name")) != key) return@mapNotNull null
                names.size to names.joinToString("") { it.optString("name") + it.optString("joinphrase") }.trim()
            }
            .maxByOrNull { it.first }
            ?.takeIf { it.first > 1 }?.second
    }
}
