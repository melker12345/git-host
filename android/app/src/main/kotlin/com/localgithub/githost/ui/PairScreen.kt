package com.localgithub.githost.ui

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.localgithub.githost.EventBus
import com.localgithub.githost.GitHostApp
import com.localgithub.githost.pairing.parseDevicePairingUri
import com.localgithub.githost.service.GitHostService
import com.localgithub.githost.ui.theme.GitHub
import com.localgithub.githost.whitelist.Whitelist
import java.security.PublicKey

private enum class PairState { IDLE, APPROVAL, DONE, ERROR }

/** A scanned key waiting for the owner's decision. */
private data class Candidate(val publicKey: PublicKey, val suggestedLabel: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(navController: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as GitHostApp
    var state by remember { mutableStateOf(PairState.IDLE) }
    var candidate by remember { mutableStateOf<Candidate?>(null) }
    var label by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }

    fun offer(c: Candidate) {
        candidate = c
        label = c.suggestedLabel
        state = PairState.APPROVAL
    }

    fun handleScanned(text: String) {
        val scanned = parseDevicePairingUri(text)
        if (scanned == null) {
            errorMsg = "That code is not a git-host pairing code."
            state = PairState.ERROR
            return
        }
        offer(Candidate(scanned.publicKey, scanned.label))
    }

    // A githost:// link opened from outside lands here.
    LaunchedEffect(Unit) {
        EventBus.pendingPairUri.collect { uri ->
            if (uri != null) {
                EventBus.pendingPairUri.value = null
                handleScanned(uri)
            }
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleScanned(it) }
    }

    fun startScan() {
        scanLauncher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the code shown by  git-host pair")
            setBeepEnabled(false)
            setOrientationLocked(true)
            setCaptureActivity(PortraitCaptureActivity::class.java)
        })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = GitHub.CanvasSubtle),
                title = { Text("Add Device") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                PairState.IDLE -> IdlePanel(onScan = ::startScan)

                PairState.APPROVAL -> ApprovalPanel(
                    fingerprint = candidate?.let { Whitelist.fingerprint(it.publicKey) } ?: "",
                    label = label,
                    onLabelChange = { label = it },
                    onApprove = {
                        val c = candidate
                        if (c != null) {
                            runCatching {
                                app.whitelist.add(c.publicKey, label)
                            }.onFailure { e ->
                                Log.e("PairScreen", "Whitelist add failed", e)
                            }
                        }
                        state = PairState.DONE
                    },
                    onDeny = {
                        candidate = null
                        state = PairState.IDLE; label = ""
                    }
                )

                PairState.DONE -> DonePanel { navController.popBackStack() }

                PairState.ERROR -> ErrorPanel(errorMsg) {
                    state = PairState.IDLE
                    errorMsg = ""
                }
            }
        }
    }
}

@Composable
private fun IdlePanel(onScan: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Text("Pair a new device", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        "Every paired device gets full access to all repositories on this host.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = GitHub.FgMuted,
    )
    Spacer(Modifier.height(32.dp))

    OptionCard(
        icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = GitHub.Accent) },
        title = "Scan QR code",
        body = "Run  git-host pair  on the other device and point the camera at the code it shows. The key never crosses the network.",
        button = "Scan QR code",
        onClick = onScan,
        primary = true,
    )
}

@Composable
private fun OptionCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    button: String,
    onClick: () -> Unit,
    primary: Boolean,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GitHub.CanvasSubtle),
        border = BorderStroke(1.dp, GitHub.Border),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = GitHub.FgMuted)
            Spacer(Modifier.height(14.dp))
            if (primary) {
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(button) }
            } else {
                OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, GitHub.Border)) { Text(button) }
            }
        }
    }
}

@Composable
private fun ApprovalPanel(
    fingerprint: String,
    label: String,
    onLabelChange: (String) -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(4.dp))
        Text("New device request", style = MaterialTheme.typography.headlineSmall)

        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = GitHub.CanvasSubtle),
            border = BorderStroke(1.dp, GitHub.Border),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Key fingerprint", style = MaterialTheme.typography.labelMedium, color = GitHub.FgMuted)
                Spacer(Modifier.height(4.dp))
                Text(fingerprint, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Check it matches the fingerprint printed next to the QR code before approving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GitHub.FgMuted,
                )
            }
        }

        OutlinedTextField(
            value = label,
            onValueChange = onLabelChange,
            label = { Text("Device name (e.g. laptop)") },
            supportingText = { Text("Spaces become dashes") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "This device gets full read and write access to every repository on this host.",
            style = MaterialTheme.typography.bodySmall,
            color = GitHub.FgMuted,
        )

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, GitHub.Border)) { Text("Deny") }
            Button(onClick = onApprove, modifier = Modifier.weight(1f),
                enabled = label.isNotBlank()) { Text("Approve") }
        }
    }
}

@Composable
private fun DonePanel(onBack: () -> Unit) {
    Spacer(Modifier.height(60.dp))
    Text("✓", fontSize = 64.sp, color = GitHub.Success)
    Spacer(Modifier.height(16.dp))
    Text("Device approved", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text("The device can now push and pull over SSH.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center, color = GitHub.FgMuted)
    Spacer(Modifier.height(32.dp))
    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Spacer(Modifier.height(60.dp))
    Text("Pairing failed", style = MaterialTheme.typography.headlineSmall, color = GitHub.Danger)
    Spacer(Modifier.height(12.dp))
    Text(message, style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center, color = GitHub.FgMuted)
    Spacer(Modifier.height(32.dp))
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try Again") }
}
