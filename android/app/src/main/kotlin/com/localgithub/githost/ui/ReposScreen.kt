package com.localgithub.githost.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localgithub.githost.GitHostApp
import com.localgithub.githost.pairing.formatPin
import com.localgithub.githost.pairing.hostPair
import com.localgithub.githost.service.GitHostService
import com.localgithub.githost.whitelist.Whitelist
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.security.PublicKey

private const val PAIRING_PORT = 2223

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReposScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as GitHostApp
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(false) }
    var repos by remember { mutableStateOf(listOf<String>()) }

    // --- Pairing state -------------------------------------------------------
    var pairingPin    by remember { mutableStateOf("") }
    var pairingPubkey by remember { mutableStateOf<PublicKey?>(null) }
    var pairingJob    by remember { mutableStateOf<Job?>(null) }
    var labelInput    by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        pairingPin = ""
        pairingPubkey = null
    }

    fun startPairing() {
        if (pairingPin.isNotEmpty() || pairingPubkey != null) return
        val job = scope.launch {
            runCatching {
                hostPair(PAIRING_PORT) { pin -> pairingPin = pin }
            }.onSuccess { pubkey ->
                pairingPubkey = pubkey
                labelInput = ""
            }.onFailure { e ->
                pairingPin = ""
                if (e !is kotlinx.coroutines.CancellationException) {
                    snackbarHostState.showSnackbar("Pairing failed: ${e.message}")
                }
            }
        }
        pairingJob = job
    }
    // -------------------------------------------------------------------------

    LaunchedEffect(running) {
        repos = app.reposDir.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".git") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Repositories") },
                actions = {
                    IconButton(onClick = { startPairing() }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Pair a device")
                    }
                    IconButton(onClick = {
                        if (running) GitHostService.stop(context)
                        else GitHostService.start(context)
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

        // --- PIN display dialog (waiting for device to connect) --------------
        if (pairingPin.isNotEmpty() && pairingPubkey == null) {
            AlertDialog(
                onDismissRequest = { cancelPairing() },
                title = { Text("Pair a Device") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                           modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "On your laptop, run:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                "git-host join ${formatPin(pairingPin)} \\\n  --host ${getLocalIp()}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            formatPin(pairingPin),
                            style = MaterialTheme.typography.displayMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Waiting for connection…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { cancelPairing() }) { Text("Cancel") }
                },
            )
        }

        // --- Approval dialog (device connected, waiting for owner decision) --
        if (pairingPubkey != null) {
            val pubkey = pairingPubkey!!
            val fingerprint = Whitelist.fingerprint(pubkey)
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Approve Device?") },
                text = {
                    Column {
                        Text(
                            "A device has connected and wants to be trusted.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            fingerprint,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = labelInput,
                            onValueChange = { labelInput = it },
                            label = { Text("Device label") },
                            placeholder = { Text("e.g. my-laptop") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = labelInput.isNotBlank(),
                        onClick = {
                            val label = labelInput.trim()
                            runCatching { app.whitelist.add(pubkey, label) }
                                .onSuccess {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("\"$label\" approved — they can now push.")
                                    }
                                }
                                .onFailure {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Failed to save key: ${it.message}")
                                    }
                                }
                            cancelPairing()
                        },
                    ) { Text("Approve") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        scope.launch { snackbarHostState.showSnackbar("Device denied.") }
                        cancelPairing()
                    }) { Text("Deny") }
                },
            )
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
