package com.localgithub.githost

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import com.localgithub.githost.service.GitHostService
import com.localgithub.githost.ui.AppNavHost
import com.localgithub.githost.ui.theme.GitHostTheme

/**
 * The single activity. Asks for notification permission (Android 13+),
 * starts the server, and hosts the Compose UI. It also receives
 * githost://pair links and hands them to the pair screen via [EventBus].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        GitHostService.start(this)
        handlePairLink(intent)

        setContent {
            GitHostTheme {
                AppNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairLink(intent)
    }

    /** Queues a githost:// VIEW intent for the pair screen; other intents are ignored. */
    private fun handlePairLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && data.scheme == "githost") {
            EventBus.pendingPairUri.value = data.toString()
        }
    }
}
