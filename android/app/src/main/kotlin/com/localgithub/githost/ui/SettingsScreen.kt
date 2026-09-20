package com.localgithub.githost.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import com.localgithub.githost.ui.theme.GitHub
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.localgithub.githost.service.GitHostService

private val IDLE_TIMEOUT_OPTIONS = listOf(
    0   to "Never",
    5   to "5 minutes",
    30  to "30 minutes",
    60  to "1 hour",
    240 to "4 hours",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("git_host_prefs", Context.MODE_PRIVATE)

    var portText by remember {
        mutableStateOf(prefs.getInt(GitHostService.PREF_PORT, GitHostService.DEFAULT_PORT).toString())
    }
    var portError by remember { mutableStateOf(false) }

    val savedMinutes = prefs.getInt(GitHostService.PREF_IDLE_TIMEOUT, 0)
    var selectedTimeout by remember {
        mutableStateOf(IDLE_TIMEOUT_OPTIONS.find { it.first == savedMinutes } ?: IDLE_TIMEOUT_OPTIONS[0])
    }
    var timeoutMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = GitHub.CanvasSubtle)) }) { padding ->
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
                        prefs.edit().putInt(GitHostService.PREF_PORT, p).apply()
                    }
                },
                label = { Text("SSH Port") },
                isError = portError,
                supportingText = {
                    if (portError) Text("Port must be 1024–65535")
                    else Text("Default: ${GitHostService.DEFAULT_PORT} (no root required)")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))

            ExposedDropdownMenuBox(
                expanded = timeoutMenuExpanded,
                onExpandedChange = { timeoutMenuExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = selectedTimeout.second,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Auto-stop server when idle") },
                    supportingText = { Text("Does not stop while app is open or a session is active") },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = timeoutMenuExpanded,
                    onDismissRequest = { timeoutMenuExpanded = false },
                ) {
                    IDLE_TIMEOUT_OPTIONS.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.second) },
                            onClick = {
                                selectedTimeout = option
                                timeoutMenuExpanded = false
                                prefs.edit().putInt(GitHostService.PREF_IDLE_TIMEOUT, option.first).apply()
                            },
                        )
                    }
                }
            }

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
