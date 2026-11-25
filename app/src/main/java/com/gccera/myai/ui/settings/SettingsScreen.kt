package com.gccera.myai.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gccera.myai.BuildConfig
import com.gccera.myai.R
import com.gccera.myai.data.settings.AppSettings
import com.gccera.myai.data.settings.LlmBackend
import com.gccera.myai.data.settings.ThemeMode
import com.gccera.myai.ui.common.ConfirmDialog
import com.gccera.myai.util.formatBytes
import com.mikepenz.markdown.m3.Markdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenModels: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onDataCleared: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val modelBytes by vm.modelBytes.collectAsStateWithLifecycle()
    val reportCount by vm.reportCount.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<String?>(null) }
    var editPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
    val settings = s ?: return

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("Models") },
                supportingContent = { Text("Download, choose and delete chat, code, image and tool models") },
                leadingContent = { Icon(Icons.Default.Memory, null) },
                modifier = Modifier.clickable(onClick = onOpenModels),
            )
            Section("Appearance")
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().selectable(settings.themeMode == mode, role = Role.RadioButton) { vm.update { it.copy(themeMode = mode) } }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.themeMode == mode, onClick = null)
                    Text(when (mode) { ThemeMode.SYSTEM -> "System default"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" }, Modifier.padding(start = 12.dp))
                }
            }
            SwitchRow("Dynamic colours (Android 12+)", settings.dynamicColor) { v -> vm.update { it.copy(dynamicColor = v) } }

            Section("Chat defaults (for new chats)")
            ListItem(
                headlineContent = { Text("System prompt") },
                supportingContent = { Text(settings.defaultSystemPrompt, maxLines = 2) },
                modifier = Modifier.clickable { editPrompt = true },
            )
            SliderRow("Temperature", settings.temperature, 0f..1.5f, "%.2f".format(settings.temperature)) { v -> vm.update { it.copy(temperature = v) } }
            SliderRow("Max reply length", settings.maxTokens.toFloat(), 64f..2048f, "${settings.maxTokens} tokens") { v -> vm.update { it.copy(maxTokens = v.toInt()) } }
            SliderRow("Top-K", settings.topK.toFloat(), 1f..100f, settings.topK.toString()) { v -> vm.update { it.copy(topK = v.toInt()) } }
            SwitchRow("Use GPU for chat (faster on many phones; falls back to CPU)", settings.backend == LlmBackend.GPU) { v ->
                vm.update { it.copy(backend = if (v) LlmBackend.GPU else LlmBackend.CPU) }
            }

            Section("Voice input")
            var langMenu by remember { mutableStateOf(false) }
            Box(Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = { langMenu = true }) {
                    Text("Speech language: " + (SPEECH_LANGS.firstOrNull { it.first == settings.speechLanguage }?.second ?: settings.speechLanguage))
                }
                DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                    SPEECH_LANGS.forEach { (code, name) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { vm.update { it.copy(speechLanguage = code) }; langMenu = false })
                    }
                }
            }

            Section("Model downloads")
            var token by remember(settings.hfToken) { mutableStateOf(settings.hfToken) }
            OutlinedTextField(
                value = token, onValueChange = { token = it.trim() },
                label = { Text("Hugging Face read token (optional)") },
                supportingText = { Text("Only for licence-gated models (Gemma 3, Llama). Stored on this device and sent only to huggingface.co.") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                trailingIcon = { if (token != settings.hfToken) TextButton(onClick = { vm.update { it.copy(hfToken = token) } }) { Text("Save") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            ListItem(headlineContent = { Text("Storage used by models") }, supportingContent = { Text(formatBytes(modelBytes)) })

            Section("Your data")
            ListItem(headlineContent = { Text("Delete all chats") }, modifier = Modifier.clickable { confirm = "chats" })
            ListItem(headlineContent = { Text("Delete all images") }, modifier = Modifier.clickable { confirm = "images" })
            ListItem(headlineContent = { Text("Delete all models") }, modifier = Modifier.clickable { confirm = "models" })
            ListItem(
                headlineContent = { Text("Clear all data", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text("Chats, images, models, $reportCount report(s) and settings") },
                modifier = Modifier.clickable { confirm = "all" },
            )

            Section("About")
            ListItem(headlineContent = { Text("Privacy policy") }, supportingContent = { Text("Nothing you type or create leaves this device") }, modifier = Modifier.clickable(onClick = onOpenPrivacy))
            ListItem(headlineContent = { Text("Licences") }, supportingContent = { Text("AI model and open-source licences") }, modifier = Modifier.clickable(onClick = onOpenLicenses))
            ListItem(headlineContent = { Text(stringResource(R.string.app_name)) }, supportingContent = { Text("Version ${BuildConfig.VERSION_NAME} · no analytics · no ads") })
        }
    }

    if (editPrompt) {
        var text by remember { mutableStateOf(settings.defaultSystemPrompt) }
        AlertDialog(
            onDismissRequest = { editPrompt = false },
            title = { Text("Default system prompt") },
            text = { OutlinedTextField(text, { text = it }, minLines = 4, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.update { it.copy(defaultSystemPrompt = text) }; editPrompt = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { text = AppSettings.DEFAULT_SYSTEM_PROMPT }) { Text("Reset") } },
        )
    }
    when (confirm) {
        "chats" -> ConfirmDialog("Delete all chats?", "This cannot be undone.", onDismiss = { confirm = null }) { vm.clearChats() }
        "images" -> ConfirmDialog("Delete all images?", "Images saved to your phone's gallery are not affected.", onDismiss = { confirm = null }) { vm.clearImages() }
        "models" -> ConfirmDialog("Delete all models?", "You'll need to download them again to chat or create images.", onDismiss = { confirm = null }) { vm.deleteModels() }
        "all" -> ConfirmDialog("Clear all data?", "Everything this app stored on your device will be deleted.", "Clear everything", onDismiss = { confirm = null }) {
            vm.clearAllData(onDataCleared)
        }
    }
}

/** Common Whisper languages; Whisper supports ~99 ("auto" detects them all). */
private val SPEECH_LANGS = listOf(
    "auto" to "Detect automatically", "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
    "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch", "hi" to "Hindi", "ar" to "Arabic", "zh" to "Chinese",
    "ja" to "Japanese", "ko" to "Korean", "ru" to "Russian", "tr" to "Turkish", "id" to "Indonesian", "vi" to "Vietnamese",
)

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("$label: ${if (local == value) display else "%.2f".format(local)}")
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range)
    }
}

/** Licences: every model licence bundled in assets/licenses plus open-source notices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val docs = listOf(
        "Gemma Terms of Use (Gemma 3)" to "licenses/gemma-terms.txt",
        "Llama 3.2 Community License" to "licenses/llama-3.2.txt",
        "CreativeML Open RAIL-M (Stable Diffusion 1.5)" to "licenses/creativeml-openrail-m.txt",
        "Apache License 2.0 (Qwen, Gemma 4, libraries)" to "licenses/apache-2.0.txt",
        "Open-source software" to "licenses/open-source.txt",
    )
    var open by remember { mutableStateOf<Pair<String, String>?>(null) }
    val current = open
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(current?.first ?: "Licences", maxLines = 1) },
            navigationIcon = { IconButton(onClick = { if (current != null) open = null else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        if (current == null) Column(Modifier.padding(pad)) {
            Text(
                "Each AI model is used under its own licence. Using a model in this app means you also agree to its use restrictions.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp),
            )
            docs.forEach { d -> ListItem(headlineContent = { Text(d.first) }, modifier = Modifier.clickable { open = d }) }
        } else Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(vm.readAsset(current.second), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val uri = LocalUriHandler.current
    val url = stringResource(R.string.privacy_policy_url)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Privacy policy") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { TextButton(onClick = { uri.openUri(url) }) { Text("Online") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Markdown(content = vm.readAsset("privacy_policy.md"))
        }
    }
}
