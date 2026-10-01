package com.novastore.app.core.updater

import com.novastore.app.core.model.UpdateCandidate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which source wins when several repositories offer an update for
 * the same package. Deterministic: highest versionCode, then repository
 * priority (lower first), then source id. Candidates are already filtered
 * to signatures compatible with the installed app.
 */
@Singleton
class SourceResolutionPolicy @Inject constructor() {

    fun select(
        candidatesBySource: Map<String, UpdateCandidate>,
        priorityOf: (String) -> Int,
    ): UpdateCandidate? =
        candidatesBySource.values
            .filter { it.compatibility.compatible }
            .sortedWith(
                compareByDescending<UpdateCandidate> { it.available.versionCode }
                    .thenBy { priorityOf(it.source) }
                    .thenBy { it.source },
            )
            .firstOrNull()
}
