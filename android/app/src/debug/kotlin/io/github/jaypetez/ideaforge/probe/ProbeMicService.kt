package io.github.jaypetez.ideaforge.probe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * A do-nothing foreground service of type `microphone`, for the probe's screen-off trial
 * (issue #89, and the assumption #92 builds DriveService on). It holds no audio itself: the
 * recognizer stays in [ProbeRunner], and the only question is whether the service's
 * foreground state keeps the recognizer hearing once the screen is off.
 *
 * API 34+ only lets a microphone FGS start while the app is visible and RECORD_AUDIO is held;
 * a refused start is logged rather than crashing the probe.
 */
class ProbeMicService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recognizer probe", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recognizer probe")
            .setContentText("Microphone foreground service is running")
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(this, ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            ProbeRunner.log("foreground service: startForeground(type=microphone) ok")
        } catch (e: Exception) {
            ProbeRunner.log("foreground service: startForeground refused: ${e.javaClass.simpleName}: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ProbeRunner.log("foreground service: destroyed")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "probe"
        private const val ID = 89

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ProbeMicService::class.java))
            } catch (e: Exception) {
                ProbeRunner.log("foreground service: startForegroundService threw ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProbeMicService::class.java))
        }
    }
}
