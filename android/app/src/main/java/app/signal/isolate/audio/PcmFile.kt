package app.signal.isolate.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Random-access reader over an interleaved float32 PCM scratch file. */
class PcmFile(file: File, val channels: Int, val frames: Long) : AutoCloseable {

    private val raf = RandomAccessFile(file, "r")
    private var scratch = ByteArray(0)

    /**
     * Reads [count] frames starting at frame [offset] into [out] (interleaved).
     * Reads past the end are zero-filled, so callers can request a full chunk at the
     * tail of a track without special-casing it.
     */
    fun read(offset: Long, count: Int, out: FloatArray) {
        val values = count * channels
        java.util.Arrays.fill(out, 0, values, 0f)
        if (offset >= frames) return
        val available = minOf(count.toLong(), frames - offset).toInt()
        val bytes = available * channels * 4
        if (scratch.size < bytes) scratch = ByteArray(bytes)
        raf.seek(offset * channels * 4L)
        raf.readFully(scratch, 0, bytes)
        val bb = ByteBuffer.wrap(scratch, 0, bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until available * channels) out[i] = bb.getFloat()
    }

    override fun close() = raf.close()
}
