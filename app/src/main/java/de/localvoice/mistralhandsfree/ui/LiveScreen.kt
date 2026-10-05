package de.localvoice.mistralhandsfree.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.domain.ChatMessage
import de.localvoice.mistralhandsfree.domain.Role
import de.localvoice.mistralhandsfree.session.LiveState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onToggleLive: () -> Unit,
    onSignIn: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val session = viewModel.session
    val state by session.state.collectAsStateWithLifecycle()
    val liveMode by session.liveMode.collectAsStateWithLifecycle()
    val needsMicPermission by session.needsMicPermission.collectAsStateWithLifecycle()
    val messages by session.messages.collectAsStateWithLifecycle()
    val statusDetail by session.statusDetail.collectAsStateWithLifecycle()
    val partial by session.partialTranscript.collectAsStateWithLifecycle()
    val level by session.level.collectAsStateWithLifecycle()
    val error by session.error.collectAsStateWithLifecycle()
    val needsSignIn by session.needsSignIn.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var showKeyboardInput by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
                        Text(
                            settings.model,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { session.clearConversation() }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.clear_history))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.open_settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AnimatedVisibility(visible = error != null) {
                ErrorBanner(
                    text = error.orEmpty(),
                    actionLabel = when {
                        needsMicPermission -> stringResource(R.string.open_app_settings)
                        needsSignIn -> stringResource(R.string.sign_in_again)
                        else -> null
                    },
                    onAction = if (needsMicPermission) onOpenAppSettings else onSignIn,
                    onDismiss = { session.dismissError() },
                )
            }

            StatusPanel(
                state = state,
                detail = statusDetail,
                level = level,
                partial = partial,
                modifier = Modifier.fillMaxWidth(),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty()) {
                    item { EmptyHint() }
                }
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }

            AnimatedVisibility(visible = showKeyboardInput) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.type_instead)) },
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            session.sendTypedMessage(draft)
                            draft = ""
                        },
                        enabled = draft.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                    }
                }
            }

            ControlBar(
                state = state,
                liveMode = liveMode,
                onToggleLive = onToggleLive,
                onInterrupt = { session.interruptCurrentTurn() },
                onToggleKeyboard = { showKeyboardInput = !showKeyboardInput },
            )
        }
    }
}

@Composable
private fun StatusPanel(
    state: LiveState,
    detail: String,
    level: Float,
    partial: String,
    modifier: Modifier = Modifier,
) {
    val target = when (state) {
        LiveState.LISTENING -> 1f + level * 0.35f
        LiveState.SPEAKING -> 1.15f
        LiveState.THINKING -> 1.05f
        else -> 1f
    }
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 160),
        label = "orb",
    )
    val color = when (state) {
        LiveState.IDLE -> MaterialTheme.colorScheme.surfaceVariant
        LiveState.PREPARING -> MaterialTheme.colorScheme.tertiary
        LiveState.LISTENING -> MaterialTheme.colorScheme.primary
        LiveState.THINKING -> MaterialTheme.colorScheme.secondary
        LiveState.SPEAKING -> MaterialTheme.colorScheme.primary
    }

    Column(
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Spacer(Modifier.height(8.dp))
        val label = stateLabel(state)
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        // "Listening" under "Listening" says nothing new.
        if (detail.isNotEmpty() && detail != label) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (partial.isNotEmpty()) {
            Text(
                text = "“$partial”",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ControlBar(
    state: LiveState,
    liveMode: Boolean,
    onToggleLive: () -> Unit,
    onInterrupt: () -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    val running = liveMode
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onToggleKeyboard) {
                Icon(Icons.Filled.Keyboard, contentDescription = stringResource(R.string.keyboard))
            }
            Button(
                onClick = onToggleLive,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Icon(
                    imageVector = if (running) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (running) R.string.stop_live else R.string.start_live))
            }
            IconButton(
                onClick = onInterrupt,
                enabled = state == LiveState.SPEAKING || state == LiveState.THINKING,
            ) {
                Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.interrupt_answer))
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val fromUser = message.role == Role.USER
    val bubbleColor = if (fromUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (fromUser) 16.dp else 4.dp,
                bottomEnd = if (fromUser) 4.dp else 16.dp,
            ),
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(if (fromUser) R.string.speaker_you else R.string.speaker_assistant),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = message.text.ifEmpty { if (message.streaming) "…" else "" },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.empty_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorBanner(
    text: String,
    actionLabel: String?,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    }
}

@Composable
private fun stateLabel(state: LiveState): String = stringResource(
    when (state) {
        LiveState.IDLE -> R.string.state_idle
        LiveState.PREPARING -> R.string.state_preparing
        LiveState.LISTENING -> R.string.state_listening
        LiveState.THINKING -> R.string.state_thinking
        LiveState.SPEAKING -> R.string.state_speaking
    },
)
