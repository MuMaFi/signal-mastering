package app.signal.isolate.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.signal.isolate.model.ModelSpec
import app.signal.isolate.model.Stem
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HT-Demucs: waveform in, four stems out, no host-side transform.
 *
 * The graph is traced for a fixed 7.8 s segment with channel-major layout, matching the
 * reference numpy implementation shipped with the export.
 *
 * With the four-stem model the instrumental is `drums + bass + other`, which is the
 * model's own decomposition. With the fine-tuned vocals specialist only the vocals head
 * is meaningful, so the instrumental is the residual `mix - vocals`.
 */
class DemucsEngine(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    spec: ModelSpec,
    requested: List<Stem>,
) : SeparationEngine {

    override val channels = CHANNELS
    override val windowFrames = SEGMENT
    override val strideFrames = SEGMENT - SEGMENT / 4
    override val producedStems: List<Stem> =
        spec.stems.filter { it in requested }.ifEmpty { listOf(Stem.VOCALS) }

    private val fullBand = spec.stems.contains(Stem.DRUMS)
    private val inputName = session.inputNames.first()
    private val outputName = session.outputNames.first()
    private val outputs = Array(producedStems.size) { FloatArray(SEGMENT * CHANNELS) }

    private val inputBuffer = ByteBuffer
        .allocateDirect(CHANNELS * SEGMENT * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    override fun process(mix: FloatArray): Array<FloatArray> {
        inputBuffer.clear()
        for (c in 0 until CHANNELS) {
            val base = c * SEGMENT
            var i = c
            for (n in 0 until SEGMENT) {
                inputBuffer.put(base + n, mix[i])
                i += CHANNELS
            }
        }
        inputBuffer.rewind()

        OnnxTensor.createTensor(env, inputBuffer, SHAPE).use { tensor ->
            session.run(mapOf(inputName to tensor), setOf(outputName)).use { result ->
                val stems = (result[0] as OnnxTensor).floatBuffer
                producedStems.forEachIndexed { index, stem ->
                    fill(outputs[index], stems, stem, mix)
                }
            }
        }
        return outputs
    }

    private fun fill(out: FloatArray, stems: java.nio.FloatBuffer, stem: Stem, mix: FloatArray) {
        when (stem) {
            Stem.INSTRUMENTAL -> if (fullBand) {
                var i = 0
                for (n in 0 until SEGMENT) {
                    for (c in 0 until CHANNELS) {
                        val offset = (c * SEGMENT) + n
                        out[i++] = stems.get(DRUMS * STEM_STRIDE + offset) +
                            stems.get(BASS * STEM_STRIDE + offset) +
                            stems.get(OTHER * STEM_STRIDE + offset)
                    }
                }
            } else {
                var i = 0
                for (n in 0 until SEGMENT) {
                    for (c in 0 until CHANNELS) {
                        out[i] = mix[i] - stems.get(VOCALS * STEM_STRIDE + (c * SEGMENT) + n)
                        i++
                    }
                }
            }
            else -> {
                val source = when (stem) {
                    Stem.DRUMS -> DRUMS
                    Stem.BASS -> BASS
                    Stem.OTHER -> OTHER
                    else -> VOCALS
                }
                var i = 0
                for (n in 0 until SEGMENT) {
                    for (c in 0 until CHANNELS) {
                        out[i++] = stems.get(source * STEM_STRIDE + (c * SEGMENT) + n)
                    }
                }
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val CHANNELS = 2

        /** 7.8 s at 44.1 kHz — the traced segment length. */
        const val SEGMENT = 343_980

        // Output order of the export: drums, bass, other, vocals.
        private const val DRUMS = 0
        private const val BASS = 1
        private const val OTHER = 2
        private const val VOCALS = 3
        private const val STEM_STRIDE = CHANNELS * SEGMENT

        private val SHAPE = longArrayOf(1, CHANNELS.toLong(), SEGMENT.toLong())
    }
}
