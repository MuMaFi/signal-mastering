package app.signal.isolate.work

import android.content.Context
import java.io.File

/**
 * A breadcrumb for runs that never got to report how they ended.
 *
 * When Android's low-memory killer takes the process there is no exception, no callback
 * and no error screen — the app is simply gone, and next time it opens as if nothing
 * happened. The journal is written when a run starts, updated as it moves through its
 * stages, and deleted when it ends by any route the app itself controls. Finding it on
 * a fresh launch therefore means the system ended the run, and at which point.
 */
object RunJournal {

    data class Interrupted(val model: String, val track: String, val stage: String)

    private fun file(context: Context) = File(context.filesDir, "run-in-progress.txt")

    fun begin(context: Context, model: String, track: String) =
        write(context, model, track, "starting")

    fun stage(context: Context, model: String, track: String, stage: String) =
        write(context, model, track, stage)

    fun end(context: Context) {
        file(context).delete()
    }

    /** Returns the run a previous process died in, once, and clears it. */
    fun takeInterrupted(context: Context): Interrupted? {
        val f = file(context)
        if (!f.isFile) return null
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
        f.delete()
        if (lines.size < 3) return null
        return Interrupted(model = lines[0], track = lines[1], stage = lines[2])
    }

    private fun write(context: Context, model: String, track: String, stage: String) {
        runCatching {
            file(context).writeText("${model.oneLine()}\n${track.oneLine()}\n${stage.oneLine()}\n")
        }
    }

    private fun String.oneLine() = replace('\n', ' ').replace('\r', ' ')
}
