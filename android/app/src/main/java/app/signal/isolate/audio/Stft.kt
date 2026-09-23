package app.signal.isolate.audio

import kotlin.math.cos
import kotlin.math.max

/**
 * `torch.stft` / `torch.istft` with `center=True`, `pad_mode="reflect"`,
 * `normalized=False` — the contract the separation models expect, since their STFTs
 * were stripped out of the graphs.
 *
 * The window is a parameter because the models disagree on it: the RoFormer uses a
 * periodic Hann, while SCNet calls `torch.stft` with no window at all, which PyTorch
 * treats as rectangular. Nothing in SCNet's config says so; feeding it a Hann-windowed
 * spectrogram still runs and returns audio, just without any separation in it.
 *
 * Frame count matches PyTorch: `1 + length / hop`.
 */
enum class Window { HANN, RECTANGULAR }

class Stft(val nFft: Int, val hop: Int, windowType: Window = Window.HANN) {

    val bins = nFft / 2 + 1
    private val pad = nFft / 2
    private val fft = Fft(nFft)

    /** Periodic (not symmetric) Hann — `torch.hann_window(n)` — or all ones. */
    val window = FloatArray(nFft) { i ->
        when (windowType) {
            Window.HANN -> (0.5 - 0.5 * cos(2.0 * Math.PI * i / nFft)).toFloat()
            Window.RECTANGULAR -> 1f
        }
    }

    fun frameCount(length: Int): Int = 1 + length / hop

    /** Length of signal that produces exactly [frames] frames. */
    fun signalLength(frames: Int): Int = (frames - 1) * hop

    private val re = FloatArray(nFft)
    private val im = FloatArray(nFft)
    private var padded = FloatArray(0)
    private var acc = FloatArray(0)
    private var norm = FloatArray(0)

    private fun reflectPad(x: FloatArray, offset: Int, length: Int): FloatArray {
        val total = length + 2 * pad
        if (padded.size != total) padded = FloatArray(total)
        System.arraycopy(x, offset, padded, pad, length)
        // Reflect without repeating the edge sample, as PyTorch does:
        // padded[pad - k] == x[k] and padded[pad + length - 1 + k] == x[length - 1 - k].
        // Chunks are always far longer than `pad`; the clamp only guards degenerate input.
        for (k in 1..pad) {
            val mirror = k.coerceAtMost(max(length - 1, 0))
            padded[pad - k] = padded[pad + mirror]
            padded[pad + length - 1 + k] = padded[pad + length - 1 - mirror]
        }
        return padded
    }

    /**
     * Transforms [length] samples starting at [offset] into [outRe]/[outIm], which are
     * indexed `frame * bins + bin`.
     */
    fun forward(x: FloatArray, offset: Int, length: Int, outRe: FloatArray, outIm: FloatArray) {
        val frames = frameCount(length)
        val src = reflectPad(x, offset, length)
        for (t in 0 until frames) {
            val base = t * hop
            for (i in 0 until nFft) {
                re[i] = src[base + i] * window[i]
                im[i] = 0f
            }
            fft.forward(re, im)
            val dst = t * bins
            for (b in 0 until bins) {
                outRe[dst + b] = re[b]
                outIm[dst + b] = im[b]
            }
        }
    }

    /**
     * Inverse transform with the window-squared envelope normalisation `torch.istft`
     * applies, trimmed back to `signalLength(frames)` samples in [out] at [outOffset].
     */
    fun inverse(
        inRe: FloatArray,
        inIm: FloatArray,
        frames: Int,
        out: FloatArray,
        outOffset: Int,
    ) {
        val length = signalLength(frames)
        val total = length + 2 * pad
        if (acc.size != total) {
            acc = FloatArray(total)
            norm = FloatArray(total)
        } else {
            acc.fill(0f)
            norm.fill(0f)
        }
        for (t in 0 until frames) {
            val src = t * bins
            // Rebuild the Hermitian-symmetric spectrum before the inverse transform.
            for (b in 0 until bins) {
                re[b] = inRe[src + b]
                im[b] = inIm[src + b]
            }
            for (b in bins until nFft) {
                re[b] = inRe[src + (nFft - b)]
                im[b] = -inIm[src + (nFft - b)]
            }
            fft.inverse(re, im)
            val base = t * hop
            val scale = 1f / nFft
            for (i in 0 until nFft) {
                val w = window[i]
                acc[base + i] += re[i] * scale * w
                norm[base + i] += w * w
            }
        }
        for (i in 0 until length) {
            val d = norm[pad + i]
            out[outOffset + i] = if (d > 1e-8f) acc[pad + i] / d else 0f
        }
    }
}
