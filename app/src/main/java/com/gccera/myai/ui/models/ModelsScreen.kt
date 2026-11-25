package com.gccera.myai.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gccera.myai.data.catalog.ModelLicense
import com.gccera.myai.data.catalog.ModelSpec
import com.gccera.myai.data.catalog.ModelType
import com.gccera.myai.data.models.DownloadState
import com.gccera.myai.data.models.ModelEntry
import com.gccera.myai.device.Fit
import com.gccera.myai.device.ModelRecommender
import com.gccera.myai.ui.common.ConfirmDialog
import com.gccera.myai.util.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(onBack: (() -> Unit)? = null, initialTab: Int = 0, onBrowseHub: () -> Unit = {}, vm: ModelsViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var showAdd by remember { mutableStateOf(false) }
    var licenseFor by remember { mutableStateOf<ModelLicense?>(null) }
    var deleteFor by remember { mutableStateOf<ModelSpec?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val type = TABS[tab].first
    val activeId = when (type) {
        ModelType.CHAT -> settings?.activeChatModelId
        ModelType.CODE -> settings?.activeCodeModelId
        ModelType.IMAGE -> settings?.activeImageModelId
        ModelType.TOOL -> null
    }
    val visible = entries.filter { it.spec.type == type }
    // If the user never picked one, the first installed model is the active one.
    val effectiveActive = if (type == ModelType.TOOL) null else activeId?.takeIf { id -> visible.any { it.spec.id == id && it.installed } }
        ?: visible.firstOrNull { it.installed }?.spec?.id

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = { onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } } },
                actions = { TextButton(onClick = onBrowseHub) { Text("Browse Hugging Face") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showAdd = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Custom") })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { i, (_, label) -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) }) }
            }
            if (type == ModelType.TOOL) Text(
                "Small helper models: voice input, document search, upscaling and background removal. Each feature asks for its model when needed.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(
                "Device: ${vm.device.ramClassGb} GB RAM class · ${formatBytes(vm.device.freeStorageBytes)} free. " +
                    "Models download from Hugging Face and run fully offline.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visible, key = { it.spec.id }) { entry ->
                    ModelCard(
                        entry = entry,
                        fit = ModelRecommender.fit(entry.spec, vm.device),
                        isActive = entry.spec.id == effectiveActive,
                        isTool = type == ModelType.TOOL,
                        onDownload = { vm.download(entry.spec) },
                        onPause = { vm.pause(entry.spec) },
                        onDelete = { deleteFor = entry.spec },
                        onUse = { vm.setActive(entry.spec) },
                        onLicense = { licenseFor = entry.spec.license },
                    )
                }
            }
        }
    }

    if (showAdd) AddCustomModelDialog(onDismiss = { showAdd = false }) { name, t, url, sha, lic, licUrl ->
        vm.addCustom(name, t, url, sha, lic, licUrl); showAdd = false
    }
    licenseFor?.let { lic -> LicenseDialog(lic, vm.readLicense(lic.id)) { licenseFor = null } }
    deleteFor?.let { spec ->
        ConfirmDialog(
            title = "Delete ${spec.name}?",
            message = "This frees ${formatBytes(spec.totalBytes)}. You can download it again later." +
                if (spec.custom) " The custom entry is removed too." else "",
            onDismiss = { deleteFor = null },
            onConfirm = { if (spec.custom) vm.removeCustom(spec) else vm.delete(spec) },
        )
    }
}

@Composable
private fun ModelCard(
    entry: ModelEntry,
    fit: Fit,
    isActive: Boolean,
    isTool: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    onUse: () -> Unit,
    onLicense: () -> Unit,
) {
    val spec = entry.spec
    Card(
        colors = if (isActive) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (isActive) Icon(Icons.Default.CheckCircle, "Active", tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                listOf(spec.params, if (spec.totalBytes > 0) formatBytes(spec.totalBytes) else "size unknown")
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            if (spec.description.isNotBlank()) Text(spec.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                AssistChip(onClick = onLicense, label = { Text(spec.license.name) })
                if (!spec.custom) AssistChip(onClick = {}, label = {
                    Text(when (fit) { Fit.RECOMMENDED -> "Recommended"; Fit.MAY_BE_SLOW -> "May be slow"; Fit.TOO_LARGE -> "Too large for this phone" })
                })
                if (spec.requiresHfToken) AssistChip(onClick = {}, label = { Text("HF token") }, leadingIcon = { Icon(Icons.Default.Lock, null, Modifier.size(16.dp)) })
            }
            Spacer(Modifier.padding(4.dp))
            DownloadRow(entry.state, isActive, isTool, onDownload, onPause, onDelete, onUse)
        }
    }
}

@Composable
private fun DownloadRow(
    state: DownloadState,
    isActive: Boolean,
    isTool: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    onUse: () -> Unit,
) {
    @Composable
    fun progress(done: Long, total: Long, label: String) {
        val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        Text("$label ${formatBytes(done)} / ${formatBytes(total)} (${(frac * 100).toInt()}%)", style = MaterialTheme.typography.bodySmall)
    }
    when (state) {
        DownloadState.NotDownloaded -> Button(onClick = onDownload) { Text("Download") }
        DownloadState.Queued -> {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Row { Text("Waiting for network…", Modifier.weight(1f).align(Alignment.CenterVertically)); TextButton(onClick = onPause) { Text("Cancel") } }
        }
        is DownloadState.Downloading -> {
            progress(state.done, state.total, "Downloading")
            Row { Spacer(Modifier.weight(1f)); OutlinedButton(onClick = onPause) { Text("Pause") } }
        }
        is DownloadState.Verifying -> {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
            Text("Verifying SHA-256…", style = MaterialTheme.typography.bodySmall)
        }
        is DownloadState.Paused -> {
            progress(state.done, state.total, "Paused")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDownload) { Text("Resume") }
                TextButton(onClick = onDelete) { Text("Discard") }
            }
        }
        is DownloadState.Failed -> {
            Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = onDownload) { Text("Retry") }
                if (state.done > 0) TextButton(onClick = onDelete) { Text("Discard") }
            }
        }
        DownloadState.Installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isTool) FilledTonalButton(onClick = {}, enabled = false) { Text("Installed") }
            else if (isActive) FilledTonalButton(onClick = {}, enabled = false) { Text("Active") }
            else Button(onClick = onUse) { Text("Use") }
            OutlinedButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

private val TABS = listOf(ModelType.CHAT to "Chat", ModelType.CODE to "Code", ModelType.IMAGE to "Image", ModelType.TOOL to "Tools")

@Composable
fun LicenseDialog(license: ModelLicense, text: String?, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(license.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (license.commercialUse) "Commercial use: allowed (subject to the licence terms)." else "Commercial use: NOT allowed.",
                    style = MaterialTheme.typography.labelLarge)
                license.attribution?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                if (text != null) SelectionContainer {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
                } else Text("Full text: ${license.url}", modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { if (license.url.isNotBlank()) TextButton(onClick = { uri.openUri(license.url) }) { Text("Open online") } },
    )
}

@Composable
private fun AddCustomModelDialog(
    onDismiss: () -> Unit,
    onAdd: (String, ModelType, String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ModelType.CHAT) }
    val image = kind == ModelType.IMAGE
    var url by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    var lic by remember { mutableStateOf("") }
    var licUrl by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom model") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ModelType.CHAT to "Chat", ModelType.CODE to "Code", ModelType.IMAGE to "Image").forEach { (t, l) ->
                        if (kind == t) Button(onClick = {}) { Text(l) } else OutlinedButton(onClick = { kind = t }) { Text(l) }
                    }
                }
                Text(
                    if (image) "Hugging Face base URL of a diffusers ONNX export (standard ONNX ops only), e.g. https://huggingface.co/org/repo/resolve/<commit>"
                    else "Direct https URL of a LiteRT-LM .litertlm file.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(url, { url = it.trim() }, label = { Text("URL") })
                if (!image) OutlinedTextField(sha, { sha = it.trim() }, label = { Text("SHA-256 (recommended)") }, singleLine = true)
                OutlinedTextField(lic, { lic = it }, label = { Text("Licence name") }, singleLine = true)
                OutlinedTextField(licUrl, { licUrl = it.trim() }, label = { Text("Licence URL") }, singleLine = true)
                Text("Only add models whose licence allows your use.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && url.isNotBlank(), onClick = {
                onAdd(name.trim(), kind, url, sha, lic, licUrl)
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

