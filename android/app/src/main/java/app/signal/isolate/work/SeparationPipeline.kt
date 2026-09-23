package app.signal.isolate.work

import android.content.Context
import app.signal.isolate.audio.AudioDecoder
import app.signal.isolate.audio.OutputFormat
import app.signal.isolate.audio.OverlapAdd
import app.signal.isolate.audio.PcmFile
import app.signal.isolate.audio.WavConverter
import app.signal.isolate.audio.WavWriter
import app.signal.isolate.engine.EngineFactory
import app.signal.isolate.engine.RuntimeTuning
import app.signal.isolate.model.DeviceCapability
import app.signal.isolate.model.ModelCatalog
import app.signal.isolate.model.ModelManager
import app.signal.isolate.model.Stem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

/**
 * Turns a request into finished stem files.
 *
 * Audio never lives in the Java heap in full: the decoder writes interleaved float PCM
 * to a scratch file, the engine reads one window at a time from it, and the overlap-add
 * streams finished samples straight into the output writers. That leaves the heap free
 * for ONNX Runtime, which needs every byte it can get for the RoFormer weights.
 */
class SeparationPipeline(private val context: Context) {

    private val models = ModelManager(context)

    suspend fun run(
        request: SeparationRequest,
        onState: (SeparationState) -> Unit,
    ): SeparationState = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        val spec = ModelCatalog.byId(request.modelId)
        val scratch = File(context.cacheDir, "work").apply { deleteRecursively(); mkdirs() }
        val outputDir = File(context.filesDir, "stems").apply { mkdirs() }

        // No point fetching 741 MB for a model this phone cannot hold right now.
        DeviceCapability.headroomProblem(context, spec)?.let {
            return@withContext SeparationState.Failed(it)
        }
        RunJournal.begin(context, spec.displayName, request.displayName)

        try {
            RunJournal.stage(context, spec.displayName, request.displayName, "downloading the model")
            onState(SeparationState.Downloading(spec.displayName, models.installedBytes(spec), spec.totalBytes))
            models.ensure(spec) { done, total ->
                onState(SeparationState.Downloading(spec.displayName, done, total))
            }

            currentCoroutineContext().ensureActive()
            RunJournal.stage(context, spec.displayName, request.displayName, "reading the track")
            onState(SeparationState.Decoding(0f))
            val mixFile = File(scratch, "mix.f32")
            val decoded = withContext(Dispatchers.IO) {
                AudioDecoder.decode(context, request.source, mixFile) { p ->
                    onState(SeparationState.Decoding(p))
                }
            }

            currentCoroutineContext().ensureActive()
            // Decoding took time and other apps may have grown; check again right before
            // the one allocation that can take the phone down.
            DeviceCapability.headroomProblem(context, spec)?.let {
                throw MemoryPressure(it)
            }
            RunJournal.stage(context, spec.displayName, request.displayName, "loading the model")
            val requested = request.stems.ifEmpty { listOf(Stem.VOCALS) }
            val engine = EngineFactory.create(
                spec = spec,
                modelFile = models.entryPath(spec),
                requested = requested,
                tuning = RuntimeTuning.forModel(
                    spec,
                    roomForOptimizer = DeviceCapability.roomForOptimizer(context, spec),
                ),
            )

            val stems = engine.producedStems
            val baseName = sanitise(request.displayName)
            val temps = stems.map { File(scratch, "${it.id}.wav") }
            val writers = temps.map { WavWriter(it, AudioDecoder.TARGET_RATE, engine.channels) }

            val totalFrames = decoded.frames
            val chunks = maxOf(
                1,
                ceil((totalFrames - engine.windowFrames).toDouble() / engine.strideFrames).toInt() + 1,
            )

            try {
                val overlapAdd = OverlapAdd(
                    stemCount = stems.size,
                    channels = engine.channels,
                    windowFrames = engine.windowFrames,
                    strideFrames = engine.strideFrames,
                    totalFrames = totalFrames,
                ) { stemIndex, data, count ->
                    writers[stemIndex].write(data, 0, count)
                }

                PcmFile(mixFile, engine.channels, totalFrames).use { pcm ->
                    val window = FloatArray(engine.windowFrames * engine.channels)
                    var chunkStart = 0L
                    var index = 0
                    var elapsedNanos = 0L
                    while (index < chunks) {
                        currentCoroutineContext().ensureActive()
                        if (DeviceCapability.underPressure(context)) {
                            throw MemoryPressure(
                                "Stopped at chunk ${index + 1} of $chunks: Android reported that " +
                                    "memory is running out. Close other apps and try again.",
                            )
                        }
                        RunJournal.stage(
                            context, spec.displayName, request.displayName,
                            "separating chunk ${index + 1} of $chunks",
                        )
                        val t0 = System.nanoTime()
                        pcm.read(chunkStart, engine.windowFrames, window)
                        val produced = engine.process(window)
                        produced.forEachIndexed { s, data -> overlapAdd.add(s, data) }
                        overlapAdd.advance(last = index == chunks - 1)
                        elapsedNanos += System.nanoTime() - t0

                        index++
                        chunkStart += engine.strideFrames
                        val perChunk = elapsedNanos / index
                        val remaining = (chunks - index).toLong() * perChunk / 1_000_000_000L
                        onState(
                            SeparationState.Separating(
                                fraction = index.toFloat() / chunks,
                                chunk = index,
                                chunks = chunks,
                                secondsRemaining = if (index >= 1) remaining else null,
                            ),
                        )
                    }
                }
            } finally {
                writers.forEach { runCatching { it.close() } }
                runCatching { engine.close() }
            }

            currentCoroutineContext().ensureActive()
            onState(SeparationState.Finalizing)
            val results = withContext(Dispatchers.IO) {
                stems.mapIndexed { i, stem ->
                    val target = File(outputDir, "${baseName}_${stem.id}.${request.format.extension}")
                    if (target.exists()) target.delete()
                    if (request.format == OutputFormat.WAV_FLOAT32) {
                        temps[i].copyTo(target, overwrite = true)
                    } else {
                        WavConverter.convert(
                            source = temps[i],
                            destination = target,
                            sampleRate = AudioDecoder.TARGET_RATE,
                            channels = engine.channels,
                            format = request.format,
                            peak = writers[i].peak,
                        )
                    }
                    StemResult(stem, target, target.length())
                }
            }

            scratch.deleteRecursively()
            RunJournal.end(context)
            SeparationState.Done(
                results = results,
                elapsedSeconds = (System.currentTimeMillis() - started) / 1000,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            scratch.deleteRecursively()
            RunJournal.end(context)
            throw e
        } catch (e: MemoryPressure) {
            scratch.deleteRecursively()
            RunJournal.end(context)
            SeparationState.Failed(e.message ?: "Not enough memory.")
        } catch (e: OutOfMemoryError) {
            scratch.deleteRecursively()
            RunJournal.end(context)
            SeparationState.Failed(
                "Ran out of memory. ${spec.displayName} needs about " +
                    "%.1f GB free — close other apps or pick a smaller model."
                        .format((spec.peakMemoryMb + 350) / 1024.0),
            )
        } catch (e: Throwable) {
            scratch.deleteRecursively()
            RunJournal.end(context)
            SeparationState.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    /** A run stopped on purpose because memory ran short, not because something broke. */
    private class MemoryPressure(message: String) : Exception(message)

    private fun sanitise(name: String): String =
        name.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9 _-]"), "_")
            .trim()
            .take(60)
            .ifEmpty { "track" }
}
