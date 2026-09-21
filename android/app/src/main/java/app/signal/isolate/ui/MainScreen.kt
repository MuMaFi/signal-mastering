package app.signal.isolate.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.signal.isolate.audio.OutputFormat
import app.signal.isolate.model.DeviceCapability
import app.signal.isolate.model.ModelCatalog
import app.signal.isolate.model.ModelManager
import app.signal.isolate.model.ModelSpec
import app.signal.isolate.model.Stem
import app.signal.isolate.work.SeparationController
import app.signal.isolate.work.SeparationRequest
import app.signal.isolate.work.SeparationService
import app.signal.isolate.work.SeparationState
import app.signal.isolate.work.isRunning
import kotlinx.coroutines.launch

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
    var installedRevision by remember { mutableStateOf(0) }

    val spec = ModelCatalog.byId(modelId)
    val running = state.isRunning

    LaunchedEffect(initialAudio) {
        if (initialAudio != null && sourceUri == null) {
            sourceUri = initialAudio
            sourceName = Sharing.displayName(context, initialAudio)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Header()

        Section("1 · Track") {
            Text(
                text = sourceName.ifEmpty { "No file selected" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (sourceName.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { picker.launch(arrayOf("audio/*")) },
                enabled = !running,
            ) { Text(if (sourceUri == null) "Choose audio file" else "Choose another file") }
        }

        Section("2 · Model") {
            ModelCatalog.all.forEach { candidate ->
                ModelRow(
                    spec = candidate,
                    selected = candidate.id == modelId,
                    installed = remember(candidate.id, installedRevision) {
                        models.isInstalled(candidate)
                    },
                    enabled = !running,
                    onSelect = { modelId = candidate.id },
                    onDelete = {
                        models.delete(candidate)
                        installedRevision++
                    },
                    warning = DeviceCapability.warning(context, candidate),
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        Section("3 · Output") {
            Text("Stems", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                spec.stems.take(3).forEach { stem ->
                    StemChip(stem, stems, running) { stems = toggle(stems, stem) }
                }
            }
            if (spec.stems.size > 3) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    spec.stems.drop(3).forEach { stem ->
                        StemChip(stem, stems, running) { stems = toggle(stems, stem) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("File format", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            OutputFormat.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = option == format,
                            enabled = !running,
                            onClick = { format = option },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == format, onClick = null, enabled = !running)
                    Spacer(Modifier.width(10.dp))
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        ActionPanel(
            state = state,
            enabled = sourceUri != null && stems.isNotEmpty(),
            spec = spec,
            onStart = {
                val uri = sourceUri ?: return@ActionPanel
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
            onCancel = {
                scope.launch {
                    SeparationController.cancel()
                    SeparationService.stop(context)
                }
            },
            onReset = { SeparationController.reset() },
        )

        Footer(spec)
        Spacer(Modifier.height(24.dp))
    }
}

private fun toggle(current: Set<Stem>, stem: Stem): Set<Stem> =
    if (stem in current) {
        (current - stem).ifEmpty { current }
    } else {
        current + stem
    }

@Composable
private fun StemChip(stem: Stem, selected: Set<Stem>, running: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = stem in selected,
        onClick = onClick,
        enabled = !running,
        label = { Text(stem.label) },
    )
}

@Composable
private fun Header() {
    Column {
        Text(
            text = "signal.isolate",
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "On-device stem separation. Nothing leaves the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ModelRow(
    spec: ModelSpec,
    selected: Boolean,
    installed: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    warning: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(spec.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    spec.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(spec.quality, style = MaterialTheme.typography.bodySmall)
        Text(
            "${ModelManager.format(spec.totalBytes)} download · ${spec.speedHint} · " +
                "${spec.minRamGb} GB RAM recommended",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (warning != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (installed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Downloaded",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDelete, enabled = enabled) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun ActionPanel(
    state: SeparationState,
    enabled: Boolean,
    spec: ModelSpec,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    Section("4 · Run") {
        when (state) {
            is SeparationState.Idle, is SeparationState.Cancelled -> {
                Button(onClick = onStart, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Separate")
                }
                if (!enabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pick a file and at least one stem.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is SeparationState.Downloading -> Progress(
                label = "Downloading ${spec.displayName} · " +
                    "${ModelManager.format(state.done)} / ${ModelManager.format(state.total)}",
                fraction = state.fraction,
                onCancel = onCancel,
            )

            is SeparationState.Decoding -> Progress(
                label = "Decoding audio",
                fraction = state.fraction,
                onCancel = onCancel,
            )

            is SeparationState.Separating -> Progress(
                label = buildString {
                    append("Separating · chunk ${state.chunk} of ${state.chunks}")
                    state.secondsRemaining?.let { append(" · ~${SeparationService.formatEta(it)} left") }
                },
                fraction = state.fraction,
                onCancel = onCancel,
            )

            SeparationState.Finalizing -> Progress("Writing files", null, onCancel)

            is SeparationState.Done -> {
                Text(
                    "Done in ${SeparationService.formatEta(state.elapsedSeconds)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                state.results.forEach { result ->
                    ResultRow(result.stem.label, result.file, result.sizeBytes)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Sharing.share(context, state.results.map { it.file }) }) {
                        Text("Share all")
                    }
                    OutlinedButton(onClick = onReset) { Text("New track") }
                }
            }

            is SeparationState.Failed -> {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onReset) { Text("Back") }
            }
        }
    }
}

@Composable
private fun ResultRow(label: String, file: java.io.File, size: Long) {
    val context = LocalContext.current
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav"),
    ) { uri -> if (uri != null) Sharing.copyTo(context, file, uri) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                ModelManager.format(size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { saver.launch(file.name) }) { Text("Save") }
        TextButton(onClick = { Sharing.share(context, listOf(file)) }) { Text("Share") }
    }
}

@Composable
private fun Progress(label: String, fraction: Float?, onCancel: () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth()) {
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun Footer(spec: ModelSpec) {
    Text(
        text = "${spec.displayName} · ${spec.license} · weights fetched once from " +
            "Hugging Face and checked against a pinned SHA-256. Separation itself is " +
            "fully offline.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
