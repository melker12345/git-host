package com.localgithub.githost.ui

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File

/** Everything the repository card shows once expanded. Read once, off the main thread. */
data class RepoContent(
    val headBranch: String?,
    val readme: RepoReadme?,
    val commits: List<RepoCommit>,
    val tree: List<RepoTreeNode>,
    val sizeBytes: Long,
    val tipStats: ChangeStats?,
    val error: String? = null,
)

data class RepoReadme(val path: String, val text: String)

data class RepoCommit(
    val shortId: String,
    val subject: String,
    val author: String,
    val whenMillis: Long,
    val stats: ChangeStats,
)

data class ChangeStats(val added: Int, val modified: Int, val deleted: Int) {
    val total get() = added + modified + deleted
}

data class RepoTreeNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val children: List<RepoTreeNode>,
)

private const val MAX_COMMITS = 20
private const val MAX_TREE_ENTRIES = 2000
private const val MAX_README_BYTES = 200 * 1024
private val README_NAMES = listOf("README.md", "readme.md", "Readme.md", "README.markdown", "README")

fun loadRepoContent(repoDir: File): RepoContent {
    if (!repoDir.exists()) return RepoContent(null, null, emptyList(), emptyList(), 0, null, "Repository not found.")
    return try {
        FileRepositoryBuilder().setGitDir(repoDir).setBare().build().use { repo ->
            val head = resolveDisplayHead(repo)
            val commits = if (head != null) readCommits(repo, head) else emptyList()
            RepoContent(
                headBranch = displayBranch(repo),
                readme = head?.let { readReadme(repo, it) },
                commits = commits,
                tree = head?.let { readTree(repo, it) } ?: emptyList(),
                sizeBytes = dirSize(repoDir),
                tipStats = commits.firstOrNull()?.stats,
            )
        }
    } catch (e: Exception) {
        RepoContent(null, null, emptyList(), emptyList(), dirSize(repoDir), null,
            "Could not read repository contents: ${e.message}")
    }
}

/**
 * HEAD of a bare repo often points at a branch that was never pushed (we
 * init with "main", most pushes are "master"). Fall back to any branch.
 */
private fun resolveDisplayHead(repo: Repository): ObjectId? {
    repo.resolve("HEAD^{commit}")?.let { return it }
    return branchNames(repo).firstNotNullOfOrNull { repo.resolve("refs/heads/$it^{commit}") }
}

private fun displayBranch(repo: Repository): String? {
    val head = repo.exactRef(Constants.HEAD)
    val target = head?.target?.name?.removePrefix("refs/heads/")
    if (target != null && repo.resolve("refs/heads/$target") != null) return target
    return branchNames(repo).firstOrNull()
}

/** Branches sorted so main and master come first. */
private fun branchNames(repo: Repository): List<String> =
    repo.refDatabase.getRefsByPrefix("refs/heads/")
        .map { it.name.removePrefix("refs/heads/") }
        .sortedWith(compareBy({ it != "main" }, { it != "master" }, { it }))

private fun readCommits(repo: Repository, head: ObjectId): List<RepoCommit> {
    val out = ArrayList<RepoCommit>(MAX_COMMITS)
    RevWalk(repo).use { walk ->
        DiffFormatter(DisabledOutputStream.INSTANCE).use { df ->
            df.setRepository(repo)
            df.isDetectRenames = false
            walk.markStart(walk.parseCommit(head))
            for (commit in walk) {
                if (out.size >= MAX_COMMITS) break
                out += RepoCommit(
                    shortId = commit.name.substring(0, 7),
                    subject = commit.shortMessage,
                    author = commit.authorIdent?.name ?: "unknown",
                    whenMillis = commit.commitTime * 1000L,
                    stats = countChanges(df, walk, commit),
                )
            }
        }
    }
    return out
}

private fun countChanges(df: DiffFormatter, walk: RevWalk, commit: RevCommit): ChangeStats {
    return try {
        val parentTree = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0)).tree else null
        var added = 0; var modified = 0; var deleted = 0
        for (entry in df.scan(parentTree, commit.tree)) {
            when (entry.changeType) {
                DiffEntry.ChangeType.ADD, DiffEntry.ChangeType.COPY -> added++
                DiffEntry.ChangeType.DELETE -> deleted++
                else -> modified++
            }
        }
        ChangeStats(added, modified, deleted)
    } catch (_: Exception) {
        ChangeStats(0, 0, 0)
    }
}

private fun readReadme(repo: Repository, head: ObjectId): RepoReadme? {
    val tree = RevWalk(repo).use { it.parseCommit(head).tree }
    for (name in README_NAMES) {
        val tw = TreeWalk.forPath(repo, name, tree) ?: continue
        tw.use {
            val loader = repo.open(it.getObjectId(0))
            val bytes = loader.getCachedBytes(MAX_README_BYTES)
            return RepoReadme(name, String(bytes, Charsets.UTF_8))
        }
    }
    return null
}

private class MutableNode(val name: String, val path: String, val isDirectory: Boolean) {
    val children = LinkedHashMap<String, MutableNode>()
    fun freeze(): RepoTreeNode = RepoTreeNode(
        name, path, isDirectory,
        children.values
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map { it.freeze() },
    )
}

private fun readTree(repo: Repository, head: ObjectId): List<RepoTreeNode> {
    val root = MutableNode("", "", true)
    val tree = RevWalk(repo).use { it.parseCommit(head).tree }
    TreeWalk(repo).use { tw ->
        tw.addTree(tree)
        tw.isRecursive = true
        var count = 0
        while (tw.next()) {
            if (++count > MAX_TREE_ENTRIES) break
            val parts = tw.pathString.split('/')
            var node = root
            var path = ""
            for ((i, part) in parts.withIndex()) {
                path = if (path.isEmpty()) part else "$path/$part"
                val isDir = i < parts.lastIndex
                node = node.children.getOrPut(part) { MutableNode(part, path, isDir) }
            }
        }
    }
    return root.freeze().children
}

private fun dirSize(dir: File): Long =
    dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
