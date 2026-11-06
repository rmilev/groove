package mk.lyrics

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.random.Random

/** A recognized song and where in it the sample started. */
data class Match(
    val key: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artwork: String?,
    /** Position in the song, in ms, at the first sample of the recording that was sent. */
    val offsetMs: Long,
    /** How much faster (positive) or slower the audio plays than the original, e.g. a turntable's speed error. */
    val timeSkew: Double,
    /** Shazam's colours for the artwork, e.g. "b:4d453cp:f2dbc4s:f3d2af…" (background, primary, secondary). */
    val colors: String? = null,
)

/** Shazam's recognition endpoint, used the same way as SongRec and Audile. */
object Shazam {
    class RateLimited : Exception("Shazam asked us to slow down")

    private val device = UUID.randomUUID().toString().uppercase() to UUID.randomUUID().toString()

    /** Sends a signature; returns Shazam's JSON reply. */
    fun request(signatureUri: String, sampleMs: Long): String {
        val now = System.currentTimeMillis()
        val body = """{"geolocation":{"altitude":${Random.nextDouble(100.0, 500.0)},"latitude":${Random.nextDouble(-90.0, 90.0)},"longitude":${Random.nextDouble(-180.0, 180.0)}},""" +
            """"signature":{"samplems":$sampleMs,"timestamp":$now,"uri":"$signatureUri"},"timestamp":$now,"timezone":"Europe/Skopje"}"""
        val url = URL(
            "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/${device.first}/${device.second}" +
                "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3",
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; BRAVIA 4K Build/UTT3.240625.001)")
            conn.setRequestProperty("Content-Language", "en_US")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode == 429) throw RateLimited()
            if (conn.responseCode !in 200..299) throw java.io.IOException("Shazam HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Null when nothing matched. */
    fun parse(json: String): Match? {
        val root = JSONObject(json)
        val matches = root.optJSONArray("matches") ?: return null
        if (matches.length() == 0) return null
        val m = matches.getJSONObject(0)
        val track = root.optJSONObject("track") ?: return null
        var album: String? = null
        val sections = track.optJSONArray("sections")
        if (sections != null) for (i in 0 until sections.length()) {
            val meta = sections.getJSONObject(i).optJSONArray("metadata") ?: continue
            for (j in 0 until meta.length()) {
                val item = meta.getJSONObject(j)
                if (item.optString("title") == "Album") album = item.optString("text").takeIf { it.isNotBlank() }
            }
        }
        val images = track.optJSONObject("images")
        val art = images?.optString("coverarthq")?.takeIf { it.isNotBlank() } ?: images?.optString("coverart")?.takeIf { it.isNotBlank() }
        return Match(
            key = track.optString("key"),
            title = track.optString("title"),
            artist = track.optString("subtitle"),
            album = album,
            artwork = art?.let(::largeArtwork),
            offsetMs = (m.optDouble("offset", 0.0) * 1000).toLong(),
            timeSkew = m.optDouble("timeskew", 0.0),
            colors = images?.optString("joecolor")?.takeIf { it.isNotBlank() },
        )
    }

    /** Apple's artwork URLs carry their size; ask for a big one. */
    private fun largeArtwork(url: String) = url.replace(Regex("""/\d+x\d+(cc|bb)?\.(jpg|png|webp)$"""), "/1200x1200cc.jpg")
}
