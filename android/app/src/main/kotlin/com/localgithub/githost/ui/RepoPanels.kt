package com.localgithub.githost.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localgithub.githost.service.LastPush
import com.localgithub.githost.ui.theme.GitHub
import com.localgithub.githost.util.formatBytes
import com.localgithub.githost.util.plural
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The tab panels shown inside an expanded repository card (see RepoCard in
// ReposScreen.kt). Each panel renders one slice of an already loaded
// [RepoContent]; none of them touch the repository themselves.

// ---------------------------------------------------------------------------
// README
// ---------------------------------------------------------------------------

/** Renders the repository's README as Markdown, headed by its file name. */
@Composable
internal fun ReadmePanel(readme: RepoReadme?) {
    if (readme == null) {
        EmptyMessage("No README found", "Add a README.md at the repository root to show it here.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(readme.path, style = MaterialTheme.typography.labelSmall, color = GitHub.FgMuted,
            fontFamily = FontFamily.Monospace)
        MarkdownView(readme.text)
    }
}

// ---------------------------------------------------------------------------
// Commits
// ---------------------------------------------------------------------------

/** The most recent commits on the displayed branch, newest first. */
@Composable
internal fun CommitsPanel(commits: List<RepoCommit>) {
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

/** One commit: subject, then short id, author, age and file change counts. */
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

/**
 * Coloured "+added ~modified -deleted" file counts, as GitHub shows them.
 * Zero counts are left out. Also used by the card's last-push line.
 */
@Composable
internal fun ChangeCounts(added: Int, modified: Int, deleted: Int) {
    Row {
        if (added > 0) Text("+$added ", style = MaterialTheme.typography.labelSmall, color = GitHub.Success)
        if (modified > 0) Text("~$modified ", style = MaterialTheme.typography.labelSmall, color = GitHub.Attention)
        if (deleted > 0) Text("-$deleted", style = MaterialTheme.typography.labelSmall, color = GitHub.Danger)
    }
}

// ---------------------------------------------------------------------------
// Files
// ---------------------------------------------------------------------------

/**
 * A collapsible file tree of the displayed branch. Folders start closed;
 * the set of open folder paths is saveable so it survives rotation.
 */
@Composable
internal fun FileTreePanel(tree: List<RepoTreeNode>) {
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

/**
 * Renders [node] indented by [depth], then, if it is an open folder, its
 * children recursively. Tapping a folder calls [toggle] with its path.
 */
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

/**
 * Clone URL, last push, size on disk, which devices have access, and the
 * delete button. Deletion itself is confirmed and performed by the screen
 * ([onDelete] only asks for it).
 */
@Composable
internal fun RepoInfoPanel(
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
                val what = listOfNotNull(push.branch, push.commits.takeIf { it > 0 }?.let { plural(it, "commit") })
                    .joinToString(", ")
                Text("${push.label}${if (what.isEmpty()) "" else " → $what"} · ${relativeTime(push.timeMillis)}",
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

/** A small titled block within [RepoInfoPanel]. */
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

/** Placeholder for a panel with nothing to show: a short title and a hint. */
@Composable
private fun EmptyMessage(title: String, hint: String) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = GitHub.FgSecondary)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = GitHub.FgMuted)
    }
}

/**
 * Compact age of a timestamp: "just now", "5m ago", "3h ago", "12d ago",
 * falling back to the full date after 30 days. Non-positive means unknown.
 */
internal fun relativeTime(millis: Long): String {
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

/** Local date and time, "yyyy-MM-dd HH:mm". */
private fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
