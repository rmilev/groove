package mk.lyrics

import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Manual check against the real service: SHAZAM_PCM=/path/to/16k-mono-s16le.raw ./gradlew :lyrics:testReleaseUnitTest */
class ShazamLiveTest {
    @Test fun recognizeFile() {
        val path = System.getenv("SHAZAM_PCM") ?: return
        val bytes = File(path).readBytes()
        val pcm = ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        val t = System.nanoTime()
        val sig = Signature.fromPcm(pcm)
        println("peaks=${Signature.peakCount(pcm)} sigLen=${sig.length} in ${(System.nanoTime() - t) / 1_000_000} ms")
        val reply = Shazam.request(sig, pcm.size * 1000L / Signature.SAMPLE_RATE)
        File(path + ".json").writeText(reply)
        println("REPLY " + reply.take(300))
    }
}
