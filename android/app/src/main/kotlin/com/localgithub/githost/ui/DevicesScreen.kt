package com.localgithub.githost.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.*
import com.localgithub.githost.ui.theme.GitHub
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.localgithub.githost.GitHostApp
import com.localgithub.githost.whitelist.Whitelist
import com.localgithub.githost.whitelist.WhitelistEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(navController: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as GitHostApp
    var entries by remember { mutableStateOf(listOf<WhitelistEntry>()) }
    var removeTarget by remember { mutableStateOf<WhitelistEntry?>(null) }
    var detailTarget by remember { mutableStateOf<WhitelistEntry?>(null) }

    fun reload() { entries = app.whitelist.list() }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("My Devices (${entries.size})") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = GitHub.CanvasSubtle)) },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("pair") }) {
                Icon(Icons.Default.Add, contentDescription = "Add device")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Devices,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No paired devices",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                        Text(
                            "Tap + to pair",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
            } else {
                LazyColumn {
                    items(entries) { entry ->
                        DeviceItem(entry = entry, onClick = { detailTarget = entry })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove device") },
            text = { Text("Remove \"${target.label}\"? It will no longer be able to connect.") },
            confirmButton = {
                TextButton(onClick = {
                    app.whitelist.removeByRawKey(target.rawKey)
                    reload()
                    removeTarget = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            }
        )
    }

    detailTarget?.let { target ->
        val fp = target.publicKey?.let { Whitelist.fingerprint(it) }
            ?: "…${target.rawKey.takeLast(12)}"
        AlertDialog(
            onDismissRequest = { detailTarget = null },
            title = { Text(target.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(fp, style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text(expiryText(target), style = MaterialTheme.typography.bodySmall)
                    Text("Full read and write access to all repositories.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            },
            confirmButton = {
                TextButton(onClick = { detailTarget = null }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = { detailTarget = null; removeTarget = target }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

private fun expiryText(entry: WhitelistEntry): String {
    if (entry.expiry == 0L) return "Permanent"
    val date = Instant.ofEpochSecond(entry.expiry)
        .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE)
    val expired = Instant.now().epochSecond > entry.expiry
    return if (expired) "Expired $date" else "Expires $date"
}

@Composable
private fun DeviceItem(entry: WhitelistEntry, onClick: () -> Unit) {
    val fp = entry.publicKey?.let { Whitelist.fingerprint(it) }
        ?: "…${entry.rawKey.takeLast(12)}"
    val isExpired = entry.expiry != 0L && Instant.now().epochSecond > entry.expiry

    ListItem(
        headlineContent = { Text(entry.label) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(fp, style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text(expiryText(entry), style = MaterialTheme.typography.labelSmall,
                    color = if (isExpired) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        },
        leadingContent = { Icon(Icons.Default.Devices, contentDescription = null) },
        trailingContent = {
            Icon(Icons.Default.ChevronRight, contentDescription = "Details",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        },
        modifier = Modifier.clickable { onClick() },
    )
}
