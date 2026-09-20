package com.localgithub.githost.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localgithub.githost.EventBus
import com.localgithub.githost.GitHostApp
import com.localgithub.githost.service.GitHostService
import com.localgithub.githost.ui.theme.GitHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

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
                        prefs.edit().apply {
                            prefs.all.keys.filter { it.startsWith("last_push_$repo") }.forEach { remove(it) }
                        }.apply()
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

private enum class Section(val title: String) { README("README"), COMMITS("Commits"), FILES("Files"), INFO("Info") }

private data class LastPush(
    val label: String, val branch: String, val bytes: Long,
    val added: Int, val modified: Int, val deleted: Int, val timeMillis: Long,
)

private fun lastPush(prefs: SharedPreferences, repo: String): LastPush? {
    val label = prefs.getString("last_push_${repo}_label", null) ?: return null
    return LastPush(
        label = label,
        branch = prefs.getString("last_push_${repo}_branch", null) ?: "unknown",
        bytes = prefs.getLong("last_push_${repo}_bytes", -1L),
        added = prefs.getInt("last_push_${repo}_added", 0),
        modified = prefs.getInt("last_push_${repo}_modified", 0),
        deleted = prefs.getInt("last_push_${repo}_deleted", 0),
        timeMillis = prefs.getLong("last_push_${repo}_time", 0L),
    )
}

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
    val push = remember(name, refreshTick) { lastPush(prefs, name) }
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
                val branch = content?.headBranch ?: push?.branch?.takeIf { it != "unknown" }
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
                        Section.README -> "Loading README..."
                        Section.COMMITS -> "Loading commits…"
                        Section.FILES -> "Loading files..."
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

@Composable
private fun PushSummaryLine(push: LastPush?) {
    if (push == null) {
        Text("No push recorded yet.", style = MaterialTheme.typography.bodySmall, color = GitHub.FgMuted)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.horizontalScroll(rememberScrollState())) {
        Text(
            "${push.label} pushed ${relativeTime(push.timeMillis)}",
            style = MaterialTheme.typography.bodySmall, color = GitHub.FgMuted,
        )
        if (push.added + push.modified + push.deleted > 0) {
            Text(" · ", style = MaterialTheme.typography.bodySmall, color = GitHub.FgMuted)
            ChangeCounts(push.added, push.modified, push.deleted)
        }
        if (push.bytes >= 0) {
            Text(" · ${formatBytes(push.bytes)}", style = MaterialTheme.typography.bodySmall, color = GitHub.FgMuted)
        }
    }
}

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
                        .background(if (active) Color(0xFFF78166) else Color.Transparent)
                )
            }
        }
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = GitHub.Accent)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = GitHub.FgMuted)
    }
}

@Composable
private fun EmptyMessage(title: String, hint: String) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = GitHub.FgSecondary)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = GitHub.FgMuted)
    }
}

// ---------------------------------------------------------------------------
// README
// ---------------------------------------------------------------------------

@Composable
private fun ReadmePanel(readme: RepoReadme?) {
    if (readme == null) {
        EmptyMessage("No README found", "Add a README.md at the repository root to show it here.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(readme.path, style = MaterialTheme.typography.labelSmall, color = GitHub.FgMuted,
            fontFamily = FontFamily.Monospace)
        MarkdownReadme(readme.text)
    }
}

sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Bullet(val text: String) : MarkdownBlock()
    data class Numbered(val number: String, val text: String) : MarkdownBlock()
    data class Code(val text: String) : MarkdownBlock()
    object Rule : MarkdownBlock()
}

private fun parseMarkdown(src: String): List<MarkdownBlock> {
    val blocks = ArrayList<MarkdownBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) blocks += MarkdownBlock.Paragraph(para.toString().trim())
        para.clear()
    }
    var inCode = false
    val code = StringBuilder()
    for (raw in src.lines()) {
        val line = raw.trimEnd()
        if (line.trimStart().startsWith("```")) {
            if (inCode) { blocks += MarkdownBlock.Code(code.toString().trimEnd()); code.clear() }
            else flush()
            inCode = !inCode
            continue
        }
        if (inCode) { code.appendLine(raw); continue }
        val t = line.trim()
        when {
            t.isEmpty() -> flush()
            t.startsWith("#") -> {
                flush()
                val level = t.takeWhile { it == '#' }.length.coerceIn(1, 6)
                blocks += MarkdownBlock.Heading(level, t.drop(level).trim())
            }
            t == "---" || t == "***" || t == "___" -> { flush(); blocks += MarkdownBlock.Rule }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> {
                flush(); blocks += MarkdownBlock.Bullet(t.drop(2).trim())
            }
            Regex("\\d+\\.\\s+.*").matches(t) -> {
                flush()
                val num = t.substringBefore('.')
                blocks += MarkdownBlock.Numbered("$num.", t.substringAfter('.').trim())
            }
            t.startsWith("|") -> { flush(); blocks += MarkdownBlock.Code(t) }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
        }
    }
    if (inCode && code.isNotBlank()) blocks += MarkdownBlock.Code(code.toString().trimEnd())
    flush()
    return blocks
}

private val IMAGE_RE = Regex("!\\[([^]]*)]\\([^)]+\\)")
private val LINK_RE = Regex("\\[([^]]+)]\\(([^)]+)\\)")
private val BOLD_RE = Regex("(\\*\\*|__)(.*?)\\1")
private val ITALIC_RE = Regex("(\\*|_)(.*?)\\1")
private val CODE_RE = Regex("`([^`]+)`")

/** Renders bold, italic, inline code and link text; strips image and link targets. */
private fun inline(text: String): AnnotatedString {
    var s = IMAGE_RE.replace(text) { it.groupValues[1] }
    s = LINK_RE.replace(s) { "\u0001${it.groupValues[1]}\u0002" }
    return buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            val rest = s.substring(i)
            val code = CODE_RE.find(rest)?.takeIf { it.range.first == 0 }
            val bold = BOLD_RE.find(rest)?.takeIf { it.range.first == 0 }
            val italic = ITALIC_RE.find(rest)?.takeIf { it.range.first == 0 }
            when {
                code != null -> {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = GitHub.BorderMuted,
                        color = GitHub.Fg)) { append(code.groupValues[1]) }
                    i += code.value.length
                }
                bold != null -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold.groupValues[2]) }
                    i += bold.value.length
                }
                italic != null -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic.groupValues[2]) }
                    i += italic.value.length
                }
                s[i] == '\u0001' -> {
                    val end = s.indexOf('\u0002', i).let { if (it < 0) s.length else it }
                    withStyle(SpanStyle(color = GitHub.Link)) { append(s.substring(i + 1, end)) }
                    i = (end + 1).coerceAtMost(s.length)
                }
                else -> { append(s[i]); i++ }
            }
        }
    }
}

@Composable
private fun MarkdownReadme(text: String) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MarkdownBlock.Heading -> {
                    val style = when (b.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Column {
                        Text(inline(b.text), style = style, color = GitHub.Fg)
                        if (b.level <= 2) HorizontalDivider(color = GitHub.BorderMuted,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
                is MarkdownBlock.Paragraph -> Text(inline(b.text),
                    style = MaterialTheme.typography.bodyMedium, color = GitHub.FgSecondary)
                is MarkdownBlock.Bullet -> MarkdownListRow("•", inline(b.text))
                is MarkdownBlock.Numbered -> MarkdownListRow(b.number, inline(b.text))
                is MarkdownBlock.Code -> MarkdownCodeBlock(b.text)
                MarkdownBlock.Rule -> HorizontalDivider(color = GitHub.Border)
            }
        }
    }
}

@Composable
private fun MarkdownListRow(marker: String, text: AnnotatedString) {
    Row(Modifier.padding(start = 8.dp)) {
        Text(marker, style = MaterialTheme.typography.bodyMedium, color = GitHub.FgMuted,
            modifier = Modifier.width(24.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = GitHub.FgSecondary)
    }
}

@Composable
private fun MarkdownCodeBlock(text: String) {
    Surface(
        color = GitHub.CanvasInset,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, GitHub.BorderMuted),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = GitHub.FgSecondary,
            softWrap = false,
        )
    }
}

// ---------------------------------------------------------------------------
// Commits
// ---------------------------------------------------------------------------

@Composable
private fun CommitsPanel(commits: List<RepoCommit>) {
    if (commits.isEmpty()) {
        EmptyMessage("No commits", "This branch has no commits yet.")
        return
    }
    Column {
        commits.forEachIndexed { i, c ->
            CommitRow(c)
            if (i < commits.lastIndex) HorizontalDivider(color = GitHub.BorderMuted)
        }
    }
}

@Composable
private fun CommitRow(c: RepoCommit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(c.subject, style = MaterialTheme.typography.bodyMedium, color = GitHub.Fg,
            fontWeight = FontWeight.Medium, maxLines = 2)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text(c.shortId, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall, color = GitHub.Accent)
            Text("  ${c.author} · ${relativeTime(c.whenMillis)}",
                style = MaterialTheme.typography.labelSmall, color = GitHub.FgMuted)
            if (c.stats.total > 0) {
                Text("  ", style = MaterialTheme.typography.labelSmall)
                ChangeCounts(c.stats.added, c.stats.modified, c.stats.deleted)
            }
        }
    }
}

@Composable
private fun ChangeCounts(added: Int, modified: Int, deleted: Int) {
    Row {
        if (added > 0) Text("+$added ", style = MaterialTheme.typography.labelSmall, color = GitHub.Success)
        if (modified > 0) Text("~$modified ", style = MaterialTheme.typography.labelSmall, color = GitHub.Attention)
        if (deleted > 0) Text("-$deleted", style = MaterialTheme.typography.labelSmall, color = GitHub.Danger)
    }
}

// ---------------------------------------------------------------------------
// Files
// ---------------------------------------------------------------------------

@Composable
private fun FileTreePanel(tree: List<RepoTreeNode>) {
    if (tree.isEmpty()) {
        EmptyMessage("No files found", "The current branch does not contain any files.")
        return
    }
    var open by rememberSaveable { mutableStateOf(setOf<String>()) }
    val dirs = remember(tree) { countDirs(tree) }
    val files = remember(tree) { countFiles(tree) }
    Column {
        Text("$dirs folders · $files files", style = MaterialTheme.typography.labelSmall,
            color = GitHub.FgMuted, modifier = Modifier.padding(bottom = 6.dp))
        tree.forEach { node -> TreeRows(node, 0, open) { p -> open = if (p in open) open - p else open + p } }
    }
}

private fun countDirs(nodes: List<RepoTreeNode>): Int =
    nodes.sumOf { if (it.isDirectory) 1 + countDirs(it.children) else 0 }

private fun countFiles(nodes: List<RepoTreeNode>): Int =
    nodes.sumOf { if (it.isDirectory) countFiles(it.children) else 1 }

@Composable
private fun TreeRows(node: RepoTreeNode, depth: Int, open: Set<String>, toggle: (String) -> Unit) {
    val isOpen = node.path in open
    Row(
        Modifier.fillMaxWidth()
            .then(if (node.isDirectory) Modifier.clickable { toggle(node.path) } else Modifier)
            .padding(start = (depth * 16).dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                !node.isDirectory -> Icons.Default.InsertDriveFile
                isOpen -> Icons.Default.FolderOpen
                else -> Icons.Default.Folder
            },
            contentDescription = null,
            tint = if (node.isDirectory) GitHub.Accent else GitHub.FgMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(node.name, style = MaterialTheme.typography.bodySmall,
            color = if (node.isDirectory) GitHub.Fg else GitHub.FgSecondary, maxLines = 1)
    }
    if (node.isDirectory && isOpen) {
        node.children.forEach { TreeRows(it, depth + 1, open, toggle) }
    }
}

// ---------------------------------------------------------------------------
// Info
// ---------------------------------------------------------------------------

@Composable
private fun RepoInfoPanel(
    content: RepoContent,
    cloneUrl: String,
    push: LastPush?,
    deviceLabels: List<String>,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        InfoSection("Clone URL") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cloneUrl, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall, color = GitHub.FgSecondary,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("clone url", cloneUrl))
                }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL",
                        modifier = Modifier.size(16.dp), tint = GitHub.FgMuted)
                }
            }
        }
        InfoSection("Last push") {
            if (push == null) {
                Text("No push recorded yet.", style = MaterialTheme.typography.bodySmall, color = GitHub.FgMuted)
            } else {
                Text("${push.label} → ${push.branch} · ${relativeTime(push.timeMillis)}",
                    style = MaterialTheme.typography.bodySmall, color = GitHub.FgSecondary)
                Text(formatDate(push.timeMillis), style = MaterialTheme.typography.labelSmall, color = GitHub.FgMuted)
            }
        }
        InfoSection("Size") {
            Text(formatBytes(content.sizeBytes), style = MaterialTheme.typography.bodySmall, color = GitHub.FgSecondary)
        }
        InfoSection("Who has access") {
            if (deviceLabels.isEmpty()) {
                Text("No devices currently have access.", style = MaterialTheme.typography.bodySmall, color = GitHub.FgMuted)
            } else {
                Text(deviceLabels.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = GitHub.FgSecondary)
                Text("All paired devices can read and write every repository.",
                    style = MaterialTheme.typography.labelSmall, color = GitHub.FgMuted)
            }
        }
        InfoSection("Danger zone", titleColor = GitHub.Danger) {
            OutlinedButton(
                onClick = onDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GitHub.Danger),
                border = BorderStroke(1.dp, GitHub.Border),
            ) { Text("Delete repository") }
        }
    }
}

@Composable
private fun InfoSection(title: String, titleColor: Color = GitHub.FgMuted, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = titleColor)
        content()
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun relativeTime(millis: Long): String {
    if (millis <= 0) return "unknown"
    val diff = System.currentTimeMillis() - millis
    val m = diff / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m}m ago"
        m < 60 * 24 -> "${m / 60}h ago"
        m < 60 * 24 * 30 -> "${m / (60 * 24)}d ago"
        else -> formatDate(millis)
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "?"
    val units = listOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble(); var u = 0
    while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
    return if (u == 0) "%.0f %s".format(v, units[u]) else "%.1f %s".format(v, units[u])
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
