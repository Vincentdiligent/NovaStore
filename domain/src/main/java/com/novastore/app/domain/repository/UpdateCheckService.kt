package com.novastore.app.domain.repository

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.model.SourceTrust
import com.novastore.app.core.model.UpdateCandidate

/** Result of one update scan. */
data class UpdateScanReport(
    val installedScanned: Int,
    val candidatesFound: Int,
    val candidates: List<UpdateCandidate>,
    val errors: List<String>,
)

/**
 * Discovery + resolution side of the update engine, implemented in
 * core:updater. It compares installed apps against all enabled sources.
 */
interface UpdateCheckService {
    suspend fun checkForUpdates(): AppResult<UpdateScanReport>
}

/** Aggregated source resolution across providers. */
data class ResolvedSources(
    val packageName: String,
    val candidatesBySource: Map<String, UpdateCandidate>,
    val selectedTrust: SourceTrust?,
)
