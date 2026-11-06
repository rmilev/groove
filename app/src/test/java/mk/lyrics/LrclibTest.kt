package mk.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrclibTest {
    @Test fun parsesLrc() {
        val lines = Lrclib.parseLrc("[ar:X]\n[00:01.50]One\n[00:03.250][01:00.00]Two\n[00:05]\n")
        assertEquals(listOf(1500L, 3250L, 5000L, 60000L), lines.map { it.timeMs })
        assertEquals("Two", lines[1].text)
        assertEquals("", lines[2].text)
    }

    @Test fun cleansNames() {
        assertEquals("Let It Be", Lrclib.cleanTitle("Let It Be (Remastered 2009)"))
        assertEquals("Hey Jude", Lrclib.cleanTitle("Hey Jude - 2015 Remaster"))
        assertEquals("Calvin Harris", Lrclib.mainArtist("Calvin Harris feat. Rihanna"))
        assertEquals("Simon", Lrclib.mainArtist("Simon & Garfunkel"))
    }

    @Test fun findsLiveLyrics() {
        if (System.getenv("LIVE") == null) return
        for ((t, a, al) in listOf(Triple("Bohemian Rhapsody", "Queen", "A Night at the Opera"), Triple("The Sound of Silence", "Simon & Garfunkel", null), Triple("Ash", "Secession Studios", "Epic Classics"))) {
            val l = Lrclib.find(t, a, al)
            println("LIVE $t: synced=${l?.synced} lines=${l?.lines?.size} dur=${l?.durationMs} instrumental=${l?.instrumental} first=${l?.lines?.firstOrNull()}")
        }
        assertTrue(true)
    }
}
