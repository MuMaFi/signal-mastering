package app.signal.isolate.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

enum class OutputFormat(val label: String, val extension: String) {
    /** `WAVE_FORMAT_IEEE_FLOAT`. Nothing clips, nothing is rescaled. */
    WAV_FLOAT32("WAV 32-bit float", "wav"),

    /** 24-bit PCM with peak-safe gain, for players that dislike float WAV. */
    WAV_PCM24("WAV 24-bit", "wav"),

    /** 16-bit PCM with peak-safe gain. */
    WAV_PCM16("WAV 16-bit", "wav"),
}

/**
 * Streaming float32 WAV writer.
 *
 * Every stem is written as float first so nothing clips mid-render, and the integer
 * formats are produced afterwards by [WavConverter], which knows the finished peak.
 */
class WavWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int,
) : AutoCloseable {

    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val path = file
    private var dataBytes = 0L
    private val bytes = ByteArray(1 shl 16)
    private val wrapper = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    var peak: Float = 0f
        private set

    init {
        out.write(ByteArray(HEADER_SIZE)) // patched in close()
    }

    /** Appends [count] interleaved float samples from [data] starting at [offset]. */
    fun write(data: FloatArray, offset: Int, count: Int) {
        var i = 0
        while (i < count) {
            val n = minOf(count - i, bytes.size / 4)
            wrapper.clear()
            for (k in 0 until n) {
                val v = data[offset + i + k]
                val a = abs(v)
                if (a > peak) peak = a
                wrapper.putFloat(v)
            }
            out.write(bytes, 0, n * 4)
            i += n
        }
        dataBytes += count.toLong() * 4
    }

    override fun close() {
        out.flush()
        out.close()
        RandomAccessFile(path, "rw").use { raf ->
            raf.seek(0)
            raf.write(header(sampleRate, channels, bitsPerSample = 32, float = true, dataBytes))
        }
    }

    companion object {
        const val HEADER_SIZE = 44

        fun header(
            sampleRate: Int,
            channels: Int,
            bitsPerSample: Int,
            float: Boolean,
            dataBytes: Long,
        ): ByteArray {
            val blockAlign = channels * bitsPerSample / 8
            val bb = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray())
            bb.putInt((36 + dataBytes).toInt())
            bb.put("WAVE".toByteArray())
            bb.put("fmt ".toByteArray())
            bb.putInt(16)
            bb.putShort(if (float) 3 else 1)
            bb.putShort(channels.toShort())
            bb.putInt(sampleRate)
            bb.putInt(sampleRate * blockAlign)
            bb.putShort(blockAlign.toShort())
            bb.putShort(bitsPerSample.toShort())
            bb.put("data".toByteArray())
            bb.putInt(dataBytes.toInt())
            return bb.array()
        }
    }
}

/** Converts a finished float32 WAV into 16- or 24-bit PCM without clipping. */
object WavConverter {

    private const val CEILING = 0.999f // just under full scale

    fun convert(
        source: File,
        destination: File,
        sampleRate: Int,
        channels: Int,
        format: OutputFormat,
        peak: Float,
    ) {
        val bits = when (format) {
            OutputFormat.WAV_PCM24 -> 24
            OutputFormat.WAV_PCM16 -> 16
            OutputFormat.WAV_FLOAT32 -> error("float32 needs no conversion")
        }
        // Only ever attenuate: a stem that already fits keeps its original level, so
        // vocals and instrumental still sum back to the mix.
        val gain = if (peak > CEILING) CEILING / peak else 1f
        val bytesPerSample = bits / 8
        val samples = (source.length() - WavWriter.HEADER_SIZE) / 4

        RandomAccessFile(source, "r").use { input ->
            input.seek(WavWriter.HEADER_SIZE.toLong())
            BufferedOutputStream(FileOutputStream(destination), 1 shl 16).use { out ->
                out.write(
                    WavWriter.header(
                        sampleRate, channels, bits, float = false,
                        dataBytes = samples * bytesPerSample,
                    ),
                )
                val inBytes = ByteArray(1 shl 16)
                val outBytes = ByteArray(inBytes.size / 4 * bytesPerSample)
                var remaining = samples
                while (remaining > 0) {
                    val chunk = minOf(remaining, (inBytes.size / 4).toLong()).toInt()
                    input.readFully(inBytes, 0, chunk * 4)
                    val bb = ByteBuffer.wrap(inBytes, 0, chunk * 4).order(ByteOrder.LITTLE_ENDIAN)
                    var w = 0
                    for (i in 0 until chunk) {
                        val v = (bb.getFloat() * gain).coerceIn(-1f, 1f)
                        if (bits == 16) {
                            val s = (v * 32767f).roundToInt()
                            outBytes[w++] = (s and 0xFF).toByte()
                            outBytes[w++] = ((s shr 8) and 0xFF).toByte()
                        } else {
                            val s = (v * 8_388_607f).roundToInt()
                            outBytes[w++] = (s and 0xFF).toByte()
                            outBytes[w++] = ((s shr 8) and 0xFF).toByte()
                            outBytes[w++] = ((s shr 16) and 0xFF).toByte()
                        }
                    }
                    out.write(outBytes, 0, w)
                    remaining -= chunk
                }
            }
        }
    }
}
