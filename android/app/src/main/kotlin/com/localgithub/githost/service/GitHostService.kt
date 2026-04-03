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
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.localgithub.githost.MainActivity
import com.localgithub.githost.R
import com.localgithub.githost.mdns.MdnsAdvertiser
import com.localgithub.githost.ssh.GitSshServer
import com.localgithub.githost.whitelist.Whitelist
import java.io.File

private const val TAG = "GitHostService"
private const val CHANNEL_ID = "git_host_server"
private const val NOTIF_ID = 1

class GitHostService : Service() {

    private var sshServer: GitSshServer? = null
    private var mdnsAdvertiser: MdnsAdvertiser? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // startForeground MUST be called within 5 s of startForegroundService().
                // Do it immediately here before any heavy work.
                val port = prefs().getInt(PREF_PORT, 2222)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, buildNotification(port),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    startForeground(NOTIF_ID, buildNotification(port))
                }
                Thread { startServer() }.start()
            }
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (sshServer?.isRunning == true) return

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
        )

        try {
            server.start()
            sshServer = server
            Log.i(TAG, "SSH server started — port=$port baseDir=${baseDir.absolutePath}")
        } catch (e: Exception) {
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
        stopServer()
        super.onDestroy()
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

    private fun ensureChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "git-host Server", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Persistent SSH server notification" }
        )
    }

    private fun prefs() = getSharedPreferences("git_host_prefs", Context.MODE_PRIVATE)

    companion object {
        const val ACTION_START = "com.localgithub.githost.START"
        const val ACTION_STOP  = "com.localgithub.githost.STOP"

        const val PREF_PORT    = "port"
        const val PREF_BASE_DIR = "base_dir"

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
