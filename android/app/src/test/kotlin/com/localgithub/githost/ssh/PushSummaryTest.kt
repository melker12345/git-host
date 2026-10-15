package com.localgithub.githost.ssh

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.ReceiveCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PushSummaryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun commit(git: Git, file: String, content: String): ObjectId {
        File(git.repository.workTree, file).writeText(content)
        git.add().addFilepattern(file).call()
        return git.commit().setMessage("edit $file").setSign(false).call().id
    }

    private fun applied(old: ObjectId, new: ObjectId, ref: String) =
        ReceiveCommand(old, new, ref).apply { setResult(ReceiveCommand.Result.OK) }

    @Test
    fun noRefUpdatesIsEmpty() {
        Git.init().setDirectory(tmp.root).setInitialBranch("main").call().use { git ->
            commit(git, "a.txt", "a")
            val s = PushSummary.of(git.repository, emptyList())
            assertTrue(s.isEmpty)
        }
    }

    @Test
    fun countsCommitsAndFilesOnAnUpdatedBranch() {
        Git.init().setDirectory(tmp.root).setInitialBranch("main").call().use { git ->
            val base = commit(git, "a.txt", "a")
            commit(git, "b.txt", "b")
            commit(git, "a.txt", "a2")
            val tip = commit(git, "c.txt", "c")

            val s = PushSummary.of(git.repository, listOf(applied(base, tip, "refs/heads/main")))
            assertEquals(listOf("main"), s.branches)
            assertEquals(3, s.commits)
            assertEquals(2, s.filesAdded)      // b.txt, c.txt
            assertEquals(1, s.filesModified)   // a.txt
            assertEquals("main", s.headline())
            assertEquals("3 commits · +2 ~1 −0 files", s.details(-1))
        }
    }

    @Test
    fun newBranchFromExistingHistoryHasNoNewCommits() {
        Git.init().setDirectory(tmp.root).setInitialBranch("main").call().use { git ->
            val tip = commit(git, "a.txt", "a")
            git.branchCreate().setName("feature").call()
            val s = PushSummary.of(git.repository, listOf(applied(ObjectId.zeroId(), tip, "refs/heads/feature")))
            assertEquals(listOf("feature"), s.branches)
            assertEquals(0, s.commits)
        }
    }

    @Test
    fun remoteTrackingRefsAreIgnoredAndDeletesReported() {
        Git.init().setDirectory(tmp.root).setInitialBranch("main").call().use { git ->
            val tip = commit(git, "a.txt", "a")
            val s = PushSummary.of(git.repository, listOf(
                applied(ObjectId.zeroId(), tip, "refs/remotes/origin/main"),
                applied(tip, ObjectId.zeroId(), "refs/heads/old"),
            ))
            assertEquals(emptyList<String>(), s.branches)
            assertEquals(listOf("old"), s.deletedBranches)
            assertEquals("deleted old", s.headline())
        }
    }
}
