package com.novastore.app.core.updater

import com.novastore.app.core.model.UpdateState

/**
 * Explicit state machine for the update lifecycle (prompt #70).
 * A single source of truth — no scattered boolean flags.
 */
object UpdateStateMachine {

    private val transitions: Map<UpdateState, Set<UpdateState>> = mapOf(
        UpdateState.DISCOVERED to setOf(UpdateState.RESOLVED, UpdateState.FAILED, UpdateState.CANCELLED),
        UpdateState.RESOLVED to setOf(UpdateState.QUEUED, UpdateState.FAILED, UpdateState.CANCELLED),
        UpdateState.QUEUED to setOf(UpdateState.DOWNLOADING, UpdateState.FAILED, UpdateState.CANCELLED),
        UpdateState.DOWNLOADING to setOf(
            UpdateState.DOWNLOADED,
            UpdateState.QUEUED, // retry
            UpdateState.FAILED,
            UpdateState.CANCELLED,
        ),
        UpdateState.DOWNLOADED to setOf(UpdateState.VERIFYING, UpdateState.FAILED, UpdateState.CANCELLED),
        UpdateState.VERIFYING to setOf(UpdateState.VERIFIED, UpdateState.FAILED, UpdateState.CANCELLED),
        UpdateState.VERIFIED to setOf(
            UpdateState.WAITING_FOR_USER,
            UpdateState.INSTALLING,
            UpdateState.FAILED,
            UpdateState.CANCELLED,
        ),
        UpdateState.WAITING_FOR_USER to setOf(UpdateState.INSTALLING, UpdateState.FAILED, UpdateState.CANCELLED),
        UpdateState.INSTALLING to setOf(UpdateState.INSTALLED, UpdateState.FAILED, UpdateState.WAITING_FOR_USER),
        UpdateState.INSTALLED to setOf(UpdateState.CONFIRMED, UpdateState.FAILED),
        UpdateState.CONFIRMED to emptySet(),
        UpdateState.FAILED to setOf(UpdateState.QUEUED), // retry after failure
        UpdateState.CANCELLED to emptySet(),
    )

    fun canTransition(from: UpdateState, to: UpdateState): Boolean =
        transitions[from]?.contains(to) == true

    /**
     * Validates and applies a transition. Throws [IllegalStateException]
     * for an illegal transition so bugs surface during development.
     */
    fun transition(from: UpdateState, to: UpdateState): UpdateState {
        if (!canTransition(from, to)) {
            throw IllegalStateException("Illegal update state transition: $from → $to")
        }
        return to
    }

    /** Non-throwing variant used at persistence boundaries. */
    fun transitionOrNull(from: UpdateState, to: UpdateState): UpdateState? =
        if (canTransition(from, to)) to else null
}
