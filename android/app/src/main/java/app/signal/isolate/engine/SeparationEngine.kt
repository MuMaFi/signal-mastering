package app.signal.isolate.engine

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.signal.isolate.model.EngineKind
import app.signal.isolate.model.ModelSpec
import app.signal.isolate.model.Stem
import java.io.File

/**
 * One window of separation.
 *
 * Engines are chunk-shaped: the pipeline feeds `windowFrames` of interleaved mix audio
 * and gets the same window back per stem, then advances by `strideFrames`. Overlap-add
 * and file I/O live outside, so the engines stay pure DSP + inference.
 */
interface SeparationEngine : AutoCloseable {
    val windowFrames: Int
    val strideFrames: Int
    val channels: Int
    /** Stems this engine returns, in the order [process] fills them. */
    val producedStems: List<Stem>

    /**
     * Separates one window. The returned arrays are interleaved, `windowFrames * channels`
     * long, owned by the engine and valid until the next call.
     */
    fun process(mix: FloatArray): Array<FloatArray>
}

class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * ONNX Runtime knobs, kept as a named type so the verification harness can sweep them
 * (`--tune=pattern,arena,threads`) rather than leaving them as constants someone
 * reasoned their way to.
 *
 * The defaults here are ONNX Runtime's own, because sweeping them did not produce a
 * reliable reason to change them. Measured through the Java API (ORT 1.30, x86_64 — the
 * closest proxy available for the Android AAR), peak process RSS over one 11 s RoFormer
 * chunk and one 7.8 s Demucs segment:
 *
 * ```
 *   memoryPattern=true,  arena=true    RoFormer 4.1 GB   Demucs 6.5 GB   <- default
 *   memoryPattern=false, arena=true    RoFormer 4.0 GB   Demucs 8.7 GB
 *   memoryPattern=false, arena=false   RoFormer 3.6 GB, and twice as slow
 * ```
 *
 * Two caveats worth stating rather than hiding. The Demucs figures did not repeat
 * cleanly across thread counts on a contended machine (6.5 GB at two threads, 8.7 GB at
 * one and at three), so treat them as a range, not a spec. And the same sweep under the
 * Python runtime ranks the options differently — which is the whole reason this is a
 * parameter.
 *
 * What *is* robust, reproducing across both runtimes and every repeat, is the effect of
 * the RoFormer graph rewrite: 10.4–10.7 GB as published, 3.0–4.1 GB once its time axis
 * is dynamic. See `tools/onnx/make_dynamic_time.py`.
 */
data class RuntimeTuning(
    val memoryPattern: Boolean = true,
    val arena: Boolean = true,
    val threads: Int = EngineFactory.defaultThreads(),
)

object EngineFactory {

    /**
     * Opens [spec] and builds the engine for it.
     *
     * The RoFormer graph carries fp16 weights and ONNX Runtime contrib operators; if a
     * device's runtime cannot service those the session fails to build, and we surface
     * that as an actionable message instead of a raw native error, because the answer is
     * always "use the Demucs model on this device".
     */
    fun create(
        spec: ModelSpec,
        modelFile: File,
        requested: List<Stem>,
        tuning: RuntimeTuning = RuntimeTuning(),
    ): SeparationEngine {
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(tuning.threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setMemoryPatternOptimization(tuning.memoryPattern)
            setCPUArenaAllocator(tuning.arena)
        }
        val session = try {
            env.createSession(modelFile.absolutePath, options)
        } catch (e: Throwable) {
            options.close()
            throw EngineException(
                "${spec.displayName} could not be loaded on this device " +
                    "(${e.message?.take(160) ?: "unknown runtime error"}). " +
                    "Try a smaller model.",
                e,
            )
        }
        return when (spec.engine) {
            EngineKind.ROFORMER -> RoformerEngine(env, session, requested)
            EngineKind.DEMUCS -> DemucsEngine(env, session, spec, requested)
        }
    }

    /** Uses the big cores and leaves one thread for decode and disk. */
    fun defaultThreads(): Int =
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
}
