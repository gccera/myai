package com.gccera.myai.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gccera.myai.data.catalog.ModelCatalogRepository
import com.gccera.myai.data.catalog.ModelSpec
import com.gccera.myai.data.models.InsufficientStorageException
import com.gccera.myai.data.models.ModelRepository
import com.gccera.myai.data.settings.SettingsRepository
import com.gccera.myai.device.DeviceInfo
import com.gccera.myai.device.DeviceInspector
import com.gccera.myai.device.ModelRecommender
import com.gccera.myai.device.Recommendation
import com.gccera.myai.util.formatBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val deviceInspector: DeviceInspector,
    private val catalog: ModelCatalogRepository,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val hfAccount: com.gccera.myai.data.hf.HfAccount,
) : ViewModel() {

    data class UiState(
        val device: DeviceInfo,
        val recommendation: Recommendation,
        val queued: Set<String> = emptySet(),
        val message: String? = null,
    )

    private val _state = MutableStateFlow(build())
    val state = _state.asStateFlow()

    private fun build(): UiState {
        val d = deviceInspector.inspect()
        return UiState(d, ModelRecommender.recommend(catalog.models.value, d))
    }

    fun recheck() { _state.value = build().copy(queued = _state.value.queued) }

    /** Queues downloads (they continue in the background) and marks them active. */
    fun download(spec: ModelSpec) = viewModelScope.launch {
        if (spec.requiresHfToken && hfAccount.state.value is com.gccera.myai.data.hf.HfAccountState.SignedOut) {
            _state.value = _state.value.copy(message = "${spec.name} is gated: sign in with Hugging Face above, accept its licence on huggingface.co, then download.")
            return@launch
        }
        try {
            models.download(spec)
            models.setActive(spec)
            _state.value = _state.value.copy(queued = _state.value.queued + spec.id, message = null)
        } catch (e: InsufficientStorageException) {
            _state.value = _state.value.copy(message = "Not enough storage for ${spec.name}: need ${formatBytes(e.neededBytes)}.")
        }
    }

    fun finish(onDone: () -> Unit) = viewModelScope.launch {
        settings.update { it.copy(onboardingDone = true) }
        onDone()
    }
}
