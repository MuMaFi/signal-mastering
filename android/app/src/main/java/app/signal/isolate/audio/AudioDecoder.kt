package app.signal.isolate.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Interleaved 32-bit float PCM on disk, at [sampleRate] with [channels] channels. */
data class DecodedAudio(
    val file: File,
    val frames: Long,
    val sampleRate: Int,
    val channels: Int,
) {
    val durationSeconds: Double get() = frames.toDouble() / sampleRate
}

/**
 * Decodes any container/codec the device supports into interleaved stereo float PCM at
 * [TARGET_RATE], written straight to a scratch file.
 *
 * Going through disk rather than a `FloatArray` is deliberate: a five-minute stereo
 * track is ~105 MB of floats, and the app also has to hold ~740 MB of model weights in
 * native memory. Keeping PCM off the Java heap is what makes long tracks survivable.
 */
object AudioDecoder {

    const val TARGET_RATE = 44_100
    const val TARGET_CHANNELS = 2

    class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun decode(
        context: Context,
        uri: Uri,
        destination: File,
        onProgress: (Float) -> Unit = {},
    ): DecodedAudio = wrapErrors {
        var frames = 0L
        BufferedOutputStream(FileOutputStream(destination), 1 shl 16).use { out ->
            val sink = FloatSink(out)
            var resampler: StreamingResampler? = null
            forEachStereoBlock(
                context,
                uri,
                onProgress,
                onFormat = { rate -> resampler = StreamingResampler(rate, TARGET_RATE, TARGET_CHANNELS) },
            ) { stereo ->
                val r = resampler!!
                val frameCount = stereo.size / TARGET_CHANNELS
                if (r.bypass) {
                    sink.write(stereo, stereo.size)
                    frames += frameCount
                } else {
                    val resampled = r.process(stereo, frameCount)
                    sink.write(resampled, resampled.size)
                    frames += resampled.size / TARGET_CHANNELS
                }
            }
            val r = resampler!!
            if (!r.bypass) {
                val tail = r.flush()
                sink.write(tail, tail.size)
                frames += tail.size / TARGET_CHANNELS
            }
            sink.finish()
        }
        onProgress(1f)

        if (frames <= 0L) throw DecodeException("The file decoded to zero samples.")
        DecodedAudio(destination, frames, TARGET_RATE, TARGET_CHANNELS)
    }

    /**
     * A peak envelope for drawing: [buckets] values in 0..1, loudest block = 1.
     *
     * Decodes at the file's own rate and never touches disk — the picture needs the
     * shape of the track, not 44.1 kHz samples — so a typical song takes a second or two.
     */
    fun peaks(context: Context, uri: Uri, buckets: Int = 400): FloatArray = wrapErrors {
        val blockFrames = 1024
        var blocks = FloatArray(4096)
        var count = 0
        var current = 0f
        var inBlock = 0
        fun push(value: Float) {
            if (count == blocks.size) blocks = blocks.copyOf(blocks.size * 2)
            blocks[count++] = value
        }
        forEachStereoBlock(context, uri, onProgress = {}, onFormat = {}) { stereo ->
            var i = 0
            while (i + 1 < stereo.size) {
                val v = maxOf(abs(stereo[i]), abs(stereo[i + 1]))
                if (v > current) current = v
                if (++inBlock == blockFrames) {
                    push(current)
                    current = 0f
                    inBlock = 0
                }
                i += 2
            }
        }
        if (inBlock > 0) push(current)
        if (count == 0) return@wrapErrors FloatArray(0)

        val out = FloatArray(buckets)
        for (b in 0 until buckets) {
            val from = (b.toLong() * count / buckets).toInt()
            val to = maxOf(from + 1, ((b + 1).toLong() * count / buckets).toInt())
            var peak = 0f
            for (k in from until minOf(to, count)) if (blocks[k] > peak) peak = blocks[k]
            out[b] = peak
        }
        val loudest = out.maxOrNull()?.takeIf { it > 1e-6f } ?: return@wrapErrors out
        for (b in out.indices) out[b] /= loudest
        out
    }

    private inline fun <T> wrapErrors(block: () -> T): T = try {
        block()
    } catch (e: DecodeException) {
        throw e
    } catch (e: Exception) {
        throw DecodeException(e.message ?: "Could not decode this file.", e)
    }

    /**
     * The one MediaCodec loop both [decode] and [peaks] share: opens the first audio
     * track, reports its sample rate through [onFormat], then hands every decoded buffer
     * to [onBlock] as interleaved stereo floats.
     */
    private fun forEachStereoBlock(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit,
        onFormat: (sampleRate: Int) -> Unit,
        onBlock: (FloatArray) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: throw DecodeException("Could not open the selected file.")

            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw DecodeException("No audio track found in this file.")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            onFormat(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            }

            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var outputChannels = sourceChannels
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEos = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) {
                                onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        outputChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        if (outIndex >= 0) {
                            if (info.size > 0) {
                                val buffer = decoder.getOutputBuffer(outIndex)!!
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                onBlock(toStereoFloat(buffer, pcmEncoding, outputChannels))
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEos = true
                            }
                        }
                    }
                }
            }
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    /**
     * Folds the codec's PCM into interleaved stereo floats.
     *
     * Beyond stereo we keep the first two channels — for the standard 5.1 layout
     * (L, R, C, LFE, Ls, Rs) that is the front pair, which is what a separation model
     * trained on stereo music expects.
     */
    private fun toStereoFloat(buffer: ByteBuffer, encoding: Int, channels: Int): FloatArray {
        buffer.order(ByteOrder.nativeOrder())
        val source: FloatArray
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = buffer.asFloatBuffer()
            source = FloatArray(fb.remaining())
            fb.get(source)
        } else {
            val sb = buffer.asShortBuffer()
            source = FloatArray(sb.remaining())
            for (i in source.indices) source[i] = sb.get(i) / 32768f
        }
        if (channels == TARGET_CHANNELS) return source

        val frameCount = source.size / channels
        val out = FloatArray(frameCount * TARGET_CHANNELS)
        if (channels == 1) {
            for (i in 0 until frameCount) {
                out[i * 2] = source[i]
                out[i * 2 + 1] = source[i]
            }
        } else {
            for (i in 0 until frameCount) {
                out[i * 2] = source[i * channels]
                out[i * 2 + 1] = source[i * channels + 1]
            }
        }
        return out
    }

    private const val TIMEOUT_US = 10_000L

    private class FloatSink(private val out: java.io.OutputStream) {
        private val bytes = ByteArray(1 shl 16)
        private val wrapper = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun write(data: FloatArray, count: Int) {
            var i = 0
            while (i < count) {
                val n = minOf(count - i, bytes.size / 4)
                wrapper.clear()
                for (k in 0 until n) wrapper.putFloat(data[i + k])
                out.write(bytes, 0, n * 4)
                i += n
            }
        }

        fun finish() = out.flush()
    }
}
