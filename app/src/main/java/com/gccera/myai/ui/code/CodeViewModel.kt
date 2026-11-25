package com.gccera.myai.ui.code

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gccera.myai.data.catalog.ModelType
import com.gccera.myai.data.code.CodeLanguages
import com.gccera.myai.data.code.FsEntry
import com.gccera.myai.data.code.ProjectFs
import com.gccera.myai.data.code.ProjectRef
import com.gccera.myai.data.code.ProjectRepository
import com.gccera.myai.data.code.ProjectTemplate
import com.gccera.myai.data.models.ModelRepository
import com.gccera.myai.data.settings.SettingsRepository
import com.gccera.myai.engine.code.BatteryMonitor
import com.gccera.myai.engine.code.CodeAction
import com.gccera.myai.engine.code.CodeAssistant
import com.gccera.myai.engine.code.GitService
import com.gccera.myai.engine.code.GitStatus
import com.gccera.myai.engine.code.PythonRunner
import com.gccera.myai.engine.code.SaverState
import com.gccera.myai.util.ThinkTags
import com.github.difflib.text.DiffRow
import com.github.difflib.text.DiffRowGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.rosemoe.sora.text.Content
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** An open editor tab. [content] keeps its own undo history across tab switches. */
class OpenTab(val path: String, val content: Content, var savedText: String) {
    val name get() = path.substringAfterLast('/')
    val dirty get() = content.toString() != savedText
}

data class SearchHit(val path: String, val line: Int, val text: String)

/** Where an AI edit should be applied: a selected range, or the whole file. */
data class EditTarget(val path: String, val original: String, val range: IntArray?) {
    override fun equals(other: Any?) = other is EditTarget && other.path == path && other.original == original && other.range.contentEquals(range)
    override fun hashCode() = path.hashCode() * 31 + original.hashCode()
}

sealed interface AssistantState {
    data object Idle : AssistantState
    data class Running(val action: CodeAction, val text: String) : AssistantState
    data class Done(val action: CodeAction, val text: String, val proposal: String?, val diff: List<DiffRow>?, val target: EditTarget?) : AssistantState
    data class Error(val message: String) : AssistantState
}

data class ConsoleLine(val text: String, val isErr: Boolean)

@HiltViewModel
class CodeViewModel @Inject constructor(
    private val repo: ProjectRepository,
    private val assistant: CodeAssistant,
    private val git: GitService,
    private val python: PythonRunner,
    private val battery: BatteryMonitor,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val projects = repo.projects
    private val _project = MutableStateFlow<ProjectRef?>(null)
    val project = _project.asStateFlow()
    var fs: ProjectFs? = null
        private set

    // ---- file tree
    private val _tree = MutableStateFlow<Map<String, List<FsEntry>>>(emptyMap())
    val tree = _tree.asStateFlow()
    private val _expanded = MutableStateFlow(setOf(""))
    val expanded = _expanded.asStateFlow()

    // ---- tabs
    private val _tabs = MutableStateFlow<List<OpenTab>>(emptyList())
    val tabs = _tabs.asStateFlow()
    private val _active = MutableStateFlow(-1)
    val active = _active.asStateFlow()
    /** Bumped when a tab's dirty flag may have changed (Content is mutable). */
    private val _dirtyTick = MutableStateFlow(0)
    val dirtyTick = _dirtyTick.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val codeModel = models.entries.map { models.activeModel(ModelType.CODE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val appSettings = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun activeTab(): OpenTab? = _tabs.value.getOrNull(_active.value)

    // ================= Projects =================
    fun createProject(name: String, template: ProjectTemplate) = viewModelScope.launch {
        runCatching { open(repo.createLocal(name.trim(), template)) }.onFailure { msg(it) }
    }

    fun openFolder(uri: Uri) = viewModelScope.launch { runCatching { open(repo.addSaf(uri)) }.onFailure { msg(it) } }

    fun open(p: ProjectRef) = viewModelScope.launch {
        closeProjectNow()
        val ref = repo.touch(p)
        fs = repo.fsFor(ref)
        _project.value = ref
        _expanded.value = setOf("")
        refreshDir("")
        // Open a sensible first file.
        val first = _tree.value[""].orEmpty().firstOrNull { !it.isDir && it.name in listOf("index.html", "main.py", "README.md") }
            ?: _tree.value[""].orEmpty().firstOrNull { !it.isDir && !CodeLanguages.isBinary(it.name) }
        first?.let { openFile(it.path) }
    }

    fun removeProject(p: ProjectRef) = viewModelScope.launch {
        if (_project.value?.id == p.id) closeProjectNow()
        repo.remove(p)
    }

    fun closeProject() = viewModelScope.launch { saveAll(); closeProjectNow() }

    private fun closeProjectNow() {
        _project.value = null; fs = null; _tabs.value = emptyList(); _active.value = -1; _tree.value = emptyMap()
        _search.value = emptyList(); _git.value = null; _assistant.value = AssistantState.Idle
    }

    // ================= Files =================
    fun toggleDir(path: String) {
        if (path in _expanded.value) _expanded.update { it - path }
        else { _expanded.update { it + path }; viewModelScope.launch { refreshDir(path) } }
    }

    private suspend fun refreshDir(path: String) {
        val f = fs ?: return
        runCatching { f.list(path) }.onSuccess { list -> _tree.update { it + (path to list) } }.onFailure { msg(it) }
    }

    private suspend fun refreshAll() { _expanded.value.forEach { refreshDir(it) } }

    fun openFile(path: String) = viewModelScope.launch {
        _tabs.value.indexOfFirst { it.path == path }.takeIf { it >= 0 }?.let { _active.value = it; return@launch }
        if (CodeLanguages.isBinary(path)) { _events.send("Binary files can't be edited here."); return@launch }
        val f = fs ?: return@launch
        runCatching { f.readText(path) }.onSuccess { text ->
            _tabs.update { it + OpenTab(path, Content(text), text) }
            _active.value = _tabs.value.lastIndex
        }.onFailure { msg(it) }
    }

    fun selectTab(i: Int) { if (i in _tabs.value.indices) { _active.value = i; clearSuggestion() } }

    fun closeTab(i: Int) = viewModelScope.launch {
        val tab = _tabs.value.getOrNull(i) ?: return@launch
        if (tab.dirty) save(tab)
        _tabs.update { it.filterIndexed { j, _ -> j != i } }
        val cur = _active.value
        _active.value = when {
            i < cur -> cur - 1          // a tab before the active one closed: shift left
            i == cur -> minOf(cur, _tabs.value.lastIndex) // active closed: neighbour takes over
            else -> cur
        }.coerceIn(-1, _tabs.value.lastIndex)
    }

    fun onEdited() { _dirtyTick.value++ }

    private suspend fun save(tab: OpenTab): Boolean {
        val f = fs ?: return false
        val text = tab.content.toString()
        return runCatching { f.writeText(tab.path, text); tab.savedText = text; _dirtyTick.value++; true }
            .getOrElse { msg(it); false }
    }

    fun saveActive() = viewModelScope.launch { activeTab()?.let { if (save(it)) _events.send("Saved ${it.name}") } }

    suspend fun saveAll() { _tabs.value.filter { it.dirty }.forEach { save(it) } }

    fun newFile(parent: String, name: String, dir: Boolean) = viewModelScope.launch {
        val f = fs ?: return@launch
        runCatching { if (dir) f.createDir(parent, name) else f.createFile(parent, name) }
            .onSuccess { p -> _expanded.update { it + parent }; refreshDir(parent); if (!dir) openFile(p) }
            .onFailure { msg(it) }
    }

    fun rename(path: String, newName: String) = viewModelScope.launch {
        val f = fs ?: return@launch
        runCatching { f.rename(path, newName) }.onSuccess { np ->
            // Re-point open tabs inside the renamed path.
            _tabs.update { tabs -> tabs.map { t -> if (t.path == path || t.path.startsWith("$path/")) OpenTab(np + t.path.removePrefix(path), t.content, t.savedText) else t } }
            refreshDir(ProjectFs.parentOf(path))
        }.onFailure { msg(it) }
    }

    fun delete(path: String) = viewModelScope.launch {
        val f = fs ?: return@launch
        runCatching { f.delete(path) }.onSuccess {
            _tabs.update { tabs -> tabs.filterNot { it.path == path || it.path.startsWith("$path/") } }
            _active.value = _active.value.coerceAtMost(_tabs.value.lastIndex)
            refreshDir(ProjectFs.parentOf(path))
        }.onFailure { msg(it) }
    }

    // ================= Project search =================
    private val _search = MutableStateFlow<List<SearchHit>>(emptyList())
    val search = _search.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching = _searching.asStateFlow()

    fun searchProject(query: String, caseSensitive: Boolean) = viewModelScope.launch {
        val f = fs ?: return@launch
        if (query.isBlank()) { _search.value = emptyList(); return@launch }
        _searching.value = true
        try {
            saveAll()
            val hits = ArrayList<SearchHit>()
            for (e in f.allFiles()) {
                if (CodeLanguages.isBinary(e.name)) continue
                val text = runCatching { f.readText(e.path) }.getOrNull() ?: continue
                text.lineSequence().forEachIndexed { i, line ->
                    if (hits.size < 500 && line.contains(query, ignoreCase = !caseSensitive)) hits += SearchHit(e.path, i, line.trim().take(160))
                }
                if (hits.size >= 500) break
            }
            _search.value = hits
            if (hits.isEmpty()) _events.send("No matches for \"$query\"")
        } finally { _searching.value = false }
    }

    // ================= AI assistant =================
    private val _assistant = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val assistantState = _assistant.asStateFlow()
    private var assistantJob: Job? = null
    private val _cloudAvailable = MutableStateFlow(false)
    val cloudAvailable = _cloudAvailable.asStateFlow()
    private val _cloudHost = MutableStateFlow("")
    val cloudHost = _cloudHost.asStateFlow()

    fun refreshCloud() = viewModelScope.launch { _cloudAvailable.value = assistant.cloudAvailable(); _cloudHost.value = assistant.cloudHost() }

    /** @param range selection as [startLine, startCol, endLine, endCol], or null for the whole file. */
    fun runAssistant(action: CodeAction, code: String, range: IntArray?, extra: String, useCloud: Boolean) {
        val tab = activeTab() ?: return
        assistantJob?.cancel()
        val lang = CodeLanguages.name(tab.path)
        val target = EditTarget(tab.path, code, range)
        assistantJob = viewModelScope.launch {
            val sb = StringBuilder()
            _assistant.value = AssistantState.Running(action, "")
            try {
                assistant.run(action, lang, tab.name, code, range != null, extra, useCloud).collect {
                    sb.append(it); _assistant.value = AssistantState.Running(action, ThinkTags.strip(sb.toString()))
                }
                val reply = ThinkTags.strip(sb.toString()).trim()
                val proposal = if (action.editsCode) CodeAssistant.extractCode(reply) else null
                val diff = proposal?.let { diffRows(code, it) }
                _assistant.value = AssistantState.Done(action, reply, proposal, diff, if (proposal != null) target else null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _assistant.value = AssistantState.Idle
            } catch (e: Exception) {
                _assistant.value = AssistantState.Error(e.message ?: "Assistant failed")
            }
        }
    }

    fun stopAssistant() { assistantJob?.cancel() }
    fun dismissAssistant() { assistantJob?.cancel(); _assistant.value = AssistantState.Idle }

    /** Applies an accepted AI edit to the open tab. Returns false if the code changed meanwhile. */
    fun acceptEdit(): Boolean {
        val s = _assistant.value as? AssistantState.Done ?: return false
        val target = s.target ?: return false
        val proposal = s.proposal ?: return false
        val tab = _tabs.value.firstOrNull { it.path == target.path } ?: return false
        val c = tab.content
        val r = target.range
        if (r == null) {
            if (c.toString() != target.original) { viewModelScope.launch { _events.send("The file changed since the request; not applied.") }; return false }
            c.replace(0, 0, c.lineCount - 1, c.getColumnCount(c.lineCount - 1), proposal)
        } else {
            val now = runCatching { c.subContent(r[0], r[1], r[2], r[3]).toString() }.getOrNull()
            if (now != target.original) { viewModelScope.launch { _events.send("The selection changed since the request; not applied.") }; return false }
            c.replace(r[0], r[1], r[2], r[3], proposal)
        }
        _assistant.value = AssistantState.Idle
        _dirtyTick.value++
        return true
    }

    /** "Write tests": save the reply's code block as a new file next to the current one. */
    fun saveTestsAsFile(code: String) = viewModelScope.launch {
        val tab = activeTab() ?: return@launch
        val dir = ProjectFs.parentOf(tab.path)
        val base = tab.name.substringBeforeLast('.')
        val ext = tab.name.substringAfterLast('.', "txt")
        val name = when (ext) { "py" -> "test_$base.py"; "js" -> "$base.test.js"; else -> "${base}Test.$ext" }
        val f = fs ?: return@launch
        runCatching {
            val p = runCatching { f.createFile(dir, name) }.getOrElse { ProjectFs.join(dir, name) }
            f.writeText(p, code); refreshDir(dir); openFile(p)
        }.onFailure { msg(it) }
    }

    // ================= Autocomplete =================
    private val _suggestion = MutableStateFlow<Pair<Int, String>?>(null)
    /** (cursor char index the suggestion was made for, text). */
    val suggestion = _suggestion.asStateFlow()
    val saver = battery.states().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SaverState(false, null))
    private var completeJob: Job? = null

    fun clearSuggestion() { completeJob?.cancel(); _suggestion.value = null }

    /** Called after each edit with the cursor index; debounced so typing doesn't trigger the model. */
    fun onTyping(cursorIndex: Int) {
        clearSuggestion()
        val s = appSettings.value ?: return
        if (!s.autocomplete || saver.value.paused || codeModel.value == null) return
        val tab = activeTab() ?: return
        completeJob = viewModelScope.launch {
            delay(1200)
            val text = tab.content.toString()
            if (cursorIndex !in 0..text.length) return@launch
            val suffix = text.substring(cursorIndex)
            val prefix = text.substring(0, cursorIndex)
            if (prefix.isBlank() || prefix.last() == '\n' && prefix.trimEnd().isEmpty()) return@launch
            val result = runCatching { assistant.complete(prefix, suffix, CodeLanguages.name(tab.path)) }.getOrNull()
            if (!result.isNullOrBlank()) _suggestion.value = cursorIndex to result
        }
    }

    fun setAutocomplete(on: Boolean) = viewModelScope.launch { settings.update { it.copy(autocomplete = on) } }

    // ================= Run =================
    private val _console = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val console = _console.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _preview = MutableStateFlow<String?>(null)
    /** HTML entry path to preview (null = closed). */
    val preview = _preview.asStateFlow()

    fun run(stdin: String = "") = viewModelScope.launch {
        val tab = activeTab() ?: return@launch
        saveAll()
        when {
            CodeLanguages.isPython(tab.path) -> runPython(tab, stdin)
            CodeLanguages.isWeb(tab.path) -> {
                val entry = if (tab.path.endsWith(".html") || tab.path.endsWith(".htm")) tab.path
                else _tree.value[ProjectFs.parentOf(tab.path)].orEmpty().firstOrNull { it.name == "index.html" }?.path
                    ?: _tree.value[""].orEmpty().firstOrNull { it.name == "index.html" }?.path
                if (entry == null) _events.send("No index.html found to preview.") else _preview.value = entry
            }
            else -> _events.send("Run supports Python (.py) and web files (.html/.css/.js).")
        }
    }

    private suspend fun runPython(tab: OpenTab, stdin: String) {
        _console.value = listOf(ConsoleLine("▶ python ${tab.name}\n", false))
        _running.value = true
        try {
            val code = python.run(tab.content.toString(), tab.name, fs?.localRoot?.let { java.io.File(it, ProjectFs.parentOf(tab.path)).path }, stdin) { text, err ->
                _console.update { it + ConsoleLine(text, err) }
            }
            _console.update { it + ConsoleLine("\n[exit code $code]\n", code != 0) }
        } catch (e: Exception) {
            _console.update { it + ConsoleLine("\n${e.message}\n", true) }
        } finally { _running.value = false }
    }

    fun stopRun() = python.stop()
    fun closePreview() { _preview.value = null }
    fun appendConsole(line: String, err: Boolean) = _console.update { it + ConsoleLine(line + "\n", err) }
    fun clearConsole() { _console.value = emptyList() }

    // ================= Git =================
    private val _git = MutableStateFlow<GitStatus?>(null)
    val gitStatus = _git.asStateFlow()
    private val _gitBusy = MutableStateFlow<String?>(null)
    val gitBusy = _gitBusy.asStateFlow()

    private fun localDir() = fs?.localRoot

    fun isGitRepo() = localDir()?.let { git.isRepo(it) } == true

    fun refreshGit() = viewModelScope.launch { localDir()?.takeIf { git.isRepo(it) }?.let { d -> runCatching { _git.value = git.status(d) }.onFailure { msg(it) } } }

    private fun gitOp(label: String, block: suspend (java.io.File) -> String?) = viewModelScope.launch {
        val d = localDir() ?: run { _events.send("Git works with app-storage projects. Use Clone, or create a new project."); return@launch }
        _gitBusy.value = label
        try {
            saveAll()
            block(d)?.let { _events.send(it) }
            refreshAll()
            // Re-read open files that git may have changed.
            _tabs.value.forEach { t -> runCatching { fs!!.readText(t.path) }.getOrNull()?.let { txt -> if (!t.dirty && txt != t.savedText) { t.content.replace(0, 0, t.content.lineCount - 1, t.content.getColumnCount(t.content.lineCount - 1), txt); t.savedText = txt } } }
            if (git.isRepo(d)) _git.value = git.status(d)
        } catch (e: Exception) { msg(e) } finally { _gitBusy.value = null }
    }

    fun gitInit() = gitOp("Initializing") { git.init(it); "Repository created" }
    fun gitCommit(message: String) = gitOp("Committing") { d -> val s = settings.current(); "Committed ${git.commitAll(d, message, s.gitAuthorName, s.gitAuthorEmail)}" }
    fun gitPull() = gitOp("Pulling") { d -> git.pull(d) { p -> _gitBusy.value = p } }
    fun gitPush() = gitOp("Pushing") { d -> git.push(d) { p -> _gitBusy.value = p } }
    fun gitCheckout(branch: String) = gitOp("Switching branch") { git.checkout(it, branch); "On $branch" }
    fun gitNewBranch(name: String) = gitOp("Creating branch") { git.createBranch(it, name.trim()); "Created and switched to ${name.trim()}" }
    fun gitSetRemote(url: String) = gitOp("Setting remote") { git.setRemote(it, url.trim()); "Remote set" }

    fun gitClone(url: String) = viewModelScope.launch {
        val name = url.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
        val (id, dir) = repo.reserveLocal(name)
        _gitBusy.value = "Cloning $name"
        try {
            git.clone(url.trim(), dir) { p -> _gitBusy.value = p }
            open(repo.addLocal(id, name))
        } catch (e: Exception) {
            dir.deleteRecursively(); msg(e)
        } finally { _gitBusy.value = null }
    }

    private fun msg(t: Throwable) { viewModelScope.launch { _events.send(t.message ?: t.javaClass.simpleName) } }

    override fun onCleared() { python.stop() }

    private fun diffRows(original: String, revised: String): List<DiffRow> = CodeDiff.rows(original, revised)
}

/** Line diff for AI-suggested edits (java-diff-utils). */
object CodeDiff {
    private val generator = DiffRowGenerator.create().showInlineDiffs(false).ignoreWhiteSpaces(false).build()
    fun rows(original: String, revised: String): List<DiffRow> = generator.generateDiffRows(original.lines(), revised.lines())
}
