package com.localgithub.githost

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Application-wide event bus using SharedFlow.
 * Replaces deprecated LocalBroadcastManager for internal app communication.
 */
object EventBus {

    private val _gitEvents = MutableSharedFlow<GitEvent>(extraBufferCapacity = 10)
    val gitEvents: SharedFlow<GitEvent> = _gitEvents.asSharedFlow()

    private val _serverStopped = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val serverStopped: SharedFlow<Unit> = _serverStopped.asSharedFlow()

    /** A githost://pair link that arrived via an intent, waiting for the pair screen to consume it. */
    val pendingPairUri = MutableStateFlow<String?>(null)

    fun emitGitEvent(event: GitEvent) {
        _gitEvents.tryEmit(event)
    }

    fun emitServerStopped() {
        _serverStopped.tryEmit(Unit)
    }

    data class GitEvent(
        val type: String,  // "push" or "pull"
        val repo: String,
        val label: String,
        val branch: String? = null,
        val bytes: Long = -1L,
        val added: Int = 0,
        val modified: Int = 0,
        val deleted: Int = 0,
    )
}
