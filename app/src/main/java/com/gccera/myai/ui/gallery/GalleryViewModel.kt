package com.gccera.myai.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gccera.myai.data.db.ImageEntity
import com.gccera.myai.data.repo.ImageRepository
import com.gccera.myai.safety.ReportReason
import com.gccera.myai.safety.ReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val images: ImageRepository,
    private val reports: ReportRepository,
) : ViewModel() {
    val all: StateFlow<List<ImageEntity>> =
        images.images.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun image(id: Long) = images.image(id)

    fun delete(id: Long) = viewModelScope.launch { images.delete(id) }

    fun saveToGallery(img: ImageEntity) = viewModelScope.launch {
        runCatching { images.exportToGallery(img) }
            .onSuccess { _events.send("Saved to Pictures") }
            .onFailure { _events.send("Could not save: ${it.message}") }
    }

    fun needsLegacyPermission() = images.needsLegacyStoragePermission()

    fun report(img: ImageEntity, reason: ReportReason, note: String) = viewModelScope.launch {
        reports.reportImage(img.id, reason, note)
        _events.send("Thanks — the image was reported and hidden.")
    }

    fun reportEmail(img: ImageEntity, reason: ReportReason, note: String) =
        reports.emailIntent("generated image", reason, note, img.prompt, img.modelId)
}
