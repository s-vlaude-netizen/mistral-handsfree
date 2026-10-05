package de.localvoice.mistralhandsfree.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.core.view.drawToBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.localvoice.mistralhandsfree.AppContainer
import de.localvoice.mistralhandsfree.HandsfreeApplication
import de.localvoice.mistralhandsfree.MainActivity
import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.service.LiveSessionService
import de.localvoice.mistralhandsfree.data.SttEngine
import de.localvoice.mistralhandsfree.data.TtsEngine
import de.localvoice.mistralhandsfree.testing.InMemoryKeyStore
import de.localvoice.mistralhandsfree.testing.MistralStub
import de.localvoice.mistralhandsfree.testing.SilentEngines
import java.io.File
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The real app - activity, view model, API client, session controller - on the
 * JVM, with only two things faked: Mistral (a local stub that checks the Bearer
 * key like the real API does) and the speech hardware.
 *
 * Screenshots of the states along the way are written to
 * `app/build/outputs/ui-screenshots/`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var stub: MistralStub
    private lateinit var keys: InMemoryKeyStore
    private lateinit var engines: SilentEngines
    private lateinit var app: HandsfreeApplication
    private var scenario: ActivityScenario<MainActivity>? = null

    private val goodKey = "good-key-0123456789abcdefABCDEF"

    @Before
    fun setUp() {
        stub = MistralStub(goodKey)
        server = MockWebServer().apply {
            dispatcher = stub
            start()
        }
        app = ApplicationProvider.getApplicationContext()
        keys = InMemoryKeyStore(null)
        engines = SilentEngines()
        install()
    }

    /** Replaces the app's container with one that talks to the stub, before any activity exists. */
    private fun install(signedInWith: String? = null) {
        keys = InMemoryKeyStore(signedInWith)
        app.container = AppContainer(app, keyStore = keys, baseUrl = server.url("/v1/"), engines = engines)
    }

    @After
    fun tearDown() {
        scenario?.close()
        server.shutdown()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun string(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun waitForText(text: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForNoText(text: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        var bitmap: Bitmap? = null
        scenario!!.onActivity { activity -> bitmap = activity.window.decorView.drawToBitmap() }
        val file = File("build/outputs/ui-screenshots/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun typeKey(key: String) {
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput(key)
    }

    // ----------------------------------------------------------------- sign-in

    @Test
    fun `a first start asks to sign in`() {
        launch()
        waitForText(string(R.string.signin_title))

        compose.onNodeWithText(string(R.string.signin_open_console)).assertExists()
        compose.onNodeWithText(string(R.string.signin_continue)).assertExists()
        assertNull(keys.key)
        // Nothing may be sent to Mistral before there is a key.
        assertTrue(stub.requests.isEmpty())
        screenshot("01-sign-in")
    }

    @Test
    fun `a wrong key is rejected, and nothing is stored`() {
        launch()
        waitForText(string(R.string.signin_title))

        typeKey("wrong-key-0123456789abcdef")
        compose.onNodeWithText(string(R.string.signin_continue)).performClick()
        waitForText(string(R.string.signin_rejected))

        assertNull(keys.key)
        // The candidate key was really checked against the API, with that very key.
        assertEquals("Bearer wrong-key-0123456789abcdef", stub.requests.single().getHeader("Authorization"))
        screenshot("02-sign-in-rejected")
    }

    @Test
    fun `something that is plainly not a key is not even sent`() {
        launch()
        waitForText(string(R.string.signin_title))

        typeKey("hello")
        compose.onNodeWithText(string(R.string.signin_continue)).performClick()
        waitForText(string(R.string.signin_implausible))

        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `a good key signs in and opens the live screen`() {
        launch()
        waitForText(string(R.string.signin_title))

        typeKey(goodKey)
        compose.onNodeWithText(string(R.string.signin_continue)).performClick()
        waitForText(string(R.string.start_live))

        assertEquals(goodKey, keys.key)
        compose.onNodeWithText(string(R.string.empty_title)).assertExists()
        screenshot("03-live-empty")
    }

    @Test
    fun `a pasted environment variable assignment works too`() {
        launch()
        waitForText(string(R.string.signin_title))

        typeKey("export MISTRAL_API_KEY=\"$goodKey\"")
        compose.onNodeWithText(string(R.string.signin_continue)).performClick()
        waitForText(string(R.string.start_live))

        assertEquals(goodKey, keys.key)
    }

    @Test
    fun `pasting a key from the clipboard signs in with that one tap`() {
        launch()
        waitForText(string(R.string.signin_title))
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("key", goodKey))

        compose.onNodeWithText(string(R.string.signin_paste)).performClick()
        waitForText(string(R.string.start_live))

        assertEquals(goodKey, keys.key)
    }

    @Test
    fun `pasting with nothing on the clipboard says so`() {
        launch()
        waitForText(string(R.string.signin_title))

        compose.onNodeWithText(string(R.string.signin_paste)).performClick()

        // Otherwise the button looks broken.
        waitForText(string(R.string.signin_clipboard_empty))
        screenshot("03b-signin-clipboard-empty")
        assertNull(keys.key)

        // Typing takes the hint away again.
        typeKey("abc")
        waitForNoText(string(R.string.signin_clipboard_empty))
    }

    // ----------------------------------------------------------- already signed in

    @Test
    fun `with a stored key it goes straight to the live screen and loads the models`() {
        install(signedInWith = goodKey)
        launch()
        waitForText(string(R.string.start_live))

        compose.waitUntil(10_000) { stub.requests.any { it.path == "/v1/models" } }
        assertEquals("Bearer $goodKey", stub.requests.first { it.path == "/v1/models" }.getHeader("Authorization"))
        // The default model is named in the title bar.
        compose.onNodeWithText("mistral-small-latest").assertExists()
    }

    @Test
    fun `a key that was revoked is reported right at launch`() {
        install(signedInWith = "revoked-key-0123456789abcdef")
        launch()

        waitForText(string(R.string.err_unauthorized))
        compose.onNodeWithText(string(R.string.sign_in_again)).assertExists()
        screenshot("04-live-key-revoked")
    }

    @Test
    fun `the sign-in button of the error banner leads to the sign-in screen`() {
        install(signedInWith = "revoked-key-0123456789abcdef")
        launch()
        waitForText(string(R.string.err_unauthorized))

        compose.onNodeWithText(string(R.string.sign_in_again)).performClick()
        waitForText(string(R.string.signin_title))
        // The old key is still there until a working one replaces it, so there is a way back.
        compose.onNodeWithText(string(R.string.cancel)).assertExists()
    }

    // --------------------------------------------------------------- the microphone

    /**
     * What the system's permission dialog sends back, delivered through the same
     * result machinery the real dialog uses. The callback is deprecated for apps to
     * override, but it is exactly what the system calls.
     */
    @Suppress("DEPRECATION")
    private fun answerPermissionDialog(granted: Boolean) {
        scenario!!.onActivity { activity ->
            val request = shadowOf(activity).lastRequestedPermission
            assertNotNull("the app never asked for a permission", request)
            val answer = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            activity.onRequestPermissionsResult(
                request.requestCode,
                request.requestedPermissions,
                IntArray(request.requestedPermissions.size) { answer },
            )
        }
    }

    @Test
    fun `refusing the microphone says so and leads to the settings`() {
        install(signedInWith = goodKey)
        launch()
        waitForText(string(R.string.start_live))

        compose.onNodeWithText(string(R.string.start_live)).performClick()
        answerPermissionDialog(granted = false)

        // Without this, a refusal looked like a button that does nothing - for good once
        // Android stops showing its dialog.
        waitForText(string(R.string.error_no_mic_permission))
        screenshot("12-live-microphone-refused")
        assertNull("no service without a microphone", shadowOf(app).peekNextStartedService())

        compose.onNodeWithText(string(R.string.open_app_settings)).performClick()
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
        assertEquals("package:${app.packageName}", opened.dataString)
    }

    @Test
    fun `allowing the microphone starts live mode`() {
        install(signedInWith = goodKey)
        launch()
        waitForText(string(R.string.start_live))

        compose.onNodeWithText(string(R.string.start_live)).performClick()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO) // what "Allow" does
        answerPermissionDialog(granted = true)

        waitForText(string(R.string.stop_live))
        waitForText(string(R.string.state_listening))
        // The state and its detail are both "Listening"; the screen says it once.
        compose.onAllNodesWithText(string(R.string.state_listening)).assertCountEquals(1)
        screenshot("13-live-listening")
        assertEquals(LiveSessionService::class.java.name, shadowOf(app).nextStartedService.component?.className)
    }

    // --------------------------------------------------------------- a conversation

    @Test
    fun `a typed message gets its answer streamed onto the screen and read out`() {
        install(signedInWith = goodKey)
        launch()
        waitForText(string(R.string.start_live))

        compose.onNodeWithContentDescription(string(R.string.keyboard)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("What is the capital of France?")
        compose.onNodeWithContentDescription(string(R.string.send)).performClick()

        val answer = "Hello! This is a test reply. Anything else?"
        waitForText(answer)
        compose.onNodeWithText("What is the capital of France?").assertExists()

        // What went to Mistral: the system instruction first, then the question.
        val request = stub.requests.last { it.path == "/v1/chat/completions" }
        val body = JSONObject(request.body.readUtf8())
        assertEquals("mistral-small-latest", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("What is the capital of France?", messages.getJSONObject(1).getString("content"))

        // And it was handed to the speaker (all in one piece: the sentences are short).
        compose.waitUntil(10_000) { engines.spoken.isNotEmpty() }
        assertEquals(answer, engines.spoken.joinToString(" "))
        // Typing a question is not live mode, so the main button must not claim it is.
        compose.onNodeWithText(string(R.string.start_live)).assertExists()
        compose.onAllNodesWithText(string(R.string.stop_live)).fetchSemanticsNodes().isEmpty().let(::assertTrue)
        screenshot("05-live-conversation")
    }

    @Test
    fun `a key that stops working during a conversation asks to sign in again`() {
        install(signedInWith = goodKey)
        launch()
        waitForText(string(R.string.start_live))
        // The key is revoked while the app is open.
        keys.key = "revoked-key-0123456789abcdef"

        compose.onNodeWithContentDescription(string(R.string.keyboard)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Hello?")
        compose.onNodeWithContentDescription(string(R.string.send)).performClick()

        waitForText(string(R.string.err_unauthorized))
        compose.onNodeWithText(string(R.string.sign_in_again)).assertExists()
        // The question stays on screen; no empty answer bubble is left behind.
        compose.onNodeWithText("Hello?").assertExists()
    }

    // ------------------------------------------------------------------- settings

    @Test
    fun `the settings list the models of the account and a choice sticks`() {
        install(signedInWith = goodKey)
        launch()
        waitForText(string(R.string.start_live))

        compose.onNodeWithContentDescription(string(R.string.open_settings)).performClick()
        waitForText(string(R.string.section_account))
        screenshot("06-settings-top")

        // Open the model picker and choose another model from the account's real list.
        compose.onNodeWithContentDescription(string(R.string.choose_model)).performClick()
        waitForText("mistral-large-latest")
        screenshot("07-settings-model-picker")
        compose.onNodeWithText("mistral-large-latest").performClick()

        compose.waitUntil(10_000) { app.container.settings.current.model == "mistral-large-latest" }
        assertEquals("mistral-large-latest", app.container.settings.current.model)
    }

    @Test
    fun `the listening and voice settings`() {
        install(signedInWith = goodKey)
        launch()
        waitForText(string(R.string.start_live))
        compose.onNodeWithContentDescription(string(R.string.open_settings)).performClick()
        waitForText(string(R.string.section_account))

        // performScrollTo() scrolls the least it can, so scroll to what is about to be clicked.
        compose.onNodeWithText(string(R.string.stt_engine_mistral)).performScrollTo().performClick()
        compose.waitUntil(10_000) { app.container.settings.current.sttEngine == SttEngine.MISTRAL }
        // With the Voxtral engine the options of the system recognizer are not shown.
        assertTrue(compose.onAllNodesWithText(string(R.string.on_device_title)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(string(R.string.pause_note_mistral)).performScrollTo()
        screenshot("10-settings-listening")

        compose.onNodeWithText(string(R.string.tts_engine_mistral)).performScrollTo().performClick()
        compose.waitUntil(10_000) { app.container.settings.current.ttsEngine == TtsEngine.MISTRAL }
        // Choosing Mistral's voice fetches the voices of the account.
        compose.waitUntil(10_000) { stub.requests.any { it.path?.startsWith("/v1/audio/voices") == true } }
        compose.onNodeWithText(string(R.string.tts_not_started)).performScrollTo()
        screenshot("11-settings-voice")
    }

    @Test
    fun `signing out removes the key and returns to the sign-in screen`() {
        install(signedInWith = goodKey)
        launch()
        waitForText(string(R.string.start_live))
        compose.onNodeWithContentDescription(string(R.string.open_settings)).performClick()
        waitForText(string(R.string.section_account))

        compose.onNodeWithText(string(R.string.account_sign_out)).performClick()
        waitForText(string(R.string.signin_title))

        assertNull(keys.key)
    }

    // ---------------------------------------------------------------- appearance

    @Test
    @Config(qualifiers = "+de")
    fun `the sign-in screen in German`() {
        launch()
        waitForText(string(R.string.signin_title))
        assertEquals("the German resources are in effect", "Bei Mistral anmelden", string(R.string.signin_title))
        screenshot("08-sign-in-de")
    }

    @Test
    @Config(qualifiers = "+night")
    fun `the sign-in screen in dark mode`() {
        launch()
        waitForText(string(R.string.signin_title))
        screenshot("09-sign-in-dark")
    }
}
