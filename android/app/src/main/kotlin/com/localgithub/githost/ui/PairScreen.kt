package com.localgithub.githost.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.localgithub.githost.GitHostApp
import com.localgithub.githost.pairing.formatPin
import com.localgithub.githost.pairing.hostPair
import com.localgithub.githost.whitelist.KeyType
import com.localgithub.githost.whitelist.Permission
import com.localgithub.githost.whitelist.Whitelist
import kotlinx.coroutines.launch
import java.security.PublicKey

private enum class PairState { IDLE, WAITING, APPROVAL, DONE, ERROR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(navController: NavController, defaultType: String = "device") {
    val context = LocalContext.current
    val app = context.applicationContext as GitHostApp
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(PairState.IDLE) }
    var pin by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var pendingKey by remember { mutableStateOf<PublicKey?>(null) }
    var label by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    var keyType by remember { mutableStateOf(if (defaultType == "friend") KeyType.FRIEND else KeyType.DEVICE) }
    var permission by remember { mutableStateOf(Permission.RW) }
    var selectedRepos by remember { mutableStateOf(setOf<String>()) }

    val port = context.getSharedPreferences("git_host_prefs", 0).getInt("port", 2222)
    val availableRepos = remember {
        app.reposDir.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".git") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add External Device") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                PairState.IDLE -> IdlePanel {
                    state = PairState.WAITING
                    scope.launch {
                        runCatching {
                            hostPair(port + 1, onPin = { p -> pin = p })
                        }.onSuccess { key ->
                            pendingKey = key
                            fingerprint = Whitelist.fingerprint(key)
                            state = PairState.APPROVAL
                        }.onFailure { e ->
                            errorMsg = e.message ?: "Pairing failed"
                            state = PairState.ERROR
                        }
                    }
                }

                PairState.WAITING -> WaitingPanel(pin)

                PairState.APPROVAL -> ApprovalPanel(
                    fingerprint = fingerprint,
                    label = label,
                    onLabelChange = { label = it },
                    keyType = keyType,
                    onKeyTypeChange = { keyType = it; selectedRepos = setOf() },
                    permission = permission,
                    onPermissionChange = { permission = it },
                    availableRepos = availableRepos,
                    selectedRepos = selectedRepos,
                    onRepoToggle = { repo ->
                        selectedRepos = if (repo in selectedRepos)
                            selectedRepos - repo else selectedRepos + repo
                    },
                    onApprove = {
                        pendingKey?.let { key ->
                            val repos = if (keyType == KeyType.DEVICE || selectedRepos.isEmpty())
                                setOf("*") else selectedRepos
                            app.whitelist.add(
                                key, label.ifBlank { "unknown" },
                                type = keyType, repos = repos, perm = permission,
                            )
                        }
                        state = PairState.DONE
                    },
                    onDeny = {
                        state = PairState.IDLE; pin = ""; label = ""
                        keyType = KeyType.DEVICE; permission = Permission.RW; selectedRepos = setOf()
                    }
                )

                PairState.DONE -> DonePanel { navController.popBackStack() }

                PairState.ERROR -> ErrorPanel(errorMsg) {
                    state = PairState.IDLE
                    pin = ""; errorMsg = ""
                }
            }
        }
    }
}

@Composable
private fun IdlePanel(onStart: () -> Unit) {
    Spacer(Modifier.height(40.dp))
    Text("Pair a new device", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text(
        "A 6-digit code will appear on this screen. The other device enters it to register their SSH key securely.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
    Spacer(Modifier.height(40.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
        Text("Generate Pairing Code")
    }
}

@Composable
private fun WaitingPanel(pin: String) {
    Spacer(Modifier.height(40.dp))
    Text("Pairing Code", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(20.dp))
    if (pin.isNotEmpty()) {
        Text(
            formatPin(pin),
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 6.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("Share this code with the device owner.\nExpires in 5 minutes.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
    Spacer(Modifier.height(32.dp))
    CircularProgressIndicator()
    Spacer(Modifier.height(12.dp))
    Text("Waiting for connection…", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ApprovalPanel(
    fingerprint: String,
    label: String,
    onLabelChange: (String) -> Unit,
    keyType: KeyType,
    onKeyTypeChange: (KeyType) -> Unit,
    permission: Permission,
    onPermissionChange: (Permission) -> Unit,
    availableRepos: List<String>,
    selectedRepos: Set<String>,
    onRepoToggle: (String) -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text("New connection request", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Key fingerprint", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(fingerprint, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        OutlinedTextField(
            value = label,
            onValueChange = onLabelChange,
            label = { Text("Label (e.g. \"Alice's laptop\")") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Access type", style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = keyType == KeyType.DEVICE,
                onClick = { onKeyTypeChange(KeyType.DEVICE) },
                label = { Text("My Device") },
            )
            FilterChip(
                selected = keyType == KeyType.FRIEND,
                onClick = { onKeyTypeChange(KeyType.FRIEND) },
                label = { Text("Friend") },
            )
        }

        if (keyType == KeyType.FRIEND) {
            Text("Permissions", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = permission == Permission.RW,
                    onClick = { onPermissionChange(Permission.RW) },
                    label = { Text("Read & Write") },
                )
                FilterChip(
                    selected = permission == Permission.RO,
                    onClick = { onPermissionChange(Permission.RO) },
                    label = { Text("Read only") },
                )
            }

            Text("Repository access", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            if (availableRepos.isEmpty()) {
                Text(
                    "No repos yet — friend will have access to all future repos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            } else {
                Text(
                    if (selectedRepos.isEmpty()) "All repos (no restriction)"
                    else "${selectedRepos.size} repo(s) selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                availableRepos.forEach { repo ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = repo in selectedRepos,
                            onCheckedChange = { onRepoToggle(repo) },
                        )
                        Text(repo, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text("Deny") }
            Button(onClick = onApprove, modifier = Modifier.weight(1f),
                enabled = label.isNotBlank()) { Text("Approve") }
        }
    }
}

@Composable
private fun DonePanel(onBack: () -> Unit) {
    Spacer(Modifier.height(60.dp))
    Text("✓", fontSize = 64.sp)
    Spacer(Modifier.height(16.dp))
    Text("Device approved", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text("The device can now push and pull over SSH.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center)
    Spacer(Modifier.height(32.dp))
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Spacer(Modifier.height(60.dp))
    Text("Pairing failed", style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(12.dp))
    Text(message, style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    Spacer(Modifier.height(32.dp))
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try Again") }
}
