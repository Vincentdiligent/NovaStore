package com.novastore.app.data.repository

import android.content.Context
import com.novastore.app.core.common.AppResult
import com.novastore.app.core.database.dao.CatalogDao
import com.novastore.app.core.database.dao.RepositoryDao
import com.novastore.app.core.database.entity.AppVersionEntity
import com.novastore.app.core.database.entity.RemoteAppEntity
import com.novastore.app.core.database.entity.RepositoryEntity
import com.novastore.app.core.model.ArtifactType
import com.novastore.app.core.model.NovaError
import com.novastore.app.core.model.RepositoryConfig
import com.novastore.app.core.model.SourceTrust
import com.novastore.app.core.network.fdroid.FdroidIndexClient
import com.novastore.app.core.network.fdroid.IndexValidators
import com.novastore.app.core.network.fdroid.ParsedIndex
import com.novastore.app.data.mapper.toModel
import com.novastore.app.domain.repository.RepositoriesRepository
import com.novastore.app.domain.repository.ScanProgress
import com.novastore.app.domain.repository.ScanProgressTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the repository list and the local catalog in sync. Every repository
 * (built-in or user added) is a row in the database; its apps are stored in
 * the catalog under the repository id.
 */
@Singleton
class RepositoriesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repositoryDao: RepositoryDao,
    private val catalogDao: CatalogDao,
    private val indexClient: FdroidIndexClient,
    private val scanProgress: ScanProgressTracker,
) : RepositoriesRepository {

    /** One lock per repository: different repositories refresh in parallel. */
    private val repositoryLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Index parsing + catalog replacement stays serialized: a parsed F-Droid
     * index lives fully in memory, two at once would double peak RAM.
     */
    private val storeMutex = Mutex()

    private fun lockFor(repositoryId: String): Mutex = repositoryLocks.getOrPut(repositoryId) { Mutex() }
    private val seedMutex = Mutex()
    @Volatile private var seeded = false

    override fun observe(): Flow<List<RepositoryConfig>> = flow {
        ensureBuiltIns()
        emitAll(repositoryDao.observeAll().map { entities -> entities.map { it.toModel() } })
    }

    override fun observeAppCounts(): Flow<Map<String, Int>> =
        catalogDao.observeCountsBySource().map { counts -> counts.associate { it.source to it.count } }

    override suspend fun get(repositoryId: String): RepositoryConfig? =
        repositoryDao.get(repositoryId)?.toModel()

    override suspend fun priorities(): Map<String, Int> {
        ensureBuiltIns()
        return repositoryDao.all().associate { it.repositoryId to it.priority }
    }

    override suspend fun add(name: String, url: String): AppResult<Unit> {
        ensureBuiltIns()
        val normalized = FdroidIndexClient.normalizeRepoUrl(url)
        if (!normalized.startsWith("https://")) {
            return AppResult.failure(NovaError.Repository(userMessage = "Repository URLs must use HTTPS."))
        }
        val existing = repositoryDao.all().firstOrNull {
            FdroidIndexClient.normalizeRepoUrl(it.baseUrl).equals(normalized, ignoreCase = true)
        }
        val id = existing?.repositoryId ?: ("custom-" + sha256(normalized.lowercase()).take(12))
        if (existing == null) {
            repositoryDao.upsert(
                RepositoryEntity(
                    repositoryId = id,
                    name = name.trim().ifBlank { normalized.substringAfter("://").substringBefore('/') },
                    baseUrl = normalized,
                    metadataUrl = "$normalized/index-v2.json",
                    trust = SourceTrust.UNKNOWN.name,
                    enabled = true,
                    isBuiltIn = false,
                    lastRefreshAt = null,
                    lastRefreshError = null,
                    priority = CUSTOM_PRIORITY,
                ),
            )
        } else {
            repositoryDao.setEnabled(id, true)
        }
        return refresh(id)
    }

    override suspend fun setEnabled(repositoryId: String, enabled: Boolean): AppResult<Unit> {
        repositoryDao.setEnabled(repositoryId, enabled)
        return if (enabled) {
            refresh(repositoryId)
        } else {
            catalogDao.clearSource(repositoryId)
            AppResult.success(Unit)
        }
    }

    override suspend fun remove(repositoryId: String) {
        val repository = repositoryDao.get(repositoryId) ?: return
        catalogDao.clearSource(repositoryId)
        if (repository.isBuiltIn) {
            repositoryDao.setEnabled(repositoryId, false)
        } else {
            repositoryDao.delete(repositoryId)
        }
    }

    override suspend fun refresh(repositoryId: String): AppResult<Unit> {
        ensureBuiltIns()
        return lockFor(repositoryId).withLock {
            val repository = repositoryDao.get(repositoryId)
                ?: return@withLock AppResult.failure(
                    NovaError.Repository(userMessage = "Unknown repository.", repositoryId = repositoryId),
                )
            refreshLocked(repository, force = true)
        }
    }

    override suspend fun refreshAll(force: Boolean): AppResult<Unit> {
        ensureBuiltIns()
        val enabled = repositoryDao.all().filter { it.enabled }.sortedBy { it.priority }
        if (enabled.isEmpty()) {
            return AppResult.failure(NovaError.Repository(userMessage = "All repositories are disabled. Enable at least one in Settings."))
        }
        // Index downloads run in parallel (bounded) — the old one-by-one loop
        // made every scan wait for the slowest mirror of every repository.
        val finished = AtomicInteger(0)
        val results = scanProgress.track(ScanProgress(ScanProgress.Stage.REPOSITORIES, 0, enabled.size)) {
            coroutineScope {
                val gate = Semaphore(REFRESH_PARALLELISM)
                enabled.map { repository ->
                    async {
                        gate.withPermit {
                            val result = lockFor(repository.repositoryId).withLock {
                                // Re-read: a concurrent refresh may have just updated this repository.
                                val current = repositoryDao.get(repository.repositoryId) ?: return@withLock null
                                if (!current.enabled) return@withLock null
                                refreshLocked(current, force)
                            }
                            scanProgress.update(
                                ScanProgress(
                                    ScanProgress.Stage.REPOSITORIES,
                                    finished.incrementAndGet(),
                                    enabled.size,
                                    repository.name,
                                ),
                            )
                            result
                        }
                    }
                }.awaitAll()
            }
        }.filterNotNull()
        val succeeded = results.count { it is AppResult.Success }
        val firstError = results.filterIsInstance<AppResult.Failure>().firstOrNull()?.error
        return if (succeeded == 0 && firstError != null) AppResult.failure(firstError) else AppResult.success(Unit)
    }

    private suspend fun refreshLocked(repository: RepositoryEntity, force: Boolean): AppResult<Unit> {
        val id = repository.repositoryId
        val now = System.currentTimeMillis()
        val hasCatalog = catalogDao.appCount(id) > 0
        val lastOk = repository.lastRefreshAt?.takeIf { repository.lastRefreshError == null }
        if (!force && hasCatalog && lastOk != null && now - lastOk < AUTO_REFRESH_INTERVAL_MS) {
            return AppResult.success(Unit)
        }

        val indexFile = File(context.cacheDir, "index_$id.json")
        try {
            val validators = if (hasCatalog) {
                IndexValidators(repository.httpLastModified, repository.httpEtag)
                    .takeIf { it.lastModified != null || it.etag != null }
            } else {
                null
            }
            val fetched = when (
                val download = indexClient.fetchIndex(
                    repoUrl = repository.baseUrl,
                    targetFile = indexFile,
                    knownIndexUrl = repository.metadataUrl,
                    validators = validators,
                )
            ) {
                is AppResult.Failure -> {
                    repositoryDao.setRefreshResult(id, now, download.error.userMessage)
                    return AppResult.failure(download.error)
                }
                is AppResult.Success -> download.value
            }
            if (fetched == null) {
                // 304 Not Modified: the cached catalog is current.
                repositoryDao.setRefreshSuccess(id, now, repository.metadataUrl, repository.httpLastModified, repository.httpEtag)
                return AppResult.success(Unit)
            }

            val parseError = storeMutex.withLock {
                val parsed = when (val result = indexClient.parseIndex(indexFile, fetched, preferredLocales())) {
                    is AppResult.Failure -> return@withLock result.error
                    is AppResult.Success -> result.value
                }
                if (parsed.apps.isEmpty()) {
                    return@withLock NovaError.Repository(
                        userMessage = "The repository index contains no installable apps.",
                        repositoryId = id,
                    )
                }
                storeCatalog(id, parsed)
                if (!repository.isBuiltIn && parsed.repoName != null && repository.name == repository.baseUrl.substringAfter("://").substringBefore('/')) {
                    repositoryDao.get(id)?.let { repositoryDao.upsert(it.copy(name = parsed.repoName!!)) }
                }
                null
            }
            if (parseError != null) {
                repositoryDao.setRefreshResult(id, now, parseError.userMessage)
                return AppResult.failure(parseError)
            }

            repositoryDao.setRefreshSuccess(id, now, fetched.indexUrl, fetched.validators.lastModified, fetched.validators.etag)
            return AppResult.success(Unit)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val error = NovaError.Repository(
                userMessage = "Updating ${repository.name} failed: ${t::class.simpleName}: ${t.message}",
                repositoryId = id,
            )
            repositoryDao.setRefreshResult(id, now, error.userMessage)
            return AppResult.failure(error)
        } finally {
            indexFile.delete()
        }
    }

    private suspend fun storeCatalog(source: String, parsed: ParsedIndex) {
        val apps = parsed.apps.map { app ->
            RemoteAppEntity(
                packageName = app.packageName,
                name = app.name,
                summary = app.summary,
                developer = app.developer,
                iconUrl = app.iconUrl,
                license = app.license,
                description = app.description,
                changelog = app.changelog,
                website = app.website,
                sourceCodeUrl = app.sourceCode,
                categories = app.categories.joinToString(SEPARATOR),
                source = source,
                addedAt = app.added,
                lastUpdatedAt = app.lastUpdated,
            )
        }
        val versions = parsed.versions.map { version ->
            AppVersionEntity(
                packageName = version.packageName,
                versionCode = version.versionCode,
                versionName = version.versionName,
                source = source,
                size = version.size,
                downloadUrl = version.downloadUrl,
                sha256 = version.sha256,
                minSdk = version.minSdk,
                targetSdk = version.targetSdk,
                addedAt = version.added,
                artifactType = ArtifactType.APK.name,
                signer = version.signer,
                nativeCode = version.nativeCode.joinToString(SEPARATOR),
            )
        }
        catalogDao.replaceSource(source, apps, versions)
    }

    /** Inserts built-in repositories that are not in the database yet. */
    private suspend fun ensureBuiltIns() {
        if (seeded) return
        seedMutex.withLock {
            if (seeded) return
            val existing = repositoryDao.all().associateBy { it.repositoryId }
            BUILT_IN_REPOSITORIES.forEachIndexed { index, builtIn ->
                val row = existing[builtIn.id]
                if (row == null) {
                    repositoryDao.upsert(
                        RepositoryEntity(
                            repositoryId = builtIn.id,
                            name = builtIn.name,
                            baseUrl = builtIn.url,
                            metadataUrl = "${builtIn.url}/index-v2.json",
                            trust = SourceTrust.TRUSTED.name,
                            enabled = builtIn.enabledByDefault,
                            isBuiltIn = true,
                            lastRefreshAt = null,
                            lastRefreshError = null,
                            priority = index,
                        ),
                    )
                } else if (row.priority != index || row.name != builtIn.name) {
                    repositoryDao.upsert(row.copy(priority = index, name = builtIn.name, isBuiltIn = true))
                }
            }
            seeded = true
        }
    }

    private fun preferredLocales(): List<String> {
        val list = context.resources.configuration.locales
        return (0 until list.size()).map { list[it].toLanguageTag() }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SEPARATOR = "|"
        const val CUSTOM_PRIORITY = 500
        const val AUTO_REFRESH_INTERVAL_MS = 3L * 60 * 60 * 1000

        /** Concurrent index downloads. */
        const val REFRESH_PARALLELISM = 4
    }
}
