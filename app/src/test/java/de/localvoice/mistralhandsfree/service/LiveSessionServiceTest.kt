package de.localvoice.mistralhandsfree.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.localvoice.mistralhandsfree.AppContainer
import de.localvoice.mistralhandsfree.HandsfreeApplication
import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.session.LiveState
import de.localvoice.mistralhandsfree.testing.InMemoryKeyStore
import de.localvoice.mistralhandsfree.testing.MistralStub
import de.localvoice.mistralhandsfree.testing.SilentEngines
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowPowerManager

/**
 * The foreground service is what keeps hands-free mode alive with the screen off.
 * Its job is small - show the state, hold a wake lock, go away when the session
 * ends - but nothing else exercises it, and a mistake here only shows on a phone
 * lying on a table.
 */
@RunWith(AndroidJUnit4::class)
class LiveSessionServiceTest {

    private val key = "good-key-0123456789abcdefABCDEF"

    private lateinit var app: HandsfreeApplication
    private lateinit var server: MockWebServer
    private lateinit var controller: ServiceController<LiveSessionService>

    private val session get() = app.container.liveSession
    private val service get() = controller.get()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply {
            dispatcher = MistralStub(key)
            start()
        }
        app.container = AppContainer(
            app,
            keyStore = InMemoryKeyStore(key),
            baseUrl = server.url("/v1/"),
            engines = SilentEngines(),
        )
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        controller = Robolectric.buildService(LiveSessionService::class.java).create()
    }

    @After
    fun tearDown() {
        session.stop()
        server.shutdown()
    }

    private fun startService(action: String? = null): Int {
        val intent = Intent(app, LiveSessionService::class.java).apply { this.action = action }
        return service.onStartCommand(intent, 0, 1)
    }

    private fun awaitUntil(timeoutMs: Long = 10_000, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun notificationTitle(): String? {
        val manager = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).getNotification(NOTIFICATION_ID) ?: return null
        return posted.extras.getString(Notification.EXTRA_TITLE)
    }

    @Test
    fun `shows a foreground notification and holds a wake lock while live mode runs`() {
        session.start()
        // The service is started in the same breath, so the state is already "preparing".
        assertEquals(LiveState.PREPARING, session.state.value)

        startService()

        assertTrue(shadowOf(service).isLastForegroundNotificationAttached)
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(wakeLock)
        assertTrue(wakeLock.isHeld)
    }

    @Test
    fun `the notification follows the state of the conversation`() {
        session.start()
        startService()

        val listening = app.getString(R.string.state_listening)
        awaitUntil(what = "the notification to say \"$listening\"") { notificationTitle() == listening }
        assertFalse("stopped by itself while listening", shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `is not restarted by the system after the process was killed`() {
        session.start()

        // A restart would find an empty session and could not start a foreground service
        // from the background anyway.
        assertEquals(Service.START_NOT_STICKY, startService())
    }

    @Test
    fun `stops itself when live mode ends`() {
        session.start()
        startService()
        awaitUntil(what = "listening") { session.state.value == LiveState.LISTENING }

        session.stop()

        awaitUntil(what = "the service to stop itself") { shadowOf(service).isStoppedBySelf }
        assertFalse(shadowOf(service).isLastForegroundNotificationAttached)
    }

    @Test
    fun `stops itself when it is started with nothing to do`() {
        // Live mode never started (say, the permission was refused): no lingering notification.
        startService()

        awaitUntil(what = "the service to stop itself") { shadowOf(service).isStoppedBySelf }
    }

    @Test
    fun `the Stop button in the notification ends live mode`() {
        session.start()
        startService()
        awaitUntil(what = "listening") { session.state.value == LiveState.LISTENING }

        val result = startService(LiveSessionService.ACTION_STOP)

        assertEquals(Service.START_NOT_STICKY, result)
        assertFalse(session.liveMode.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `swiping the app away ends live mode`() {
        session.start()
        startService()
        awaitUntil(what = "listening") { session.state.value == LiveState.LISTENING }

        service.onTaskRemoved(Intent())

        assertFalse(session.liveMode.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `lets go of the wake lock when it is destroyed`() {
        session.start()
        startService()
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(wakeLock.isHeld)

        controller.destroy()

        assertFalse(wakeLock.isHeld)
    }

    private companion object {
        const val NOTIFICATION_ID = 42
    }
}
