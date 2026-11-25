package com.gccera.myai.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoneyOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gccera.myai.R
import com.gccera.myai.data.catalog.ModelSpec
import com.gccera.myai.util.formatBytes

@Composable
fun OnboardingScreen(onDone: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val d = s.device
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Default.AutoAwesome, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Welcome to ${stringResource(R.string.app_name)}", style = MaterialTheme.typography.headlineMedium)
        Text("A private AI assistant for chat and image generation that runs entirely on your phone.")
        Promise(Icons.Default.CloudOff, "Works offline", "After a one-time model download, no internet is needed.")
        Promise(Icons.Default.Lock, "Private by design", "Chats and images never leave your device. No account, no analytics, no ads.")
        Promise(Icons.Default.MoneyOff, "Free to run", "No servers or paid APIs — your phone does the work.")

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Memory, null)
                    Text("Your device", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    IconButton(onClick = vm::recheck) { Icon(Icons.Default.Refresh, "Re-check") }
                }
                Info("Model", "${d.manufacturer} ${d.model}")
                Info("Chipset", d.chipset)
                Info("CPU cores", d.cpuCores.toString())
                Info("RAM", "${formatBytes(d.totalRamBytes)} (≈${d.ramClassGb} GB class), ${formatBytes(d.availRamBytes)} free now")
                Info("Free storage", formatBytes(d.freeStorageBytes))
                Info("Android", "API ${d.sdkInt} · ${d.abis.firstOrNull() ?: "?"}")
            }
        }

        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Hugging Face account (optional)", style = MaterialTheme.typography.titleMedium)
                com.gccera.myai.ui.hf.HfAccountCard(Modifier.padding(top = 8.dp))
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recommended for this phone", style = MaterialTheme.typography.titleMedium)
                s.recommendation.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                RecommendedModel("Chat", s.recommendation.chat, s.queued, vm::download)
                HorizontalDivider()
                RecommendedModel("Image", s.recommendation.image, s.queued, vm::download)
                HorizontalDivider()
                RecommendedModel("Code", s.recommendation.code, s.queued, vm::download)
                s.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Downloads continue in the background. Use Wi-Fi: models are large. You can change models anytime in the Models tab.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        Button(onClick = { vm.finish(onDone) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (s.queued.isEmpty()) "Skip for now" else "Continue")
        }
    }
}

@Composable
private fun Promise(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.35f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.65f))
    }
}

@Composable
private fun RecommendedModel(kind: String, spec: ModelSpec?, queued: Set<String>, onDownload: (ModelSpec) -> Unit) {
    if (spec == null) {
        Text("$kind: no suitable model for this device", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$kind: ${spec.name}", style = MaterialTheme.typography.bodyLarge)
            Text("${formatBytes(spec.totalBytes)} · ${spec.license.name}", style = MaterialTheme.typography.bodySmall)
        }
        if (spec.id in queued) OutlinedButton(onClick = {}, enabled = false) { Text("Queued") }
        else Button(onClick = { onDownload(spec) }) { Text("Download") }
    }
}
