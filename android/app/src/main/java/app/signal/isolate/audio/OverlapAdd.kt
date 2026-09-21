package app.signal.isolate.audio

/**
 * Windowed overlap-add that streams finished audio out instead of holding the whole
 * track in memory.
 *
 * The window is a trapezoid whose ramps are exactly as long as the chunk overlap, so
 * two consecutive windows sum to 1 everywhere — the non-overlapping middle of each
 * chunk comes through as the model produced it, with no cross-fade smearing, and the
 * running weight division keeps the first and last chunk correct too.
 *
 * After chunk `i` no later chunk can reach frames below `(i + 1) * stride`, so those
 * frames are emitted and the buffer slides forward.
 */
class OverlapAdd(
    private val stemCount: Int,
    private val channels: Int,
    private val windowFrames: Int,
    private val strideFrames: Int,
    private val totalFrames: Long,
    private val onOutput: (stem: Int, data: FloatArray, count: Int) -> Unit,
) {
    val window: FloatArray = FloatArray(windowFrames).also { w ->
        val fade = windowFrames - strideFrames
        for (i in 0 until windowFrames) {
            w[i] = when {
                fade <= 0 -> 1f
                i < fade -> (i + 1).toFloat() / (fade + 1)
                i >= windowFrames - fade -> (windowFrames - i).toFloat() / (fade + 1)
                else -> 1f
            }
        }
    }

    private val acc = Array(stemCount) { FloatArray(windowFrames * channels) }
    private val weight = FloatArray(windowFrames)
    private val emit = FloatArray(strideFrames * channels)
    private var emitted = 0L

    /** Accumulates one window of interleaved samples for [stem]. */
    fun add(stem: Int, data: FloatArray) {
        val target = acc[stem]
        var i = 0
        for (f in 0 until windowFrames) {
            val w = window[f]
            for (c in 0 until channels) {
                target[i] += data[i] * w
                i++
            }
        }
    }

    /** Call once per chunk, after every stem has been added. */
    fun advance(last: Boolean) {
        for (f in 0 until windowFrames) weight[f] += window[f]
        val flushFrames = if (last) windowFrames else strideFrames
        flush(flushFrames)
        if (last) return
        for (s in 0 until stemCount) {
            val a = acc[s]
            System.arraycopy(a, strideFrames * channels, a, 0, (windowFrames - strideFrames) * channels)
            java.util.Arrays.fill(a, (windowFrames - strideFrames) * channels, a.size, 0f)
        }
        System.arraycopy(weight, strideFrames, weight, 0, windowFrames - strideFrames)
        java.util.Arrays.fill(weight, windowFrames - strideFrames, weight.size, 0f)
    }

    private fun flush(frameCount: Int) {
        var offset = 0
        while (offset < frameCount) {
            val remainingTrack = totalFrames - emitted
            if (remainingTrack <= 0L) return
            val n = minOf(
                (frameCount - offset).toLong(),
                (emit.size / channels).toLong(),
                remainingTrack,
            ).toInt()
            for (s in 0 until stemCount) {
                val source = acc[s]
                var i = 0
                for (f in 0 until n) {
                    val d = weight[offset + f]
                    val inv = if (d > 1e-8f) 1f / d else 0f
                    val base = (offset + f) * channels
                    for (c in 0 until channels) {
                        emit[i] = source[base + c] * inv
                        i++
                    }
                }
                // The buffer is handed over, not retained: sinks consume it immediately.
                onOutput(s, emit, n * channels)
            }
            emitted += n
            offset += n
        }
    }
}
