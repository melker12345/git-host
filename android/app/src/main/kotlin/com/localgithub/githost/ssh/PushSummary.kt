package com.localgithub.githost.ssh

import com.localgithub.githost.util.formatBytes
import com.localgithub.githost.util.plural
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.util.io.DisabledOutputStream

/**
 * What one `git push` changed, worked out from the ref updates ReceivePack
 * actually applied.
 *
 * A sync pushes every repository every time, and most of those pushes change
 * nothing. Those must be recognisable ([isEmpty]) so they neither raise a
 * notification nor overwrite the repo's "last push" record with zeros.
 *
 * Only branches and tags count. A mirror push also updates the client's
 * remote-tracking refs (refs/remotes/...), which is noise to the user.
 */
data class PushSummary(
    /** Branches created or moved, short names, primary branch first. */
    val branches: List<String>,
    /** Branches removed by the push (mirror mode deletes them). */
    val deletedBranches: List<String>,
    /** Tags created or moved. */
    val tags: List<String>,
    /** Commits the repository did not have before this push. */
    val commits: Int,
    /** Files added, modified and deleted on the primary branch. */
    val filesAdded: Int,
    val filesModified: Int,
    val filesDeleted: Int,
) {
    val isEmpty get() = branches.isEmpty() && deletedBranches.isEmpty() && tags.isEmpty()

    /** The branch the file counts describe, if any. */
    val primaryBranch get() = branches.firstOrNull()

    /**
     * Names what moved, for a notification title or summary line:
     * "main", "main +2 branches", "tag v1.2", "deleted old-ui".
     */
    fun headline(): String {
        val parts = mutableListOf<String>()
        when (branches.size) {
            0 -> {}
            1 -> parts += branches[0]
            2 -> parts += "${branches[0]} +1 branch"
            else -> parts += "${branches[0]} +${branches.size - 1} branches"
        }
        when (tags.size) {
            0 -> {}
            1 -> parts += "tag ${tags[0]}"
            else -> parts += "${tags.size} tags"
        }
        when (deletedBranches.size) {
            0 -> {}
            1 -> parts += "deleted ${deletedBranches[0]}"
            else -> parts += "deleted ${deletedBranches.size} branches"
        }
        return parts.joinToString(", ")
    }

    /** "3 commits · +2 ~5 −1 files · 12.4 KB", leaving out whatever is zero or unknown. */
    fun details(packBytes: Long): String = listOfNotNull(
        commits.takeIf { it > 0 }?.let { plural(it, "commit") },
        fileCounts(filesAdded, filesModified, filesDeleted),
        packBytes.takeIf { it > 0 }?.let { formatBytes(it) },
    ).joinToString(" · ")

    companion object {
        /** "+2 ~5 −1 files", or null when nothing changed. */
        fun fileCounts(added: Int, modified: Int, deleted: Int): String? {
            if (added + modified + deleted == 0) return null
            return "+$added ~$modified \u2212$deleted files"
        }

        /** Counting stops here, so a push of a huge history stays cheap. */
        const val MAX_COUNTED_COMMITS = 10_000

        /**
         * Summarises [commands], the ref updates of one push, against [repo]
         * as it is after the push.
         */
        fun of(repo: Repository, commands: Collection<ReceiveCommand>): PushSummary {
            val applied = commands.filter { it.result == ReceiveCommand.Result.OK }
            val heads = applied.filter { it.refName.startsWith(Constants.R_HEADS) }
            val tags = applied.filter { it.refName.startsWith(Constants.R_TAGS) }

            val (deleted, updated) = heads.partition { it.type == ReceiveCommand.Type.DELETE }
            // The branch HEAD points at is the one a user cares about; list it
            // first so it becomes the primary branch.
            val headRef = runCatching { repo.fullBranch }.getOrNull()
            val ordered = updated.sortedWith(compareBy({ it.refName != headRef }, { it.refName }))

            val primary = ordered.firstOrNull()
            val files = primary?.let { diffFiles(repo, it.oldId, it.newId) } ?: FileCounts(0, 0, 0)

            return PushSummary(
                branches = ordered.map { Repository.shortenRefName(it.refName) },
                deletedBranches = deleted.map { Repository.shortenRefName(it.refName) },
                tags = tags.filter { it.type != ReceiveCommand.Type.DELETE }
                    .map { Repository.shortenRefName(it.refName) },
                commits = countNewCommits(repo, updated + tags),
                filesAdded = files.added,
                filesModified = files.modified,
                filesDeleted = files.deleted,
            )
        }

        /**
         * Counts commits reachable from the pushed tips but not from what the
         * repository already had: the old tips, and every ref the push did
         * not touch. A new branch made from an existing one therefore counts
         * zero commits, which is what actually arrived.
         */
        private fun countNewCommits(repo: Repository, pushed: List<ReceiveCommand>): Int {
            if (pushed.isEmpty()) return 0
            return runCatching {
                RevWalk(repo).use { rw ->
                    for (cmd in pushed) {
                        commitOrNull(rw, cmd.newId)?.let { rw.markStart(it) }
                        commitOrNull(rw, cmd.oldId)?.let { rw.markUninteresting(it) }
                    }
                    val touched = pushed.map { it.refName }.toSet()
                    for (ref in repo.refDatabase.refs) {
                        // HEAD and other symbolic refs point at a pushed branch;
                        // marking them would hide exactly the commits we count.
                        if (ref.isSymbolic || ref.name in touched) continue
                        ref.objectId?.let { id -> commitOrNull(rw, id)?.let { rw.markUninteresting(it) } }
                    }
                    var n = 0
                    for (c in rw) {
                        if (++n >= MAX_COUNTED_COMMITS) break
                    }
                    n
                }
            }.getOrDefault(0)
        }

        /** Resolves [id] to a commit, peeling annotated tags; null for anything else. */
        private fun commitOrNull(rw: RevWalk, id: ObjectId?): RevCommit? {
            if (id == null || ObjectId.zeroId() == id) return null
            return runCatching { rw.parseCommit(id) }.getOrNull()
        }

        private data class FileCounts(val added: Int, val modified: Int, val deleted: Int)

        /** File changes between two commits; an all-zero [oldId] means "from nothing". */
        private fun diffFiles(repo: Repository, oldId: ObjectId, newId: ObjectId): FileCounts =
            runCatching {
                RevWalk(repo).use { rw ->
                    val newTree = rw.parseCommit(newId).tree
                    val oldTree = if (ObjectId.zeroId() == oldId) null else rw.parseCommit(oldId).tree
                    DiffFormatter(DisabledOutputStream.INSTANCE).use { df ->
                        df.setRepository(repo)
                        var added = 0; var modified = 0; var deleted = 0
                        for (entry in df.scan(oldTree, newTree)) {
                            when (entry.changeType) {
                                DiffEntry.ChangeType.ADD, DiffEntry.ChangeType.COPY -> added++
                                DiffEntry.ChangeType.MODIFY, DiffEntry.ChangeType.RENAME -> modified++
                                DiffEntry.ChangeType.DELETE -> deleted++
                                null -> {}
                            }
                        }
                        FileCounts(added, modified, deleted)
                    }
                }
            }.getOrDefault(FileCounts(0, 0, 0))
    }
}
