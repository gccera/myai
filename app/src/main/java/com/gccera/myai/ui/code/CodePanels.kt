package com.gccera.myai.ui.code

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewAssetLoader
import com.gccera.myai.data.code.CodeLanguages
import com.gccera.myai.engine.code.CodeAction
import com.github.difflib.text.DiffRow
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream

// ======================= AI assistant =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSheet(vm: CodeViewModel, ref: EditorRef, onOpenModels: () -> Unit, onDismiss: () -> Unit) {
    val state by vm.assistantState.collectAsStateWithLifecycle()
    val codeModel by vm.codeModel.collectAsStateWithLifecycle()
    val cloudAvailable by vm.cloudAvailable.collectAsStateWithLifecycle()
    val cloudHost by vm.cloudHost.collectAsStateWithLifecycle()
    var useCloud by remember { mutableStateOf(false) }
    var extra by remember { mutableStateOf("") }
    var sideBySide by remember { mutableStateOf(true) }
    val selection = remember { ref.selection() }
    val tab = vm.activeTab()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI assistant", style = MaterialTheme.typography.titleLarge)
            Text(
                (if (selection != null) "Scope: selected code (${selection.first.lines().size} lines)" else "Scope: whole file ${tab?.name ?: ""}") +
                    " · " + (if (useCloud) "cloud model" else codeModel?.name ?: "no coding model"),
                style = MaterialTheme.typography.bodySmall,
            )
            if (codeModel == null && !useCloud) TextButton(onClick = onOpenModels) { Text("Download a coding model") }
            if (cloudAvailable) Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(useCloud, { useCloud = it })
                Text(" Use cloud model", Modifier.padding(start = 4.dp))
            }
            if (useCloud) Text(
                "⚠ Your code and question will be sent to $cloudHost.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge,
            )
            OutlinedTextField(extra, { extra = it }, label = { Text("Question, error message or focus (optional)") }, minLines = 1, maxLines = 4, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CodeAction.entries.forEach { a ->
                    AssistChip(
                        enabled = state !is AssistantState.Running && (a != CodeAction.ASK || extra.isNotBlank()),
                        onClick = {
                            val (code, range) = selection ?: ((tab?.content?.toString() ?: "") to null)
                            vm.runAssistant(a, code, range, extra, useCloud)
                        },
                        label = { Text(a.label) },
                    )
                }
            }
            when (val s = state) {
                is AssistantState.Running -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${s.action.label}…", Modifier.weight(1f))
                        TextButton(onClick = vm::stopAssistant) { Text("Stop") }
                    }
                    if (s.text.isNotBlank()) SelectionContainer { Markdown(content = s.text) }
                }
                is AssistantState.Done -> {
                    if (s.diff != null && s.proposal != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Suggested change", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            FilterChip(sideBySide, { sideBySide = !sideBySide }, label = { Text(if (sideBySide) "Side by side" else "Unified") })
                        }
                        DiffView(s.diff, sideBySide)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { if (vm.acceptEdit()) onDismiss() }) { Text("Accept") }
                            OutlinedButton(onClick = vm::dismissAssistant) { Text("Reject") }
                        }
                        val notes = s.text.replace(Regex("(?s)```.*?```"), "").trim()
                        if (notes.isNotBlank()) Markdown(content = notes)
                    } else {
                        SelectionContainer { Markdown(content = s.text) }
                        if (s.action == CodeAction.TESTS) com.gccera.myai.engine.code.CodeAssistant.extractCode(s.text)?.let { code ->
                            Button(onClick = { vm.saveTestsAsFile(code); onDismiss() }) { Text("Save tests as a new file") }
                        }
                        if (s.action.editsCode) Text("The model didn't return a code block to apply.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                is AssistantState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                AssistantState.Idle -> Text("Tip: select code in the editor first to ask about just that part.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Line diff from java-diff-utils, shown side by side (old | new) or unified. */
@Composable
fun DiffView(rows: List<DiffRow>, sideBySide: Boolean) {
    val removed = Color(0x33E53935); val added = Color(0x3343A047); val changedOld = Color(0x33FB8C00); val changedNew = Color(0x331E88E5)
    val mono = Modifier.padding(horizontal = 4.dp)
    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surfaceVariant)) {
        rows.forEach { r ->
            if (sideBySide) {
                Row(Modifier.fillMaxWidth()) {
                    val (ob, nb) = when (r.tag) {
                        DiffRow.Tag.DELETE -> removed to Color.Transparent
                        DiffRow.Tag.INSERT -> Color.Transparent to added
                        DiffRow.Tag.CHANGE -> changedOld to changedNew
                        else -> Color.Transparent to Color.Transparent
                    }
                    Text(r.oldLine, Modifier.weight(1f).background(ob).then(mono), fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(r.newLine, Modifier.weight(1f).background(nb).then(mono), fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            } else {
                when (r.tag) {
                    DiffRow.Tag.EQUAL -> Text("  " + r.oldLine, mono, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    DiffRow.Tag.DELETE -> Text("- " + r.oldLine, Modifier.fillMaxWidth().background(removed).then(mono), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    DiffRow.Tag.INSERT -> Text("+ " + r.newLine, Modifier.fillMaxWidth().background(added).then(mono), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    DiffRow.Tag.CHANGE -> {
                        Text("- " + r.oldLine, Modifier.fillMaxWidth().background(removed).then(mono), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text("+ " + r.newLine, Modifier.fillMaxWidth().background(added).then(mono), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                    null -> Unit
                }
            }
        }
    }
}

// ======================= Run console =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleSheet(vm: CodeViewModel, onDismiss: () -> Unit) {
    val lines by vm.console.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    var stdin by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Console", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (running) TextButton(onClick = vm::stopRun) { Text("Stop") }
                else {
                    TextButton(onClick = { vm.run(stdin) }) { Text("Run again") }
                    TextButton(onClick = vm::clearConsole) { Text("Clear") }
                }
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(stdin, { stdin = it }, label = { Text("Input for input() / stdin (optional)") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp).background(Color(0xFF111418)).padding(8.dp)) {
                    items(lines) { l ->
                        Text(l.text, color = if (l.isErr) Color(0xFFFF8A80) else Color(0xFFE6E6E6), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
            Text("Python 3.13 runs on your phone (standard library only). Web files open in a live preview.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

// ======================= Git =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitSheet(vm: CodeViewModel, onDismiss: () -> Unit) {
    val status by vm.gitStatus.collectAsStateWithLifecycle()
    val busy by vm.gitBusy.collectAsStateWithLifecycle()
    val isLocal = vm.fs?.isLocal == true
    var message by remember { mutableStateOf("") }
    var branchMenu by remember { mutableStateOf(false) }
    var newBranch by remember { mutableStateOf("") }
    var remote by remember(status) { mutableStateOf(status?.remote ?: "") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Git", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = vm::refreshGit) { Icon(Icons.Default.Refresh, "Refresh") }
            }
            busy?.let { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it, style = MaterialTheme.typography.bodySmall) }
            when {
                !isLocal -> Text("Git needs a project in app storage (Code → New project or Clone). Folders opened from your phone's storage can't be used with Git.")
                !vm.isGitRepo() -> Button(onClick = vm::gitInit, enabled = busy == null) { Text("Initialize repository") }
                status == null -> Text("Loading…")
                else -> {
                    val st = status!!
                    Box {
                        OutlinedButton(onClick = { branchMenu = true }) { Text("Branch: ${st.branch} ▾") }
                        DropdownMenu(expanded = branchMenu, onDismissRequest = { branchMenu = false }) {
                            st.branches.filter { it != "origin/HEAD" }.forEach { b ->
                                DropdownMenuItem(text = { Text(b) }, onClick = { branchMenu = false; vm.gitCheckout(b) })
                            }
                        }
                    }
                    st.lastCommit?.let { Text("Last commit: $it", style = MaterialTheme.typography.bodySmall) }
                    Text(if (st.clean) "No changes" else "Changes:", style = MaterialTheme.typography.titleSmall)
                    (st.changed.map { "M $it" } + st.untracked.map { "? $it" }).take(50).forEach {
                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    OutlinedTextField(message, { message = it }, label = { Text("Commit message") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.gitCommit(message); message = "" }, enabled = busy == null && message.isNotBlank() && !st.clean) { Text("Commit all") }
                        OutlinedButton(onClick = vm::gitPull, enabled = busy == null && st.remote != null) { Text("Pull") }
                        OutlinedButton(onClick = vm::gitPush, enabled = busy == null && st.remote != null) { Text("Push") }
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(newBranch, { newBranch = it.trim() }, label = { Text("New branch") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.gitNewBranch(newBranch); newBranch = "" }, enabled = newBranch.isNotBlank() && busy == null) { Text("Create") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(remote, { remote = it.trim() }, label = { Text("Remote (origin) URL") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.gitSetRemote(remote) }, enabled = remote.startsWith("https://") && remote != st.remote) { Text("Save") }
                    }
                    Text("Pull/push use the internet and your GitHub token (Settings → Git).", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ======================= Project search =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(vm: CodeViewModel, onOpen: (SearchHit) -> Unit, onDismiss: () -> Unit) {
    val hits by vm.search.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Search in project", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Text to find") }, modifier = Modifier.weight(1f))
                Button(onClick = { vm.searchProject(q, caseSensitive) }, enabled = q.isNotBlank() && !searching, modifier = Modifier.padding(start = 8.dp)) { Text("Find") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(caseSensitive, { caseSensitive = it }); Text("Match case") }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (hits.isNotEmpty()) Text("${hits.size} matches", style = MaterialTheme.typography.labelMedium)
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(hits) { h ->
                    Column(Modifier.fillMaxWidth().clickable { onOpen(h) }.padding(vertical = 6.dp)) {
                        Text("${h.path}:${h.line + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(h.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ======================= Web preview =======================
/**
 * Live preview of HTML/CSS/JS served from the project through WebViewAssetLoader
 * (https://appassets.androidplatform.net/project/…). Network loads are blocked unless the user allows
 * them, so pages run offline by default. console.log output goes to the Console.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewDialog(vm: CodeViewModel, entry: String, onClose: () -> Unit) {
    var allowNetwork by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    val fs = vm.fs ?: return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            TopAppBar(
                title = { Text("Preview · ${entry.substringAfterLast('/')}", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close preview") } },
                actions = {
                    Text("Internet", style = MaterialTheme.typography.labelSmall)
                    Switch(allowNetwork, { allowNetwork = it; reloadKey++ }, modifier = Modifier.padding(horizontal = 4.dp))
                    IconButton(onClick = { reloadKey++ }) { Icon(Icons.Default.Refresh, "Reload") }
                },
            )
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val loader = WebViewAssetLoader.Builder()
                        .addPathHandler("/project/", WebViewAssetLoader.PathHandler { path ->
                            // Called on a WebView background thread, so blocking reads are fine here.
                            val bytes = runBlocking { fs.readBytes(path.ifEmpty { "index.html" }) } ?: return@PathHandler null
                            WebResourceResponse(mimeOf(path), "utf-8", ByteArrayInputStream(bytes))
                        })
                        .build()
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                loader.shouldInterceptRequest(request.url)

                            // A crashing/killed preview renderer must not take the whole app down.
                            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                                vm.appendConsole("[preview] The page's renderer stopped (crash or out of memory).", true)
                                (view.parent as? android.view.ViewGroup)?.removeView(view)
                                view.destroy()
                                onClose()
                                return true
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                vm.appendConsole("[js] ${m.message()}  (${m.sourceId().substringAfterLast('/')}:${m.lineNumber()})", m.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                                return true
                            }
                        }
                    }
                },
                update = { wv ->
                    wv.settings.blockNetworkLoads = !allowNetwork
                    val url = "https://appassets.androidplatform.net/project/$entry"
                    if (wv.tag != reloadKey) { wv.tag = reloadKey; vm.clearConsole(); wv.clearCache(true); wv.loadUrl(url) }
                },
            )
        }
    }
}

private fun mimeOf(path: String) = when (path.substringAfterLast('.').lowercase()) {
    "html", "htm" -> "text/html"; "css" -> "text/css"; "js", "mjs" -> "text/javascript"; "json" -> "application/json"
    "svg" -> "image/svg+xml"; "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
    "wasm" -> "application/wasm"; "txt", "md" -> "text/plain"; else -> "application/octet-stream"
}

