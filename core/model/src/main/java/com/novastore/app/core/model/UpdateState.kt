package com.novastore.app.core.model

/**
 * Single source of truth for the lifecycle of an update.
 * Transitions are validated by [com.novastore.app.core.updater.UpdateStateMachine].
 */
enum class UpdateState {
    DISCOVERED,
    RESOLVED,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    VERIFYING,
    VERIFIED,
    WAITING_FOR_USER,
    INSTALLING,
    INSTALLED,
    CONFIRMED,
    FAILED,
    CANCELLED,
}
