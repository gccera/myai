package com.gccera.myai.ui.models

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gccera.myai.data.catalog.ModelCatalogRepository
import com.gccera.myai.data.catalog.ModelFile
import com.gccera.myai.data.catalog.ModelLicense
import com.gccera.myai.data.catalog.ModelSpec
import com.gccera.myai.data.catalog.ModelType
import com.gccera.myai.data.catalog.ImageModelConfig
import com.gccera.myai.data.models.InsufficientStorageException
import com.gccera.myai.data.models.ModelEntry
import com.gccera.myai.data.models.ModelRepository
import com.gccera.myai.data.settings.SettingsRepository
import com.gccera.myai.device.DeviceInfo
import com.gccera.myai.device.DeviceInspector
import com.gccera.myai.engine.ModelSessionManager
import com.gccera.myai.util.formatBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ModelRepository,
    private val catalog: ModelCatalogRepository,
    private val settingsRepo: SettingsRepository,
    private val hfAccount: com.gccera.myai.data.hf.HfAccount,
    sessions: ModelSessionManager,
    deviceInspector: DeviceInspector,
) : ViewModel() {

    val entries: StateFlow<List<ModelEntry>> =
        repo.entries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val session = sessions.state
    val device: DeviceInfo = deviceInspector.inspect()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun download(spec: ModelSpec) = viewModelScope.launch {
        if (spec.requiresHfToken && hfAccount.state.value is com.gccera.myai.data.hf.HfAccountState.SignedOut) {
            _messages.send("${spec.name} is licence-gated: sign in with Hugging Face (Settings) and accept its licence on huggingface.co.")
            return@launch
        }
        try {
            repo.download(spec)
        } catch (e: InsufficientStorageException) {
            _messages.send("Not enough storage: need ${formatBytes(e.neededBytes)}, ${formatBytes(e.freeBytes)} free.")
        }
    }

    fun pause(spec: ModelSpec) = repo.pause(spec)

    fun delete(spec: ModelSpec) = viewModelScope.launch {
        repo.delete(spec)
        _messages.send("${spec.name} deleted")
    }

    fun setActive(spec: ModelSpec) = viewModelScope.launch {
        repo.setActive(spec)
        _messages.send("${spec.name} is now the active ${spec.type.name.lowercase()} model")
    }

    fun removeCustom(spec: ModelSpec) = viewModelScope.launch {
        repo.delete(spec)
        catalog.removeCustom(spec.id)
    }

    /** "Add custom model": a single-file LiteRT-LM chat model or a diffusers-ONNX image model. */
    fun addCustom(name: String, type: ModelType, url: String, sha256: String, licenseName: String, licenseUrl: String) =
        viewModelScope.launch {
            try {
                require(url.startsWith("https://")) { "URL must start with https://" }
                require(sha256.isBlank() || sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "SHA-256 must be 64 hex characters" }
                val id = "custom-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                val spec = if (type == ModelType.CHAT || type == ModelType.CODE) {
                    ModelSpec(
                        id = id, type = type, name = name, description = "Custom model", params = "custom",
                        license = ModelLicense("custom", licenseName.ifBlank { "Unknown" }, licenseUrl),
                        files = listOf(ModelFile(url.substringAfterLast('/').substringBefore('?'), url, 0, sha256.ifBlank { null }?.lowercase())),
                        custom = true,
                    )
                } else {
                    // Image: URL is the Hugging Face repo base, e.g. https://huggingface.co/<org>/<repo>/resolve/main
                    val base = url.trimEnd('/')
                    val files = listOf(
                        "text_encoder/model.onnx", "unet/model.onnx", "vae_decoder/model.onnx",
                        "tokenizer/vocab.json", "tokenizer/merges.txt", "tokenizer/special_tokens_map.json",
                    ).map { ModelFile(it, "$base/$it", 0, null) }
                    ModelSpec(
                        id = id, type = type, name = name, description = "Custom ONNX diffusion model (unverified)",
                        params = "custom", minRamGb = 6, recommendedRamGb = 8,
                        license = ModelLicense("custom", licenseName.ifBlank { "Unknown" }, licenseUrl),
                        files = files, image = ImageModelConfig(), custom = true,
                    )
                }
                catalog.addCustom(spec)
                _messages.send("Added $name")
            } catch (e: Exception) {
                _messages.send(e.message ?: "Could not add model")
            }
        }

    fun readLicense(id: String): String? = runCatching {
        context.assets.open("licenses/$id.txt").bufferedReader().use { it.readText() }
    }.getOrNull()
}
