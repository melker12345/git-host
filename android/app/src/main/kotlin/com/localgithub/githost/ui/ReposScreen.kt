package com.localgithub.githost.ui

import android.content.SharedPreferences
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localgithub.githost.EventBus
import com.localgithub.githost.GitHostApp
import com.localgithub.githost.service.GitHostService
import com.localgithub.githost.service.LastPush
import com.localgithub.githost.ui.theme.GitHub
import com.localgithub.githost.util.formatBytes
import com.localgithub.githost.util.plural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.NetworkInterface

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

/**
 * The home screen: the server on/off switch and status, and one expandable
 * card per bare repository held under [GitHostApp.reposDir].
 *
 * The list is re-read whenever a git event arrives on [EventBus], so a push
 * that creates a repository or changes its last-push line shows up at once.
 * [refreshTick] is bumped on every re-read; cards key their cached content on
 * it, which is how an expanded card notices a push to its repository.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReposScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as GitHostApp

    val prefs = remember { context.getSharedPreferences("git_host_prefs", 0) }
    var running by remember { mutableStateOf(GitHostService.isRunning(context)) }
    val port = prefs.getInt(GitHostService.PREF_PORT, GitHostService.DEFAULT_PORT)
    var repos by remember { mutableStateOf(listOf<String>()) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    fun refreshRepos() {
        repos = app.reposDir.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".git") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
        refreshTick++
    }

    // "server_running" is the owner's wish (the power button), whereas
    // GitHostService.isRunning reports the actual state. Restart the server if
    // the owner left it on but it has since stopped.
    LaunchedEffect(Unit) {
        refreshRepos()
        val shouldRun = prefs.getBoolean("server_running", false)
        if (shouldRun && !GitHostService.isRunning(context)) {
            GitHostService.start(context)
        }
        running = GitHostService.isRunning(context)
    }

    DisposableEffect(Unit) {
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)
        val gitEventsJob = scope.launch { EventBus.gitEvents.collect { refreshRepos() } }
        val stoppedJob = scope.launch { EventBus.serverStopped.collect { running = false } }
        onDispose { gitEventsJob.cancel(); stoppedJob.cancel() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Repositories") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = GitHub.CanvasSubtle),
                actions = {
                    IconButton(onClick = {
                        if (running) {
                            GitHostService.stop(context)
                            prefs.edit().putBoolean("server_running", false).apply()
                        } else {
                            GitHostService.start(context)
                            prefs.edit().putBoolean("server_running", true).apply()
                        }
                        running = !running
                    }) {
                        Icon(
                            Icons.Default.PowerSettingsNew,
                            contentDescription = if (running) "Stop server" else "Start server",
                            tint = if (running) GitHub.Success else GitHub.FgMuted,
                        )
                    }
                }
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ServerStatusBanner(running, port)
            if (repos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Book, contentDescription = null,
                            modifier = Modifier.size(56.dp), tint = GitHub.FgSubtle)
                        Spacer(Modifier.height(12.dp))
                        Text("No repositories yet", style = MaterialTheme.typography.titleMedium,
                            color = GitHub.FgMuted)
                        Text("Push to this host to create one",
                            style = MaterialTheme.typography.bodySmall, color = GitHub.FgSubtle)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(repos, key = { it }) { repo ->
                        RepoCard(
                            name = repo,
                            repoDir = File(app.reposDir, repo),
                            cloneUrl = "ssh://git@${getLocalIp()}:$port/$repo",
                            prefs = prefs,
                            refreshTick = refreshTick,
                            expanded = expanded == repo,
                            onToggle = { expanded = if (expanded == repo) null else repo },
                            onDelete = { deleteTarget = repo },
                            deviceLabels = app.whitelist.list().map { it.label },
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { repo ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = GitHub.CanvasSubtle,
            title = { Text("Delete repository") },
            text = { Text("Delete \"${repo.removeSuffix(".git")}\" from this device? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        File(app.reposDir, repo).deleteRecursively()
                        LastPush.clear(prefs, repo)
                        if (expanded == repo) expanded = null
                        deleteTarget = null
                        refreshRepos()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GitHub.Danger, contentColor = GitHub.Canvas),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

/** One-line strip under the top bar: a status dot, and the address to push to while running. */
@Composable
private fun ServerStatusBanner(running: Boolean, port: Int) {
    val ip = remember { getLocalIp() }
    Surface(color = GitHub.CanvasSubtle, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(8.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (running) GitHub.Success else GitHub.FgSubtle)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (running) "SSH server running · $ip:$port" else "SSH server stopped",
                style = MaterialTheme.typography.labelMedium,
                color = GitHub.FgSecondary,
            )
        }
        HorizontalDivider(color = GitHub.Border, modifier = Modifier.padding(top = 38.dp))
    }
}

// ---------------------------------------------------------------------------
// Repo card
// ---------------------------------------------------------------------------

/** The tabs of an expanded repository card, in display order. */
private enum class Section(val title: String) { README("README"), COMMITS("Commits"), FILES("Files"), INFO("Info") }

/**
 * One repository. Collapsed, it shows the name, branch and last push; expanded,
 * it adds [Section] tabs whose panels live in RepoPanels.kt.
 *
 * The repository itself is only read (via [loadRepoContent], on the IO
 * dispatcher) while the card is expanded, and again whenever [refreshTick]
 * changes, so a long list of collapsed cards costs nothing.
 */
@Composable
private fun RepoCard(
    name: String,
    repoDir: File,
    cloneUrl: String,
    prefs: SharedPreferences,
    refreshTick: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    deviceLabels: List<String>,
) {
    val push = remember(name, refreshTick) { LastPush.load(prefs, name) }
    var section by remember(name) { mutableStateOf(Section.README) }
    var content by remember(name) { mutableStateOf<RepoContent?>(null) }

    LaunchedEffect(name, expanded, refreshTick) {
        if (expanded) {
            content = null
            content = withContext(Dispatchers.IO) { loadRepoContent(repoDir) }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = GitHub.CanvasSubtle),
        border = BorderStroke(1.dp, GitHub.Border),
    ) {
        Column(Modifier.clickable(onClick = onToggle).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Book, contentDescription = null, tint = GitHub.FgMuted,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    name.removeSuffix(".git"),
                    style = MaterialTheme.typography.titleMedium,
                    color = GitHub.Link,
                    modifier = Modifier.weight(1f),
                )
                val branch = content?.headBranch ?: push?.branch
                if (branch != null) BranchChip(branch)
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse repository details" else "Expand repository details",
                    tint = GitHub.FgMuted,
                )
            }
            Spacer(Modifier.height(6.dp))
            PushSummaryLine(push)
        }

        if (expanded) {
            HorizontalDivider(color = GitHub.Border)
            SectionTabs(section) { section = it }
            HorizontalDivider(color = GitHub.BorderMuted)
            Box(Modifier.padding(14.dp)) {
                val c = content
                when {
                    c == null -> LoadingRow(when (section) {
                        Section.README -> "Loading README…"
                        Section.COMMITS -> "Loading commits…"
                        Section.FILES -> "Loading files…"
                        Section.INFO -> "Loading…"
                    })
                    c.error != null -> Text(c.error, color = GitHub.Danger,
                        style = MaterialTheme.typography.bodySmall)
                    else -> when (section) {
                        Section.README -> ReadmePanel(c.readme)
                        Section.COMMITS -> CommitsPanel(c.commits)
                        Section.FILES -> FileTreePanel(c.tree)
                        Section.INFO -> RepoInfoPanel(c, cloneUrl, push, deviceLabels, onDelete)
                    }
                }
            }
        }
    }
}

/** The branch name as a small monospace pill, like GitHub's branch selector. */
@Composable
private fun BranchChip(branch: String) {
    Surface(
        color = GitHub.BorderMuted,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, GitHub.Border),
    ) {
        Text(
            branch,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = GitHub.FgSecondary,
        )
    }
}

/**
 * "laptop pushed 5m ago · 2 commits · +1 ~3 · 4.2 KB". Parts with nothing
 * to say are left out. Scrolls sideways rather than wrapping on narrow screens.
 */
@Composable
private fun PushSummaryLine(push: LastPush?) {
    if (push == null) {
        Text("No push recorded yet.", style = MaterialTheme.typography.bodySmall, color = GitHub.FgMuted)
        return
    }
    val muted = MaterialTheme.typography.bodySmall
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Text("${push.label} pushed ${relativeTime(push.timeMillis)}", style = muted, color = GitHub.FgMuted)
        if (push.commits > 0) {
            Text(" · ${plural(push.commits, "commit")}", style = muted, color = GitHub.FgMuted)
        }
        if (push.added + push.modified + push.deleted > 0) {
            Text(" · ", style = muted, color = GitHub.FgMuted)
            ChangeCounts(push.added, push.modified, push.deleted)
        }
        if (push.bytes > 0) {
            Text(" · ${formatBytes(push.bytes)}", style = muted, color = GitHub.FgMuted)
        }
    }
}

/** GitHub-style underline tabs; the active tab gets an orange bar beneath it. */
@Composable
private fun SectionTabs(selected: Section, onSelect: (Section) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Section.entries.forEach { s ->
            val active = s == selected
            Column(
                Modifier.clickable { onSelect(s) }.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    s.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) GitHub.Fg else GitHub.FgMuted,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.height(2.dp).width(36.dp).clip(RoundedCornerShape(1.dp))
                        .background(if (active) GitHub.TabUnderline else Color.Transparent)
                )
            }
        }
    }
}

/** A small spinner with a caption, shown while a card's content loads. */
@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = GitHub.Accent)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = GitHub.FgMuted)
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * The first non-loopback IPv4 address of this device, for the clone URL and
 * status banner, or "localhost" if there is none (e.g. no Wi-Fi).
 */
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
