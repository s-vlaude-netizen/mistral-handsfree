package de.localvoice.mistralhandsfree.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import de.localvoice.mistralhandsfree.HandsfreeApplication
import de.localvoice.mistralhandsfree.MainActivity
import de.localvoice.mistralhandsfree.R
import de.localvoice.mistralhandsfree.session.LiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps live mode alive when the screen is off or the app is in the background -
 * which is exactly the case this app is for.
 *
 * The conversation itself runs in the application's
 * [de.localvoice.mistralhandsfree.session.LiveSessionController]; the service
 * shows the state and stops the system from collecting the process. It also
 * holds a partial wake lock: a phone lying on a table with the screen off would
 * otherwise doze off in the middle of a conversation.
 */
class LiveSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            controller().stop()
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground(getString(R.string.app_name), getString(R.string.state_preparing))
        acquireWakeLock()

        if (watcher == null) {
            watcher = scope.launch {
                controller().state.collectLatest { state ->
                    if (state == LiveState.IDLE) {
                        stopForegroundCompat()
                        stopSelf()
                    } else {
                        notify(label(state), controller().statusDetail.value)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watcher = null
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Without a screen, nobody should be listened to either.
        controller().stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun controller() = (application as HandsfreeApplication).container.liveSession

    private fun label(state: LiveState): String = getString(
        when (state) {
            LiveState.IDLE -> R.string.state_idle
            LiveState.PREPARING -> R.string.state_preparing
            LiveState.LISTENING -> R.string.state_listening
            LiveState.THINKING -> R.string.state_thinking
            LiveState.SPEAKING -> R.string.state_speaking
        },
    )

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:live").apply {
            setReferenceCounted(false)
            // A safety net: a forgotten live mode must not drain the battery for good.
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun startInForeground(title: String, text: String) {
        val notification = buildNotification(title, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LiveSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text.ifEmpty { getString(R.string.app_name) })
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_stop), stop)
            .build()
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "live_session"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "de.localvoice.mistralhandsfree.action.STOP"
        private const val WAKE_LOCK_TIMEOUT_MS = 3L * 60 * 60 * 1000

        fun start(context: Context) {
            val intent = Intent(context, LiveSessionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveSessionService::class.java))
        }
    }
}
