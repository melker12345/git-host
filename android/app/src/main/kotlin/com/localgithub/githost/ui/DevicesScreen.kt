package com.localgithub.githost.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.localgithub.githost.GitHostApp
import com.localgithub.githost.whitelist.KeyType
import com.localgithub.githost.whitelist.Permission
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
    var allEntries by remember { mutableStateOf(listOf<WhitelistEntry>()) }
    var removeTarget by remember { mutableStateOf<WhitelistEntry?>(null) }
    var selectedTab by remember { mutableStateOf(0) }

    fun reload() { allEntries = app.whitelist.list() }
    LaunchedEffect(Unit) { reload() }

    val devices = allEntries.filter { it.type == KeyType.DEVICE }
    val friends = allEntries.filter { it.type == KeyType.FRIEND }
    val tabTitles = listOf("My Devices (${devices.size})", "Friends (${friends.size})")

    Scaffold(
        topBar = { TopAppBar(title = { Text("Access") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("pair") }) {
                Icon(Icons.Default.Add, contentDescription = "Add device or friend")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                tabTitles.forEachIndexed { i, title ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        text = { Text(title) },
                    )
                }
            }

            val currentList = if (selectedTab == 0) devices else friends

            if (currentList.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (selectedTab == 0) Icons.Default.DeviceUnknown else Icons.Default.Group,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (selectedTab == 0) "No paired devices" else "No friends added",
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
                    items(currentList) { entry ->
                        AccessItem(
                            entry = entry,
                            isFriend = selectedTab == 1,
                            onRemove = { removeTarget = entry },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove access") },
            text = { Text("Remove \"${target.label}\"? They will no longer be able to connect.") },
            confirmButton = {
                TextButton(onClick = {
                    target.publicKey
                        ?.let { Whitelist.fingerprint(it) }
                        ?.let { app.whitelist.remove(it) }
                    reload()
                    removeTarget = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun AccessItem(entry: WhitelistEntry, isFriend: Boolean, onRemove: () -> Unit) {
    val fp = entry.publicKey?.let { Whitelist.fingerprint(it) } ?: "(unknown)"
    val now = Instant.now().epochSecond
    val isExpired = entry.expiry != 0L && now > entry.expiry
    val expiryText = when {
        entry.expiry == 0L -> "Permanent"
        isExpired -> {
            val date = Instant.ofEpochSecond(entry.expiry)
                .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            "Expired $date"
        }
        else -> {
            val date = Instant.ofEpochSecond(entry.expiry)
                .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_LOCAL_DATE)
            "Expires $date"
        }
    }

    ListItem(
        headlineContent = { Text(entry.label) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(fp, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Text(expiryText, style = MaterialTheme.typography.labelSmall,
                    color = if (isExpired) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                if (isFriend) {
                    val permLabel = if (entry.perm == Permission.RO) "Read only" else "Read & Write"
                    val repoLabel = if (entry.repos == setOf("*")) "All repos"
                                   else entry.repos.joinToString(", ")
                    Text("$permLabel · $repoLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
        },
        leadingContent = {
            Icon(
                if (isFriend) Icons.Default.Group else Icons.Default.DeviceUnknown,
                contentDescription = null,
            )
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    )
}

