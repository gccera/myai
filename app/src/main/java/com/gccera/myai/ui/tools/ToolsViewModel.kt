package com.gccera.myai.ui.tools

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gccera.myai.data.repo.DocumentRepository
import com.gccera.myai.data.repo.MlKitRepository
import com.gccera.myai.engine.ImageTool
import com.gccera.myai.engine.ImageToolsController
import com.gccera.myai.safety.ContentFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TranslateUi(
    val input: String = "",
    val output: String = "",
    val source: String? = null, // null = auto-detect
    val target: String = "en",
    val detected: String? = null,
    val busy: Boolean = false,
    val downloaded: Set<String> = emptySet(),
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val mlkit: MlKitRepository,
    private val imageTools: ImageToolsController,
    private val documents: DocumentRepository,
    private val filter: ContentFilter,
    private val settings: com.gccera.myai.data.settings.SettingsRepository,
) : ViewModel() {
    val mlKitConsent = settings.settings.map { it.mlKitConsent }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun giveMlKitConsent() = viewModelScope.launch { settings.update { it.copy(mlKitConsent = true) }; refreshLanguages() }
    fun revokeMlKitConsent() = viewModelScope.launch { settings.update { it.copy(mlKitConsent = false) } }

    val languages = mlkit.languages()

    private val _ocr = MutableStateFlow<String?>(null)
    val ocr = _ocr.asStateFlow()
    private val _ocrBusy = MutableStateFlow(false)
    val ocrBusy = _ocrBusy.asStateFlow()

    private val _translate = MutableStateFlow(TranslateUi())
    val translate = _translate.asStateFlow()

    val imageState = imageTools.state
    val docs = documents.documents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _docBusy = MutableStateFlow<String?>(null)
    val docBusy = _docBusy.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init { viewModelScope.launch { if (settings.current().mlKitConsent) refreshLanguages() } }

    fun extractText(uri: Uri) = viewModelScope.launch {
        _ocrBusy.value = true
        try { _ocr.value = mlkit.recognizeText(uri).ifBlank { "(No text found)" } }
        catch (e: Exception) { _events.send("OCR failed: ${e.message}") }
        finally { _ocrBusy.value = false }
    }

    fun clearOcr() { _ocr.value = null }

    fun setTranslateInput(t: String) = _translate.update { it.copy(input = t) }
    fun setSource(code: String?) = _translate.update { it.copy(source = code) }
    fun setTarget(code: String) = _translate.update { it.copy(target = code) }
    fun swap() = _translate.update { it.copy(source = it.target, target = it.source ?: it.detected ?: it.target, input = it.output, output = it.input) }

    fun runTranslate() = viewModelScope.launch {
        val s = _translate.value
        if (s.input.isBlank()) return@launch
        if (filter.check(s.input).blocked) { _events.send(ContentFilter.BLOCKED_PROMPT_MESSAGE); return@launch }
        _translate.update { it.copy(busy = true) }
        try {
            val needsDownload = (s.source != null && s.source !in s.downloaded && s.source != "en") || (s.target !in s.downloaded && s.target != "en")
            if (needsDownload) _events.send("Downloading the language pack once (~30 MB); later translations work offline.")
            val (out, src) = mlkit.translate(s.input, s.source, s.target)
            _translate.update { it.copy(output = out, detected = src) }
        } catch (e: Exception) {
            _events.send(e.message ?: "Translation failed")
        } finally {
            _translate.update { it.copy(busy = false) }
            refreshLanguages()
        }
    }

    fun deleteLanguage(code: String) = viewModelScope.launch { runCatching { mlkit.deleteLanguage(code) }; refreshLanguages() }

    private fun refreshLanguages() = viewModelScope.launch {
        runCatching { mlkit.downloadedLanguages() }.onSuccess { d -> _translate.update { it.copy(downloaded = d) } }
    }

    fun runImageTool(uri: Uri, tool: ImageTool) = imageTools.runOnUri(uri, tool)
    fun cancelImageTool() = imageTools.cancel()
    fun resetImageTool() = imageTools.reset()

    fun importDocument(uri: Uri) = viewModelScope.launch {
        _docBusy.value = "Reading…"
        try {
            val d = documents.import(uri) { done, total -> _docBusy.value = "Indexing $done / $total passages…" }
            _events.send("Imported ${d.name}. Attach it from a chat (document icon).")
        } catch (e: Exception) { _events.send(e.message ?: "Import failed") }
        finally { _docBusy.value = null }
    }

    fun deleteDocument(id: Long) = viewModelScope.launch { documents.delete(id) }
}
