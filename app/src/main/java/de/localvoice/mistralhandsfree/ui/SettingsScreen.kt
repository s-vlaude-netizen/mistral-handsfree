package de.localvoice.mistralhandsfree.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.data.AppSettings
import de.localvoice.mistralhandsfree.data.SttEngine
import de.localvoice.mistralhandsfree.data.TtsEngine
import de.localvoice.mistralhandsfree.mistral.ChatModel
import de.localvoice.mistralhandsfree.mistral.Voice
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onReplaceKey: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val loading by viewModel.loadingLists.collectAsStateWithLifecycle()
    val listError by viewModel.listError.collectAsStateWithLifecycle()
    val speechDiagnostics by viewModel.session.speechDiagnostics.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showModelPicker by remember { mutableStateOf(false) }
    var showVoicePicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (catalog == null) viewModel.refreshModels()
    }
    LaunchedEffect(settings.ttsEngine) {
        if (settings.ttsEngine == TtsEngine.MISTRAL && voices == null) viewModel.refreshVoices()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section(title = stringResource(R.string.section_account)) {
                Text(
                    stringResource(R.string.account_key, viewModel.maskedKey()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onReplaceKey) { Text(stringResource(R.string.account_replace)) }
                    OutlinedButton(onClick = { viewModel.signOut() }) { Text(stringResource(R.string.account_sign_out)) }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.account_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section(title = stringResource(R.string.section_model)) {
                PickerRow(
                    label = stringResource(R.string.model_label),
                    value = settings.model,
                    onClick = { showModelPicker = true },
                    trailing = {
                        if (loading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { viewModel.refreshModels() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                            }
                        }
                    },
                )
                listError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                SliderRow(
                    label = stringResource(R.string.temperature),
                    value = settings.temperature,
                    range = 0f..1.5f,
                    stepSize = 0.05f,
                    format = { String.format("%.2f", it) },
                    onChange = { value -> viewModel.updateSettings { it.copy(temperature = value) } },
                )
                SliderRow(
                    label = stringResource(R.string.max_answer_length),
                    value = settings.maxTokens.toFloat(),
                    range = 128f..2048f,
                    stepSize = 128f,
                    format = { context.getString(R.string.tokens_value, it.roundToInt()) },
                    onChange = { value -> viewModel.updateSettings { it.copy(maxTokens = value.roundToInt()) } },
                )
            }

            Section(title = stringResource(R.string.section_conversation)) {
                SwitchRow(
                    title = stringResource(R.string.hands_free_title),
                    subtitle = stringResource(R.string.hands_free_subtitle),
                    checked = settings.handsFree,
                    onChange = { value -> viewModel.updateSettings { it.copy(handsFree = value) } },
                )
                SwitchRow(
                    title = stringResource(R.string.fresh_start_title),
                    subtitle = stringResource(R.string.fresh_start_subtitle),
                    checked = settings.freshStart,
                    onChange = { value -> viewModel.updateSettings { it.copy(freshStart = value) } },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.systemPrompt,
                    onValueChange = { value -> viewModel.updateSettings { it.copy(systemPrompt = value) } },
                    label = { Text(stringResource(R.string.section_system_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
            }

            Section(title = stringResource(R.string.section_listening)) {
                RadioRow(
                    title = stringResource(R.string.stt_engine_system),
                    subtitle = stringResource(R.string.stt_engine_system_note),
                    selected = settings.sttEngine == SttEngine.SYSTEM,
                    onSelect = { viewModel.updateSettings { it.copy(sttEngine = SttEngine.SYSTEM) } },
                )
                RadioRow(
                    title = stringResource(R.string.stt_engine_mistral),
                    subtitle = stringResource(R.string.stt_engine_mistral_note),
                    selected = settings.sttEngine == SttEngine.MISTRAL,
                    onSelect = { viewModel.updateSettings { it.copy(sttEngine = SttEngine.MISTRAL) } },
                )
                Spacer(Modifier.height(8.dp))
                SliderRow(
                    label = stringResource(R.string.pause_label),
                    value = settings.pauseMs.toFloat(),
                    range = AppSettings.MIN_PAUSE_MS.toFloat()..AppSettings.MAX_PAUSE_MS.toFloat(),
                    stepSize = 100f,
                    format = { String.format("%.1f s", it / 1000f) },
                    onChange = { value -> viewModel.updateSettings { it.copy(pauseMs = value.roundToInt()) } },
                )
                Text(
                    stringResource(
                        if (settings.sttEngine == SttEngine.MISTRAL) R.string.pause_note_mistral
                        else R.string.pause_note_system,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.sttEngine == SttEngine.SYSTEM) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settings.sttLanguageTag,
                        onValueChange = { value -> viewModel.updateSettings { it.copy(sttLanguageTag = value.trim()) } },
                        label = { Text(stringResource(R.string.stt_language_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    SwitchRow(
                        title = stringResource(R.string.on_device_title),
                        subtitle = stringResource(R.string.on_device_subtitle),
                        checked = settings.preferOnDevice,
                        onChange = { value -> viewModel.updateSettings { it.copy(preferOnDevice = value) } },
                    )
                }
            }

            Section(title = stringResource(R.string.section_voice)) {
                RadioRow(
                    title = stringResource(R.string.tts_engine_system),
                    subtitle = stringResource(R.string.tts_engine_system_note),
                    selected = settings.ttsEngine == TtsEngine.SYSTEM,
                    onSelect = { viewModel.updateSettings { it.copy(ttsEngine = TtsEngine.SYSTEM) } },
                )
                RadioRow(
                    title = stringResource(R.string.tts_engine_mistral),
                    subtitle = stringResource(R.string.tts_engine_mistral_note),
                    selected = settings.ttsEngine == TtsEngine.MISTRAL,
                    onSelect = { viewModel.updateSettings { it.copy(ttsEngine = TtsEngine.MISTRAL) } },
                )
                Spacer(Modifier.height(8.dp))
                if (settings.ttsEngine == TtsEngine.MISTRAL) {
                    PickerRow(
                        label = stringResource(R.string.voice_label),
                        value = voiceSummary(context, settings.mistralVoiceId, voices),
                        onClick = { showVoicePicker = true },
                        trailing = {
                            IconButton(onClick = { viewModel.refreshVoices() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                            }
                        },
                    )
                }
                OutlinedTextField(
                    value = settings.ttsLanguageTag,
                    onValueChange = { value -> viewModel.updateSettings { it.copy(ttsLanguageTag = value.trim()) } },
                    label = { Text(stringResource(R.string.tts_language_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (settings.ttsEngine == TtsEngine.SYSTEM) {
                    SliderRow(
                        label = stringResource(R.string.speech_rate),
                        value = settings.speechRate,
                        range = 0.5f..2.0f,
                        format = { String.format("%.2fx", it) },
                        onChange = { value -> viewModel.updateSettings { it.copy(speechRate = value) } },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.testSpeech() }) { Text(stringResource(R.string.test_speech_button)) }
                Spacer(Modifier.height(4.dp))
                Text(
                    speechDiagnostics,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section(title = stringResource(R.string.section_about)) {
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            models = catalog?.chatModels.orEmpty(),
            selected = settings.model,
            onSelect = { id ->
                viewModel.updateSettings { it.copy(model = id) }
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }
    if (showVoicePicker) {
        VoicePickerDialog(
            voices = voices.orEmpty(),
            selectedId = settings.mistralVoiceId,
            onSelect = { id ->
                viewModel.updateSettings { it.copy(mistralVoiceId = id) }
                showVoicePicker = false
            },
            onDismiss = { showVoicePicker = false },
        )
    }
}

/** A labelled value that opens a chooser when tapped. */
@Composable
private fun PickerRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
        trailing()
    }
}

private fun voiceSummary(context: Context, selectedId: String, voices: List<Voice>?): String {
    if (selectedId.isBlank()) return context.getString(R.string.voice_automatic)
    return voices?.firstOrNull { it.id == selectedId }?.name ?: selectedId.take(8)
}

@Composable
private fun ModelPickerDialog(
    models: List<ChatModel>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_label)) },
        text = {
            Column {
                if (models.isEmpty()) {
                    Text(stringResource(R.string.models_not_loaded), style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(models, key = { it.id }) { model ->
                            RadioRow(
                                title = model.id,
                                subtitle = model.description?.take(90),
                                selected = model.id == selected,
                                onSelect = { onSelect(model.id) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.trim() },
                    label = { Text(stringResource(R.string.model_other)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(custom) }, enabled = custom.isNotBlank()) {
                Text(stringResource(R.string.use_this))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun VoicePickerDialog(
    voices: List<Voice>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_label)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                item {
                    RadioRow(
                        title = stringResource(R.string.voice_automatic),
                        subtitle = stringResource(R.string.voice_automatic_note),
                        selected = selectedId.isBlank(),
                        onSelect = { onSelect("") },
                    )
                }
                items(voices, key = { it.id }) { voice ->
                    RadioRow(
                        title = voice.name,
                        subtitle = listOfNotNull(
                            voice.languages.takeIf { it.isNotEmpty() }?.joinToString(", "),
                            voice.gender,
                        ).joinToString(" · ").ifEmpty { null },
                        selected = voice.id == selectedId,
                        onSelect = { onSelect(voice.id) },
                    )
                }
                if (voices.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.voices_none),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
