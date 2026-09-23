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
 * (`--tune=opt,pattern,arena,threads`) rather than leaving them as constants someone
 * reasoned their way to.
 *
 * The one that matters is [optimization], and the default is **off**. ONNX Runtime's
 * graph optimiser constant-folds while it builds the session, and on HT-Demucs that
 * folding alone peaks at 6.6 GB before a single sample is processed — the kernel's
 * high-water mark (VmHWM), same machine, same input:
 *
 * ```
 *                        optimiser on          optimiser off
 *   HT-Demucs            6.60 GB  RTF 0.36     1.06 GB  RTF 0.41
 *   RoFormer, 11 s       3.44 GB  RTF 1.75     2.66 GB  RTF 2.08
 *   RoFormer, 5.5 s        —                   1.77 GB  RTF 1.80
 * ```
 *
 * On an 8 GB phone the left column is the difference between a slow render and a phone
 * that stops responding for minutes. With the optimiser off, the RoFormer weights are
 * also left memory-mapped from disk, so the kernel can drop and re-read those pages
 * under pressure instead of having to kill something.
 *
 * An earlier version of this comment tuned the arena and the memory-pattern planner and
 * concluded the runtime's defaults were fine. They were measured with the optimiser on,
 * where the optimiser's own spike swamped everything else.
 */
data class RuntimeTuning(
    val optimization: OrtSession.SessionOptions.OptLevel = OrtSession.SessionOptions.OptLevel.NO_OPT,
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
            setOptimizationLevel(tuning.optimization)
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

    /**
     * Half the cores, at most four. Phone SoCs pair fast cores with efficiency ones
     * (the Snapdragon 8 Gen 1 is 1 + 3 fast, 4 slow); spreading a matmul across the
     * slow ones makes every step wait for its stragglers, and each thread brings its
     * own scratch buffers.
     */
    fun defaultThreads(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
}
