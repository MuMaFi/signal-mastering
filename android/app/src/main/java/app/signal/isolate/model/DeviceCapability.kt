package app.signal.isolate.model

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlin.math.roundToInt

/**
 * Whether this phone can run a model — decided from memory that is free *now*.
 *
 * The first release checked total RAM against a recommendation and let an 8 GB phone
 * start a model whose session build peaked at 6.6 GB. Android had no room left, swapped
 * until it stalled, and because the separation runs as a foreground service it killed
 * everything around the app before the app itself. The phone froze for minutes.
 *
 * So the guard works from `availMem` — what the system can hand out without killing
 * anything — against each model's measured peak, and refuses to start rather than
 * gamble. During a run, Android's own low-memory signal ends the run cleanly.
 */
object DeviceCapability {

    /** Process overhead on top of the model: runtime, JVM, decode and overlap buffers. */
    private const val APP_OVERHEAD_MB = 350L

    enum class Verdict { FINE, TIGHT, TOO_SMALL }

    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
    }

    fun totalRamGb(context: Context): Double =
        (memoryInfo(context)?.totalMem ?: 0L) / 1_073_741_824.0

    fun verdict(context: Context, spec: ModelSpec): Verdict {
        val ram = totalRamGb(context)
        if (ram <= 0.0) return Verdict.FINE // unknown; the headroom check still runs
        return when {
            ram >= spec.minRamGb -> Verdict.FINE
            ram >= spec.minRamGb - 2 -> Verdict.TIGHT
            else -> Verdict.TOO_SMALL
        }
    }

    /** Shown under the model list, before anything is started. */
    fun warning(context: Context, spec: ModelSpec): String? {
        val ram = totalRamGb(context).roundToInt()
        return when (verdict(context, spec)) {
            Verdict.FINE -> null
            Verdict.TIGHT ->
                "Tight on this device (${ram} GB RAM, ${spec.minRamGb} GB recommended) — " +
                    "close other apps first."
            Verdict.TOO_SMALL ->
                "This device has about $ram GB of RAM; ${spec.displayName} needs " +
                    "${spec.minRamGb} GB. Try a smaller model."
        }
    }

    /**
     * Why [spec] must not start right now, or `null` if there is room for it.
     *
     * Needs the model's measured peak plus the app's own overhead to fit in what the
     * system reports as available, *above* the threshold at which Android starts
     * killing background processes.
     */
    fun headroomProblem(context: Context, spec: ModelSpec): String? {
        val info = memoryInfo(context) ?: return null
        val needed = (spec.peakMemoryMb + APP_OVERHEAD_MB) * 1_048_576L
        val usable = usableBytes(info)
        if (usable >= needed) return null
        return "Not enough free memory to start ${spec.displayName}: it needs about " +
            "${gb(needed)}, and ${gb(usable.coerceAtLeast(0))} is free right now. " +
            "Close other apps and try again" +
            if (spec.peakMemoryMb > ModelCatalog.all.minOf { it.peakMemoryMb }) {
                ", or pick a smaller model."
            } else {
                "."
            }
    }

    /**
     * Whether [spec]'s faster, hungrier graph-optimised setting fits right now, by the
     * same rule as [headroomProblem]. When it does not, the model still runs, lean.
     */
    fun roomForOptimizer(context: Context, spec: ModelSpec): Boolean {
        val peak = spec.optimizedPeakMemoryMb ?: return false
        val info = memoryInfo(context) ?: return false
        return usableBytes(info) >= (peak + APP_OVERHEAD_MB) * 1_048_576L
    }

    private fun usableBytes(info: ActivityManager.MemoryInfo) = info.availMem - info.threshold

    /** Android's own verdict that memory is running out; checked between chunks. */
    fun underPressure(context: Context): Boolean = memoryInfo(context)?.lowMemory == true

    private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)

    /** 32-bit builds cannot address what these models need. */
    val is64Bit: Boolean
        get() = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
}
