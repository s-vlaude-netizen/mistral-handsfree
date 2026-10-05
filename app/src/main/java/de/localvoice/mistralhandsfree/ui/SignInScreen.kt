package de.localvoice.mistralhandsfree.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.auth.ApiKeys

/**
 * Mistral has no "Sign in with Google" for other apps: its API is opened with an
 * API key, and OAuth for third parties does not exist. What does exist is the
 * login of the Mistral console itself - Google, Microsoft, Apple or e-mail -
 * where the key is created. So this screen walks through exactly that: it opens
 * the console in a browser tab (where the user logs in the way they always do,
 * with the app never seeing a password), and takes the key back by paste.
 */
private const val CONSOLE_URL = "https://console.mistral.ai/api-keys"

@Composable
fun SignInScreen(
    viewModel: MainViewModel,
    canCancel: Boolean,
    onFinished: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.signIn.collectAsStateWithLifecycle()

    var key by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    var browserMissing by rememberSaveable { mutableStateOf(false) }
    var clipboardEmpty by rememberSaveable { mutableStateOf(false) }

    val checking = state is SignInState.Checking

    LaunchedEffect(state) {
        if (state is SignInState.Done) {
            key = ""
            viewModel.resetSignIn()
            onFinished()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                stringResource(R.string.signin_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.signin_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            StepCard(number = 1, title = stringResource(R.string.signin_step1_title)) {
                Text(stringResource(R.string.signin_step1_body), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { browserMissing = !openConsole(context) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.signin_open_console))
                }
                if (browserMissing) {
                    Text(
                        stringResource(R.string.signin_no_browser),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            StepCard(number = 2, title = stringResource(R.string.signin_step2_title)) {
                Text(stringResource(R.string.signin_step2_body), style = MaterialTheme.typography.bodyMedium)
            }

            StepCard(number = 3, title = stringResource(R.string.signin_step3_title)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        key = it
                        clipboardEmpty = false
                        if (state is SignInState.Failed) viewModel.resetSignIn()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.signin_key_label)) },
                    singleLine = true,
                    enabled = !checking,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = state is SignInState.Failed,
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(R.string.signin_toggle_visibility),
                            )
                        }
                    },
                )
                (state as? SignInState.Failed)?.let {
                    Text(
                        it.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (clipboardEmpty) {
                    Text(
                        stringResource(R.string.signin_clipboard_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        enabled = !checking,
                        onClick = {
                            val pasted = readClipboard(context)
                            // Say so, or the button looks broken.
                            clipboardEmpty = pasted.isBlank()
                            if (pasted.isNotBlank()) {
                                key = pasted
                                // A plausible key is checked right away: one tap instead of two.
                                if (ApiKeys.looksPlausible(ApiKeys.normalize(pasted))) viewModel.submitKey(pasted)
                            }
                        },
                    ) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.signin_paste))
                    }
                    Button(
                        enabled = !checking && key.isNotBlank(),
                        onClick = { viewModel.submitKey(key) },
                        modifier = Modifier.weight(1f),
                    ) {
                        if (checking) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.signin_checking))
                        } else {
                            Text(stringResource(R.string.signin_continue))
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.signin_fine_print),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (canCancel) {
                TextButton(onClick = onCancel, enabled = !checking) {
                    Text(stringResource(R.string.cancel))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StepCard(number: Int, title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        number.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            content()
        }
    }
}

/** @return false if there is no browser at all to open the page in. */
private fun openConsole(context: Context): Boolean = try {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(CONSOLE_URL))
    true
} catch (_: android.content.ActivityNotFoundException) {
    false
}

/** The text on the clipboard, or an empty string. Read only when the user taps "Paste". */
private fun readClipboard(context: Context): String {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return ""
    val clip = clipboard.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
}
