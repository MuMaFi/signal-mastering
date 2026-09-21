package app.signal.isolate.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.signal.isolate.audio.Stft
import app.signal.isolate.model.Stem
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mel-Band RoFormer with the transform on the host side.
 *
 * The export has `torch.stft` / `torch.istft` cut out of the graph: it consumes a
 * precomputed spectrogram and returns a complex mask, so this class owns the STFT, the
 * complex multiply and the inverse transform. The published contract is `n_fft = 2048`,
 * `hop = 441` at 44.1 kHz, periodic Hann, centred reflect padding, and a packed tensor
 * of shape `[1, (n_fft/2 + 1) * channels, frames, 2]` indexed `2 * freq + channel`.
 *
 * The model produces vocals; the instrumental side is the residual `mix - vocals`,
 * which is how the reference host uses it and keeps the two stems summing back to the
 * original mix exactly.
 */
class RoformerEngine(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    requested: List<Stem>,
) : SeparationEngine {

    override val channels = CHANNELS
    override val windowFrames = WINDOW
    override val strideFrames = STRIDE
    override val producedStems: List<Stem> =
        listOf(Stem.VOCALS, Stem.INSTRUMENTAL).filter { it in requested }
            .ifEmpty { listOf(Stem.VOCALS) }

    private val stft = Stft(N_FFT, HOP)
    private val inputName = session.inputNames.first()
    private val outputName = session.outputNames.first()

    private val spectrumSize = FRAMES * BINS
    private val re = Array(CHANNELS) { FloatArray(spectrumSize) }
    private val im = Array(CHANNELS) { FloatArray(spectrumSize) }
    private val deinterleaved = Array(CHANNELS) { FloatArray(WINDOW) }
    private val vocalChannel = Array(CHANNELS) { FloatArray(WINDOW) }
    private val outputs = Array(producedStems.size) { FloatArray(WINDOW * CHANNELS) }

    // Direct so ONNX Runtime reads the tensor without copying it onto the Java heap.
    private val inputBuffer = ByteBuffer
        .allocateDirect(TENSOR_VALUES * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    override fun process(mix: FloatArray): Array<FloatArray> {
        for (c in 0 until CHANNELS) {
            val dst = deinterleaved[c]
            var i = c
            for (n in 0 until WINDOW) {
                dst[n] = mix[i]
                i += CHANNELS
            }
            stft.forward(dst, 0, WINDOW, re[c], im[c])
        }

        inputBuffer.clear()
        for (f in 0 until BINS) {
            for (c in 0 until CHANNELS) {
                val base = ((f * CHANNELS + c) * FRAMES) * 2
                val sr = re[c]
                val si = im[c]
                for (t in 0 until FRAMES) {
                    val s = t * BINS + f
                    inputBuffer.put(base + t * 2, sr[s])
                    inputBuffer.put(base + t * 2 + 1, si[s])
                }
            }
        }
        inputBuffer.rewind()

        OnnxTensor.createTensor(env, inputBuffer, SHAPE).use { tensor ->
            session.run(mapOf(inputName to tensor), setOf(outputName)).use { result ->
                val mask = (result[0] as OnnxTensor).floatBuffer
                // Apply the complex mask in place on the analysis spectrum.
                for (f in 0 until BINS) {
                    for (c in 0 until CHANNELS) {
                        val base = ((f * CHANNELS + c) * FRAMES) * 2
                        val sr = re[c]
                        val si = im[c]
                        for (t in 0 until FRAMES) {
                            val s = t * BINS + f
                            val mr = mask.get(base + t * 2)
                            val mi = mask.get(base + t * 2 + 1)
                            val a = sr[s]
                            val b = si[s]
                            sr[s] = a * mr - b * mi
                            si[s] = a * mi + b * mr
                        }
                    }
                }
            }
        }

        for (c in 0 until CHANNELS) {
            stft.inverse(re[c], im[c], FRAMES, vocalChannel[c], 0)
        }

        producedStems.forEachIndexed { index, stem ->
            val out = outputs[index]
            when (stem) {
                Stem.VOCALS -> {
                    var i = 0
                    for (n in 0 until WINDOW) {
                        for (c in 0 until CHANNELS) out[i++] = vocalChannel[c][n]
                    }
                }
                else -> {
                    var i = 0
                    for (n in 0 until WINDOW) {
                        for (c in 0 until CHANNELS) {
                            out[i] = mix[i] - vocalChannel[c][n]
                            i++
                        }
                    }
                }
            }
        }
        return outputs
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val N_FFT = 2048
        const val HOP = 441
        const val FRAMES = 1101
        const val BINS = N_FFT / 2 + 1
        const val CHANNELS = 2

        /** 485 100 samples ≈ 11.0 s — the chunk the export was traced for. */
        const val WINDOW = (FRAMES - 1) * HOP

        /** 8 s step, i.e. ~3 s of overlap between neighbouring chunks. */
        const val STRIDE = 8 * 44_100

        private const val TENSOR_VALUES = BINS * CHANNELS * FRAMES * 2
        private val SHAPE = longArrayOf(1, (BINS * CHANNELS).toLong(), FRAMES.toLong(), 2)
    }
}
