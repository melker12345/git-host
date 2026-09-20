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
import androidx.compose.material.icons.filled.Wifi
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.localgithub.githost.EventBus
import com.localgithub.githost.GitHostApp
import com.localgithub.githost.pairing.PairingRequest
import com.localgithub.githost.pairing.formatPin
import com.localgithub.githost.pairing.hostPair
import com.localgithub.githost.pairing.parseDevicePairingUri
import com.localgithub.githost.service.GitHostService
import com.localgithub.githost.ui.theme.GitHub
import com.localgithub.githost.whitelist.Whitelist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.PublicKey

private enum class PairState { IDLE, WAITING, APPROVAL, DONE, ERROR }

/** A key waiting for the owner's decision, whichever way it arrived. */
private data class Candidate(
    val publicKey: PublicKey,
    val suggestedLabel: String,
    /** Set for network pairing, where the peer is still waiting to be told the outcome. */
    val request: PairingRequest?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(navController: NavController) {
    val context = LocalContext.current
    val app = context.applicationContext as GitHostApp
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(PairState.IDLE) }
    var pin by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<Candidate?>(null) }
    var label by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }

    val port = context.getSharedPreferences("git_host_prefs", 0)
        .getInt(GitHostService.PREF_PORT, GitHostService.DEFAULT_PORT)

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
        offer(Candidate(scanned.publicKey, scanned.label, request = null))
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
            setPrompt("Scan the code shown by  git-host pair --qr")
            setBeepEnabled(false)
            setOrientationLocked(true)
            setCaptureActivity(PortraitCaptureActivity::class.java)
        })
    }

    fun startNetworkPairing() {
        state = PairState.WAITING
        scope.launch {
            Log.i("PairScreen", "Launching hostPair on port ${port + 1}")
            runCatching {
                hostPair(port + 1, onPin = { p -> pin = p })
            }.onSuccess { req ->
                offer(Candidate(req.publicKey, "", request = req))
            }.onFailure { e ->
                Log.e("PairScreen", "hostPair failed: ${e.message}", e)
                errorMsg = e.message ?: "Pairing failed"
                state = PairState.ERROR
            }
        }
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
                PairState.IDLE -> IdlePanel(onScan = ::startScan, onNetwork = ::startNetworkPairing)

                PairState.WAITING -> WaitingPanel(pin)

                PairState.APPROVAL -> ApprovalPanel(
                    fingerprint = candidate?.let { Whitelist.fingerprint(it.publicKey) } ?: "",
                    viaQr = candidate?.request == null,
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
                            c.request?.let { req -> scope.launch(Dispatchers.IO) { req.approve() } }
                        }
                        state = PairState.DONE
                    },
                    onDeny = {
                        candidate?.request?.let { req -> scope.launch(Dispatchers.IO) { req.deny() } }
                        candidate = null
                        state = PairState.IDLE; pin = ""; label = ""
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
private fun IdlePanel(onScan: () -> Unit, onNetwork: () -> Unit) {
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
        body = "Run  git-host pair --qr  on the other device and point the camera at the code. Works without discovery or a shared network.",
        button = "Scan QR code",
        onClick = onScan,
        primary = true,
    )
    Spacer(Modifier.height(16.dp))
    OptionCard(
        icon = { Icon(Icons.Default.Wifi, contentDescription = null, tint = GitHub.FgMuted) },
        title = "Pair over the network",
        body = "Run  git-host pair  on the other device. A 6-digit code appears on both screens; check that they match.",
        button = "Generate pairing code",
        onClick = onNetwork,
        primary = false,
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
            color = GitHub.Accent,
        )
        Spacer(Modifier.height(16.dp))
        Text("The other device must show the same code.\nExpires in 5 minutes.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = GitHub.FgMuted)
    }
    Spacer(Modifier.height(32.dp))
    CircularProgressIndicator(color = GitHub.Accent)
    Spacer(Modifier.height(12.dp))
    Text("Waiting for connection…", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ApprovalPanel(
    fingerprint: String,
    viaQr: Boolean,
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
                    if (viaQr) "Scanned from a QR code. Check it matches the fingerprint printed next to the code."
                    else "Compare with the fingerprint printed by git-host pair before approving.",
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
