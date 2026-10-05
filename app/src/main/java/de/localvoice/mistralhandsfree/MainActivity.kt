package de.localvoice.mistralhandsfree

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.localvoice.mistralhandsfree.service.LiveSessionService
import de.localvoice.mistralhandsfree.ui.LiveScreen
import de.localvoice.mistralhandsfree.ui.MainViewModel
import de.localvoice.mistralhandsfree.ui.SettingsScreen
import de.localvoice.mistralhandsfree.ui.SignInScreen
import de.localvoice.mistralhandsfree.ui.theme.HandsfreeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HandsfreeTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)
    val liveMode by viewModel.session.liveMode.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Entering a new key while an old one is stored (it was rejected, or the user wants another).
    var replacingKey by rememberSaveable { mutableStateOf(false) }
    var startAfterPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (startAfterPermission) {
            startAfterPermission = false
            if (granted[Manifest.permission.RECORD_AUDIO] == true) {
                startLive(context, viewModel)
            } else {
                // Nothing starts, but the screen says why - and offers the settings page.
                viewModel.session.start()
            }
        }
    }

    // After signing out nothing may keep listening, and settings make no sense without an account.
    LaunchedEffect(signedIn) {
        if (!signedIn) {
            showSettings = false
            LiveSessionService.stop(context)
        }
    }

    BackHandler(enabled = signedIn && replacingKey) { replacingKey = false }
    BackHandler(enabled = signedIn && !replacingKey && showSettings) { showSettings = false }

    when {
        !signedIn || replacingKey -> SignInScreen(
            viewModel = viewModel,
            canCancel = signedIn,
            onFinished = { replacingKey = false },
            onCancel = { replacingKey = false },
        )

        showSettings -> SettingsScreen(
            viewModel = viewModel,
            onBack = { showSettings = false },
            onReplaceKey = { replacingKey = true },
        )

        else -> LiveScreen(
            viewModel = viewModel,
            onOpenSettings = { showSettings = true },
            onSignIn = { replacingKey = true },
            onOpenAppSettings = { openAppSettings(context) },
            onToggleLive = {
                if (liveMode) {
                    viewModel.session.stop()
                    LiveSessionService.stop(context)
                } else if (hasMicPermission(context)) {
                    startLive(context, viewModel)
                } else {
                    startAfterPermission = true
                    permissionLauncher.launch(requiredPermissions())
                }
            },
        )
    }
}

private fun startLive(context: Context, viewModel: MainViewModel) {
    LiveSessionService.start(context)
    viewModel.session.start()
}

/** The system page where the microphone can be allowed again after "Don't ask again". */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A phone without a settings app: nothing to open.
    }
}

private fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }
