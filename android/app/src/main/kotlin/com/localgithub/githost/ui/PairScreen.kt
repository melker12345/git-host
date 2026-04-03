package com.localgithub.githost.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.localgithub.githost.GitHostApp
import com.localgithub.githost.pairing.formatPin
import com.localgithub.githost.pairing.hostPair
import com.localgithub.githost.whitelist.Whitelist
import kotlinx.coroutines.launch
import java.security.PublicKey

private enum class PairState { IDLE, WAITING, APPROVAL, DONE, ERROR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(navController: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as GitHostApp
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(PairState.IDLE) }
    var pin by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var pendingKey by remember { mutableStateOf<PublicKey?>(null) }
    var label by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }

    val port = context.getSharedPreferences("git_host_prefs", 0).getInt("port", 2222)

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
                    onApprove = {
                        pendingKey?.let { key ->
                            app.whitelist.add(key, label.ifBlank { "unknown" })
                        }
                        state = PairState.DONE
                    },
                    onDeny = { state = PairState.IDLE; pin = ""; label = "" }
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
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Spacer(Modifier.height(20.dp))
    Text("Device wants to connect", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(20.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Key fingerprint", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(fingerprint, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = label,
        onValueChange = onLabelChange,
        label = { Text("Label (e.g. \"Alice's laptop\")") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(24.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text("Deny") }
        Button(onClick = onApprove, modifier = Modifier.weight(1f),
            enabled = label.isNotBlank()) { Text("Approve") }
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
