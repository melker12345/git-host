package com.localgithub.githost.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.localgithub.githost.EventBus
import com.localgithub.githost.MainActivity
import com.localgithub.githost.R
import com.localgithub.githost.mdns.MdnsAdvertiser
import com.localgithub.githost.ssh.GitSshServer
import com.localgithub.githost.whitelist.Whitelist
import java.io.File

private const val TAG = "GitHostService"
private const val CHANNEL_ID = "git_host_server"
private const val EVENT_CHANNEL_ID = "git_host_events"
private const val NOTIF_ID = 1
private const val EVENT_SUMMARY_ID = 2
private const val EVENT_ID_BASE = 1000
private const val EVENT_GROUP = "git_host_activity"

/**
 * Foreground service that owns the SSH server and its mDNS advertisement,
 * and turns git events into activity notifications and last-push records.
 *
 * Everything is driven by intent actions (see the companion object):
 * [ACTION_START] / [ACTION_STOP] from the UI and the notification, and
 * [ACTION_SESSION_OPEN] / [ACTION_SESSION_CLOSE] from each git command, which
 * keep a count of live sessions so the optional idle timeout never stops the
 * server mid-transfer.
 */
class GitHostService : Service() {

    private var sshServer: GitSshServer? = null
    private var mdnsAdvertiser: MdnsAdvertiser? = null
    @Volatile private var starting = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gitEventsJob: Job? = null

    private val activeSessions = AtomicInteger(0)
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { onIdleTimeout() }

    override fun onCreate() {
        super.onCreate()
        gitEventsJob = serviceScope.launch {
            EventBus.gitEvents.collect { event ->
                handleGitEvent(event)
            }
        }
    }

    /** Dispatches on the intent action; see the class comment. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SESSION_OPEN -> {
                activeSessions.incrementAndGet()
                idleHandler.removeCallbacks(idleRunnable)
                Log.d(TAG, "Session opened  active=${activeSessions.get()}")
                return START_STICKY
            }
            ACTION_SESSION_CLOSE -> {
                val remaining = activeSessions.decrementAndGet().coerceAtLeast(0)
                activeSessions.set(remaining)
                Log.d(TAG, "Session closed  active=$remaining")
                scheduleIdleTimeout()
                return START_STICKY
            }
            ACTION_START -> {
                val port = prefs().getInt(PREF_PORT, DEFAULT_PORT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, buildNotification(port),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    startForeground(NOTIF_ID, buildNotification(port))
                }
                CoroutineScope(Dispatchers.IO + SupervisorJob()).launch { startServer() }
                scheduleIdleTimeout()
            }
            ACTION_STOP -> {
                idleHandler.removeCallbacks(idleRunnable)
                stopServer()
            }
        }
        return START_STICKY
    }

    /**
     * Starts the SSH server (blocking; runs on an IO coroutine) and then
     * advertises it over mDNS. A no-op if it is already running or starting.
     * A port still held by a previous instance is retried once after 2 s;
     * any other failure stops the service.
     */
    @Synchronized
    private fun startServer() {
        if (sshServer?.isRunning == true || starting) return
        starting = true

        // MINA SSHD's ServerBuilder static initializer reads user.home to locate
        // ~/.ssh/authorized_keys.  On Android the property is unset → crash.
        System.setProperty("user.home", filesDir.absolutePath)

        val port    = prefs().getInt(PREF_PORT, DEFAULT_PORT)
        val baseDir = File(prefs().getString(PREF_BASE_DIR, null)
            ?: File(filesDir, "repos").absolutePath)
        val cfgDir  = File(filesDir, "config")
        val hostKeyFile = File(cfgDir, "host_key")
        val whitelistFile = File(cfgDir, "whitelist")

        baseDir.mkdirs()
        cfgDir.mkdirs()

        val whitelist = Whitelist(whitelistFile)
        val server = GitSshServer(
            port = port,
            baseDir = baseDir,
            whitelist = whitelist,
            hostKeyFile = hostKeyFile,
            context = this,
        )

        try {
            server.start()
            sshServer = server
            starting = false
            prefs().edit().putBoolean(PREF_SERVICE_RUNNING, true).apply()
            Log.i(TAG, "SSH server started — port=$port baseDir=${baseDir.absolutePath}")
        } catch (e: java.net.BindException) {
            starting = false
            Log.w(TAG, "Bind failed (port in use?), retrying in 2s", e)
            Thread.sleep(2000)
            startServer() // single retry
            return
        } catch (e: Exception) {
            starting = false
            Log.e(TAG, "Failed to start SSH server", e)
            stopSelf()
            return
        }

        val advertiser = MdnsAdvertiser(this)
        advertiser.advertise(port)
        mdnsAdvertiser = advertiser
    }

    /** Stops the server and advertisement and takes the service out of the foreground. */
    private fun stopServer() {
        sshServer?.stop()
        sshServer = null
        mdnsAdvertiser?.stop()
        mdnsAdvertiser = null
        prefs().edit().putBoolean(PREF_SERVICE_RUNNING, false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Service stopped")
    }

    override fun onDestroy() {
        idleHandler.removeCallbacks(idleRunnable)
        gitEventsJob?.cancel()
        gitEventsJob = null
        serviceScope.cancel()
        sshServer?.stop()
        sshServer = null
        mdnsAdvertiser?.stop()
        mdnsAdvertiser = null
        super.onDestroy()
    }

    /** (Re)arms the idle timer from the current preference; 0 minutes means never. */
    private fun scheduleIdleTimeout() {
        idleHandler.removeCallbacks(idleRunnable)
        val minutes = prefs().getInt(PREF_IDLE_TIMEOUT, 0)
        if (minutes <= 0) return
        idleHandler.postDelayed(idleRunnable, minutes * 60_000L)
        Log.d(TAG, "Idle timeout scheduled in ${minutes}min")
    }

    /**
     * Stops the server when the idle timer fires, unless a session is active
     * or the app is on screen, in which case the check is retried shortly.
     */
    private fun onIdleTimeout() {
        if (activeSessions.get() > 0) {
            Log.d(TAG, "Idle timeout fired but sessions active — deferring")
            idleHandler.postDelayed(idleRunnable, 30_000L)
            return
        }
        if (isAppInForeground()) {
            Log.d(TAG, "Idle timeout fired but app is in foreground — deferring")
            idleHandler.postDelayed(idleRunnable, 60_000L)
            return
        }
        Log.i(TAG, "Idle timeout: stopping server")
        prefs().edit().putBoolean(PREF_SERVICE_RUNNING, false).apply()
        postEventNotification(key = "server", title = "SSH server stopped",
            text = "Stopped after the idle timeout. Open git-host to start it again.")
        EventBus.emitServerStopped()
        stopServer()
    }

    private fun isAppInForeground(): Boolean {
        return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------

    /**
     * Records a push on the repository's card and tells the user about it.
     * Only pushes that changed something reach here (see ReceivePackCommand),
     * so a routine sync of unchanged repositories stays silent.
     */
    private fun handleGitEvent(event: EventBus.GitEvent) {
        val repo = event.repo.removeSuffix(".git")
        when (event.type) {
            EventBus.GitEvent.PUSH -> {
                val push = event.push ?: return
                LastPush.record(prefs(), event)
                val details = push.details(event.bytes)
                Log.i(TAG, "push from ${event.label} to $repo: ${push.headline()} $details")
                postEventNotification(
                    key = event.repo,
                    title = "${event.label} pushed to $repo",
                    text = listOf(push.headline(), details).filter { it.isNotEmpty() }.joinToString(" · "),
                )
            }
            EventBus.GitEvent.PULL ->
                postEventNotification(key = event.repo, title = "${event.label} fetched $repo", text = null)
        }
    }

    /** The ongoing foreground notification, with a Stop action. */
    private fun buildNotification(port: Int): Notification {
        ensureChannel()
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, GitHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle("git-host running")
            .setContentText("SSH server active on port $port")
            .setContentIntent(tapIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * Posts an activity notification. Notifications share one id per
     * repository ([key]), so repeated pushes to a repo replace each other
     * instead of piling up, and they are grouped so a sync touching many
     * repositories collapses into one entry.
     */
    private fun postEventNotification(key: String, title: String, text: String?) {
        ensureEventChannel()
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, EVENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setContentIntent(tapIntent)
            .setGroup(EVENT_GROUP)
            .setAutoCancel(true)
            .build()
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Ids 1 and 2 belong to the server and group summary notifications.
        mgr.notify(EVENT_ID_BASE + (key.hashCode() and 0xFFFFFF), notif)
        mgr.notify(EVENT_SUMMARY_ID, NotificationCompat.Builder(this, EVENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle("git-host activity")
            .setContentIntent(tapIntent)
            .setGroup(EVENT_GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build())
    }

    /** Creates the low-importance channel for the ongoing server notification. */
    private fun ensureChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "git-host Server", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Persistent SSH server notification" }
        )
    }

    /** Creates the channel for push/pull activity notifications. */
    private fun ensureEventChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(EVENT_CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(EVENT_CHANNEL_ID, "git-host Activity", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Push and pull activity notifications" }
        )
    }

    private fun prefs() = getSharedPreferences("git_host_prefs", Context.MODE_PRIVATE)

    companion object {
        const val ACTION_START          = "com.localgithub.githost.START"
        const val ACTION_STOP           = "com.localgithub.githost.STOP"
        const val ACTION_SESSION_OPEN   = "com.localgithub.githost.SESSION_OPEN"
        const val ACTION_SESSION_CLOSE  = "com.localgithub.githost.SESSION_CLOSE"

        const val PREF_PORT         = "port"

        /** Debug builds install side by side with the release app, so they take another port. */
        val DEFAULT_PORT: Int = if (com.localgithub.githost.BuildConfig.DEBUG) 2322 else 2222
        const val PREF_BASE_DIR     = "base_dir"
        const val PREF_IDLE_TIMEOUT = "idle_timeout_minutes"
        const val PREF_SERVICE_RUNNING = "service_running"

        /**
         * Whether the server was last seen running. Read from preferences so
         * it can be asked without binding to the service.
         */
        fun isRunning(context: Context): Boolean {
            return context.getSharedPreferences("git_host_prefs", Context.MODE_PRIVATE)
                .getBoolean(PREF_SERVICE_RUNNING, false)
        }

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, GitHostService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GitHostService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
