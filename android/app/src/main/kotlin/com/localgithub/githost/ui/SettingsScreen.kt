package com.localgithub.githost.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("git_host_prefs", Context.MODE_PRIVATE)

    var portText by remember {
        mutableStateOf(prefs.getInt("port", 2222).toString())
    }
    var portError by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Server", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            OutlinedTextField(
                value = portText,
                onValueChange = { v ->
                    portText = v
                    val p = v.toIntOrNull()
                    portError = p == null || p !in 1024..65535
                    if (!portError && p != null) {
                        prefs.edit().putInt("port", p).apply()
                    }
                },
                label = { Text("SSH Port") },
                isError = portError,
                supportingText = {
                    if (portError) Text("Port must be 1024–65535")
                    else Text("Default: 2222 (no root required)")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Text("About", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            ListItem(
                headlineContent = { Text("git-host") },
                supportingContent = { Text("v0.1.0 — LocalGitHub Android") },
            )
            ListItem(
                headlineContent = { Text("Behavior Contract") },
                supportingContent = {
                    Text("Compatible with the Go CLI (github.com/melker/git-host)")
                },
            )
        }
    }
}
