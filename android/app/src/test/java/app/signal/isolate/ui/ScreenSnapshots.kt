package app.signal.isolate.ui

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.signal.isolate.audio.OutputFormat
import app.signal.isolate.model.ModelCatalog
import app.signal.isolate.model.Stem
import app.signal.isolate.work.RunJournal
import app.signal.isolate.work.SeparationState
import app.signal.isolate.work.StemResult
import com.android.resources.NightMode
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Rule
import org.junit.Test

/**
 * Renders every screen of the app, light and dark, to PNG.
 *
 * These exist so the Compose UI gets looked at, not only compiled — the same check the
 * web preview gets from a browser. Record with `./gradlew :app:recordPaparazziDebug`;
 * the images land in `app/src/test/snapshots/images/`.
 */
class ScreenSnapshots {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = PHONE,
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    private val peaks = FloatArray(400) { i ->
        (0.3f + 0.55f * abs(sin(i / 9.0)).toFloat() * (0.55f + 0.45f * sin(i / 53.0).toFloat()))
            .coerceIn(0f, 1f)
    }

    @Test fun setupLight() = shoot("setup-light", dark = false) { setup() }
    @Test fun setupDark() = shoot("setup-dark", dark = true) { setup() }
    @Test fun setupFourStemsLight() = shoot("setup-4stem-light", dark = false) { setupFourStems() }
    @Test fun separatingLight() = shoot("separating-light", dark = false) { separating() }
    @Test fun separatingDark() = shoot("separating-dark", dark = true) { separating() }
    @Test fun doneLight() = shoot("done-light", dark = false) { done() }
    @Test fun doneDark() = shoot("done-dark", dark = true) { done() }
    @Test fun failedLight() = shoot("failed-light", dark = false) { failed() }
    @Test fun interruptedLight() = shoot("interrupted-light", dark = false) { interrupted() }

    @Composable
    private fun setup() = SetupView(
        sourceName = "04 Nightdrive (master).wav",
        track = TrackInfo(seconds = 247.0, peaks = peaks),
        spec = ModelCatalog.ROFORMER,
        installed = emptySet(),
        stems = setOf(Stem.VOCALS, Stem.INSTRUMENTAL),
        format = OutputFormat.WAV_FLOAT32,
        canStart = true,
        onPick = {}, onModel = {}, onRemove = {}, onStem = { _, _ -> }, onFormat = {}, onStart = {},
    )

    @Composable
    private fun setupFourStems() = SetupView(
        sourceName = "04 Nightdrive (master).wav",
        track = TrackInfo(seconds = 247.0, peaks = peaks),
        spec = ModelCatalog.DEMUCS_4STEM,
        installed = setOf(ModelCatalog.DEMUCS_4STEM.id),
        stems = setOf(Stem.VOCALS, Stem.DRUMS),
        format = OutputFormat.WAV_PCM24,
        canStart = true,
        onPick = {}, onModel = {}, onRemove = {}, onStem = { _, _ -> }, onFormat = {}, onStart = {},
    )

    @Composable
    private fun separating() = RunView(
        state = SeparationState.Separating(fraction = 0.42f, chunk = 13, chunks = 31, secondsRemaining = 301),
        trackName = "04 Nightdrive (master).wav",
        seconds = 247.0,
        spec = ModelCatalog.ROFORMER,
        stems = listOf(Stem.VOCALS, Stem.INSTRUMENTAL),
        onCancel = {},
    )

    @Composable
    private fun done() = DoneView(
        results = listOf(
            StemResult(Stem.VOCALS, File("04 Nightdrive (master)_vocals.wav"), 87_150_044),
            StemResult(Stem.INSTRUMENTAL, File("04 Nightdrive (master)_instrumental.wav"), 87_150_044),
        ),
        elapsedSeconds = 371,
        onReset = {},
    )

    @Composable
    private fun failed() = FailedView(
        message = "Not enough free memory to start Mel-Band RoFormer: it needs about 2.1 GB, " +
            "and 1.4 GB is free right now. Close other apps and try again, or pick a " +
            "smaller model.",
        onBack = {},
    )

    @Composable
    private fun interrupted() {
        InterruptedNotice(
            RunJournal.Interrupted(
                model = "HT-Demucs FT (vocals)",
                track = "04 Nightdrive (master).wav",
                stage = "loading the model",
            ),
            onDismiss = {},
        )
        SectionSpacer()
        setup()
    }

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = PHONE.copy(nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT),
        )
        paparazzi.snapshot(name) {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides NoOpRegistryOwner) {
                SignalIsolateTheme(dark = dark) {
                    LargeTitleScaffold(
                        title = "Isolate",
                        subtitle = "Separate vocals from the backing track, on your own device.",
                    ) { content() }
                }
            }
        }
    }

    /** Save buttons register launchers; nothing is ever launched in a snapshot. */
    private object NoOpRegistryOwner : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) = Unit
        }
    }

    private companion object {
        /** A 6.1" phone, tall enough that the whole setup screen fits in one image. */
        val PHONE = DeviceConfig.PIXEL_5.copy(screenHeight = 3400)
    }
}
