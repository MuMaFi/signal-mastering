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
 * Two of them are off by default, both for memory. Memory here is the kernel's
 * high-water mark (VmHWM) above the process's own, over four consecutive chunks —
 * because that is what a song is — through this Java API, four threads:
 *
 * ```
 *                        optimiser on                optimiser off
 *   Mel-Band RoFormer    +2.99 GB, 31 % faster       +1.76 GB
 *   HT-Demucs            +6.34 GB,  8 % faster       +1.11 GB
 *   SCNet Small          +1.53 GB,  no faster        +0.64 GB
 * ```
 *
 * - [optimization]: off unless the model has a ModelSpec.optimizedPeakMemoryMb and that
 *   much is free when the run starts. The graph optimiser constant-folds while it builds
 *   the session, and on HT-Demucs that alone peaks past 6 GB before a sample is
 *   processed — it is what froze a phone in 1.0.0. On the RoFormer the same switch costs
 *   1.2 GB and buys 31 %, so it is worth having whenever the phone can spare it.
 * - [memoryPattern]: the planner records the first run's allocations and pre-allocates
 *   that plan as one block from the second run on. For the RoFormer the jump is 1.78 →
 *   2.85 GB at chunk two, then flat — invisible to any single-run measurement, which is
 *   how 1.0.1 shipped with it on.
 */
data class RuntimeTuning(
    val optimization: OrtSession.SessionOptions.OptLevel = OrtSession.SessionOptions.OptLevel.NO_OPT,
    val memoryPattern: Boolean = false,
    val arena: Boolean = true,
    val threads: Int = EngineFactory.defaultThreads(),
) {
    companion object {
        /**
         * The defaults above, with the graph optimiser on only where [spec] was measured
         * to gain from it *and* the caller found room for its higher peak.
         */
        fun forModel(spec: ModelSpec, roomForOptimizer: Boolean) = RuntimeTuning(
            optimization = if (spec.optimizedPeakMemoryMb != null && roomForOptimizer) {
                OrtSession.SessionOptions.OptLevel.ALL_OPT
            } else {
                OrtSession.SessionOptions.OptLevel.NO_OPT
            },
        )
    }
}

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
        tuning: RuntimeTuning,
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
            EngineKind.SCNET -> ScnetEngine(env, session, spec, requested)
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
