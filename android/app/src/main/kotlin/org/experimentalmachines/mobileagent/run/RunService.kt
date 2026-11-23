package org.experimentalmachines.mobileagent.run

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.experimentalmachines.mobileagent.MainActivity
import org.experimentalmachines.mobileagent.MobileAgentApp
import org.experimentalmachines.mobileagent.R
import org.experimentalmachines.mobileagent.agent.RunState

/**
 * Keeps a run going while other apps are in front (a foreground service) and shows its status in the notification,
 * with Stop and Pause / Resume.
 */
class RunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = MobileAgentApp.get(this).controller
        runCatching {
            startForeground(Notifier.ID, Notifier.build(this, controller.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.onFailure { Log.w(TAG, "startForeground: ${it.message}") }
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        scope.launch {
            controller.state.collect { s ->
                if (s !is RunState.Running) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    Notifier.update(this@RunService, s)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MobileAgent"

        /** Starts the foreground service; if Android refuses (app in the background), the notification still updates. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, RunService::class.java)) }
                .onFailure { Log.w(TAG, "foreground service not started: ${it.message}") }
        }
    }
}

/** Stop / Pause / Resume from the notification. */
class RunActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = MobileAgentApp.get(context).controller
        when (intent.action) {
            Notifier.ACTION_STOP -> c.stop()
            Notifier.ACTION_PAUSE -> c.setPaused(true)
            Notifier.ACTION_RESUME -> c.setPaused(false)
            Notifier.ACTION_ALLOW -> c.decide(org.experimentalmachines.mobileagent.agent.Decision.ALLOW)
            Notifier.ACTION_SKIP -> c.decide(org.experimentalmachines.mobileagent.agent.Decision.SKIP)
        }
    }
}

/** The run's notification: built from the run state, updated on every change. */
object Notifier {
    const val ID = 1
    const val CHANNEL = "run"
    const val ACTION_STOP = "org.experimentalmachines.mobileagent.action.STOP"
    const val ACTION_PAUSE = "org.experimentalmachines.mobileagent.action.PAUSE"
    const val ACTION_RESUME = "org.experimentalmachines.mobileagent.action.RESUME"
    const val ACTION_ALLOW = "org.experimentalmachines.mobileagent.action.ALLOW"
    const val ACTION_SKIP = "org.experimentalmachines.mobileagent.action.SKIP"

    fun channel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.channel_run), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun action(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(action).setPackage(context.packageName).setClass(context, RunActionReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun build(context: Context, s: RunState): Notification {
        channel(context)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_mark).setContentIntent(open).setShowWhen(false)
        when (s) {
            is RunState.Running -> {
                b.setContentTitle(if (s.paused) "Paused · step ${s.step} of ${s.maxSteps}" else "Step ${s.step} of ${s.maxSteps} · ${s.phase}")
                    .setContentText(s.confirm?.let { "Waiting for you: $it" } ?: s.intent ?: s.goal)
                    .setOngoing(true)
                if (s.confirm != null) {
                    b.addAction(Notification.Action.Builder(null, "Allow", action(context, ACTION_ALLOW, 4)).build())
                        .addAction(Notification.Action.Builder(null, "Skip", action(context, ACTION_SKIP, 5)).build())
                        .addAction(Notification.Action.Builder(null, "Stop", action(context, ACTION_STOP, 1)).build())
                } else {
                    b.addAction(Notification.Action.Builder(null, "Stop", action(context, ACTION_STOP, 1)).build())
                        .addAction(
                            if (s.paused) Notification.Action.Builder(null, "Resume", action(context, ACTION_RESUME, 2)).build()
                            else Notification.Action.Builder(null, "Pause", action(context, ACTION_PAUSE, 3)).build(),
                        )
                }
            }
            is RunState.Finished -> b.setContentTitle("Done · ${s.status} · ${s.steps} steps")
                .setContentText(s.answer ?: s.error ?: s.goal).setAutoCancel(true)
            RunState.Idle -> b.setContentTitle("Mobile Agent").setContentText("Idle")
        }
        return b.build()
    }

    fun update(context: Context, s: RunState) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (s == RunState.Idle) nm.cancel(ID) else runCatching { nm.notify(ID, build(context, s)) }
    }
}
