package app.signal.isolate.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads and verifies model weights into app-private storage.
 *
 * The weights are far too large to ship inside an APK, so they are fetched once from
 * Hugging Face and pinned by SHA-256 — a truncated or swapped download is rejected
 * rather than handed to the runtime. Downloads resume from wherever they stopped,
 * which matters when the RoFormer weights are 741 MB.
 */
class ModelManager(private val context: Context) {

    class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

    fun directory(spec: ModelSpec): File =
        File(context.filesDir, "models/${spec.id}").apply { mkdirs() }

    fun entryPath(spec: ModelSpec): File = File(directory(spec), spec.entryFile)

    fun isInstalled(spec: ModelSpec): Boolean =
        spec.files.all { f ->
            val file = File(directory(spec), f.name)
            val marker = File(directory(spec), "${f.name}.sha256")
            file.isFile && file.length() == f.bytes &&
                marker.isFile && marker.readText().trim() == f.sha256
        } && (spec.bundledGraphAsset == null || entryPath(spec).isFile)

    fun installedBytes(spec: ModelSpec): Long =
        spec.files.sumOf { File(directory(spec), it.name).let { f -> if (f.isFile) f.length() else 0L } }

    fun delete(spec: ModelSpec) {
        directory(spec).deleteRecursively()
    }

    /**
     * Makes sure every file of [spec] is present and verified.
     * [onProgress] receives bytes completed and the total for the whole model.
     */
    suspend fun ensure(
        spec: ModelSpec,
        onProgress: (done: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        installBundledGraph(spec)
        if (isInstalled(spec)) {
            onProgress(spec.totalBytes, spec.totalBytes)
            return@withContext
        }

        val missing = spec.files.filter { f ->
            val file = File(directory(spec), f.name)
            val marker = File(directory(spec), "${f.name}.sha256")
            !(file.isFile && file.length() == f.bytes && marker.isFile &&
                marker.readText().trim() == f.sha256)
        }
        val needed = missing.sumOf { it.bytes }
        val free = context.filesDir.usableSpace
        if (free < needed + SAFETY_MARGIN) {
            throw DownloadException(
                "Not enough free space: ${format(needed)} needed, ${format(free)} available.",
            )
        }

        var doneBefore = spec.files.sumOf { if (it in missing) 0L else it.bytes }
        for (file in missing) {
            download(spec, file) { fileDone ->
                onProgress(doneBefore + fileDone, spec.totalBytes)
            }
            doneBefore += file.bytes
            onProgress(doneBefore, spec.totalBytes)
        }
    }

    /**
     * Copies the APK-bundled graph in beside the weights.
     *
     * The RoFormer graph the app runs is the published export with its time axis made
     * dynamic — same arithmetic, a third of the peak memory — so it cannot be fetched
     * from upstream. It references the downloaded `.onnx.data` by name, which is why the
     * two have to end up in the same directory.
     */
    private fun installBundledGraph(spec: ModelSpec) {
        val asset = spec.bundledGraphAsset ?: return
        val target = entryPath(spec)
        if (target.isFile && target.length() > 0) return
        context.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        }
    }

    private suspend fun download(spec: ModelSpec, file: ModelFile, onBytes: (Long) -> Unit) {
        val target = File(directory(spec), file.name)
        val part = File(directory(spec), "${file.name}.part")
        File(directory(spec), "${file.name}.sha256").delete()
        if (target.exists() && target.length() != file.bytes) target.delete()

        var attempt = 0
        while (true) {
            attempt++
            try {
                fetch(file, part, onBytes)
                break
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (attempt >= MAX_ATTEMPTS) {
                    throw DownloadException(
                        "Download of ${file.name} failed after $attempt attempts: ${e.message}", e,
                    )
                }
            }
        }

        if (part.length() != file.bytes) {
            part.delete()
            throw DownloadException("${file.name} has the wrong size — the download was incomplete.")
        }
        val digest = sha256(part)
        if (!digest.equals(file.sha256, ignoreCase = true)) {
            part.delete()
            throw DownloadException("${file.name} failed its checksum and was discarded.")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            throw DownloadException("Could not move ${file.name} into place.")
        }
        File(directory(spec), "${file.name}.sha256").writeText(file.sha256)
    }

    private suspend fun fetch(file: ModelFile, part: File, onBytes: (Long) -> Unit) {
        var existing = if (part.isFile) part.length() else 0L
        if (existing > file.bytes) {
            part.delete()
            existing = 0L
        }
        if (existing == file.bytes) {
            onBytes(existing)
            return
        }

        val connection = (URL(file.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = connection.responseCode
            val append = when {
                code == HttpURLConnection.HTTP_PARTIAL -> true
                code == HttpURLConnection.HTTP_OK -> false
                else -> throw DownloadException("Server returned HTTP $code for ${file.name}.")
            }
            if (!append) existing = 0L

            var written = existing
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReport > PROGRESS_STEP) {
                            lastReport = written
                            onBytes(written)
                        }
                    }
                    output.flush()
                }
            }
            onBytes(written)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
        private const val PROGRESS_STEP = 1L shl 20
        private const val SAFETY_MARGIN = 128L * 1024 * 1024

        /** Decimal units, so a download reads the same here as on the page it came from. */
        fun format(bytes: Long): String = when {
            bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1e9)
            bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1e6)
            else -> "%.0f kB".format(bytes / 1e3)
        }
    }
}
