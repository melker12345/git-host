package com.localgithub.githost.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.concurrent.atomic.AtomicInteger
import com.localgithub.githost.MainActivity
import com.localgithub.githost.R
import com.localgithub.githost.mdns.MdnsAdvertiser
import com.localgithub.githost.ssh.ACTION_GIT_EVENT
import com.localgithub.githost.ssh.EXTRA_ADDED
import com.localgithub.githost.ssh.EXTRA_BRANCH
import com.localgithub.githost.ssh.EXTRA_BYTES
import com.localgithub.githost.ssh.EXTRA_DELETED
import com.localgithub.githost.ssh.EXTRA_DEVICE_LABEL
import com.localgithub.githost.ssh.EXTRA_EVENT_TYPE
import com.localgithub.githost.ssh.EXTRA_MODIFIED
import com.localgithub.githost.ssh.EXTRA_REPO_NAME
import com.localgithub.githost.ssh.GitSshServer
import com.localgithub.githost.whitelist.Whitelist
import java.io.File

private const val TAG = "GitHostService"
private const val CHANNEL_ID = "git_host_server"
private const val EVENT_CHANNEL_ID = "git_host_events"
private const val NOTIF_ID = 1

class GitHostService : Service() {

    private var sshServer: GitSshServer? = null
    private var mdnsAdvertiser: MdnsAdvertiser? = null
    @Volatile private var starting = false

    private val activeSessions = AtomicInteger(0)
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { onIdleTimeout() }

    private val gitEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val type  = intent.getStringExtra(EXTRA_EVENT_TYPE) ?: return
            val repo  = intent.getStringExtra(EXTRA_REPO_NAME)  ?: return
            val label = intent.getStringExtra(EXTRA_DEVICE_LABEL) ?: "unknown"
            val repoDisplay = repo.removeSuffix(".git")
            val msg = when (type) {
                "push" -> {
                    val branch   = intent.getStringExtra(EXTRA_BRANCH) ?: "unknown"
                    val bytes    = intent.getLongExtra(EXTRA_BYTES, -1L)
                    val added    = intent.getIntExtra(EXTRA_ADDED, 0)
                    val modified = intent.getIntExtra(EXTRA_MODIFIED, 0)
                    val deleted  = intent.getIntExtra(EXTRA_DELETED, 0)
                    val sizeStr  = if (bytes >= 0) formatBytes(bytes) else "?"
                    "$label → $repoDisplay/$branch  [$added, $modified, $deleted]  $sizeStr"
                }
                "pull" -> "$label cloned $repoDisplay"
                else   -> "$label: $type on $repoDisplay"
            }
            Log.i(TAG, "GIT_EVENT received: $msg")
            postEventNotification(msg)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(gitEventReceiver, IntentFilter(ACTION_GIT_EVENT))

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
                val port = prefs().getInt(PREF_PORT, 2222)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, buildNotification(port),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    startForeground(NOTIF_ID, buildNotification(port))
                }
                Thread { startServer() }.start()
                scheduleIdleTimeout()
            }
            ACTION_STOP -> {
                idleHandler.removeCallbacks(idleRunnable)
                stopServer()
            }
        }
        return START_STICKY
    }

    @Synchronized
    private fun startServer() {
        if (sshServer?.isRunning == true || starting) return
        starting = true

        // MINA SSHD's ServerBuilder static initializer reads user.home to locate
        // ~/.ssh/authorized_keys.  On Android the property is unset → crash.
        System.setProperty("user.home", filesDir.absolutePath)

        val port    = prefs().getInt(PREF_PORT, 2222)
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

    private fun stopServer() {
        sshServer?.stop()
        sshServer = null
        mdnsAdvertiser?.stop()
        mdnsAdvertiser = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Service stopped")
    }

    override fun onDestroy() {
        idleHandler.removeCallbacks(idleRunnable)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(gitEventReceiver)
        sshServer?.stop()
        sshServer = null
        mdnsAdvertiser?.stop()
        mdnsAdvertiser = null
        super.onDestroy()
    }

    private fun scheduleIdleTimeout() {
        idleHandler.removeCallbacks(idleRunnable)
        val minutes = prefs().getInt(PREF_IDLE_TIMEOUT, 0)
        if (minutes <= 0) return
        idleHandler.postDelayed(idleRunnable, minutes * 60_000L)
        Log.d(TAG, "Idle timeout scheduled in ${minutes}min")
    }

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
        prefs().edit().putBoolean("server_running", false).apply()
        postEventNotification("SSH server stopped (idle timeout)")
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(ACTION_SERVER_STOPPED))
        stopServer()
    }

    private fun isAppInForeground(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = am.getRunningTasks(1)
        return tasks.isNotEmpty() &&
            tasks[0].topActivity?.packageName == packageName
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------

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

    private fun postEventNotification(msg: String) {
        ensureEventChannel()
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, EVENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle("git-host")
            .setContentText(msg)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun ensureChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "git-host Server", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Persistent SSH server notification" }
        )
    }

    private fun ensureEventChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(EVENT_CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(EVENT_CHANNEL_ID, "git-host Activity", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Push and pull activity notifications" }
        )
    }

    private fun prefs() = getSharedPreferences("git_host_prefs", Context.MODE_PRIVATE)

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024        -> "${bytes} B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else                -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
    }

    companion object {
        const val ACTION_START          = "com.localgithub.githost.START"
        const val ACTION_STOP           = "com.localgithub.githost.STOP"
        const val ACTION_SESSION_OPEN   = "com.localgithub.githost.SESSION_OPEN"
        const val ACTION_SESSION_CLOSE  = "com.localgithub.githost.SESSION_CLOSE"
        const val ACTION_SERVER_STOPPED = "com.localgithub.githost.SERVER_STOPPED"

        const val PREF_PORT         = "port"
        const val PREF_BASE_DIR     = "base_dir"
        const val PREF_IDLE_TIMEOUT = "idle_timeout_minutes"

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
