package app.signal.isolate.audio

import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT.
 *
 * The separation models dwarf the transform cost (a full 11 s Roformer chunk needs
 * ~4400 transforms of size 2048, which is noise next to 2300 transformer nodes), so
 * this is deliberately a plain, allocation-free Cooley-Tukey rather than anything
 * exotic. Twiddles and the bit-reversal permutation are precomputed once per size.
 */
class Fft(val n: Int) {

    init {
        require(n > 1 && (n and (n - 1)) == 0) { "FFT size must be a power of two, got $n" }
    }

    private val reverse = IntArray(n).also { rev ->
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            rev[i] = j
        }
    }

    // cos/sin for every butterfly stage, laid out stage by stage.
    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)

    init {
        for (i in 0 until n / 2) {
            val angle = -2.0 * Math.PI * i / n
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
    }

    /** Forward transform of [re]/[im], both of length [n]. */
    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, inverse = false)

    /** Unnormalised inverse transform; callers divide by [n]. */
    fun inverse(re: FloatArray, im: FloatArray) = transform(re, im, inverse = true)

    private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 1 until n) {
            val j = reverse[i]
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            val half = len / 2
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosTable[k]
                    val wi = if (inverse) -sinTable[k] else sinTable[k]
                    val ur = re[j]
                    val ui = im[j]
                    val vr = re[j + half] * wr - im[j + half] * wi
                    val vi = re[j + half] * wi + im[j + half] * wr
                    re[j] = ur + vr
                    im[j] = ui + vi
                    re[j + half] = ur - vr
                    im[j + half] = ui - vi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
