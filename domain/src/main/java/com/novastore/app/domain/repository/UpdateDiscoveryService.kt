package com.novastore.app.domain.repository

/**
 * Nova Resolver v8 — the DISCOVERY tier contract.
 *
 * Implementations watch public listing pages of packages and report the
 * ones whose publish date changed since the previous observation. The
 * signal is intentionally unconfirmed: it invites the user to check, it
 * never claims a concrete versionCode.
 */
interface UpdateDiscoveryService {

    /**
     * Returns a signal for every package whose public listing was updated
     * since the last call. The first observation of a package establishes
     * the baseline and returns no signal.
     */
    suspend fun findStalenessSignals(packageNames: Collection<String>): List<PlayStalenessSignal>
}

/** One unconfirmed freshness signal for [packageName]. */
data class PlayStalenessSignal(
    val packageName: String,
    val playUpdatedMillis: Long,
)
