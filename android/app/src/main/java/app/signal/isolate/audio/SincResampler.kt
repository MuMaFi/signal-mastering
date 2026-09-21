package app.signal.isolate.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming polyphase windowed-sinc resampler.
 *
 * Both separation models are 44.1 kHz-only, and a cheap linear resample would smear
 * exactly the high-frequency detail (sibilance, cymbals) that separation quality is
 * judged on — so the conversion uses a Kaiser-windowed sinc with 32 taps per output
 * sample and 1024 interpolated phases. Each phase row is normalised to unity DC gain.
 */
class StreamingResampler(
    private val inRate: Int,
    private val outRate: Int,
    private val channels: Int,
) {
    val bypass = inRate == outRate

    private val step = inRate.toDouble() / outRate

    private val kernel: FloatArray
    private var buf = FloatArray(0)
    private var bufFrames = 0
    private var bufStart = -HALF.toLong()
    private var outIndex = 0L
    private var totalInFrames = 0L

    init {
        // Anti-alias when decimating: pull the cutoff below the *output* Nyquist.
        val cutoff = minOf(1.0, outRate.toDouble() / inRate) * 0.95
        kernel = FloatArray(PHASES * TAPS)
        val scratch = DoubleArray(TAPS)
        for (p in 0 until PHASES) {
            val frac = p.toDouble() / PHASES
            var sum = 0.0
            for (k in 0 until TAPS) {
                val u = (HALF - 1 - k) + frac
                val v = cutoff * sinc(cutoff * u) * kaiser(u / HALF)
                scratch[k] = v
                sum += v
            }
            val norm = if (abs(sum) > 1e-12) 1.0 / sum else 1.0
            for (k in 0 until TAPS) kernel[p * TAPS + k] = (scratch[k] * norm).toFloat()
        }
        // Frames [-HALF, -1] are implicit silence before the first real sample.
        buf = FloatArray(HALF * channels)
        bufFrames = HALF
    }

    /** Feeds [frames] interleaved frames and returns whatever output is ready. */
    fun process(input: FloatArray, frames: Int): FloatArray {
        if (frames <= 0) return EMPTY
        append(input, frames)
        totalInFrames += frames
        return produce(limit = null)
    }

    /** Drains the tail, padding the filter with silence. */
    fun flush(): FloatArray {
        val tail = FloatArray(HALF * channels)
        append(tail, HALF)
        val total = ceil(totalInFrames * outRate.toDouble() / inRate).toLong()
        return produce(limit = total)
    }

    private fun append(input: FloatArray, frames: Int) {
        val needed = (bufFrames + frames) * channels
        if (buf.size < needed) {
            buf = buf.copyOf(maxOf(needed, buf.size * 2))
        }
        System.arraycopy(input, 0, buf, bufFrames * channels, frames * channels)
        bufFrames += frames
    }

    private fun produce(limit: Long?): FloatArray {
        val out = ArrayBuilder(channels)
        val lastAvailable = bufStart + bufFrames - 1
        while (true) {
            if (limit != null && outIndex >= limit) break
            val pos = outIndex * step
            val i0 = floor(pos).toLong()
            if (i0 + HALF > lastAvailable) break
            val phase = ((pos - i0) * PHASES).toInt().coerceIn(0, PHASES - 1)
            val kOff = phase * TAPS
            val base = ((i0 - HALF + 1) - bufStart).toInt()
            for (c in 0 until channels) {
                var acc = 0f
                var idx = base * channels + c
                for (k in 0 until TAPS) {
                    acc += buf[idx] * kernel[kOff + k]
                    idx += channels
                }
                out.add(acc)
            }
            outIndex++
        }
        compact()
        return out.toFloatArray()
    }

    /** Drops input frames no future output sample can reach. */
    private fun compact() {
        val pos = outIndex * step
        val keepFrom = floor(pos).toLong() - HALF + 1
        val drop = (keepFrom - bufStart).toInt()
        if (drop > MAX_SLACK) {
            System.arraycopy(buf, drop * channels, buf, 0, (bufFrames - drop) * channels)
            bufFrames -= drop
            bufStart += drop
        }
    }

    private class ArrayBuilder(channels: Int) {
        private var data = FloatArray(4096 * channels)
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toFloatArray(): FloatArray = if (size == data.size) data else data.copyOf(size)
    }

    companion object {
        private const val HALF = 16
        private const val TAPS = HALF * 2
        private const val PHASES = 1024
        private const val MAX_SLACK = 8192
        private val EMPTY = FloatArray(0)

        private fun sinc(x: Double): Double =
            if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)

        /** Kaiser window over `x in [-1, 1]`, beta = 8.6 (~ -90 dB stopband). */
        private fun kaiser(x: Double): Double {
            if (abs(x) >= 1.0) return 0.0
            val beta = 8.6
            return besselI0(beta * sqrt(1.0 - x * x)) / besselI0(beta)
        }

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val half = x / 2.0
            for (k in 1 until 64) {
                term *= (half / k) * (half / k)
                sum += term
                if (term < 1e-17 * sum) break
            }
            return sum
        }
    }
}
