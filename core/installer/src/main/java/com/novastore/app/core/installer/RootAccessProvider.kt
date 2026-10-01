package com.novastore.app.core.installer

import com.novastore.app.core.model.RootAccessResult
import com.novastore.app.core.model.RootAccessState
import com.novastore.app.core.model.RootCommand
import com.novastore.app.core.model.RootCommandResult
import kotlinx.coroutines.flow.Flow

/**
 * Access to the device's root management system (e.g. Magisk).
 * No passwords are stored or requested; authorization happens through
 * the user's root management app.
 */
interface RootAccessProvider {
    /** Verifies actual privileged execution capability, not just `su` presence. */
    suspend fun isRootAvailable(): Boolean

    suspend fun requestRootAccess(): RootAccessResult

    suspend fun execute(command: RootCommand): RootCommandResult

    fun observeState(): Flow<RootAccessState>

    suspend fun currentState(): RootAccessState
}
