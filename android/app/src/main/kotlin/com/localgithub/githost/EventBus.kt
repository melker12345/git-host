package com.localgithub.githost

import com.localgithub.githost.ssh.PushSummary
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process event bus connecting the SSH server, the foreground service and
 * the UI. Everything runs in one process, so plain flows replace the
 * deprecated LocalBroadcastManager.
 *
 * Emission uses `tryEmit` into a buffer so the SSH threads never suspend on a
 * slow collector; events nobody is collecting are simply dropped.
 */
object EventBus {

    private val _gitEvents = MutableSharedFlow<GitEvent>(extraBufferCapacity = 64)
    /** Pushes and fetches, as they finish. Collected by the service and the repos screen. */
    val gitEvents: SharedFlow<GitEvent> = _gitEvents.asSharedFlow()

    private val _serverStopped = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fires when the service stops the server by itself (idle timeout), so the UI can update. */
    val serverStopped: SharedFlow<Unit> = _serverStopped.asSharedFlow()

    /** A githost://pair link that arrived via an intent, waiting for the pair screen to consume it. */
    val pendingPairUri = MutableStateFlow<String?>(null)

    fun emitGitEvent(event: GitEvent) {
        _gitEvents.tryEmit(event)
    }

    fun emitServerStopped() {
        _serverStopped.tryEmit(Unit)
    }

    /**
     * A git operation a paired device performed against one repository.
     *
     * @property type [PUSH] or [PULL].
     * @property repo the repository directory name, e.g. "code-foo.git".
     * @property label the device's label from the whitelist.
     * @property bytes size of the received pack, or -1 when unknown.
     * @property push what a push changed; null for a pull.
     */
    data class GitEvent(
        val type: String,
        val repo: String,
        val label: String,
        val bytes: Long = -1L,
        val push: PushSummary? = null,
    ) {
        companion object {
            const val PUSH = "push"
            const val PULL = "pull"
        }
    }
}
