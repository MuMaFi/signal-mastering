package app.signal.isolate.work

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.signal.isolate.MainActivity
import app.signal.isolate.R
import app.signal.isolate.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and visible while a track is being separated.
 *
 * Separation is minutes of sustained CPU; without a foreground service Android would be
 * free to kill the process the moment the user leaves the app.
 */
class SeparationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground(notification("Preparing…", null))
        scope.launch {
            SeparationController.state.collectLatest { state ->
                if (!state.isRunning) {
                    stopSelf()
                    return@collectLatest
                }
                notify(state)
            }
        }
    }

    private fun notify(state: SeparationState) {
        val (text, progress) = when (state) {
            is SeparationState.Downloading -> {
                val done = ModelManager.format(state.done)
                val total = ModelManager.format(state.total)
                "Downloading model · $done / $total" to (state.fraction * 100).toInt()
            }
            is SeparationState.Decoding -> "Decoding audio" to (state.fraction * 100).toInt()
            is SeparationState.Separating -> {
                val eta = state.secondsRemaining
                val suffix = if (eta != null && eta > 0) " · ~${formatEta(eta)} left" else ""
                "Separating ${state.chunk}/${state.chunks}$suffix" to (state.fraction * 100).toInt()
            }
            SeparationState.Finalizing -> "Writing files" to 100
            else -> "Working" to null
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text, progress))
    }

    private fun notification(text: String, progress: Int?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .apply {
                if (progress != null) setProgress(100, progress.coerceIn(0, 100), false)
                else setProgress(0, 0, true)
            }
            .build()
    }

    private fun startInForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "separation"
        private const val NOTIFICATION_ID = 1

        fun formatEta(seconds: Long): String = when {
            seconds >= 3600 -> "%dh %02dm".format(seconds / 3600, (seconds % 3600) / 60)
            seconds >= 60 -> "%dm %02ds".format(seconds / 60, seconds % 60)
            else -> "${seconds}s"
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SeparationService::class.java))
        }
    }
}
