package app.signal.isolate.work

import android.net.Uri
import app.signal.isolate.audio.OutputFormat
import app.signal.isolate.model.Stem
import java.io.File

data class SeparationRequest(
    val source: Uri,
    val displayName: String,
    val modelId: String,
    val stems: List<Stem>,
    val format: OutputFormat,
)

data class StemResult(
    val stem: Stem,
    val file: File,
    val sizeBytes: Long,
)

sealed interface SeparationState {

    data object Idle : SeparationState

    data class Downloading(
        val modelName: String,
        val done: Long,
        val total: Long,
    ) : SeparationState {
        val fraction: Float get() = if (total > 0) done.toFloat() / total else 0f
    }

    data class Decoding(val fraction: Float) : SeparationState

    data class Separating(
        val fraction: Float,
        val chunk: Int,
        val chunks: Int,
        val secondsRemaining: Long?,
    ) : SeparationState

    data object Finalizing : SeparationState

    data class Done(
        val results: List<StemResult>,
        val elapsedSeconds: Long,
    ) : SeparationState

    data class Failed(val message: String) : SeparationState

    data object Cancelled : SeparationState
}

val SeparationState.isRunning: Boolean
    get() = this is SeparationState.Downloading ||
        this is SeparationState.Decoding ||
        this is SeparationState.Separating ||
        this is SeparationState.Finalizing
