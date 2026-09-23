package app.signal.isolate.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.signal.isolate.audio.AudioDecoder
import app.signal.isolate.audio.OutputFormat
import app.signal.isolate.model.DeviceCapability
import app.signal.isolate.model.ModelCatalog
import app.signal.isolate.model.ModelManager
import app.signal.isolate.model.ModelSpec
import app.signal.isolate.model.Stem
import app.signal.isolate.work.SeparationController
import app.signal.isolate.work.SeparationRequest
import app.signal.isolate.work.SeparationService
import app.signal.isolate.work.RunJournal
import app.signal.isolate.work.SeparationState
import app.signal.isolate.work.StemResult
import app.signal.isolate.work.isRunning
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the track row shows: the name at once, the length and the envelope once read. */
internal data class TrackInfo(val seconds: Double? = null, val peaks: FloatArray? = null)

@Composable
fun MainScreen(initialAudio: Uri? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models = remember { ModelManager(context) }
    val state by SeparationController.state.collectAsStateWithLifecycle()

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf(ModelCatalog.ROFORMER.id) }
    var stems by remember { mutableStateOf(setOf(Stem.VOCALS, Stem.INSTRUMENTAL)) }
    var format by remember { mutableStateOf(OutputFormat.WAV_FLOAT32) }
    var installedRevision by remember { mutableIntStateOf(0) }

    val spec = ModelCatalog.byId(modelId)

    // A journal left behind by a previous process means Android ended that run, most
    // likely for memory. Read once per launch — and never while a run is live here.
    var interrupted by remember {
        mutableStateOf(
            if (SeparationController.state.value.isRunning) null else RunJournal.takeInterrupted(context),
        )
    }

    LaunchedEffect(initialAudio) {
        if (initialAudio != null && sourceUri == null) {
            sourceUri = initialAudio
            sourceName = Sharing.displayName(context, initialAudio)
        }
    }

    // Length first (a header read, instant), then the envelope (a real decode).
    val track by produceState(TrackInfo(), sourceUri) {
        val uri = sourceUri ?: run { value = TrackInfo(); return@produceState }
        value = TrackInfo()
        val seconds = withContext(Dispatchers.IO) { Sharing.durationSeconds(context, uri) }
        value = TrackInfo(seconds = seconds)
        val peaks = withContext(Dispatchers.Default) {
            runCatching { AudioDecoder.peaks(context, uri) }.getOrNull()
        }
        value = TrackInfo(seconds = seconds, peaks = peaks)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            sourceUri = uri
            sourceName = Sharing.displayName(context, uri)
        }
    }

    // Keep the stem selection inside what the chosen model can produce.
    LaunchedEffect(modelId) {
        stems = stems.filter { it in spec.stems }.toSet().ifEmpty { setOf(Stem.VOCALS) }
    }
    LaunchedEffect(state) {
        if (state is SeparationState.Done) installedRevision++
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { models.pruneRetired() }
    }

    LargeTitleScaffold(
        title = "Isolate",
        subtitle = "Separate vocals from the backing track, on your own device.",
    ) {
        when (val s = state) {
            is SeparationState.Done -> DoneView(
                results = s.results,
                elapsedSeconds = s.elapsedSeconds,
                onReset = { SeparationController.reset() },
            )
            is SeparationState.Failed -> FailedView(
                message = s.message,
                onBack = { SeparationController.reset() },
            )
            else -> if (s.isRunning) {
                RunView(
                    state = s,
                    trackName = sourceName,
                    seconds = track.seconds,
                    spec = spec,
                    stems = spec.stems.filter { it in stems },
                    onCancel = {
                        scope.launch {
                            SeparationController.cancel()
                            SeparationService.stop(context)
                        }
                    },
                )
            } else {
                interrupted?.let { run ->
                    InterruptedNotice(run, onDismiss = { interrupted = null })
                    SectionSpacer()
                }
                SetupView(
                    sourceName = sourceName,
                    track = track,
                    spec = spec,
                    installed = remember(installedRevision) {
                        ModelCatalog.all.filter { models.isInstalled(it) }.map { it.id }.toSet()
                    },
                    stems = stems,
                    format = format,
                    onPick = { picker.launch(arrayOf("audio/*")) },
                    onModel = { modelId = it },
                    onRemove = {
                        models.delete(spec)
                        installedRevision++
                    },
                    onStem = { stem, on -> stems = if (on) stems + stem else stems - stem },
                    onFormat = { format = it },
                    onStart = {
                        val uri = sourceUri ?: return@SetupView
                        SeparationController.start(
                            context,
                            SeparationRequest(
                                source = uri,
                                displayName = sourceName,
                                modelId = modelId,
                                stems = spec.stems.filter { it in stems },
                                format = format,
                            ),
                        )
                    },
                    canStart = sourceUri != null && spec.stems.any { it in stems },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- setup

@Composable
internal fun SetupView(
    sourceName: String,
    track: TrackInfo,
    spec: ModelSpec,
    installed: Set<String>,
    stems: Set<Stem>,
    format: OutputFormat,
    canStart: Boolean,
    onPick: () -> Unit,
    onModel: (String) -> Unit,
    onRemove: () -> Unit,
    onStem: (Stem, Boolean) -> Unit,
    onFormat: (OutputFormat) -> Unit,
    onStart: () -> Unit,
) {
    val context = LocalContext.current

    GroupHeader("Track")
    InsetGroup {
        ListRow(onClick = onPick, role = Role.Button, trailing = { Chevron() }) {
            RowTitle(sourceName.ifEmpty { "Choose an audio file" })
            RowSubtitle(
                when {
                    sourceName.isEmpty() -> "Any format your device can play"
                    track.seconds != null -> clock(track.seconds)
                    else -> "Reading…"
                },
            )
        }
        track.peaks?.let { peaks ->
            Hairline()
            ListRow { Waveform(peaks) }
        }
    }
    SectionSpacer()

    GroupHeader("Model")
    InsetGroup {
        ModelCatalog.all.forEachIndexed { index, candidate ->
            if (index > 0) Hairline(startInset = 50.dp)
            val ready = candidate.id in installed
            ListRow(
                onClick = { onModel(candidate.id) },
                role = Role.RadioButton,
                selected = candidate.id == spec.id,
                leading = { CheckSlot(candidate.id == spec.id) },
                trailing = {
                    Badge(if (ready) "Ready" else ModelManager.format(candidate.totalBytes), highlighted = ready)
                },
            ) {
                RowTitle(candidate.displayName)
                RowSubtitle(candidate.subtitle)
            }
        }
        if (spec.id in installed) {
            Hairline()
            ListRow(onClick = onRemove, role = Role.Button) {
                RowTitle("Remove downloaded model", color = Apple.colors.red)
            }
        }
    }
    GroupFooter("${spec.quality} ${spec.speedHint}")
    DeviceCapability.warning(context, spec)?.let { GroupFooter(it, warning = true) }
    SectionSpacer()

    GroupHeader("Stems")
    InsetGroup {
        spec.stems.forEachIndexed { index, stem ->
            if (index > 0) Hairline()
            ListRow(
                trailing = {
                    AppleSwitch(
                        checked = stem in stems,
                        onCheckedChange = { onStem(stem, it) },
                        label = stem.label,
                    )
                },
            ) { RowTitle(stem.label) }
        }
    }
    GroupFooter(stemNote(spec))
    SectionSpacer()

    GroupHeader("Format")
    SegmentedControl(
        options = OutputFormat.entries,
        selected = format,
        label = { it.short },
        onSelect = onFormat,
    )
    GroupFooter(formatNote(format))
    SectionSpacer()

    FilledButton(text = "Separate", onClick = onStart, enabled = canStart)
    if (!canStart) {
        GroupFooter("Choose a track and at least one stem.", center = true)
    }
}

/**
 * Only the vocals-only models build the instrumental as `mix − vocals`, which is what
 * makes the pair sum back exactly. The four-stem model's instrumental is its own
 * drums + bass + other, which comes close to the mix but measurably not all the way.
 */
private fun stemNote(spec: ModelSpec): String =
    if (Stem.DRUMS in spec.stems) {
        "Each stem is the model's own estimate. Together they come close to the original mix, not exactly."
    } else {
        "Vocals and instrumental always add back up to the original mix, exactly."
    }

private fun formatNote(format: OutputFormat): String =
    if (format == OutputFormat.WAV_FLOAT32) {
        "Nothing clips and nothing is rescaled — the safest choice for further editing."
    } else {
        "Peak-safe gain is applied only if a stem would otherwise clip."
    }

// ---------------------------------------------------------------- running

@Composable
internal fun RunView(
    state: SeparationState,
    trackName: String,
    seconds: Double?,
    spec: ModelSpec,
    stems: List<Stem>,
    onCancel: () -> Unit,
) {
    val (title, detail, progress, eta) = when (state) {
        is SeparationState.Downloading -> RunCopy(
            "Downloading model",
            "${spec.displayName} · ${ModelManager.format(state.done)} of ${ModelManager.format(state.total)}",
            state.fraction,
            null,
        )
        is SeparationState.Decoding -> RunCopy(
            "Reading the track",
            "Decoding and resampling to 44.1 kHz",
            state.fraction,
            null,
        )
        is SeparationState.Separating -> RunCopy(
            "Separating",
            "Chunk ${state.chunk} of ${state.chunks}",
            state.fraction,
            state.secondsRemaining,
        )
        else -> RunCopy("Writing files", "Almost there", null, null)
    }

    GroupHeader("Now separating")
    InsetGroup {
        ListRow {
            RowTitle(trackName)
            RowSubtitle(
                listOfNotNull(
                    seconds?.let(::clock),
                    spec.displayName,
                    stems.joinToString(", ") { it.label },
                ).joinToString(" · "),
                maxLines = 1,
            )
        }
        Hairline()
        ListRow {
            RowTitle(title, strong = true)
            RowSubtitle(detail, maxLines = 1)
            Spacer(Modifier.height(14.dp))
            ThinProgressBar(progress)
            Spacer(Modifier.height(8.dp))
            Row {
                Text(
                    text = progress?.let { "${(it * 100).toInt()}%" } ?: "Working",
                    style = Apple.type.footnote,
                    color = Apple.colors.secondaryLabel,
                    modifier = Modifier.weight(1f),
                )
                eta?.takeIf { it > 0 }?.let {
                    Text(
                        text = "${humanEta(it)} left",
                        style = Apple.type.footnote,
                        color = Apple.colors.secondaryLabel,
                    )
                }
            }
        }
    }
    SectionSpacer()
    TintedButton(text = "Cancel", onClick = onCancel)
    GroupFooter("You can leave the app — the work keeps running.", center = true)
}

private data class RunCopy(val title: String, val detail: String, val progress: Float?, val eta: Long?)

// ---------------------------------------------------------------- done / failed

@Composable
internal fun DoneView(results: List<StemResult>, elapsedSeconds: Long, onReset: () -> Unit) {
    val context = LocalContext.current
    GroupHeader("${results.size} file${if (results.size == 1) "" else "s"}")
    InsetGroup {
        results.forEachIndexed { index, result ->
            if (index > 0) Hairline()
            ResultRow(result)
        }
    }
    GroupFooter("Finished in ${clock(elapsedSeconds.toDouble())}.")
    SectionSpacer()
    FilledButton(text = "Share all", onClick = { Sharing.share(context, results.map { it.file }) })
    Spacer(Modifier.height(10.dp))
    TintedButton(text = "Separate another track", onClick = onReset)
}

@Composable
private fun ResultRow(result: StemResult) {
    val context = LocalContext.current
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav"),
    ) { uri -> if (uri != null) Sharing.copyTo(context, result.file, uri) }

    ListRow(
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction("Save", onClick = { saver.launch(result.file.name) })
                IconButton(onClick = { Sharing.share(context, listOf<File>(result.file)) }) {
                    Icon(
                        imageVector = AppleIcons.Share,
                        contentDescription = "Share ${result.stem.label}",
                        tint = Apple.colors.tint,
                        modifier = Modifier.size(width = 16.dp, height = 20.dp),
                    )
                }
            }
        },
    ) {
        RowTitle(result.stem.label)
        // Size first: when the name is long, the ellipsis should eat the name, not the size.
        RowSubtitle("${ModelManager.format(result.sizeBytes)} · ${result.file.name}", maxLines = 1)
    }
}

@Composable
internal fun FailedView(message: String, onBack: () -> Unit) {
    GroupHeader("Separation failed")
    InsetGroup {
        ListRow(
            leading = {
                Icon(
                    imageVector = AppleIcons.Warning,
                    contentDescription = null,
                    tint = Apple.colors.red,
                    modifier = Modifier.size(18.dp),
                )
            },
        ) {
            RowTitle("This track could not be separated", strong = true)
            RowSubtitle(message, maxLines = 6)
        }
    }
    SectionSpacer()
    TintedButton(text = "Back", onClick = onBack)
}

/** Tells the user that the system, not the app, ended their last run — and what to do. */
@Composable
internal fun InterruptedNotice(run: RunJournal.Interrupted, onDismiss: () -> Unit) {
    GroupHeader("Last run did not finish")
    InsetGroup {
        ListRow(
            leading = {
                Icon(
                    imageVector = AppleIcons.Warning,
                    contentDescription = null,
                    tint = Apple.colors.orange,
                    modifier = Modifier.size(18.dp),
                )
            },
        ) {
            RowTitle("Android stopped the separation", strong = true)
            RowSubtitle(
                "${run.model} was ${run.stage} for “${run.track}” when the system ended " +
                    "the app, most likely because memory ran out. This version checks free " +
                    "memory before it starts; closing other apps first gives it the most room.",
                maxLines = 6,
            )
        }
        Hairline()
        ListRow(onClick = onDismiss, role = Role.Button) {
            RowTitle("OK", color = Apple.colors.tint)
        }
    }
}

// ---------------------------------------------------------------- formatting

private fun clock(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    val m = s / 60
    return if (m >= 60) "%dh %02dm".format(m / 60, m % 60) else "%d:%02d".format(m, s % 60)
}

private fun humanEta(seconds: Long): String = when {
    seconds < 45 -> "less than a minute"
    seconds < 3600 -> (seconds / 60.0).let { Math.round(it) }.let { "about $it minute${if (it == 1L) "" else "s"}" }
    else -> "about ${seconds / 3600}h ${(seconds % 3600) / 60}m"
}
