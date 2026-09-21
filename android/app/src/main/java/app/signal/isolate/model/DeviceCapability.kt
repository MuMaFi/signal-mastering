package app.signal.isolate.model

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlin.math.roundToInt

/**
 * Whether this phone can actually run a given model.
 *
 * Both engines peak around 3.5 GB of native memory, measured. That is not a number a
 * user can be expected to reason about, so the model list says plainly which entries
 * this device is likely to survive instead of letting them pick one and get killed by
 * the low-memory killer twenty minutes in.
 */
object DeviceCapability {

    enum class Verdict { FINE, TIGHT, TOO_SMALL }

    fun totalRamGb(context: Context): Double {
        val info = ActivityManager.MemoryInfo()
        val manager = context.getSystemService(ActivityManager::class.java) ?: return 0.0
        manager.getMemoryInfo(info)
        return info.totalMem / 1_073_741_824.0
    }

    fun verdict(context: Context, spec: ModelSpec): Verdict {
        val ram = totalRamGb(context)
        if (ram <= 0.0) return Verdict.FINE // unknown; do not block the user
        return when {
            ram >= spec.minRamGb -> Verdict.FINE
            ram >= spec.minRamGb - 2 -> Verdict.TIGHT
            else -> Verdict.TOO_SMALL
        }
    }

    fun warning(context: Context, spec: ModelSpec): String? {
        val ram = totalRamGb(context).roundToInt()
        return when (verdict(context, spec)) {
            Verdict.FINE -> null
            Verdict.TIGHT ->
                "Tight on this device (${ram} GB RAM, ${spec.minRamGb} GB recommended) — " +
                    "close other apps first."
            Verdict.TOO_SMALL ->
                "This device has about $ram GB of RAM; ${spec.displayName} needs " +
                    "${spec.minRamGb} GB and will most likely be killed mid-render."
        }
    }

    /** 32-bit builds cannot address what these models need. */
    val is64Bit: Boolean
        get() = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
}
