package com.gccera.myai.ui.code

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gccera.myai.data.code.CodeLanguages
import com.gccera.myai.data.code.FsEntry
import com.gccera.myai.data.code.ProjectRef
import com.gccera.myai.data.code.ProjectTemplate
import com.gccera.myai.data.settings.ThemeMode
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.launch

@Composable
fun CodeScreen(onOpenModels: () -> Unit, vm: CodeViewModel = hiltViewModel()) {
    val project by vm.project.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
    if (project == null) ProjectsPane(vm, snackbar, onOpenModels) else Workspace(vm, snackbar, onOpenModels)
}

// ======================= Project manager =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectsPane(vm: CodeViewModel, snackbar: SnackbarHostState, onOpenModels: () -> Unit) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val codeModel by vm.codeModel.collectAsStateWithLifecycle()
    val gitBusy by vm.gitBusy.collectAsStateWithLifecycle()
    var newDialog by remember { mutableStateOf(false) }
    var cloneDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<ProjectRef?>(null) }
    val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::openFolder) }
    Scaffold(topBar = { TopAppBar(title = { Text("Code") }) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("AI coding model", style = MaterialTheme.typography.titleSmall)
                        Text(codeModel?.name ?: "None downloaded: the assistant and autocomplete need one.", style = MaterialTheme.typography.bodySmall)
                        if (codeModel == null) Button(onClick = onOpenModels) { Text("Get Qwen2.5-Coder") }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Button(onClick = { newDialog = true }) { Icon(Icons.Default.NoteAdd, null); Text(" New project") }
                    OutlinedButton(onClick = { openTree.launch(null) }) { Icon(Icons.Default.FolderOpen, null); Text(" Open folder") }
                    OutlinedButton(onClick = { cloneDialog = true }) { Text("Clone (Git)") }
                }
            }
            gitBusy?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
            if (projects.isEmpty()) item { Text("No projects yet. Create one, open a folder on your phone, or clone a Git repository.", style = MaterialTheme.typography.bodyMedium) }
            items(projects, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable { vm.open(p) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (p.kind == "local") Icons.Default.Folder else Icons.Default.FolderOpen, null)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(if (p.kind == "local") "App storage · Git + Python run supported" else "Phone folder", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { removeTarget = p }) { Icon(Icons.Default.Delete, "Remove") }
                    }
                }
            }
        }
    }
    if (newDialog) {
        var name by remember { mutableStateOf("") }
        var template by remember { mutableStateOf(ProjectTemplate.WEB) }
        AlertDialog(
            onDismissRequest = { newDialog = false },
            title = { Text("New project") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    ProjectTemplate.entries.forEach { t ->
                        Row(Modifier.fillMaxWidth().clickable { template = t }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(template == t, onClick = { template = t }); Text(t.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.createProject(name, template); newDialog = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newDialog = false }) { Text("Cancel") } },
        )
    }
    if (cloneDialog) {
        var url by remember { mutableStateOf("https://github.com/") }
        AlertDialog(
            onDismissRequest = { cloneDialog = false },
            title = { Text("Clone repository") },
            text = {
                Column {
                    OutlinedTextField(url, { url = it.trim() }, label = { Text("https:// URL") }, singleLine = true)
                    Text("Private repositories need a GitHub token in Settings → Git. Uses the internet only for this clone.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(enabled = url.startsWith("https://") && url.length > 20, onClick = { vm.gitClone(url); cloneDialog = false }) { Text("Clone") } },
            dismissButton = { TextButton(onClick = { cloneDialog = false }) { Text("Cancel") } },
        )
    }
    removeTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove ${p.name}?") },
            text = { Text(if (p.kind == "local") "The project and all its files will be deleted from app storage." else "It will be removed from this list. The folder on your phone is not touched.") },
            confirmButton = { TextButton(onClick = { vm.removeProject(p); removeTarget = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } },
        )
    }
}

// ======================= Workspace =======================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Workspace(vm: CodeViewModel, snackbar: SnackbarHostState, onOpenModels: () -> Unit) {
    val project by vm.project.collectAsStateWithLifecycle()
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") val tick by vm.dirtyTick.collectAsStateWithLifecycle()
    val settings by vm.appSettings.collectAsStateWithLifecycle()
    val suggestion by vm.suggestion.collectAsStateWithLifecycle()
    val saver by vm.saver.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val ref = remember { EditorRef() }
    var findOpen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) } // "ai" | "run" | "git" | "search"
    val tab = tabs.getOrNull(active)

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(Modifier.width(300.dp)) {
                FileTree(vm, onOpen = { p -> vm.openFile(p); scope.launch { drawer.close() } })
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Files") } },
                    title = {
                        Column {
                            Text(project?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(tab?.let { "${CodeLanguages.name(it.path)} · ${it.path}" } ?: "No file open", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.run(); if (tab != null && CodeLanguages.isPython(tab.path)) sheet = "run" }, enabled = tab != null) { Icon(Icons.Default.PlayArrow, "Run") }
                        IconButton(onClick = { sheet = "ai"; vm.refreshCloud() }, enabled = tab != null) { Icon(Icons.Default.AutoAwesome, "AI assistant") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Save") }, leadingIcon = { Icon(Icons.Default.Save, null) }, onClick = { menu = false; vm.saveActive() })
                                DropdownMenuItem(text = { Text("Find & replace") }, leadingIcon = { Icon(Icons.Default.FindReplace, null) }, onClick = { menu = false; findOpen = true })
                                DropdownMenuItem(text = { Text("Search in project") }, leadingIcon = { Icon(Icons.Default.Search, null) }, onClick = { menu = false; sheet = "search" })
                                DropdownMenuItem(text = { Text("Console") }, onClick = { menu = false; sheet = "run" })
                                DropdownMenuItem(text = { Text("Git") }, onClick = { menu = false; sheet = "git"; vm.refreshGit() })
                                DropdownMenuItem(
                                    text = { Text((if (settings?.autocomplete == true) "✓ " else "") + "AI autocomplete") },
                                    onClick = { menu = false; vm.setAutocomplete(settings?.autocomplete != true) },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Close project") }, onClick = { menu = false; vm.closeProject() })
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
                TabsRow(tabs, active, vm::selectTab, vm::closeTab)
                if (findOpen) FindBar(ref) { findOpen = false; ref.editor?.searcher?.stopSearch() }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    EditorHost(
                        tab = tab,
                        themeMode = settings?.themeMode ?: ThemeMode.SYSTEM,
                        ref = ref,
                        onEdited = { idx -> vm.onEdited(); vm.onTyping(idx) },
                        onCursorMoved = {
                            val s = vm.suggestion.value
                            if (s != null && s.first != ref.cursorIndex()) vm.clearSuggestion()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (tab == null) Text("Open a file from the ☰ menu", Modifier.align(Alignment.Center))
                }
                // Inline AI suggestion (tap to insert) + battery-saver notice.
                when {
                    suggestion != null -> Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().clickable {
                        val (at, text) = suggestion!!
                        ref.editor?.let { e -> if (e.cursor.left == at) e.commitText(text) }
                        vm.clearSuggestion()
                    }) {
                        Text("⇥ " + suggestion!!.second.replace("\n", " ⏎ "), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                    saver.paused && settings?.autocomplete == true -> Text("Autocomplete paused: ${saver.reason}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp))
                }
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { ref.editor?.undo() }) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = { ref.editor?.redo() }) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    SymbolRow(ref, MaterialTheme.colorScheme.onSurfaceVariant.toArgb(), Modifier.weight(1f).height(44.dp))
                }
            }
        }
    }

    when (sheet) {
        "ai" -> AssistantSheet(vm, ref, onOpenModels) { sheet = null }
        "run" -> ConsoleSheet(vm) { sheet = null }
        "git" -> GitSheet(vm) { sheet = null }
        "search" -> SearchSheet(vm, onOpen = { hit -> vm.openFile(hit.path); sheet = null; scope.launch { kotlinx.coroutines.delay(300); ref.editor?.jumpToLine(hit.line) } }) { sheet = null }
    }
    preview?.let { entry -> PreviewDialog(vm, entry) { vm.closePreview() } }
}

@Composable
private fun TabsRow(tabs: List<OpenTab>, active: Int, onSelect: (Int) -> Unit, onClose: (Int) -> Unit) {
    if (tabs.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surfaceContainer)) {
        tabs.forEachIndexed { i, t ->
            val selected = i == active
            Row(
                Modifier.clickable { onSelect(i) }
                    .background(if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer)
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text((if (t.dirty) "● " else "") + t.name, style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { onClose(i) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Close ${t.name}", Modifier.size(16.dp)) }
            }
        }
    }
}

@Composable
private fun FindBar(ref: EditorRef, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    var count by remember { mutableStateOf("") }
    fun search() {
        val s = ref.editor?.searcher ?: return
        if (query.isEmpty()) { s.stopSearch(); count = ""; return }
        runCatching {
            s.search(query, EditorSearcher.SearchOptions(if (regex) EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION else EditorSearcher.SearchOptions.TYPE_NORMAL, true))
        }.onFailure { count = "bad pattern" }
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it; search() }, placeholder = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { ref.editor?.searcher?.gotoPrevious() }) { Text("↑") }
            TextButton(onClick = { ref.editor?.searcher?.gotoNext() }) { Text("↓") }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close find") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(replace, { replace = it }, placeholder = { Text("Replace") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { runCatching { ref.editor?.searcher?.replaceCurrentMatch(replace) } }) { Text("One") }
            TextButton(onClick = { runCatching { ref.editor?.searcher?.replaceAll(replace) } }) { Text("All") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(regex, { regex = it; search() }); Text("Regex", style = MaterialTheme.typography.labelMedium)
            Text(count, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

// ======================= File tree =======================
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTree(vm: CodeViewModel, onOpen: (String) -> Unit) {
    val tree by vm.tree.collectAsStateWithLifecycle()
    val expanded by vm.expanded.collectAsStateWithLifecycle()
    val project by vm.project.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<FsEntry?>(null) }
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) } // (mode, parentOrPath)
    // Flatten expanded directories into rows (depth-first).
    val rows = remember(tree, expanded) {
        val out = ArrayList<Pair<FsEntry, Int>>()
        fun add(dir: String, depth: Int) {
            tree[dir].orEmpty().filter { it.name != ".git" }.forEach { e ->
                out += e to depth
                if (e.isDir && e.path in expanded) add(e.path, depth + 1)
            }
        }
        add("", 0); out
    }
    Column(Modifier.fillMaxHeight()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(project?.name ?: "", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = { dialog = "file" to "" }) { Icon(Icons.Default.NoteAdd, "New file") }
            IconButton(onClick = { dialog = "dir" to "" }) { Icon(Icons.Default.CreateNewFolder, "New folder") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.first.path }) { (e, depth) ->
                Box {
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { if (e.isDir) vm.toggleDir(e.path) else onOpen(e.path) }, onLongClick = { menuFor = e })
                            .padding(start = (12 + depth * 14).dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when { e.isDir && e.path in expanded -> Icons.Default.ExpandMore; e.isDir -> Icons.Default.ChevronRight; else -> Icons.Default.Description },
                            null, Modifier.size(18.dp),
                        )
                        Text(e.name, Modifier.padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = menuFor == e, onDismissRequest = { menuFor = null }) {
                        if (e.isDir) {
                            DropdownMenuItem(text = { Text("New file here") }, onClick = { menuFor = null; dialog = "file" to e.path })
                            DropdownMenuItem(text = { Text("New folder here") }, onClick = { menuFor = null; dialog = "dir" to e.path })
                        }
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuFor = null; dialog = "rename" to e.path })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuFor = null; dialog = "delete" to e.path })
                    }
                }
            }
        }
        Text("Long-press a file or folder for more actions.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp))
    }
    dialog?.let { (mode, target) ->
        var name by remember(dialog) { mutableStateOf(if (mode == "rename") target.substringAfterLast('/') else "") }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(when (mode) { "file" -> "New file"; "dir" -> "New folder"; "rename" -> "Rename"; else -> "Delete ${target.substringAfterLast('/')}?" }) },
            text = { if (mode != "delete") OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text(if (mode == "file") "main.py" else "name") }) else Text("This cannot be undone.") },
            confirmButton = {
                TextButton(enabled = mode == "delete" || name.isNotBlank(), onClick = {
                    when (mode) {
                        "file" -> vm.newFile(target, name.trim(), dir = false)
                        "dir" -> vm.newFile(target, name.trim(), dir = true)
                        "rename" -> vm.rename(target, name.trim())
                        "delete" -> vm.delete(target)
                    }
                    dialog = null
                }) { Text(if (mode == "delete") "Delete" else "OK") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }
}

