package com.novastore.app.data.repository

import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.database.dao.CatalogDao
import com.novastore.app.core.database.dao.InstalledAppDao
import com.novastore.app.core.database.dao.UpdateDao
import com.novastore.app.core.database.dao.UpdateHistoryDao
import com.novastore.app.core.database.entity.AppVersionEntity
import com.novastore.app.core.database.entity.UpdateEntity
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.model.SyntheticVersionCodes
import com.novastore.app.core.model.UpdateCandidate
import com.novastore.app.core.model.UpdateConfidence
import com.novastore.app.core.model.UpdateHistoryRecord
import com.novastore.app.core.model.UpdateState
import com.novastore.app.core.model.VersionComparator
import com.novastore.app.core.updater.UpdateStateMachine
import com.novastore.app.data.mapper.toCandidate
import com.novastore.app.data.mapper.toEntity
import com.novastore.app.data.mapper.toModel
import com.novastore.app.domain.repository.UpdateHistoryRepository
import com.novastore.app.domain.repository.UpdatesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed persistence for update candidates. Every state change goes
 * through [UpdateStateMachine] — the single source of truth.
 */
@Singleton
class UpdatesRepositoryImpl @Inject constructor(
    private val updateDao: UpdateDao,
    private val updateHistoryDao: UpdateHistoryDao,
    private val installedAppDao: InstalledAppDao,
    private val catalogDao: CatalogDao,
    private val settingsDataStore: SettingsDataStore,
    private val dispatcherProvider: DispatcherProvider,
) : UpdatesRepository, UpdateHistoryRepository {

    override fun observeCandidates(): Flow<List<UpdateCandidate>> =
        updateDao.observeAll().map { entities -> entities.mapNotNull { it.toCandidateOrNull() } }

    override fun observeState(packageName: String): Flow<UpdateState?> =
        updateDao.observeAll().map { entities ->
            entities.firstOrNull { it.packageName == packageName }?.let { parseState(it.state) }
        }

    override suspend fun currentCandidates(): List<UpdateCandidate> =
        updateDao.all().mapNotNull { it.toCandidateOrNull() }

    override suspend fun saveCandidate(candidate: UpdateCandidate, state: UpdateState) {
        updateDao.upsert(candidate.toEntity(System.currentTimeMillis()).copy(state = state.name))
    }

    override suspend fun transition(packageName: String, to: UpdateState) {
        val current = updateDao.get(packageName) ?: return
        val from = parseState(current.state)
        val next = UpdateStateMachine.transitionOrNull(from, to)
            ?: to // persistence boundary: unknown/legacy state — store the requested state
        updateDao.updateState(packageName, next.name, System.currentTimeMillis())
    }

    override suspend fun remove(packageName: String) {
        updateDao.delete(packageName)
    }

    /**
     * Rows queued or mid-download survive the scan cleanup so an active
     * download is never deleted under the user — but only while they are
     * actually fresh. A row "in flight" whose clock stopped over a day ago
     * is a zombie from a killed process or a lost task, not a download.
     */
    override suspend fun retainOnly(packageNames: Set<String>) {
        val now = System.currentTimeMillis()
        updateDao.all()
            .filter { entity ->
                val staleInFlight = entity.state in IN_FLIGHT_STATES &&
                    now - entity.updatedAt > IN_FLIGHT_STALENESS_MS
                entity.packageName !in packageNames &&
                    (entity.state !in IN_FLIGHT_STATES || staleInFlight)
            }
            .forEach { updateDao.delete(it.packageName) }
    }

    override suspend fun clearFinished() {
        updateDao.clearFinished()
    }

    override suspend fun lastCheckedAt(): Long = settingsDataStore.lastScanSnapshot()

    override suspend fun setLastCheckedAt(timestampMillis: Long) {
        settingsDataStore.setLastScanTimestamp(timestampMillis)
    }

    // ------------------------------------------------------------------
    // UpdateHistoryRepository
    // ------------------------------------------------------------------

    override suspend fun record(entry: UpdateHistoryRecord) {
        updateHistoryDao.insert(entry.toEntity())
    }

    override fun observe(): Flow<List<UpdateHistoryRecord>> =
        updateHistoryDao.observe().map { entities -> entities.map { it.toModel() } }

    override suspend fun clear() {
        updateHistoryDao.clear()
    }

    // ------------------------------------------------------------------

    private suspend fun UpdateEntity.toCandidateOrNull(): UpdateCandidate? {
        val installed = installedAppDao.get(packageName) ?: return null
        val version = catalogDao.getVersions(packageName)
            .firstOrNull { it.versionCode == availableVersionCode && it.source == source }
            ?: toFallbackVersion()

        // EXACT hard rule (Nova Resolver): the offered versionCode must be
        // STRICTLY higher than the currently installed one. The installed
        // side of a row refreshes live from the system while the stored
        // available side is frozen, so without this check a row created
        // before the app was updated (by Play, another store or manually)
        // keeps rendering "11.11.3 → 11.11.3, ready to install" forever.
        // DISCOVERY rows are exempt: they are date signals whose available
        // versionCode IS the installed one by design.
        //
        // Additional guards (v7.0.2): APKCombo rows carry SYNTHETIC
        // versionCodes (~900M minus rank) that pass the code rule
        // trivially — that is exactly why v7.0.1's fix alone did not clear
        // the junk rows. For synthetic codes the version NAME must also be
        // strictly newer (the same rule the scan applies at creation), and
        // an EQUAL normalized name never counts as an update for anyone —
        // a same-name release is the same release.
        val rowConfidence = runCatching { UpdateConfidence.valueOf(confidence) }
            .getOrDefault(UpdateConfidence.EXACT)
        // DISCOVERY rows (a Play page date signal whose available version IS
        // the installed one) rendered as "11.0.3 -> 11.0.3". Never shown.
        if (rowConfidence == UpdateConfidence.DISCOVERY) return null
        run {
            if (version.versionCode <= installed.versionCode) return null

            val availableName = normalizeVersionName(version.versionName)
            val installedName = normalizeVersionName(installed.versionName)
            if (availableName != null && installedName != null) {
                // Same release name = same release, whatever the codes say.
                if (VersionComparator.compareVersionNames(installedName, availableName) == 0) return null
                // Synthetic mirror codes: require a strictly NEWER name —
                // the code alone proves nothing for them.
                if (SyntheticVersionCodes.isSynthetic(version.versionCode) &&
                    VersionComparator.compareVersionNames(installedName, availableName) >= 0
                ) {
                    return null
                }
            } else if (SyntheticVersionCodes.isSynthetic(version.versionCode)) {
                // No usable names: a synthetic code alone is never an update.
                return null
            }
        }
        return toCandidate(installed, version)
    }

    /** `"v4.0.2 "` and `"4.0.2"` are the same release name. */
    private fun normalizeVersionName(name: String?): String? =
        name?.trim()?.removePrefix("v")?.removePrefix("V")?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * Google Play candidates are not persisted in the catalog database;
     * their version is reconstructed from the stored update row so the
     * candidate list survives process restarts.
     */
    private fun UpdateEntity.toFallbackVersion(): AppVersionEntity = AppVersionEntity(
        packageName = packageName,
        versionCode = availableVersionCode,
        versionName = availableVersionName,
        source = source,
        size = size,
        // Play download URLs are single-use and resolved fresh at download time.
        downloadUrl = downloadUrl,
        sha256 = sha256,
        minSdk = null,
        targetSdk = null,
        addedAt = null,
        artifactType = "APK",
        signer = null,
        nativeCode = "",
    )

    private fun parseState(name: String): UpdateState =
        runCatching { UpdateState.valueOf(name) }.getOrDefault(UpdateState.DISCOVERED)

    private companion object {
        val IN_FLIGHT_STATES = setOf(
            UpdateState.QUEUED.name,
            UpdateState.DOWNLOADING.name,
            UpdateState.VERIFYING.name,
            UpdateState.INSTALLING.name,
        )

        /** Any in-flight row untouched for this long is a zombie, not a download. */
        const val IN_FLIGHT_STALENESS_MS = 24L * 60 * 60 * 1000
    }
}
