package com.novastore.app.domain.repository

import com.novastore.app.core.model.UpdateCandidate
import com.novastore.app.core.model.UpdateState
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for update candidates and their state machine transitions.
 */
interface UpdatesRepository {
    fun observeCandidates(): Flow<List<UpdateCandidate>>
    fun observeState(packageName: String): Flow<UpdateState?>
    suspend fun currentCandidates(): List<UpdateCandidate>
    suspend fun saveCandidate(candidate: UpdateCandidate, state: UpdateState)
    suspend fun transition(packageName: String, to: UpdateState)
    suspend fun remove(packageName: String)

    /** Drops stored candidates for packages that no longer have an update. */
    suspend fun retainOnly(packageNames: Set<String>)
    suspend fun clearFinished()
    suspend fun lastCheckedAt(): Long
    suspend fun setLastCheckedAt(timestampMillis: Long)
}
