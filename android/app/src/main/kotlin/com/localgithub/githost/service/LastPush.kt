package com.localgithub.githost.service

import android.content.SharedPreferences
import com.localgithub.githost.EventBus

/**
 * The most recent push that changed a repository, as shown on its card.
 *
 * Stored in shared preferences under `last_push_<repo>_<field>` keys. Only
 * pushes that changed something are recorded, so the routine no-op pushes of
 * a sync never overwrite a meaningful entry.
 */
data class LastPush(
    val label: String,
    /** Primary branch the push moved, or null for a tag-only or delete-only push. */
    val branch: String?,
    val commits: Int,
    val bytes: Long,
    val added: Int,
    val modified: Int,
    val deleted: Int,
    val timeMillis: Long,
) {
    companion object {
        private fun key(repo: String, field: String) = "last_push_${repo}_$field"

        /** Remembers [event], a push that changed something, as the repo's last push. */
        fun record(prefs: SharedPreferences, event: EventBus.GitEvent) {
            val push = event.push ?: return
            val repo = event.repo
            prefs.edit()
                .putString(key(repo, "label"), event.label)
                .putString(key(repo, "branch"), push.primaryBranch)
                .putInt(key(repo, "commits"), push.commits)
                .putLong(key(repo, "bytes"), event.bytes)
                .putInt(key(repo, "added"), push.filesAdded)
                .putInt(key(repo, "modified"), push.filesModified)
                .putInt(key(repo, "deleted"), push.filesDeleted)
                .putLong(key(repo, "time"), System.currentTimeMillis())
                .apply()
        }

        /** The recorded last push of [repo], or null if none has been seen. */
        fun load(prefs: SharedPreferences, repo: String): LastPush? {
            val label = prefs.getString(key(repo, "label"), null) ?: return null
            return LastPush(
                label = label,
                branch = prefs.getString(key(repo, "branch"), null)?.takeIf { it != "unknown" },
                commits = prefs.getInt(key(repo, "commits"), 0),
                bytes = prefs.getLong(key(repo, "bytes"), -1L),
                added = prefs.getInt(key(repo, "added"), 0),
                modified = prefs.getInt(key(repo, "modified"), 0),
                deleted = prefs.getInt(key(repo, "deleted"), 0),
                timeMillis = prefs.getLong(key(repo, "time"), 0L),
            )
        }

        /** Forgets the last push of [repo], e.g. when the repository is deleted. */
        fun clear(prefs: SharedPreferences, repo: String) {
            val prefix = "last_push_${repo}_"
            prefs.edit().apply {
                prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
            }.apply()
        }
    }
}
