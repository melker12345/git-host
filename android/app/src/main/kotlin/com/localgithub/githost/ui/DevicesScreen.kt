package com.localgithub.githost.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    fun reload() { entries = app.whitelist.list() }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Devices") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("pair") }) {
                Icon(Icons.Default.Add, contentDescription = "Add device")
            }
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DeviceUnknown, contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    Spacer(Modifier.height(12.dp))
                    Text("No authorized devices",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Text("Tap + to pair a new device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(entries) { entry ->
                    DeviceItem(entry, onRemove = { removeTarget = entry })
                    HorizontalDivider()
                }
            }
        }
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove device") },
            text = { Text("Remove \"${target.label}\" from authorized devices?") },
            confirmButton = {
                TextButton(onClick = {
                    target.publicKey
                        ?.let { Whitelist.fingerprint(it) }
                        ?.let { app.whitelist.remove(it) }
                    reload()
                    removeTarget = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DeviceItem(entry: WhitelistEntry, onRemove: () -> Unit) {
    val fp = entry.publicKey?.let { Whitelist.fingerprint(it) } ?: "(unknown)"
    val expiry = when {
        entry.expiry == 0L -> "Permanent"
        Instant.now().epochSecond > entry.expiry -> {
            val date = Instant.ofEpochSecond(entry.expiry)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
            "Expired $date"
        }
        else -> {
            val date = Instant.ofEpochSecond(entry.expiry)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
            "Expires $date"
        }
    }
    ListItem(
        headlineContent = { Text(entry.label) },
        supportingContent = {
            Column {
                Text(fp, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text(expiry, style = MaterialTheme.typography.labelSmall,
                    color = if (entry.expiry != 0L && Instant.now().epochSecond > entry.expiry)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        },
        leadingContent = { Icon(Icons.Default.DeviceUnknown, contentDescription = null) },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    )
}

