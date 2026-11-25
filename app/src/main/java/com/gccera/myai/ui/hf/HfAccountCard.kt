package com.gccera.myai.ui.hf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gccera.myai.data.hf.HfAccount
import com.gccera.myai.data.hf.HfAccountState
import com.gccera.myai.ui.common.openInBrowser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HfAccountViewModel @Inject constructor(val account: HfAccount) : ViewModel()

/** Hugging Face account: "Sign in with Hugging Face" (OAuth) or a pasted access token. */
@Composable
fun HfAccountCard(modifier: Modifier = Modifier, onBrowse: (() -> Unit)? = null, vm: HfAccountViewModel = hiltViewModel()) {
    val state by vm.account.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val s = state) {
            is HfAccountState.SignedIn -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text("Signed in as @${s.username}", style = MaterialTheme.typography.titleSmall)
                        Text((s.fullName?.let { "$it · " } ?: "") + if (s.viaOAuth) "Hugging Face sign-in" else "access token", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onBrowse?.let { Button(onClick = it) { Text("Browse models") } }
                    OutlinedButton(onClick = { vm.account.signOut() }) { Text("Sign out") }
                }
            }
            HfAccountState.SignedOut -> {
                Text("Sign in with your Hugging Face account to download gated models (Gemma 3, Llama) and see your own models. The token is stored encrypted and only sent to huggingface.co.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = vm.account.oauthConfigured,
                        onClick = { runCatching { openInBrowser(context, vm.account.authorizeUri()) }.onFailure { message = it.message } },
                    ) { Text("Sign in with Hugging Face") }
                    onBrowse?.let { TextButton(onClick = it) { Text("Browse") } }
                }
                if (!vm.account.oauthConfigured) Text("Sign-in isn't set up in this build (no OAuth client ID). You can paste an access token instead.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                if (!showToken) TextButton(onClick = { showToken = true }) { Text("Use an access token instead") }
                else {
                    OutlinedTextField(
                        value = token, onValueChange = { token = it.trim() }, singleLine = true,
                        label = { Text("hf_… read token") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row {
                        Button(enabled = token.length > 8 && !busy, onClick = {
                            busy = true
                            scope.launch {
                                message = runCatching { vm.account.useAccessToken(token); token = ""; showToken = false; null }.getOrElse { it.message }
                                busy = false
                            }
                        }) { Text(if (busy) "Checking…" else "Save token") }
                        TextButton(onClick = { openInBrowser(context, android.net.Uri.parse("https://huggingface.co/settings/tokens")) }) { Text("Create a token") }
                    }
                }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
