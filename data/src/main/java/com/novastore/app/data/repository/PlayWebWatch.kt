package com.novastore.app.data.repository

import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.database.dao.PlayFreshnessDao
import com.novastore.app.core.database.entity.PlayFreshnessEntity
import com.novastore.app.data.websource.PlayWebClient
import com.novastore.app.domain.repository.PlayStalenessSignal
import com.novastore.app.domain.repository.UpdateDiscoveryService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Nova Resolver v8 — DISCOVERY tier.
 *
 * Watches the public play.google.com listings of installed packages and
 * reports the ones whose "Updated on" date changed since the previous scan.
 * No account, no mirror, no third-party server: the listing page is public
 * and served everywhere (unlike the Cloudflare-gated mirrors).
 *
 * The signal is deliberately weak-but-honest: a changed date means the
 * developer published something new; it does NOT confirm a new versionCode.
 * That confirmation (EXACT) still comes from a native session or a mirror,
 * so the engine only ever tags these candidates as DISCOVERY.
 *
 * The very first observation of a package only records the baseline —
 * a date nobody has seen before must not produce an update signal.
 */
@Singleton
class PlayWebWatch @Inject constructor(
    private val playWebClient: PlayWebClient,
    private val playFreshnessDao: PlayFreshnessDao,
    private val settingsDataStore: SettingsDataStore,
    private val dispatcherProvider: DispatcherProvider,
) : UpdateDiscoveryService {

    override suspend fun findStalenessSignals(packageNames: Collection<String>): List<PlayStalenessSignal> =
        withContext(dispatcherProvider.io) {
            if (packageNames.isEmpty()) return@withContext emptyList()
            if (!settingsDataStore.playWebCatalogEnabledSnapshot()) return@withContext emptyList()

            val now = System.currentTimeMillis()
            val previous = playFreshnessDao.getAll(packageNames)
                .associateBy { it.packageName }

            // Listing pages are heavy (~1 MB each); one scan covers a bounded
            // pool, oldest-checked first, so every package is eventually
            // watched without hammering the network.
            val pool = packageNames.distinct()
                .sortedBy { pkg -> previous[pkg]?.checkedAt ?: 0L }
                .take(SCAN_LIMIT)

            val semaphore = Semaphore(PARALLELISM)
            coroutineScope {
                pool.map { pkg ->
                    async {
                        semaphore.withPermit {
                            // The listing is always requested in English so the
                            // "Updated on" date parses deterministically.
                            val details = runCatching {
                                playWebClient.details(pkg, "en-US")
                            }.getOrNull() ?: return@withPermit null

                            val updated = details.updatedMillis ?: return@withPermit null
                            val known = previous[pkg]
                            when {
                                // First observation: baseline only, no signal.
                                known == null || known.playUpdatedMillis == null -> {
                                    playFreshnessDao.upsert(
                                        PlayFreshnessEntity(pkg, updated, now),
                                    )
                                    null
                                }
                                // The date moved — this is the signal.
                                known.playUpdatedMillis != updated -> {
                                    playFreshnessDao.upsert(
                                        PlayFreshnessEntity(pkg, updated, now),
                                    )
                                    PlayStalenessSignal(pkg, updated)
                                }
                                else -> {
                                    playFreshnessDao.upsert(
                                        PlayFreshnessEntity(pkg, updated, now),
                                    )
                                    null
                                }
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }

    private companion object {
        /** Listing pages are heavy (~1 MB); a small pool keeps the scan polite. */
        const val PARALLELISM = 6

        /** Max listings fetched per scan; the rest wait for the next scan. */
        const val SCAN_LIMIT = 24
    }
}
