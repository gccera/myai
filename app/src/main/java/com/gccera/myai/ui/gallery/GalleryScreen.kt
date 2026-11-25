package com.gccera.myai.ui.gallery

import android.Manifest
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gccera.myai.data.db.ImageEntity
import com.gccera.myai.ui.common.ConfirmDialog
import com.gccera.myai.ui.common.EmptyState
import com.gccera.myai.ui.common.ReportDialog
import com.gccera.myai.ui.common.rememberBitmap
import com.gccera.myai.ui.common.shareImage
import com.gccera.myai.util.formatDuration
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(onOpen: (Long) -> Unit, onBack: () -> Unit, vm: GalleryViewModel = hiltViewModel()) {
    val images by vm.all.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Gallery") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        if (images.isEmpty()) {
            Box(Modifier.padding(pad)) {
                EmptyState(Icons.Default.PhotoLibrary, "No images yet", "Images you create are kept privately on this device.")
            }
        } else LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(pad),
        ) {
            items(images, key = { it.id }) { img ->
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).clickable { onOpen(img.id) }) {
                    if (img.reported) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
                            Icon(Icons.Default.VisibilityOff, "Reported", Modifier.padding(48.dp))
                        }
                    } else {
                        val bmp by rememberBitmap(img.filePath, maxSize = 384)
                        bmp?.let { Image(it.asImageBitmap(), img.prompt, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryDetailScreen(id: Long, onBack: () -> Unit, onOpen: (Long) -> Unit = {}, vm: GalleryViewModel = hiltViewModel()) {
    val toolState by vm.toolState.collectAsStateWithLifecycle()
    var toolsMenu by remember { mutableStateOf(false) }
    val img by remember(id) { vm.image(id) }.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<ImageEntity?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingSave?.let { if (granted) vm.saveToGallery(it) }
        pendingSave = null
    }
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Image") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    img?.let { i ->
                        IconButton(onClick = { shareImage(context, i.filePath, i.prompt) }, enabled = !i.reported) { Icon(Icons.Default.Share, "Share") }
                        IconButton(onClick = {
                            if (vm.needsLegacyPermission()) { pendingSave = i; permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
                            else vm.saveToGallery(i)
                        }, enabled = !i.reported) { Icon(Icons.Default.Download, "Save to gallery") }
                        IconButton(onClick = { reporting = true }, enabled = !i.reported) { Icon(Icons.Default.Flag, "Report") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                        Box {
                            IconButton(onClick = { toolsMenu = true }, enabled = !i.reported) { Icon(Icons.Default.AutoFixHigh, "Image tools") }
                            DropdownMenu(expanded = toolsMenu, onDismissRequest = { toolsMenu = false }) {
                                com.gccera.myai.engine.ImageTool.entries.forEach { t ->
                                    DropdownMenuItem(text = { Text(t.label) }, onClick = { toolsMenu = false; vm.runTool(i.id, t) })
                                }
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val i = img ?: return@Scaffold
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (i.reported && !reveal) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { reveal = true }) {
                    Box(contentAlignment = Alignment.Center) { Text("Reported image hidden. Tap to show.") }
                }
            } else {
                val bmp by rememberBitmap(i.filePath)
                bmp?.let {
                    Image(it.asImageBitmap(), i.prompt, Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height).clip(RoundedCornerShape(12.dp)))
                }
            }
            when (val ts = toolState) {
                is com.gccera.myai.engine.ImageToolState.Running -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${ts.tool.label}… ${if (ts.total > 1) "${ts.done}/${ts.total}" else ""}", Modifier.weight(1f))
                    TextButton(onClick = vm::cancelTool) { Text("Cancel") }
                }
                is com.gccera.myai.engine.ImageToolState.Done -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Done: saved as a new image.", Modifier.weight(1f))
                    TextButton(onClick = { vm.resetTool(); onOpen(ts.imageId) }) { Text("Open") }
                }
                is com.gccera.myai.engine.ImageToolState.Error -> Text(ts.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                else -> Unit
            }
            SelectionContainer {
                Column(Modifier.padding(top = 12.dp)) {
                    Text("Prompt", style = MaterialTheme.typography.labelLarge)
                    Text(i.prompt)
                    if (i.negativePrompt.isNotBlank()) {
                        Text("Negative prompt", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        Text(i.negativePrompt)
                    }
                    Text(
                        "${i.width}×${i.height} · ${i.steps} steps · guidance ${i.guidance} · seed ${i.seed}\n" +
                            "${i.modelId} · ${formatDuration(i.durationMs)} · ${DateFormat.getDateTimeInstance().format(Date(i.createdAt))}",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    if (confirmDelete) ConfirmDialog("Delete image?", "It will be removed from this device.", onDismiss = { confirmDelete = false }) {
        vm.delete(id); onBack()
    }
    if (reporting) img?.let { i ->
        ReportDialog(onDismiss = { reporting = false }) { reason, note, email ->
            vm.report(i, reason, note)
            if (email) try { context.startActivity(vm.reportEmail(i, reason, note)) } catch (_: ActivityNotFoundException) {}
            reporting = false
        }
    }
}

