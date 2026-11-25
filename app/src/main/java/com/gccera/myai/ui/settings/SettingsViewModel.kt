package com.gccera.myai.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gccera.myai.data.catalog.ModelCatalogRepository
import com.gccera.myai.data.models.ModelRepository
import com.gccera.myai.data.models.ModelStorage
import com.gccera.myai.data.repo.ChatRepository
import com.gccera.myai.data.repo.ImageRepository
import com.gccera.myai.data.settings.AppSettings
import com.gccera.myai.data.settings.SettingsRepository
import com.gccera.myai.engine.ModelSessionManager
import com.gccera.myai.safety.ReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val chats: ChatRepository,
    private val images: ImageRepository,
    private val models: ModelRepository,
    private val storage: ModelStorage,
    private val catalog: ModelCatalogRepository,
    private val reports: ReportRepository,
    private val sessions: ModelSessionManager,
    private val secrets: com.gccera.myai.engine.code.SecretStore,
    private val projects: com.gccera.myai.data.code.ProjectRepository,
    private val documents: com.gccera.myai.data.repo.DocumentRepository,
) : ViewModel() {
    // Secrets are never shown again after saving; the UI only knows whether one is set.
    private val _hasGitToken = MutableStateFlow(secrets.has(com.gccera.myai.engine.code.SecretStore.GITHUB_TOKEN))
    val hasGitToken = _hasGitToken.asStateFlow()
    private val _hasCloudKey = MutableStateFlow(secrets.has(com.gccera.myai.engine.code.SecretStore.CLOUD_API_KEY))
    val hasCloudKey = _hasCloudKey.asStateFlow()

    fun setGitToken(token: String?) {
        secrets.put(com.gccera.myai.engine.code.SecretStore.GITHUB_TOKEN, token?.trim())
        _hasGitToken.value = !token.isNullOrBlank()
    }

    fun setCloudKey(key: String?) {
        secrets.put(com.gccera.myai.engine.code.SecretStore.CLOUD_API_KEY, key?.trim())
        _hasCloudKey.value = !key.isNullOrBlank()
    }


    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val reportCount = reports.count.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _modelBytes = MutableStateFlow(0L)
    val modelBytes = _modelBytes.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init { refreshUsage() }

    fun refreshUsage() = viewModelScope.launch(Dispatchers.IO) { _modelBytes.value = storage.usedBytes() }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        val before = settingsRepo.current()
        settingsRepo.update(transform)
        // Switching CPU/GPU takes effect on the next model load.
        if (before.backend != settingsRepo.current().backend) sessions.releaseIfIdle()
    }

    fun clearChats() = viewModelScope.launch { chats.deleteAll(); _events.send("All chats deleted") }
    fun clearImages() = viewModelScope.launch { images.deleteAll(); _events.send("All images deleted") }
    fun deleteModels() = viewModelScope.launch { models.deleteAll(); refreshUsage(); _events.send("All models deleted") }

    /** Deletes every chat, image, report, model, custom model entry and setting on this device. */
    fun clearAllData(onDone: () -> Unit) = viewModelScope.launch {
        chats.deleteAll()
        images.deleteAll()
        reports.clear()
        models.deleteAll()
        catalog.clearCustom()
        documents.deleteAll()
        projects.deleteAll()
        secrets.clear()
        settingsRepo.clear()
        withContext(Dispatchers.IO) { File(context.cacheDir.path).listFiles()?.forEach { it.deleteRecursively() } }
        refreshUsage()
        onDone()
    }

    fun readAsset(path: String): String =
        runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("Not found: $path")
}
