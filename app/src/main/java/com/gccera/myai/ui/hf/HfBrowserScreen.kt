package com.gccera.myai.ui.hf

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gccera.myai.data.catalog.ModelType
import com.gccera.myai.data.hf.HfAccountState
import com.gccera.myai.data.hf.HfHub
import com.gccera.myai.data.hf.HubModel
import com.gccera.myai.data.hf.HubSort
import com.gccera.myai.ui.common.openInBrowser
import com.gccera.myai.util.formatBytes

/** Browse Hugging Face for on-device (LiteRT-LM) models, incl. the signed-in user's own models. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HfBrowserScreen(onBack: () -> Unit, onOpenAccount: () -> Unit, vm: HfBrowserViewModel = hiltViewModel()) {
    val account by vm.accountState.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val mine by vm.mine.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sortMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hugging Face models") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    when (val a = account) {
                        is HfAccountState.SignedIn -> AssistChip(onClick = onOpenAccount, label = { Text(a.username) }, leadingIcon = { Icon(Icons.Default.AccountCircle, null, Modifier.size(18.dp)) })
                        HfAccountState.SignedOut -> TextButton(onClick = onOpenAccount) { Text("Sign in") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = vm::setQuery, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text("Search models (e.g. gemma, qwen, coder)") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(!mine, { vm.setMine(false) }, label = { Text("All on-device") })
                FilterChip(mine, { vm.setMine(true) }, label = { Text("My models") })
                Box {
                    TextButton(onClick = { sortMenu = true }) { Text(sort.label + " ▾") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        HubSort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { vm.setSort(s); sortMenu = false }) }
                    }
                }
            }
            Text(
                "Showing models in LiteRT-LM format (.litertlm), which run offline in this app. Check each model's licence before commercial use.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.id }) { m -> ModelRow(m) { vm.open(m) } }
                if (!loading && results.isEmpty() && error == null) item { Text("No models found.") }
            }
        }
    }
    detail?.let { DetailSheet(vm, it, account) }
}

@Composable
private fun ModelRow(m: HubModel, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Text(m.id, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.Center) {
                Stat(Icons.Default.Download, compact(m.downloads))
                Stat(Icons.Default.Favorite, compact(m.likes))
                if (m.gated || m.private) Stat(Icons.Default.Lock, if (m.private) "private" else "gated")
                Text(m.license ?: "no licence tag", style = MaterialTheme.typography.labelSmall,
                    color = if (HfHub.commercialOk(m.license)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Stat(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Text(" $text", style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(vm: HfBrowserViewModel, ui: DetailUi, account: HfAccountState) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = vm::closeDetail, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.loading) { CircularProgressIndicator(); return@Column }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error); return@Column }
            val d = ui.detail ?: return@Column
            Text(d.model.id, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { openInBrowser(context, Uri.parse(HfHub.pageUrl(d.model.id))) }) { Text("Open model page") }
            val commercial = HfHub.commercialOk(d.model.license)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!commercial) Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Text(
                    "Licence: ${d.model.license ?: "not stated"}" + if (commercial) " (allows commercial use, check its terms)" else " — may NOT allow commercial use; read it before using this model in a product.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (d.model.gated || d.model.private) {
                when {
                    account is HfAccountState.SignedOut -> Text("This model is ${if (d.model.private) "private" else "gated"}: sign in with Hugging Face to download it.", style = MaterialTheme.typography.bodyMedium)
                    ui.accessible == false -> {
                        Text("Your account doesn't have access yet. Accept the licence / request access on the model page, then come back.", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { openInBrowser(context, Uri.parse(HfHub.pageUrl(d.model.id))) }) { Text("Request access on Hugging Face") }
                    }
                    ui.accessible == true -> Text("✓ You have access to this model.", color = MaterialTheme.colorScheme.primary)
                    else -> Text("Checking access…", style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
            if (d.files.isEmpty()) Text("No .litertlm files in this repository.")
            d.files.forEach { f ->
                Column(Modifier.fillMaxWidth()) {
                    Text(f.path, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${formatBytes(f.size)}" + if (f.sha256 == null) " · no checksum" else "", style = MaterialTheme.typography.labelSmall)
                    val blocked = (d.model.gated || d.model.private) && ui.accessible != true
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !blocked && f.sha256 != null, onClick = { vm.add(d, f, ModelType.CHAT) }) { Text("Add as Chat") }
                        OutlinedButton(enabled = !blocked && f.sha256 != null, onClick = { vm.add(d, f, ModelType.CODE) }) { Text("Add as Code") }
                    }
                }
            }
            Text("Files are pinned to this exact version and verified with SHA-256 after download.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun compact(n: Long) = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6)
    n >= 1_000 -> "%.1fk".format(n / 1e3)
    else -> n.toString()
}
