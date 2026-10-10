package io.github.jaypetez.ideaforge.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.jaypetez.ideaforge.IdeaForgeApp
import io.github.jaypetez.ideaforge.R
import io.github.jaypetez.ideaforge.core.runtime.DrivePhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The microphone foreground service that keeps a hands-free drive hearing.
 *
 * The #89 emulator spike measured why it exists: with the screen off and no microphone
 * foreground service, every recogniser session started afterwards is silenced by the system,
 * with no error at all (level pinned at -2.0, an ordinary NO_MATCH, restarts continuing). With
 * the service, recognition keeps hearing.
 *
 * What it is not: it does not own the drive and never starts the microphone. The loop lives in
 * [DriveController] (the app graph); this service only holds the process in the foreground
 * while a drive is attached, shows the ongoing notification, and turns its Pause/Resume and
 * Exit actions into the same controller calls the stage's buttons make. It is started only by
 * [DriveController.start] / [DriveController.resume], which are user taps on the visible app
 * (the platform refuses a microphone foreground service started from the background), and it
 * stops itself the moment the controller has no run: Exit, done, or a fatal stand-down.
 * START_NOT_STICKY: after process death it is not restarted, so nothing reopens the mic.
 */
class DriveService : Service() {
    private val scope = MainScope()
    private var watch: Job? = null
    private var foreground = false

    private val drive: DriveController get() = (application as IdeaForgeApp).graph.driveController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> drive.pause()
            ACTION_RESUME -> drive.resume()
            ACTION_EXIT -> drive.exit()
        }
        val state = drive.state.value
        if (!state.attached) {
            // An action from a stale notification, or Exit just now: nothing to keep alive.
            // A service started for the foreground must still enter it before stopping.
            if (intent?.action == ACTION_START) enterForeground(state)
            stop()
            return START_NOT_STICKY
        }
        enterForeground(state)
        if (watch == null) {
            watch = scope.launch {
                drive.state.collect { s ->
                    if (!s.attached) stop() else notifyState(s)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(state: VoiceStageState) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, state), type)
        foreground = true
    }

    private fun notifyState(state: VoiceStageState) {
        if (!foreground) return
        // Without the permission (API 33+) the update is dropped; the stage still has the controls.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(this, state))
    }

    private fun stop() {
        watch?.cancel()
        watch = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "drive"
        const val NOTIFICATION_ID = 92
        const val ACTION_START = "io.github.jaypetez.ideaforge.drive.START"
        const val ACTION_PAUSE = "io.github.jaypetez.ideaforge.drive.PAUSE"
        const val ACTION_RESUME = "io.github.jaypetez.ideaforge.drive.RESUME"
        const val ACTION_EXIT = "io.github.jaypetez.ideaforge.drive.EXIT"

        /** The Android [DriveServiceControl]. */
        fun control(context: Context): DriveServiceControl = object : DriveServiceControl {
            private val app = context.applicationContext
            override fun start() {
                ContextCompat.startForegroundService(app, Intent(app, DriveService::class.java).setAction(ACTION_START))
            }
            override fun stop() {
                app.stopService(Intent(app, DriveService::class.java))
            }
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.drive_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.drive_channel_description)
                    setShowBadge(false)
                },
            )
        }

        /** An action the notification sends back to this service. */
        fun action(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context, action.hashCode(), Intent(context, DriveService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        internal fun statusText(context: Context, state: VoiceStageState): String = when {
            state.mode == VoiceMode.PAUSED -> context.getString(R.string.drive_status_paused)
            state.mode == VoiceMode.BLOCKED -> state.detail
            state.writing -> context.getString(R.string.drive_status_writing)
            state.deaf -> context.getString(R.string.voice_deaf)
            state.phase == DrivePhase.SPEAKING -> context.getString(R.string.drive_status_speaking)
            state.phase == DrivePhase.LISTENING -> context.getString(R.string.drive_status_listening)
            state.phase == DrivePhase.PROCESSING -> context.getString(R.string.drive_status_thinking)
            else -> context.getString(R.string.drive_status_starting)
        }

        fun notification(context: Context, state: VoiceStageState): Notification {
            ensureChannel(context)
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val paused = state.mode == VoiceMode.PAUSED || state.mode == VoiceMode.BLOCKED
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.drive_notification_title))
                .setContentText(statusText(context, state))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(
                    0,
                    context.getString(if (paused) R.string.voice_resume else R.string.voice_pause),
                    action(context, if (paused) ACTION_RESUME else ACTION_PAUSE),
                )
                .addAction(0, context.getString(R.string.voice_exit), action(context, ACTION_EXIT))
                .build()
        }
    }
}
