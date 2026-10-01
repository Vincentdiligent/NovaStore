package com.novastore.app.domain.repository

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the catalog/update pipeline is busy with right now. */
data class ScanProgress(
    val stage: Stage,
    /** Finished units of the current stage (repositories, packages…). */
    val done: Int = 0,
    /** Total units of the current stage; 0 = indeterminate. */
    val total: Int = 0,
    /** Name of the repository/source currently being processed, if any. */
    val label: String? = null,
) {
    enum class Stage { REPOSITORIES, INSTALLED_APPS, GOOGLE_PLAY, MIRRORS }
}

/**
 * Process-wide progress of repository refreshes and update scans. The app
 * module mirrors it into an ongoing notification, so the user always sees
 * that repositories are loading — also when the scan runs from the UI.
 */
@Singleton
class ScanProgressTracker @Inject constructor() {
    private val state = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = state.asStateFlow()

    @Volatile
    private var depth = 0

    /** Marks the start of a (possibly nested) operation. */
    @Synchronized
    fun begin(initial: ScanProgress) {
        depth++
        state.value = initial
    }

    fun update(progress: ScanProgress) {
        if (depth > 0) state.value = progress
    }

    /** Ends one operation; the progress clears when the outermost one ends. */
    @Synchronized
    fun end() {
        depth = (depth - 1).coerceAtLeast(0)
        if (depth == 0) state.value = null
    }

    inline fun <T> track(initial: ScanProgress, block: () -> T): T {
        begin(initial)
        try {
            return block()
        } finally {
            end()
        }
    }
}
