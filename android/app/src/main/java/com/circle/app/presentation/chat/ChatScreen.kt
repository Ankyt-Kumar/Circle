package com.circle.app.presentation.chat

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.circle.app.domain.model.ChatMessage
import com.circle.app.domain.model.ConversationGuide
import com.circle.app.presentation.theme.CircleTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRoute(
    vm: ChatViewModel,
    viewerId: String,
    onBlockMember: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(vm, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.refresh()
                delay(5000.milliseconds)
            }
        }
    }

    ChatScreen(
        state = state,
        viewerId = viewerId,
        onBack = onBack,
        onTextChange = vm::edit,
        onSend = vm::send,
        onLoadOlder = vm::loadOlder,
        onGuide = vm::guide,
        onDismissGuide = vm::dismissGuide,
        onBlockMember = onBlockMember,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    viewerId: String,
    onBack: () -> Unit,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onLoadOlder: () -> Unit,
    onGuide: () -> Unit,
    onDismissGuide: () -> Unit,
    onBlockMember: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Scroll to bottom when new messages are added
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    val showScrollToBottom by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            state.messages.isNotEmpty() && lastVisibleIndex < state.messages.size - 2
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                if (state.archived) "Circle Chat · Archived" else "Circle Chat",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (state.archived) "Read-only mode"
                                else if (state.privateAi) "🔒 Private AI active"
                                else "Live updates active",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        FilledTonalButton(
                            onClick = onGuide,
                            enabled = !state.unavailable,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Icebreakers",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    },
                )
                if (state.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            when {
                state.archived -> {}
                state.unavailable -> AlertBanner(
                    text = "You can't send messages in this chat right now.",
                    container = MaterialTheme.colorScheme.surfaceContainerHigh,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                    icon = { Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                else -> MessageInputBar(
                    text = state.text,
                    sending = state.sending,
                    onTextChange = onTextChange,
                    onSend = onSend,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                InfoStrip(
                    text = if (state.archived)
                        "This chat is read-only. Messages are retained for 30 days after the event."
                    else
                        "Messages are shared with attendees immediately. Avoid sharing private contact details.",
                    tonal = state.archived,
                    privateAi = state.privateAi,
                )

                state.error?.let { err ->
                    AlertBanner(
                        text = err,
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                        icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }

                state.notice?.let { not ->
                    AlertBanner(
                        text = not,
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                        icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.hasOlder) {
                        item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                OutlinedButton(
                                    onClick = onLoadOlder,
                                    shape = CircleShape,
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Load earlier messages")
                                }
                            }
                        }
                    }

                    if (!state.loading && state.messages.isEmpty()) {
                        item {
                            EmptyChatView(
                                onSelectIcebreaker = { prompt ->
                                    onTextChange(prompt)
                                },
                                onOpenGuide = onGuide,
                            )
                        }
                    }

                    items(state.messages.size, key = { state.messages[it].id }) { index ->
                        val message = state.messages[index]
                        val prevMessage = if (index > 0) state.messages[index - 1] else null
                        val nextMessage = if (index < state.messages.size - 1) state.messages[index + 1] else null

                        val showDateHeader = prevMessage == null || !isSameDay(message.createdAt, prevMessage.createdAt)
                        if (showDateHeader) {
                            DateHeader(dateText = formatHeaderDate(message.createdAt))
                        }

                        val isMe = message.authorId == viewerId
                        val isSameSenderAsPrevious = prevMessage != null && prevMessage.authorId == message.authorId && !showDateHeader
                        val isSameSenderAsNext = nextMessage != null && nextMessage.authorId == message.authorId && isSameDay(message.createdAt, nextMessage.createdAt)

                        MessageBubble(
                            authorName = message.name,
                            body = message.body,
                            status = message.status,
                            timestamp = formatMessageTime(message.createdAt),
                            isMe = isMe,
                            showAvatar = !isMe && !isSameSenderAsNext,
                            showAuthorName = !isMe && !isSameSenderAsPrevious,
                            onBlock = { onBlockMember(message.authorId) },
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showScrollToBottom,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 16.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            listState.animateScrollToItem(state.messages.size - 1)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = CircleShape,
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom")
                }
            }
        }
    }

    state.guide?.let { g ->
        GuideDialog(
            guide = g,
            onDismiss = onDismissGuide,
            onSelectIcebreaker = { line ->
                onTextChange(line)
            },
        )
    }
}

@Composable
private fun DateHeader(dateText: String) {
    if (dateText.isBlank()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = dateText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun InfoStrip(text: String, tonal: Boolean, privateAi: Boolean) {
    Surface(
        color = if (tonal) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (tonal) Icons.Outlined.Lock else Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (privateAi) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        "🔒 Private AI",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertBanner(
    text: String,
    container: Color,
    content: Color,
    icon: @Composable (() -> Unit)? = null,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon?.invoke()
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun EmptyChatView(
    onSelectIcebreaker: (String) -> Unit,
    onOpenGuide: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp, horizontal = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Forum,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Start the conversation",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Say hi or tap a quick starter prompt below to break the ice with fellow attendees.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "Quick Starters",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "Hey everyone! Excited to meet up 👋",
                    "What time is everyone planning to arrive? 🕒",
                    "Looking forward to this event! 🎉",
                ).forEach { prompt ->
                    OutlinedCard(
                        onClick = { onSelectIcebreaker(prompt) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                prompt,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onOpenGuide) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("More icebreakers")
            }
        }
    }
}

@Composable
private fun MessageInputBar(
    text: String,
    sending: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "Message the circle...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                },
                enabled = !sending,
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(
                    onSend = { if (text.isNotBlank() && !sending) onSend() }
                ),
            )
            FilledIconButton(
                onClick = onSend,
                enabled = !sending && text.isNotBlank(),
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current,
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    authorName: String,
    body: String,
    status: String,
    timestamp: String,
    isMe: Boolean,
    showAvatar: Boolean,
    showAuthorName: Boolean,
    onBlock: () -> Unit,
) {
    val isVisible = status == "allowed"
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isMe) {
            if (showAvatar) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp, bottom = 2.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(avatarColor(authorName)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        authorName.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            } else {
                Spacer(Modifier.width(40.dp))
            }
        }

        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
        ) {
            if (!isMe && showAuthorName) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                ) {
                    Text(
                        authorName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (isVisible) {
                        Box {
                            IconButton(
                                onClick = { menuOpen = true },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "Message options",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Block,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    text = { Text("Block $authorName", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menuOpen = false
                                        onBlock()
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                color = if (isMe) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (isMe) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isMe) 18.dp else 4.dp,
                    bottomEnd = if (isMe) 4.dp else 18.dp,
                ),
                tonalElevation = if (isMe) 0.dp else 1.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    if (isVisible) {
                        Text(
                            text = body,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 20.sp,
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (status == "rejected") "Message unavailable" else "Not available",
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic,
                                color = if (isMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (timestamp.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = timestamp,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = if (isMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            modifier = Modifier.align(Alignment.End),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideDialog(
    guide: ConversationGuide,
    onDismiss: () -> Unit,
    onSelectIcebreaker: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (guide.ended) Icons.Outlined.CheckCircle else Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Text(
                if (guide.ended) "Event Recap" else "Conversation Starters",
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (guide.ended) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        StatChip(
                            label = "Total Joined",
                            value = "${guide.joined}",
                            modifier = Modifier.weight(1f),
                        )
                        StatChip(
                            label = "Attended",
                            value = "${guide.selfReportedAttendance}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (guide.icebreakers.isNotEmpty()) {
                    Text(
                        if (guide.ended) "Post-event topics:" else "Tap an icebreaker to insert it:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        guide.icebreakers.forEach { line ->
                            Surface(
                                onClick = {
                                    onSelectIcebreaker(line)
                                    onDismiss()
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Lightbulb,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

private fun avatarColor(name: String): Color {
    val colors = listOf(
        Color(0xFF6750A4), // Purple
        Color(0xFF006874), // Cyan
        Color(0xFF984061), // Pink/Magenta
        Color(0xFF006D3B), // Green
        Color(0xFF8B5000), // Amber
        Color(0xFF235EA8), // Blue
        Color(0xFF7D5260), // Reddish
    )
    val hash = abs(name.hashCode())
    return colors[hash % colors.size]
}

private fun formatMessageTime(instant: Instant): String {
    return try {
        DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(instant)
    } catch (_: Exception) {
        ""
    }
}

private fun formatHeaderDate(instant: Instant): String {
    return try {
        val messageDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now(ZoneId.systemDefault())
        when (messageDate) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
                .withZone(ZoneId.systemDefault())
                .format(instant)
        }
    } catch (_: Exception) {
        ""
    }
}

private fun isSameDay(i1: Instant, i2: Instant): Boolean {
    return try {
        val d1 = i1.atZone(ZoneId.systemDefault()).toLocalDate()
        val d2 = i2.atZone(ZoneId.systemDefault()).toLocalDate()
        d1 == d2
    } catch (_: Exception) {
        false
    }
}

@Preview(showBackground = true, name = "Chat Screen Light")
@Composable
private fun ChatScreenPreview() {
    CircleTheme {
        ChatScreen(
            state = ChatUiState(
                messages = listOf(
                    ChatMessage(
                        id = 1,
                        clientId = "1",
                        authorId = "user1",
                        name = "Aria",
                        body = "Hey everyone! Looking forward to coffee this Saturday.",
                        status = "allowed",
                        createdAt = Instant.now().minusSeconds(3600),
                    ),
                    ChatMessage(
                        id = 2,
                        clientId = "2",
                        authorId = "me",
                        name = "Me",
                        body = "Same here! What venue are we going to?",
                        status = "allowed",
                        createdAt = Instant.now().minusSeconds(1800),
                    ),
                    ChatMessage(
                        id = 3,
                        clientId = "3",
                        authorId = "user1",
                        name = "Aria",
                        body = "The local café near the central park!",
                        status = "allowed",
                        createdAt = Instant.now().minusSeconds(300),
                    ),
                ),
                loading = false,
                privateAi = true,
            ),
            viewerId = "me",
            onBack = {},
            onTextChange = {},
            onSend = {},
            onLoadOlder = {},
            onGuide = {},
            onDismissGuide = {},
            onBlockMember = {},
        )
    }
}

@Preview(showBackground = true, name = "Empty Chat Screen")
@Composable
private fun EmptyChatScreenPreview() {
    CircleTheme {
        ChatScreen(
            state = ChatUiState(
                messages = emptyList(),
                loading = false,
            ),
            viewerId = "me",
            onBack = {},
            onTextChange = {},
            onSend = {},
            onLoadOlder = {},
            onGuide = {},
            onDismissGuide = {},
            onBlockMember = {},
        )
    }
}
