package com.localgithub.githost.ui

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.localgithub.githost.GitHostApp
import com.localgithub.githost.service.GitHostService
import com.localgithub.githost.service.GitHostService.Companion.ACTION_SERVER_STOPPED
import com.localgithub.githost.ssh.ACTION_GIT_EVENT
import java.net.NetworkInterface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReposScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as GitHostApp

    val prefs = remember { context.getSharedPreferences("git_host_prefs", 0) }
    var running by remember { mutableStateOf(isServiceRunning(context)) }
    var repos by remember { mutableStateOf(listOf<String>()) }

    fun refreshRepos() {
        repos = app.reposDir.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".git") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    LaunchedEffect(Unit) {
        refreshRepos()
        val shouldRun = prefs.getBoolean("server_running", false)
        if (shouldRun && !isServiceRunning(context)) {
            GitHostService.start(context)
        }
        running = isServiceRunning(context)
    }

    DisposableEffect(Unit) {
        val gitEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                refreshRepos()
            }
        }
        val stoppedReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                running = false
            }
        }
        val lbm = LocalBroadcastManager.getInstance(context)
        lbm.registerReceiver(gitEventReceiver, IntentFilter(ACTION_GIT_EVENT))
        lbm.registerReceiver(stoppedReceiver, IntentFilter(ACTION_SERVER_STOPPED))
        onDispose {
            lbm.unregisterReceiver(gitEventReceiver)
            lbm.unregisterReceiver(stoppedReceiver)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Repositories") },
                actions = {
                    IconButton(onClick = {
                        if (running) {
                            GitHostService.stop(context)
                            prefs.edit().putBoolean("server_running", false).apply()
                        } else {
                            GitHostService.start(context)
                            prefs.edit().putBoolean("server_running", true).apply()
                        }
                        running = !running
                    }) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = if (running) "Stop server" else "Start server",
                            tint = if (running) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (running) {
                ServerStatusBanner()
            }
            if (repos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                        Spacer(Modifier.height(12.dp))
                        Text("No repositories yet", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Text("Push to this host to create one",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }
            } else {
                LazyColumn {
                    items(repos) { repo ->
                        RepoItem(repo, getLocalIp(), 2222, context)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerStatusBanner() {
    val ip = getLocalIp()
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "SSH server running · $ip:2222",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RepoItem(name: String, ip: String, port: Int, context: Context) {
    val cloneUrl = "ssh://git@$ip:$port/${name}"
    var expanded by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(name) },
        supportingContent = {
            if (expanded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        cloneUrl,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("clone url", cloneUrl))
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL",
                            modifier = Modifier.size(16.dp))
                    }
                }
            }
        },
        leadingContent = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
        modifier = Modifier.clickable { expanded = !expanded },
    )
    HorizontalDivider()
}

@Suppress("DEPRECATION")
private fun isServiceRunning(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return am.getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == "com.localgithub.githost.service.GitHostService" }
}

private fun getLocalIp(): String {
    return try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
            ?.hostAddress ?: "localhost"
    } catch (e: Exception) {
        "localhost"
    }
}
