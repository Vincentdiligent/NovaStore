package com.novastore.app.data.mapper

import com.novastore.app.core.database.entity.AppVersionEntity
import com.novastore.app.core.database.entity.InstalledAppEntity
import com.novastore.app.core.database.entity.RemoteAppEntity
import com.novastore.app.core.database.entity.RepositoryEntity
import com.novastore.app.core.database.entity.UpdateEntity
import com.novastore.app.core.database.entity.UpdateHistoryEntity
import com.novastore.app.core.model.AppVersion
import com.novastore.app.core.model.ArtifactType
import com.novastore.app.core.model.InstalledApp
import com.novastore.app.core.model.RemoteApp
import com.novastore.app.core.model.RemoteAppDetails
import com.novastore.app.core.model.RepositoryConfig
import com.novastore.app.core.model.SourceTrust
import com.novastore.app.core.model.UpdateCandidate
import com.novastore.app.core.model.UpdateConfidence
import com.novastore.app.core.model.UpdateHistoryRecord
import com.novastore.app.core.model.UpdateHistoryResult
import com.novastore.app.core.model.UpdateState

fun InstalledApp.toEntity(icon: ByteArray?, scannedAt: Long): InstalledAppEntity =
    InstalledAppEntity(
        packageName = packageName,
        appName = appName,
        versionName = versionName,
        versionCode = versionCode,
        firstInstallTime = firstInstallTime,
        lastUpdateTime = lastUpdateTime,
        installerSource = installerSource,
        signingCertDigest = signingCertDigest,
        isSystemApp = isSystemApp,
        icon = icon,
        scannedAt = scannedAt,
    )

fun InstalledAppEntity.toModel(): InstalledApp =
    InstalledApp(
        packageName = packageName,
        appName = appName,
        versionName = versionName,
        versionCode = versionCode,
        firstInstallTime = firstInstallTime,
        lastUpdateTime = lastUpdateTime,
        installerSource = installerSource,
        signingCertDigest = signingCertDigest,
        isSystemApp = isSystemApp,
    )

fun RemoteAppEntity.toModel(): RemoteApp =
    RemoteApp(
        packageName = packageName,
        name = name,
        summary = summary,
        developer = developer,
        iconUrl = iconUrl,
        license = license,
        categories = categories.split(CATEGORY_SEPARATOR).filter { it.isNotBlank() },
        source = source,
    )

fun AppVersion.toEntity(): AppVersionEntity =
    AppVersionEntity(
        packageName = packageName,
        versionCode = versionCode,
        versionName = versionName,
        source = source,
        size = size,
        downloadUrl = downloadUrl,
        sha256 = sha256,
        minSdk = minSdk,
        targetSdk = targetSdk,
        addedAt = addedAt,
        artifactType = artifactType.name,
        signer = signer,
        nativeCode = nativeCode.joinToString(CATEGORY_SEPARATOR),
    )

fun AppVersionEntity.toModel(): AppVersion =
    AppVersion(
        packageName = packageName,
        versionCode = versionCode,
        versionName = versionName,
        source = source,
        size = size,
        downloadUrl = downloadUrl,
        sha256 = sha256,
        minSdk = minSdk,
        targetSdk = targetSdk,
        addedAt = addedAt,
        artifactType = runCatching { ArtifactType.valueOf(artifactType) }.getOrDefault(ArtifactType.APK),
        signer = signer,
        nativeCode = nativeCode.split(CATEGORY_SEPARATOR).filter { it.isNotBlank() },
    )

fun UpdateCandidate.toEntity(now: Long, state: UpdateState = UpdateState.DISCOVERED): UpdateEntity =
    UpdateEntity(
        packageName = installed.packageName,
        installedVersionCode = installed.versionCode,
        installedVersionName = installed.versionName,
        availableVersionCode = available.versionCode,
        availableVersionName = available.versionName,
        source = source,
        downloadUrl = available.downloadUrl,
        sha256 = available.sha256,
        size = available.size,
        state = state.name,
        lastError = null,
        createdAt = now,
        updatedAt = now,
        confidence = confidence.name,
    )

fun UpdateEntity.toCandidate(app: InstalledAppEntity?, version: AppVersionEntity?): UpdateCandidate? {
    if (app == null || version == null) return null
    return UpdateCandidate(
        installed = app.toModel(),
        available = version.toModel(),
        source = source,
        confidence = runCatching { UpdateConfidence.valueOf(confidence) }
            .getOrDefault(UpdateConfidence.EXACT),
    )
}

fun RepositoryEntity.toModel(): RepositoryConfig =
    RepositoryConfig(
        repositoryId = repositoryId,
        name = name,
        baseUrl = baseUrl,
        metadataUrl = metadataUrl,
        trust = runCatching { SourceTrust.valueOf(trust) }.getOrDefault(SourceTrust.UNKNOWN),
        enabled = enabled,
        isBuiltIn = isBuiltIn,
        lastRefreshAt = lastRefreshAt,
        lastRefreshError = lastRefreshError,
    )

fun RepositoryConfig.toEntity(lastRefreshAt: Long?, lastRefreshError: String?, priority: Int): RepositoryEntity =
    RepositoryEntity(
        repositoryId = repositoryId,
        name = name,
        baseUrl = baseUrl,
        metadataUrl = metadataUrl,
        trust = trust.name,
        enabled = enabled,
        isBuiltIn = isBuiltIn,
        lastRefreshAt = lastRefreshAt,
        lastRefreshError = lastRefreshError,
        priority = priority,
    )

fun UpdateHistoryEntity.toModel(): UpdateHistoryRecord =
    UpdateHistoryRecord(
        packageName = packageName,
        appName = appName,
        oldVersion = oldVersion,
        newVersion = newVersion,
        timestamp = timestamp,
        source = source,
        result = runCatching { UpdateHistoryResult.valueOf(result) }.getOrDefault(UpdateHistoryResult.FAILED),
        error = error,
    )

fun UpdateHistoryRecord.toEntity(): UpdateHistoryEntity =
    UpdateHistoryEntity(
        packageName = packageName,
        appName = appName,
        oldVersion = oldVersion,
        newVersion = newVersion,
        timestamp = timestamp,
        source = source,
        result = result.name,
        error = error,
    )

fun remoteDetails(
    app: RemoteAppEntity,
    versions: List<AppVersionEntity>,
): RemoteAppDetails =
    RemoteAppDetails(
        app = app.toModel(),
        description = app.description,
        changelog = app.changelog,
        website = app.website,
        sourceCodeUrl = app.sourceCodeUrl,
        versions = versions.map { it.toModel() },
    )

private const val CATEGORY_SEPARATOR = "|"
