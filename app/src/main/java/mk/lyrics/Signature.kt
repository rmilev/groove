package mk.lyrics

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Shazam audio fingerprint ("signature") from 16 kHz mono PCM.
 *
 * A Kotlin port of the algorithm documented by SongRec (github.com/marin-m/SongRec, GPL-3.0):
 * spectrogram peaks every 128 samples, grouped by frequency band and packed into Shazam's binary format.
 */
object Signature {
    const val SAMPLE_RATE = 16_000
    private const val DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"

    private class Peak(val pass: Int, val magnitude: Int, val bin: Int)

    /** Returns the signature as Shazam's data URI. */
    fun fromPcm(samples: ShortArray): String {
        val peaks = Generator().run(samples)
        return DATA_URI_PREFIX + Base64.getEncoder().encodeToString(encode(samples.size, peaks))
    }

    /** Number of peaks found: too few means the audio was too quiet or plain to match. */
    fun peakCount(samples: ShortArray) = Generator().run(samples).values.sumOf { it.size }

    private val hanning = FloatArray(2048) { i -> (0.5 - 0.5 * cos(2 * PI * (i + 1) / 2049)).toFloat() }

    private class Generator {
        private val ring = ShortArray(2048)
        private var ringIndex = 0
        private val fftOutputs = Array(256) { FloatArray(1025) }
        private var fftIndex = 0
        private val spread = Array(256) { FloatArray(1025) }
        private var spreadIndex = 0
        private var spreadDone = 0
        private val re = DoubleArray(2048)
        private val im = DoubleArray(2048)
        val bands = sortedMapOf<Int, MutableList<Peak>>()

        fun run(samples: ShortArray): Map<Int, List<Peak>> {
            var i = 0
            while (i + 128 <= samples.size) {
                fft(samples, i)
                spreadPeaks()
                spreadDone++
                if (spreadDone >= 46) recognize()
                i += 128
            }
            return bands
        }

        private fun fft(samples: ShortArray, from: Int) {
            System.arraycopy(samples, from, ring, ringIndex, 128)
            ringIndex = (ringIndex + 128) and 2047
            for (k in 0 until 2048) {
                re[k] = ring[(k + ringIndex) and 2047] * hanning[k].toDouble()
                im[k] = 0.0
            }
            Fft.transform(re, im)
            val out = fftOutputs[fftIndex]
            for (k in 0..1024) out[k] = max(((re[k] * re[k] + im[k] * im[k]) / (1 shl 17)).toFloat(), 1e-10f)
            fftIndex = (fftIndex + 1) and 255
        }

        private fun spreadPeaks() {
            val last = fftOutputs[(fftIndex - 1) and 255]
            val s = spread[spreadIndex]
            System.arraycopy(last, 0, s, 0, 1025)
            for (p in 0..1022) s[p] = max(s[p], max(s[p + 1], s[p + 2]))
            for (p in 0..1024) for (back in intArrayOf(1, 3, 6)) {
                val former = spread[(spreadIndex - back) and 255]
                former[p] = max(former[p], s[p])
            }
            spreadIndex = (spreadIndex + 1) and 255
        }

        private fun recognize() {
            val f46 = fftOutputs[(fftIndex - 46) and 255]
            val f49 = spread[(spreadIndex - 49) and 255]
            for (bin in 10..1014) {
                val v = f46[bin]
                if (v < 1f / 64 || v < f49[bin - 1]) continue
                var maxNear = 0f
                for (o in NEAR) maxNear = max(maxNear, f49[bin + o])
                if (v <= maxNear) continue
                var maxOther = maxNear
                for (o in OTHER) maxOther = max(maxOther, spread[(spreadIndex + o) and 255][bin - 1])
                if (v <= maxOther) continue

                val pass = spreadDone - 46
                val mag = magnitude(v)
                val before = magnitude(f46[bin - 1])
                val after = magnitude(f46[bin + 1])
                val variation1 = mag * 2 - before - after
                if (variation1 <= 0) continue
                val variation2 = (after - before) * 32 / variation1
                val corrected = bin * 64 + variation2
                val hz = corrected * (SAMPLE_RATE / 2.0 / 1024 / 64)
                val band = when {
                    hz < 250 -> continue
                    hz < 520 -> 0
                    hz < 1450 -> 1
                    hz < 3500 -> 2
                    hz <= 5500 -> 3
                    else -> continue
                }
                bands.getOrPut(band) { mutableListOf() } += Peak(pass, mag.toInt(), corrected.toInt())
            }
        }

        private fun magnitude(v: Float) = ln(max(1f / 64, v).toDouble()) * 1477.3 + 6144
    }

    private val NEAR = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
    private val OTHER = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

    private fun encode(sampleCount: Int, bands: Map<Int, List<Peak>>): ByteArray {
        val contents = ByteArrayOutputStream()
        for ((band, peaks) in bands) {
            val buf = ByteArrayOutputStream()
            var pass = 0
            for (p in peaks) {
                if (p.pass - pass >= 255) {
                    buf.write(0xFF); buf.write(le32(p.pass)); pass = p.pass
                }
                buf.write(p.pass - pass)
                buf.write(le16(p.magnitude)); buf.write(le16(p.bin))
                pass = p.pass
            }
            val bytes = buf.toByteArray()
            contents.write(le32(0x60030040 + band)); contents.write(le32(bytes.size)); contents.write(bytes)
            repeat((4 - bytes.size % 4) % 4) { contents.write(0) }
        }
        val body = contents.toByteArray()
        val out = ByteBuffer.allocate(48 + 8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0xCAFE2580.toInt()); out.putInt(0) // crc filled in below
        out.putInt(body.size + 8); out.putInt(0x94119C00.toInt())
        out.putInt(0); out.putInt(0); out.putInt(0)
        out.putInt(3 shl 27) // 16 kHz
        out.putInt(0); out.putInt(0)
        out.putInt((sampleCount + SAMPLE_RATE * 0.24).toInt())
        out.putInt((15 shl 19) + 0x40000)
        out.putInt(0x40000000); out.putInt(body.size + 8)
        out.put(body)
        val all = out.array()
        val crc = CRC32().apply { update(all, 8, all.size - 8) }.value
        ByteBuffer.wrap(all, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc.toInt())
        return all
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
}

/** In-place iterative radix-2 FFT. */
internal object Fft {
    private val cache = HashMap<Int, Pair<DoubleArray, DoubleArray>>()

    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        val (cosT, sinT) = synchronized(cache) {
            cache.getOrPut(n) { DoubleArray(n / 2) { cos(-2 * PI * it / n) } to DoubleArray(n / 2) { sin(-2 * PI * it / n) } }
        }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]; val wi = sinT[k * step]
                    val a = i + k; val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }
}
