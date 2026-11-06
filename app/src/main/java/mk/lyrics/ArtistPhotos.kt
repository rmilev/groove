package mk.lyrics

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/** TheAudioDB (theaudiodb.com): widescreen artist photos ("fanart"), several per artist. Uses its free public key. */
object AudioDb {
    /** Up to four widescreen photos of [artist], or none if it doesn't know them. */
    fun photos(artist: String): List<String> {
        val name = Lrclib.mainArtist(artist)
        val conn = URL("https://www.theaudiodb.com/api/v1/json/123/search.php?s=${URLEncoder.encode(name, "UTF-8")}").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000; conn.readTimeout = 10_000
            if (conn.responseCode != 200) return emptyList()
            val arr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("artists") ?: return emptyList()
            // Only the same artist, not a near-namesake
            val a = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { key(it.optString("strArtist")) == key(name) } ?: return emptyList()
            listOf("strArtistFanart", "strArtistFanart2", "strArtistFanart3", "strArtistFanart4")
                .mapNotNull { a.optString(it).takeIf { u -> u.startsWith("http") } }
        } finally {
            conn.disconnect()
        }
    }

    /** Folds case, accents and punctuation so "Fontaines D.C." matches "fontaines dc". */
    fun key(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("""\p{M}"""), "").replace(Regex("""[^\p{L}\p{N}]"""), "")
}
