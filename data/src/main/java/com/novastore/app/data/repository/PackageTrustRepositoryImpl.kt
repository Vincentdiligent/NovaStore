package com.novastore.app.data.repository

import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.database.dao.PackageTrustDao
import com.novastore.app.core.database.entity.PackageTrustEntity
import com.novastore.app.domain.repository.PackageTrustRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/** Room-backed local trust cache (see [PackageTrustRepository]). */
@Singleton
class PackageTrustRepositoryImpl @Inject constructor(
    private val packageTrustDao: PackageTrustDao,
    private val dispatcherProvider: DispatcherProvider,
) : PackageTrustRepository {

    override suspend fun recordInstall(packageName: String, certSha256: String?, source: String?) =
        withContext(dispatcherProvider.io) {
            val now = System.currentTimeMillis()
            val existing = packageTrustDao.get(packageName)
            if (existing == null) {
                packageTrustDao.upsert(
                    PackageTrustEntity(
                        packageName = packageName,
                        certSha256 = certSha256,
                        source = source,
                        firstSeenAt = now,
                        lastSeenAt = now,
                        installs = 1,
                    ),
                )
            } else {
                packageTrustDao.recordInstall(packageName, certSha256, source, now)
            }
        }
}
