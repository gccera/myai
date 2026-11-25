package com.gccera.myai.ui.chat

import android.Manifest
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.RadioButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.gccera.myai.ui.common.rememberBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gccera.myai.data.db.ConversationEntity
import com.gccera.myai.data.db.MessageEntity
import com.gccera.myai.data.db.MessageStatus
import com.gccera.myai.data.db.Role
import com.gccera.myai.engine.SessionState
import com.gccera.myai.ui.common.ConfirmDialog
import com.gccera.myai.ui.common.EmptyState
import com.gccera.myai.ui.common.ReportDialog
import com.gccera.myai.ui.common.shareText
import com.gccera.myai.util.ThinkTags
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onOpenModels: () -> Unit, vm: ChatViewModel = hiltViewModel()) {
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val currentId by vm.conversationId.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val activeModel by vm.activeModel.collectAsStateWithLifecycle()

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var input by rememberSaveable { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var reportTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var deleteConv by remember { mutableStateOf<ConversationEntity?>(null) }
    val listState = rememberLazyListState()
    val generating = streaming != null
    val pendingImage by vm.pendingImage.collectAsStateWithLifecycle()
    val attachedDoc by vm.attachedDocument.collectAsStateWithLifecycle()
    val documents by vm.allDocuments.collectAsStateWithLifecycle()
    val indexing by vm.indexing.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val speakingId by vm.speaker.speaking.collectAsStateWithLifecycle()
    var showDocs by remember { mutableStateOf(false) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::attachImage) }
    val pickDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importDocument) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoice() else scope.launch { snackbar.showSnackbar("Microphone permission is needed for voice input.") }
    }
    LaunchedEffect(Unit) { vm.transcripts.collect { t -> input = (input.trimEnd() + " " + t).trim() } }

    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
    // Keep the newest message in view while streaming.
    LaunchedEffect(messages.size, streaming?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text("Conversations", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                NavigationDrawerItem(
                    label = { Text("New chat") }, selected = false,
                    icon = { Icon(Icons.Default.Add, null) },
                    onClick = { vm.newChat(); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LazyColumn {
                    items(conversations, key = { it.id }) { c ->
                        NavigationDrawerItem(
                            label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            selected = c.id == currentId,
                            onClick = { vm.selectConversation(c.id); scope.launch { drawer.close() } },
                            badge = { IconButton(onClick = { deleteConv = c }) { Icon(Icons.Default.Delete, "Delete chat") } },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Conversations") } },
                    title = {
                        Column {
                            Text(conversation?.title ?: "Chat", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(sessionLabel(session, activeModel?.name), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDocs = true }) {
                            Icon(Icons.Default.Description, "Chat with a document", tint = if (attachedDoc != null) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        }
                        IconButton(onClick = { vm.newChat() }) { Icon(Icons.Default.Add, "New chat") }
                        IconButton(onClick = { showSettings = true }, enabled = conversation != null) { Icon(Icons.Default.Tune, "Chat settings") }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
                attachedDoc?.let { d ->
                    AssistChip(
                        onClick = { showDocs = true },
                        label = { Text("Answering from: ${d.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Default.Description, null, Modifier.size(18.dp)) },
                        trailingIcon = { Icon(Icons.Default.Close, "Detach document", Modifier.size(18.dp).clickable { vm.attachDocument(null) }) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                indexing?.let { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp)) }
                Box(Modifier.weight(1f)) {
                    when {
                        activeModel == null -> EmptyState(
                            Icons.Default.ChatBubbleOutline, "No chat model yet",
                            "Download a chat model to start a private, offline conversation.",
                        ) { Button(onClick = onOpenModels) { Text("Open Models") } }
                        messages.isEmpty() -> EmptyState(
                            Icons.Default.ChatBubbleOutline, "Ask me anything",
                            "Everything stays on this device. Replies are generated by ${activeModel?.name}.",
                        )
                        else -> LazyColumn(state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(messages, key = { it.id }) { m ->
                                val live = streaming?.takeIf { it.messageId == m.id }
                                MessageBubble(
                                    message = m,
                                    liveText = live?.text,
                                    isLastAssistant = m.role == Role.ASSISTANT && m.id == messages.last().id,
                                    generating = generating,
                                    onCopy = { clipboard.setText(AnnotatedString(m.content)) },
                                    onShare = { shareText(context, m.content) },
                                    onRegenerate = { vm.regenerate(m.id) },
                                    onDelete = { vm.deleteMessage(m.id) },
                                    onReport = { reportTarget = m },
                                    speaking = speakingId == m.id,
                                    onSpeak = { vm.speak(m) },
                                )
                            }
                        }
                    }
                }
                pendingImage?.let { p ->
                    Row(Modifier.padding(start = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val bmp by rememberBitmap(p, 256)
                        bmp?.let { Image(it.asImageBitmap(), "Attached photo", Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
                        TextButton(onClick = vm::clearImage) { Text("Remove photo") }
                    }
                }
                InputBar(
                    text = input,
                    onTextChange = { input = it },
                    enabled = activeModel != null,
                    generating = generating,
                    hasAttachment = pendingImage != null,
                    voice = voice,
                    visionModel = activeModel?.vision == true,
                    onSend = { vm.send(input); input = "" },
                    onStop = vm::stop,
                    onMic = {
                        when (voice) {
                            VoiceState.Recording -> vm.stopVoice()
                            VoiceState.Idle -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            VoiceState.Transcribing -> Unit
                        }
                    },
                    onAttachPhoto = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onAttachDocument = { pickDoc.launch(arrayOf("application/pdf", "text/plain", "text/markdown", "text/*")) },
                )
            }
        }
    }

    if (showSettings) conversation?.let { c ->
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            ConversationSettings(c) { prompt, temp, max, title ->
                vm.updateConversationSettings(prompt, temp, max, title); showSettings = false
            }
        }
    }
    reportTarget?.let { m ->
        ReportDialog(onDismiss = { reportTarget = null }) { reason, note, email ->
            vm.report(m.id, reason, note)
            if (email) try { context.startActivity(vm.reportEmailIntent(m, reason, note)) } catch (_: ActivityNotFoundException) {}
            reportTarget = null
        }
    }
    if (showDocs) AlertDialog(
        onDismissRequest = { showDocs = false },
        title = { Text("Chat with a document") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Answers in this chat will use passages from the selected document. It never leaves your phone.", style = MaterialTheme.typography.bodySmall)
                documents.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = attachedDoc?.id == d.id, onClick = { vm.attachDocument(d.id); showDocs = false })
                        Column(Modifier.weight(1f)) {
                            Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${d.chunkCount} passages", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { vm.deleteDocument(d.id) }) { Icon(Icons.Default.Delete, "Delete document") }
                    }
                }
                if (documents.isEmpty()) Text("No documents yet.", Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { showDocs = false; pickDoc.launch(arrayOf("application/pdf", "text/plain", "text/markdown", "text/*")) }) { Text("Import PDF / TXT") } },
        dismissButton = { if (attachedDoc != null) TextButton(onClick = { vm.attachDocument(null); showDocs = false }) { Text("Detach") } else TextButton(onClick = { showDocs = false }) { Text("Close") } },
    )
    deleteConv?.let { c ->
        ConfirmDialog("Delete chat?", "\"${c.title}\" and all its messages will be deleted from this device.",
            onDismiss = { deleteConv = null }, onConfirm = { vm.deleteConversation(c.id) })
    }
}

private fun sessionLabel(s: SessionState, modelName: String?): String = when (s) {
    is SessionState.Loading -> "Loading ${s.spec.name}…"
    is SessionState.Busy -> "${s.spec.name} · ${s.task}…"
    is SessionState.Ready -> "${s.spec.name} · ${s.detail}"
    SessionState.Empty -> modelName?.let { "$it · not loaded" } ?: "No model"
}

@Composable
private fun MessageBubble(
    message: MessageEntity,
    liveText: String?,
    isLastAssistant: Boolean,
    generating: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    speaking: Boolean,
    onSpeak: () -> Unit,
) {
    val isUser = message.role == Role.USER
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 600.dp),
        ) {
            Box(Modifier.padding(12.dp)) {
                when {
                    message.reported -> Text("You reported this reply. It is hidden.", style = MaterialTheme.typography.bodySmall)
                    isUser -> Column {
                        message.imagePath?.let { p ->
                            val bmp by rememberBitmap(p, 512)
                            bmp?.let { Image(it.asImageBitmap(), "Attached photo", Modifier.padding(bottom = 6.dp).size(160.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
                        }
                        SelectionContainer { Text(message.content) }
                    }
                    liveText != null -> {
                        val visible = ThinkTags.strip(liveText)
                        when {
                            visible.isNotBlank() -> Markdown(content = visible)
                            ThinkTags.isThinking(liveText) -> Text("Thinking…", style = MaterialTheme.typography.bodyMedium)
                            else -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                    else -> SelectionContainer { Markdown(content = message.content.ifBlank { "_(empty)_" }) }
                }
            }
        }
        if (!isUser && liveText == null && !message.reported) message.sources?.let { src ->
            var open by remember { mutableStateOf(false) }
            Text(
                if (open) "Sources ▲\n$src" else "Sources ▼",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.widthIn(max = 600.dp).clickable { open = !open }.padding(4.dp),
            )
        }
        if (liveText == null && !message.reported) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (message.status == MessageStatus.STOPPED) Text("Stopped", style = MaterialTheme.typography.labelSmall)
                SmallAction(Icons.Default.ContentCopy, "Copy", onCopy)
                SmallAction(Icons.Default.Share, "Share", onShare)
                if (isLastAssistant && !generating) SmallAction(Icons.Default.Refresh, "Regenerate", onRegenerate)
                if (!generating) SmallAction(Icons.Default.Delete, "Delete", onDelete)
                if (!isUser) SmallAction(if (speaking) Icons.Default.StopCircle else Icons.AutoMirrored.Filled.VolumeUp, if (speaking) "Stop reading" else "Read aloud", onSpeak)
                if (!isUser) SmallAction(Icons.Default.Flag, "Report", onReport)
            }
        }
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) { Icon(icon, label, Modifier.size(18.dp)) }
}

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    enabled: Boolean,
    generating: Boolean,
    hasAttachment: Boolean,
    voice: VoiceState,
    visionModel: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMic: () -> Unit,
    onAttachPhoto: () -> Unit,
    onAttachDocument: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled && !generating) { Icon(Icons.Default.AddCircleOutline, "Attach") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (visionModel) "Photo" else "Photo (needs Gemma 4 E2B)") },
                    leadingIcon = { Icon(Icons.Default.Image, null) },
                    onClick = { menu = false; onAttachPhoto() },
                )
                DropdownMenuItem(text = { Text("Document (PDF / TXT)") }, leadingIcon = { Icon(Icons.Default.Description, null) }, onClick = { menu = false; onAttachDocument() })
            }
        }
        OutlinedTextField(
            value = text, onValueChange = onTextChange, enabled = enabled,
            placeholder = { Text(when (voice) { VoiceState.Recording -> "Listening… tap ■ when done"; VoiceState.Transcribing -> "Transcribing…"; else -> "Message" }) },
            maxLines = 6,
            modifier = Modifier.weight(1f),
        )
        when {
            generating -> FilledIconButton(onClick = onStop, modifier = Modifier.padding(start = 8.dp)) { Icon(Icons.Default.Stop, "Stop") }
            voice == VoiceState.Transcribing -> Box(Modifier.padding(start = 8.dp).size(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            voice == VoiceState.Recording -> FilledIconButton(onClick = onMic, modifier = Modifier.padding(start = 8.dp)) { Icon(Icons.Default.Stop, "Stop recording") }
            text.isBlank() && !hasAttachment -> IconButton(onClick = onMic, enabled = enabled, modifier = Modifier.padding(start = 4.dp)) { Icon(Icons.Default.Mic, "Voice input") }
            else -> FilledIconButton(onClick = onSend, enabled = enabled, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

@Composable
private fun ConversationSettings(c: ConversationEntity, onSave: (String, Float, Int, String) -> Unit) {
    var title by remember { mutableStateOf(c.title) }
    var prompt by remember { mutableStateOf(c.systemPrompt) }
    var temp by remember { mutableFloatStateOf(c.temperature) }
    var max by remember { mutableFloatStateOf(c.maxTokens.toFloat()) }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Chat settings", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(prompt, { prompt = it }, label = { Text("System prompt") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
        Text("Temperature: ${"%.2f".format(temp)} (lower = focused, higher = creative)")
        Slider(value = temp, onValueChange = { temp = it }, valueRange = 0f..1.5f)
        Text("Max reply length: ${max.toInt()} tokens")
        Slider(value = max, onValueChange = { max = it }, valueRange = 64f..2048f, steps = 30)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { prompt = com.gccera.myai.data.settings.AppSettings.DEFAULT_SYSTEM_PROMPT }) { Text("Reset prompt") }
            Button(onClick = { onSave(prompt, temp, max.toInt(), title) }) { Text("Save") }
        }
    }
}

