package com.gccera.myai.ui.common

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.core.content.FileProvider
import com.gccera.myai.safety.ReportReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Opens Android's share sheet with plain text. */
fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(send, null))
}

/** Shares an app-private image through our FileProvider (read-only, temporary grant). */
fun shareImage(context: Context, path: String, text: String? = null) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/** Decodes a (down-sampled) bitmap off the main thread. */
@Composable
fun rememberBitmap(path: String?, maxSize: Int = 2048): State<Bitmap?> =
    produceState<Bitmap?>(initialValue = null, path, maxSize) {
        value = if (path.isNullOrEmpty()) null else withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

/** Play-policy "Report" flow: pick a reason, optional note, optional e-mail to the developer. */
@Composable
fun ReportDialog(
    onDismiss: () -> Unit,
    onSubmit: (reason: ReportReason, note: String, alsoEmail: Boolean) -> Unit,
) {
    var reason by remember { mutableStateOf(ReportReason.OTHER) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report content") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Reported content is hidden on this device. Reports are saved locally; you can " +
                        "optionally e-mail one to the developer.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ReportReason.entries.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = reason == r, onClick = { reason = r }, role = Role.RadioButton)
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == r, onClick = null)
                        Text(r.label, Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(500) },
                    label = { Text("Details (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onSubmit(reason, note, true) }) { Text("Report & e-mail") }
                TextButton(onClick = { onSubmit(reason, note, false) }) { Text("Report") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Delete",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp), textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
        if (action != null) Row(Modifier.padding(top = 16.dp)) { action() }
    }
}
