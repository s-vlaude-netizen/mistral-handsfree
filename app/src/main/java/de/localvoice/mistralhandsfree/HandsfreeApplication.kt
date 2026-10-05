package de.localvoice.mistralhandsfree

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import de.localvoice.mistralhandsfree.auth.ApiKeyStore
import de.localvoice.mistralhandsfree.auth.KeystoreApiKeyStore
import de.localvoice.mistralhandsfree.data.SettingsStore
import de.localvoice.mistralhandsfree.llm.MistralLlmEngine
import de.localvoice.mistralhandsfree.mistral.MistralAudio
import de.localvoice.mistralhandsfree.mistral.MistralClient
import de.localvoice.mistralhandsfree.mistral.MistralHttp
import de.localvoice.mistralhandsfree.mistral.MistralRepository
import de.localvoice.mistralhandsfree.session.LiveSessionController
import de.localvoice.mistralhandsfree.session.TextSource
import de.localvoice.mistralhandsfree.speech.AndroidSpeechEngines
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/**
 * Holds the long-lived parts.
 *
 * The conversation state is deliberately attached to the application and not to
 * the activity: rotating the screen, the lock screen or a switch to another app
 * must not cut a running conversation off.
 */
class AppContainer(application: Application) {
    val applicationScope = CoroutineScope(SupervisorJob())
    val settings = SettingsStore(application)
    val keyStore: ApiKeyStore = KeystoreApiKeyStore(application)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // The longest the connection may stay silent. Streaming answers have no overall
        // deadline, but a model that "thinks" first can be quiet for a good while.
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val http = MistralHttp(
        client = httpClient,
        apiKey = { keyStore.load() },
        userAgent = "HandsfreeForMistral/${versionName(application)} (Android)",
    )

    val mistral = MistralRepository(MistralClient(http), MistralAudio(http))

    private val text = TextSource.of(application)

    val liveSession = LiveSessionController(
        text = text,
        settingsStore = settings,
        keyStore = keyStore,
        llm = MistralLlmEngine(mistral.client) { settings.current },
        engines = AndroidSpeechEngines(application, mistral, applicationScope, text),
        hasMicrophonePermission = {
            ContextCompat.checkSelfPermission(application, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        },
        scope = applicationScope,
    )

    private fun versionName(application: Application): String = runCatching {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName
    }.getOrNull() ?: "dev"
}

class HandsfreeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
