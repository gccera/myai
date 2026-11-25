package com.gccera.myai.ui.image

import android.Manifest
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gccera.myai.engine.ImageGenState
import com.gccera.myai.ui.common.EmptyState
import com.gccera.myai.ui.common.ReportDialog
import com.gccera.myai.ui.common.rememberBitmap
import com.gccera.myai.ui.common.shareImage
import com.gccera.myai.util.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageScreen(onOpenModels: () -> Unit, vm: ImageViewModel = hiltViewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val state by vm.genState.collectAsStateWithLifecycle()
    val improving by vm.improving.collectAsStateWithLifecycle()
    val model by vm.activeModel.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reportId by remember { mutableStateOf<Long?>(null) }
    var pendingSaveId by remember { mutableStateOf<Long?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingSaveId?.let { if (granted) vm.saveToGallery(it) }
        pendingSaveId = null
    }
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    val busy = state is ImageGenState.Loading || state is ImageGenState.Running
    val cfg = vm.config()

    Scaffold(topBar = { TopAppBar(title = { Text("Create image") }) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        if (model == null) {
            EmptyState(Icons.Default.Image, "No image model yet", "Download an image model to generate pictures on-device.") {
                Button(onClick = onOpenModels) { Text("Open Models") }
            }
            return@Scaffold
        }
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = form.prompt, onValueChange = { v -> vm.update { it.copy(prompt = v) } },
                label = { Text("Prompt") }, minLines = 2, maxLines = 6, enabled = !busy && !improving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { vm.improvePrompt() }, enabled = !busy && !improving && form.prompt.isNotBlank()) {
                if (improving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.AutoAwesome, null)
                Text("  Improve my prompt")
            }
            if (cfg.supportsNegativePrompt) OutlinedTextField(
                value = form.negativePrompt, onValueChange = { v -> vm.update { it.copy(negativePrompt = v) } },
                label = { Text("Negative prompt (what to avoid)") }, maxLines = 3, enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Steps: ${form.steps}  (more = better quality, slower)")
            Slider(
                value = form.steps.toFloat(), onValueChange = { v -> vm.update { it.copy(steps = v.toInt()) } },
                valueRange = 1f..cfg.maxSteps.toFloat(), enabled = !busy,
            )
            if (cfg.supportsNegativePrompt) {
                Text("Guidance: ${"%.1f".format(form.guidance)}  (how strictly to follow the prompt)")
                Slider(value = form.guidance, onValueChange = { v -> vm.update { it.copy(guidance = v) } }, valueRange = 1f..12f, enabled = !busy)
            }
            SizePicker(form.size, enabled = !busy) { s -> vm.update { it.copy(size = s) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = form.seedText, onValueChange = { v -> vm.update { it.copy(seedText = v.filter(Char::isDigit).take(12)) } },
                    label = { Text("Seed") }, singleLine = true, enabled = !busy && !form.randomSeed,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Checkbox(checked = form.randomSeed, onCheckedChange = { c -> vm.update { it.copy(randomSeed = c) } }, enabled = !busy)
                Text("Random")
            }

            when (val s = state) {
                is ImageGenState.Loading -> Progress("Loading ${s.modelName}…", null, onCancel = vm::cancel)
                is ImageGenState.Running -> {
                    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(s.startedAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
                    val label = when {
                        s.step >= s.total -> "Decoding image…"
                        s.step == 0 -> s.stage.ifEmpty { "Starting…" }
                        else -> "Step ${s.step} of ${s.total}" + (s.secPerStep?.let { " · %.1fs/step".format(it) } ?: "")
                    }
                    Progress("$label · ${formatDuration(now - s.startedAt)}", s.step.toFloat() / s.total, onCancel = vm::cancel)
                }
                else -> Button(onClick = vm::generate, enabled = !improving && form.prompt.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Generate")
                }
            }

            when (val s = state) {
                is ImageGenState.Done -> ResultCard(
                    path = s.path, info = "Generated in ${formatDuration(s.durationMs)} · seed ${form.seedText}",
                    onShare = { shareImage(context, s.path) },
                    onSave = {
                        if (vm.needsLegacyPermission()) { pendingSaveId = s.imageId; permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
                        else vm.saveToGallery(s.imageId)
                    },
                    onReport = { reportId = s.imageId },
                )
                is ImageGenState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                ImageGenState.Cancelled -> Text("Cancelled.", style = MaterialTheme.typography.bodySmall)
                else -> Unit
            }
            Text(
                "Runs on your phone's CPU with ${model?.name}. Generation can take a few minutes; " +
                    "you can leave this screen — it keeps going in the background.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    reportId?.let { id ->
        ReportDialog(onDismiss = { reportId = null }) { reason, note, email ->
            vm.report(id, reason, note)
            if (email) scope.launch {
                vm.reportEmail(id, reason, note)?.let { try { context.startActivity(it) } catch (_: ActivityNotFoundException) {} }
            }
            reportId = null
        }
    }
}

@Composable
private fun Progress(label: String, fraction: Float?, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(label, Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun ResultCard(path: String, info: String, onShare: () -> Unit, onSave: () -> Unit, onReport: () -> Unit) {
    val bmp by rememberBitmap(path)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            bmp?.let {
                Image(
                    it.asImageBitmap(), "Generated image",
                    Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
            Text(info, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            Row {
                TextButton(onClick = onShare) { Icon(Icons.Default.Share, null); Text(" Share") }
                TextButton(onClick = onSave) { Icon(Icons.Default.Download, null); Text(" Save") }
                TextButton(onClick = onReport) { Icon(Icons.Default.Flag, null); Text(" Report") }
            }
        }
    }
}

@Composable
private fun SizePicker(selected: ImageSize, enabled: Boolean, onSelect: (ImageSize) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Size: ${selected.label}", Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ImageSize.entries.forEach { s ->
                DropdownMenuItem(text = { Text(s.label) }, onClick = { onSelect(s); open = false })
            }
        }
    }
}
