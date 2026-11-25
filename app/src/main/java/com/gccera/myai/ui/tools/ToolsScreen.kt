package com.gccera.myai.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gccera.myai.engine.ImageTool
import com.gccera.myai.engine.ImageToolState
import com.gccera.myai.ui.common.rememberBitmap
import com.gccera.myai.ui.common.shareImage
import com.gccera.myai.ui.common.shareText

/** Phase 2 utilities: OCR, translation, image upscaling/background removal, and document library. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onOpenGallery: () -> Unit, onOpenImage: (Long) -> Unit, vm: ToolsViewModel = hiltViewModel()) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Tools") }, actions = {
                IconButton(onClick = onOpenGallery) { Icon(Icons.Default.PhotoLibrary, "Gallery") }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val consent by vm.mlKitConsent.collectAsStateWithLifecycle()
            if (consent) {
                OcrCard(vm)
                TranslateCard(vm)
            } else MlKitConsentCard(onAccept = vm::giveMlKitConsent)
            ImageToolsCard(vm, onOpenImage)
            DocumentsCard(vm)
            if (consent) TextButton(onClick = vm::revokeMlKitConsent) { Text("Turn off Google ML Kit features") }
        }
    }
}

/** Shown before ML Kit is ever started: Google's SDK reports usage metrics (not content). */
@Composable
private fun MlKitConsentCard(onAccept: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(Icons.Default.Translate, "Text from photos & translation")
            Text(
                "These two features use Google ML Kit. Your photos and text are processed on this phone and are not sent anywhere, " +
                    "but Google's ML Kit SDK sends Google anonymous usage and performance metrics (device model, OS version, " +
                    "app info). Translation language packs (~30 MB each) are downloaded once from Google. " +
                    "Everything else in this app works without ML Kit.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onAccept) { Text("Enable ML Kit features") }
        }
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun OcrCard(vm: ToolsViewModel) {
    val text by vm.ocr.collectAsStateWithLifecycle()
    val busy by vm.ocrBusy.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(vm::extractText) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(Icons.Default.DocumentScanner, "Extract text from a photo")
            Button(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) {
                Text(if (busy) "Reading…" else "Pick photo")
            }
            text?.let { t ->
                SelectionContainer { Text(t, style = MaterialTheme.typography.bodyMedium) }
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(t)) }) { Icon(Icons.Default.ContentCopy, null); Text(" Copy") }
                    TextButton(onClick = { shareText(context, t) }) { Icon(Icons.Default.Share, null); Text(" Share") }
                    TextButton(onClick = { vm.setTranslateInput(t) }) { Icon(Icons.Default.Translate, null); Text(" Translate") }
                }
            }
        }
    }
}

@Composable
private fun TranslateCard(vm: ToolsViewModel) {
    val s by vm.translate.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(Icons.Default.Translate, "Translate (offline)")
            Row(verticalAlignment = Alignment.CenterVertically) {
                LanguagePicker(
                    label = s.source?.let { c -> vm.languages.firstOrNull { it.first == c }?.second } ?: ("Detect" + (s.detected?.let { " ($it)" } ?: "")),
                    options = listOf<Pair<String?, String>>(null to "Detect language") + vm.languages,
                    downloaded = s.downloaded, onPick = vm::setSource, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::swap) { Icon(Icons.AutoMirrored.Filled.CompareArrows, "Swap languages") }
                LanguagePicker(
                    label = vm.languages.firstOrNull { it.first == s.target }?.second ?: s.target,
                    options = vm.languages, downloaded = s.downloaded, onPick = { it?.let(vm::setTarget) }, modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(s.input, vm::setTranslateInput, label = { Text("Text") }, minLines = 2, maxLines = 8, modifier = Modifier.fillMaxWidth())
            Button(onClick = vm::runTranslate, enabled = !s.busy && s.input.isNotBlank()) { Text(if (s.busy) "Translating…" else "Translate") }
            if (s.output.isNotEmpty()) {
                SelectionContainer { Text(s.output) }
                TextButton(onClick = { clipboard.setText(AnnotatedString(s.output)) }) { Icon(Icons.Default.ContentCopy, null); Text(" Copy") }
            }
            if (s.downloaded.isNotEmpty()) {
                Text("Downloaded languages (tap to delete):", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.downloaded.sorted().forEach { code ->
                        TextButton(onClick = { vm.deleteLanguage(code) }) { Text(vm.languages.firstOrNull { it.first == code }?.second ?: code) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguagePicker(
    label: String,
    options: List<Pair<String?, String>>,
    downloaded: Set<String>,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name + if (code != null && (code in downloaded || code == "en")) "  ✓" else "") },
                    onClick = { onPick(code); open = false },
                )
            }
        }
    }
}

@Composable
private fun ImageToolsCard(vm: ToolsViewModel, onOpenImage: (Long) -> Unit) {
    val state by vm.imageState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tool by remember { mutableStateOf(ImageTool.UPSCALE_2X) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? -> uri?.let { vm.runImageTool(it, tool) } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(Icons.Default.Wallpaper, "Image tools")
            Text("Upscale a photo or remove its background. Results are saved to the Gallery. You can also run these from any Gallery image.",
                style = MaterialTheme.typography.bodySmall)
            val running = state is ImageToolState.Running
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImageTool.entries.forEach { t ->
                    OutlinedButton(onClick = { tool = t; pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !running) {
                        Text(t.label)
                    }
                }
            }
            when (val s = state) {
                is ImageToolState.Running -> {
                    if (s.total > 1) LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${s.tool.label}… ${if (s.total > 1) "${s.done}/${s.total} tiles" else ""}", Modifier.weight(1f))
                        TextButton(onClick = vm::cancelImageTool) { Text("Cancel") }
                    }
                }
                is ImageToolState.Done -> {
                    val bmp by rememberBitmap(s.path, 1024)
                    bmp?.let {
                        Image(it.asImageBitmap(), "Result", Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height).clip(RoundedCornerShape(12.dp)))
                        Text("${it.width} × ${it.height}", style = MaterialTheme.typography.labelSmall)
                    }
                    Row {
                        TextButton(onClick = { shareImage(context, s.path) }) { Icon(Icons.Default.Share, null); Text(" Share") }
                        TextButton(onClick = { onOpenImage(s.imageId) }) { Text("Open in Gallery") }
                        TextButton(onClick = vm::resetImageTool) { Text("Done") }
                    }
                }
                is ImageToolState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                ImageToolState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun DocumentsCard(vm: ToolsViewModel) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val busy by vm.docBusy.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importDocument) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(Icons.Default.Description, "Documents for chat")
            Text("Import PDFs or text files, then attach one in a chat to ask questions about it.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { pick.launch(arrayOf("application/pdf", "text/plain", "text/markdown", "text/*")) }, enabled = busy == null) { Text("Import PDF / TXT") }
                busy?.let { CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp); Text(it, Modifier.padding(start = 8.dp)) }
            }
            docs.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${d.chunkCount} passages · ${d.charCount / 1000}k characters", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { vm.deleteDocument(d.id) }) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
        }
    }
}
