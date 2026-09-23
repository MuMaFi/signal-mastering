package app.signal.isolate.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.signal.isolate.audio.Stft
import app.signal.isolate.audio.Window
import app.signal.isolate.model.ModelSpec
import app.signal.isolate.model.Stem
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * SCNet with the transform on the host side, exported by tools/onnx/export_scnet.py.
 *
 * Mirrors the original PyTorch forward exactly (checked to 7e-7 against it):
 *
 * - An 11 s chunk is padded with 1 300 zeros so the frame count comes out even (476),
 *   which SCNet's time-axis FFT needs.
 * - `torch.stft` is called **without a window** — rectangular — with `n_fft = 4096`,
 *   `hop = 1024`, centred reflect padding and `normalized=True` (a 1/√4096 scale).
 * - Input `[1, 4, 2049, 476]`, channel index `2·channel + (re|im)`. Output
 *   `[1, 4, 4, 2049, 476]`: source, then the same channel index. Sources are drums,
 *   bass, other, vocals — the spectrograms themselves, not masks.
 *
 * The instrumental is `drums + bass + other`, the model's own decomposition.
 */
class ScnetEngine(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    spec: ModelSpec,
    requested: List<Stem>,
) : SeparationEngine {

    override val channels = CHANNELS
    override val windowFrames = WINDOW
    override val strideFrames = STRIDE
    override val producedStems: List<Stem> =
        spec.stems.filter { it in requested }.ifEmpty { listOf(Stem.VOCALS) }

    private val stft = Stft(N_FFT, HOP, Window.RECTANGULAR)
    private val inputName = session.inputNames.first()
    private val outputName = session.outputNames.first()

    private val spectrumSize = FRAMES * BINS
    private val padded = Array(CHANNELS) { FloatArray(PADDED) }
    private val re = Array(CHANNELS) { FloatArray(spectrumSize) }
    private val im = Array(CHANNELS) { FloatArray(spectrumSize) }
    private val stemRe = FloatArray(spectrumSize)
    private val stemIm = FloatArray(spectrumSize)
    private val stemWave = FloatArray(PADDED)

    /** Which model outputs the requested stems need. */
    private val neededSources: Set<Int> = producedStems.flatMap { sourcesFor(it) }.toSet()
    private val sources = Array(SOURCE_COUNT) { s ->
        if (s in neededSources) Array(CHANNELS) { FloatArray(WINDOW) } else emptyArray()
    }
    private val outputs = Array(producedStems.size) { FloatArray(WINDOW * CHANNELS) }

    private val inputBuffer = ByteBuffer
        .allocateDirect(CHANNELS * 2 * spectrumSize * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    override fun process(mix: FloatArray): Array<FloatArray> {
        for (c in 0 until CHANNELS) {
            val dst = padded[c]
            var i = c
            for (n in 0 until WINDOW) {
                dst[n] = mix[i]
                i += CHANNELS
            }
            java.util.Arrays.fill(dst, WINDOW, PADDED, 0f)
            stft.forward(dst, 0, PADDED, re[c], im[c])
        }

        inputBuffer.clear()
        for (c in 0 until CHANNELS) {
            val baseRe = (2 * c) * spectrumSize
            val baseIm = (2 * c + 1) * spectrumSize
            val r = re[c]
            val m = im[c]
            for (f in 0 until BINS) {
                val row = f * FRAMES
                for (t in 0 until FRAMES) {
                    val s = t * BINS + f
                    inputBuffer.put(baseRe + row + t, r[s] * INV_NORM)
                    inputBuffer.put(baseIm + row + t, m[s] * INV_NORM)
                }
            }
        }
        inputBuffer.rewind()

        OnnxTensor.createTensor(env, inputBuffer, INPUT_SHAPE).use { tensor ->
            session.run(mapOf(inputName to tensor), setOf(outputName)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                for (s in neededSources) {
                    for (c in 0 until CHANNELS) {
                        val baseRe = (s * 4 + 2 * c) * spectrumSize
                        val baseIm = (s * 4 + 2 * c + 1) * spectrumSize
                        for (f in 0 until BINS) {
                            val row = f * FRAMES
                            for (t in 0 until FRAMES) {
                                stemRe[t * BINS + f] = out.get(baseRe + row + t) * NORM
                                stemIm[t * BINS + f] = out.get(baseIm + row + t) * NORM
                            }
                        }
                        stft.inverse(stemRe, stemIm, FRAMES, stemWave, 0)
                        System.arraycopy(stemWave, 0, sources[s][c], 0, WINDOW)
                    }
                }
            }
        }

        producedStems.forEachIndexed { index, stem ->
            val out = outputs[index]
            val parts = sourcesFor(stem)
            var i = 0
            for (n in 0 until WINDOW) {
                for (c in 0 until CHANNELS) {
                    var v = 0f
                    for (s in parts) v += sources[s][c][n]
                    out[i++] = v
                }
            }
        }
        return outputs
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val N_FFT = 4096
        const val HOP = 1024
        const val BINS = N_FFT / 2 + 1
        const val CHANNELS = 2
        private const val SOURCE_COUNT = 4

        /** 11 s — the chunk SCNet was trained on. */
        const val WINDOW = 485_100

        /** SCNet pads until the frame count is even: 485 100 + 1 300 = 475 hops. */
        private const val PAD = 1_300
        private const val PADDED = WINDOW + PAD
        const val FRAMES = 1 + PADDED / HOP

        /** 25 % overlap, as HT-Demucs. */
        const val STRIDE = WINDOW - WINDOW / 4

        /** `torch.stft(normalized=True)` scales by 1/√n_fft; istft undoes it. */
        private val NORM = sqrt(N_FFT.toFloat())
        private val INV_NORM = 1f / NORM

        private val INPUT_SHAPE = longArrayOf(1, (CHANNELS * 2).toLong(), BINS.toLong(), FRAMES.toLong())

        // Output order of the export: drums, bass, other, vocals.
        private fun sourcesFor(stem: Stem): List<Int> = when (stem) {
            Stem.DRUMS -> listOf(0)
            Stem.BASS -> listOf(1)
            Stem.OTHER -> listOf(2)
            Stem.VOCALS -> listOf(3)
            Stem.INSTRUMENTAL -> listOf(0, 1, 2)
        }
    }
}
