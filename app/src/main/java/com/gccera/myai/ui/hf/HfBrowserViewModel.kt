package com.gccera.myai.ui.hf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gccera.myai.data.catalog.ModelCatalogRepository
import com.gccera.myai.data.catalog.ModelType
import com.gccera.myai.data.hf.HfAccount
import com.gccera.myai.data.hf.HfAccountState
import com.gccera.myai.data.hf.HfHub
import com.gccera.myai.data.hf.HubDetail
import com.gccera.myai.data.hf.HubFile
import com.gccera.myai.data.hf.HubModel
import com.gccera.myai.data.hf.HubSort
import com.gccera.myai.data.models.InsufficientStorageException
import com.gccera.myai.data.models.ModelRepository
import com.gccera.myai.util.formatBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUi(val detail: HubDetail? = null, val loading: Boolean = true, val accessible: Boolean? = null, val error: String? = null)

@HiltViewModel
class HfBrowserViewModel @Inject constructor(
    private val hub: HfHub,
    val account: HfAccount,
    private val catalog: ModelCatalogRepository,
    private val models: ModelRepository,
) : ViewModel() {
    val accountState = account.state

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()
    private val _mine = MutableStateFlow(false)
    val mine = _mine.asStateFlow()
    private val _sort = MutableStateFlow(HubSort.DOWNLOADS)
    val sort = _sort.asStateFlow()
    private val _results = MutableStateFlow<List<HubModel>>(emptyList())
    val results = _results.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _detail = MutableStateFlow<DetailUi?>(null)
    val detail = _detail.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var searchJob: Job? = null

    init { refresh() }

    fun setQuery(q: String) { _query.value = q; refresh(debounce = true) }
    fun setMine(on: Boolean) { _mine.value = on; refresh() }
    fun setSort(s: HubSort) { _sort.value = s; refresh() }

    fun refresh(debounce: Boolean = false) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) delay(400)
            _loading.value = true; _error.value = null
            try {
                val author = (accountState.value as? HfAccountState.SignedIn)?.username?.takeIf { _mine.value }
                if (_mine.value && author == null) { _results.value = emptyList(); _error.value = "Sign in to see your models."; return@launch }
                _results.value = hub.search(_query.value, author, _sort.value)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { _error.value = e.message ?: "Couldn't reach Hugging Face" }
            finally { _loading.value = false }
        }
    }

    fun open(m: HubModel) = viewModelScope.launch {
        _detail.value = DetailUi()
        try {
            val d = hub.detail(m.id)
            _detail.value = DetailUi(d, loading = false)
            val first = d.files.firstOrNull()
            if (first != null && (d.model.gated || d.model.private)) {
                _detail.value = _detail.value?.copy(accessible = runCatching { hub.canDownload(d.model.id, d.sha, first.path) }.getOrDefault(false))
            }
        } catch (e: Exception) { _detail.value = DetailUi(loading = false, error = e.message) }
    }

    fun closeDetail() { _detail.value = null }

    /** Adds the file to the model list and starts the download right away. */
    fun add(d: HubDetail, f: HubFile, type: ModelType) = viewModelScope.launch {
        if (f.sha256 == null) { _events.send("This file has no SHA-256 on Hugging Face, so it can't be verified."); return@launch }
        val spec = HfHub.toSpec(d, f, type)
        try {
            if (catalog.find(spec.id) == null) catalog.addCustom(spec)
            models.download(catalog.find(spec.id)!!)
            models.setActive(spec)
            _events.send("Downloading ${spec.name} (${formatBytes(f.size)}). Progress is in Models.")
            _detail.value = null
        } catch (e: InsufficientStorageException) {
            _events.send("Not enough storage: need ${formatBytes(e.neededBytes)}.")
        } catch (e: Exception) { _events.send(e.message ?: "Couldn't add the model") }
    }
}
