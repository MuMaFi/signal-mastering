package verify

import app.signal.isolate.audio.Fft
import app.signal.isolate.audio.OverlapAdd
import app.signal.isolate.audio.StreamingResampler
import app.signal.isolate.audio.Stft
import app.signal.isolate.engine.EngineFactory
import app.signal.isolate.engine.RuntimeTuning
import ai.onnxruntime.OrtSession
import app.signal.isolate.model.ModelCatalog
import app.signal.isolate.model.ModelSpec
import app.signal.isolate.model.Stem
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import kotlin.system.exitProcess

/**
 * Offline harness for the app's DSP and inference code.
 *
 * It compiles the very sources the APK ships and runs them against fixtures produced by
 * a NumPy reference, so a drift in framing, tensor packing or stem order shows up as a
 * number here instead of as a muddy stem on someone's phone.
 */
object Main

private var failures = 0

fun main(args: Array<String>) {
    val data = File(args.getOrElse(0) { "data" })
    val models = File(args.getOrElse(1) { "models" })
    // Peak RSS is only meaningful per process, so a run can be limited to one model.
    val only = args.getOrNull(2)?.removePrefix("--only=")
    // Re-measure the runtime knobs: --tune=opt,pattern,arena[,threads]
    // e.g. --tune=none,true,true,4  (opt: none | basic | extended | all)
    val tuning = args.firstOrNull { it.startsWith("--tune=") }
        ?.removePrefix("--tune=")?.split(",")
        ?.let {
            RuntimeTuning(
                optimization = when (it[0]) {
                    "all" -> OrtSession.SessionOptions.OptLevel.ALL_OPT
                    "extended" -> OrtSession.SessionOptions.OptLevel.EXTENDED_OPT
                    "basic" -> OrtSession.SessionOptions.OptLevel.BASIC_OPT
                    else -> OrtSession.SessionOptions.OptLevel.NO_OPT
                },
                memoryPattern = it[1].toBoolean(),
                arena = it[2].toBoolean(),
                threads = it.getOrNull(3)?.toIntOrNull() ?: EngineFactory.defaultThreads(),
            )
        }
        ?: RuntimeTuning()
    println(
        "tuning: opt=${tuning.optimization} memoryPattern=${tuning.memoryPattern} " +
            "arena=${tuning.arena} threads=${tuning.threads}",
    )

    println("== fft ==")
    checkFft()

    println("\n== stft round-trip ==")
    checkStftRoundTrip()

    println("\n== overlap-add ==")
    checkOverlapAdd()

    println("\n== resampler ==")
    checkResampler()

    val mixFile = File(data, "test_mix.f32")
    if (!mixFile.isFile) {
        println("\n(no fixtures at ${data.absolutePath}; skipping model checks)")
        finish()
    }
    val mix = readFloats(mixFile)

    println("\n== stft vs numpy reference ==")
    checkStftAgainstReference(mix, data)

    if (only == null || only == "roformer") {
        println("\n== mel-band roformer ==")
        runEngine(ModelCatalog.ROFORMER, models, mix, data, "kt_roformer",
            listOf(Stem.VOCALS, Stem.INSTRUMENTAL), tuning)
    }

    if (only == null || only == "demucs") {
        println("\n== ht-demucs ==")
        runEngine(ModelCatalog.DEMUCS_4STEM, models, mix, data, "kt_demucs",
            listOf(Stem.VOCALS, Stem.INSTRUMENTAL, Stem.DRUMS, Stem.BASS, Stem.OTHER), tuning)
    }

    finish()
}

private fun finish(): Nothing {
    println(if (failures == 0) "\nALL CHECKS PASSED" else "\n$failures CHECK(S) FAILED")
    exitProcess(if (failures == 0) 0 else 1)
}

private fun report(name: String, value: Double, tolerance: Double) {
    val ok = value <= tolerance
    if (!ok) failures++
    println("  %-46s %.3e  (max %.0e) %s".format(name, value, tolerance, if (ok) "ok" else "FAILED"))
}

private fun checkFft() {
    // A single bin in, a pure tone out: catches twiddle sign and bit-reversal errors.
    val n = 2048
    val fft = Fft(n)
    val re = FloatArray(n) { sin(2.0 * Math.PI * 7.0 * it / n).toFloat() }
    val im = FloatArray(n)
    val original = re.copyOf()
    fft.forward(re, im)
    val peakBin = (0 until n / 2).maxByOrNull { re[it] * re[it] + im[it] * im[it] }
    println("  peak bin: $peakBin (expected 7)")
    if (peakBin != 7) failures++
    fft.inverse(re, im)
    var err = 0.0
    for (i in 0 until n) err = maxOf(err, abs(re[i] / n - original[i]).toDouble())
    report("inverse(forward(x)) == x", err, 1e-5)
}

private fun checkStftRoundTrip() {
    val stft = Stft(2048, 441)
    val length = 1100 * 441
    val x = FloatArray(length) {
        (0.4 * sin(2.0 * Math.PI * 440.0 * it / 44100.0) +
            0.2 * sin(2.0 * Math.PI * 3100.0 * it / 44100.0)).toFloat()
    }
    val frames = stft.frameCount(length)
    if (frames != 1101) {
        failures++
        println("  frame count $frames, expected 1101 FAILED")
    } else {
        println("  frame count: $frames")
    }
    val re = FloatArray(frames * stft.bins)
    val im = FloatArray(frames * stft.bins)
    stft.forward(x, 0, length, re, im)
    val out = FloatArray(length)
    stft.inverse(re, im, frames, out, 0)
    var err = 0.0
    for (i in 0 until length) err = maxOf(err, abs(out[i] - x[i]).toDouble())
    report("istft(stft(x)) == x", err, 1e-4)
}

private fun checkOverlapAdd() {
    // Constant input must come out constant: the trapezoid window plus the running
    // weight division has to be a partition of unity, including at both edges.
    val window = 485_100
    val stride = 352_800
    val total = window + stride * 3L
    val collected = FloatArray(total.toInt() * 2)
    var written = 0
    val ola = OverlapAdd(1, 2, window, stride, total) { _, data, count ->
        System.arraycopy(data, 0, collected, written, count)
        written += count
    }
    val chunk = FloatArray(window * 2) { 1f }
    val chunks = 4
    for (i in 0 until chunks) {
        ola.add(0, chunk)
        ola.advance(last = i == chunks - 1)
    }
    var err = 0.0
    for (i in 0 until written) err = maxOf(err, abs(collected[i] - 1f).toDouble())
    println("  frames emitted: ${written / 2} (expected $total)")
    if (written / 2 != total.toInt()) failures++
    report("constant signal survives overlap-add", err, 1e-6)
}

private fun checkResampler() {
    // A 1 kHz tone at 48 kHz, resampled to 44.1 kHz, must stay a 1 kHz tone.
    val inRate = 48_000
    val outRate = 44_100
    val frames = inRate * 2
    val resampler = StreamingResampler(inRate, outRate, 1)
    val input = FloatArray(frames) { sin(2.0 * Math.PI * 1000.0 * it / inRate).toFloat() }
    val produced = ArrayList<Float>()
    var offset = 0
    while (offset < frames) {
        val n = minOf(4096, frames - offset)
        val block = input.copyOfRange(offset, offset + n)
        resampler.process(block, n).forEach { produced.add(it) }
        offset += n
    }
    resampler.flush().forEach { produced.add(it) }
    println("  output frames: ${produced.size} (expected ~${frames.toLong() * outRate / inRate})")

    // Compare the settled middle against an ideal tone, allowing for the filter delay.
    val start = outRate / 2
    val count = outRate
    var err = 0.0
    for (i in 0 until count) {
        val expected = sin(2.0 * Math.PI * 1000.0 * (start + i) / outRate).toFloat()
        err = maxOf(err, abs(produced[start + i] - expected).toDouble())
    }
    report("1 kHz tone through 48k -> 44.1k", err, 5e-3)
}

private fun checkStftAgainstReference(mix: FloatArray, data: File) {
    val reference = File(data, "ref_stft.f32")
    if (!reference.isFile) {
        println("  (no ref_stft.f32)")
        return
    }
    val frames = 1101
    val bins = 1025
    val channels = 2
    val window = (frames - 1) * 441
    val stft = Stft(2048, 441)
    val packed = FloatArray(bins * channels * frames * 2)
    for (c in 0 until channels) {
        val mono = FloatArray(window) { mix[it * channels + c] }
        val re = FloatArray(frames * bins)
        val im = FloatArray(frames * bins)
        stft.forward(mono, 0, window, re, im)
        for (f in 0 until bins) {
            val base = ((f * channels + c) * frames) * 2
            for (t in 0 until frames) {
                packed[base + t * 2] = re[t * bins + f]
                packed[base + t * 2 + 1] = im[t * bins + f]
            }
        }
    }
    val expected = readFloats(reference)
    var err = 0.0
    var scale = 0.0
    for (i in packed.indices) {
        err = maxOf(err, abs(packed[i] - expected[i]).toDouble())
        scale = maxOf(scale, abs(expected[i]).toDouble())
    }
    println("  reference magnitude scale: %.3f".format(scale))
    report("packed stft tensor vs numpy", err / maxOf(scale, 1e-9), 1e-5)
}

private fun runEngine(
    spec: ModelSpec,
    models: File,
    mix: FloatArray,
    data: File,
    prefix: String,
    requested: List<Stem>,
    tuning: RuntimeTuning = RuntimeTuning(),
) {
    val file = File(models, spec.entryFile)
    if (!file.isFile) {
        println("  (missing ${file.name}; skipped)")
        return
    }
    val rss = PeakRss()
    val engine = EngineFactory.create(spec, file, requested, tuning)
    engine.use {
        val window = FloatArray(engine.windowFrames * engine.channels)
        System.arraycopy(mix, 0, window, 0, minOf(mix.size, window.size))
        val started = System.nanoTime()
        val outputs = engine.process(window)
        val seconds = (System.nanoTime() - started) / 1e9
        val audioSeconds = engine.windowFrames / 44100.0
        println("  %.1f s for %.1f s of audio (RTF %.2f)".format(seconds, audioSeconds, seconds / audioSeconds))
        val (baseline, peak) = rss.stop()
        println("  RSS %.2f GB before the session, %.2f GB peak (+%.2f GB)"
            .format(baseline / 1e9, peak / 1e9, (peak - baseline) / 1e9))
        engine.producedStems.forEachIndexed { i, stem ->
            val rms = Math.sqrt(outputs[i].sumOf { it.toDouble() * it } / outputs[i].size)
            val peak = outputs[i].maxOf { abs(it) }
            println("  %-12s rms %.6f peak %.6f".format(stem.id, rms, peak))
            writeFloats(File(data, "${prefix}_${stem.id}.f32"), outputs[i])
        }
    }
}

/**
 * Peak resident memory from the kernel's own high-water mark (VmHWM).
 *
 * Peak RSS is the number that decides whether a model is usable on a phone at all. An
 * earlier version sampled RSS every 50 ms and missed short spikes — the optimiser's
 * allocation during session build among them — so its numbers scattered between runs.
 * VmHWM cannot miss a spike.
 */
private class PeakRss {
    private val baseline = read("VmRSS")

    /** Returns the RSS before the session was opened, and the peak reached since. */
    fun stop(): Pair<Long, Long> = baseline to maxOf(read("VmHWM"), baseline)

    private fun read(field: String): Long = runCatching {
        File("/proc/self/status").readLines()
            .first { it.startsWith("$field:") }
            .split(Regex("\\s+"))[1].toLong() * 1024
    }.getOrDefault(0L)
}

private fun readFloats(file: File): FloatArray {
    val bytes = file.readBytes()
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / 4) { bb.getFloat() }
}

private fun writeFloats(file: File, data: FloatArray) {
    val bb = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    data.forEach { bb.putFloat(it) }
    file.writeBytes(bb.array())
}
