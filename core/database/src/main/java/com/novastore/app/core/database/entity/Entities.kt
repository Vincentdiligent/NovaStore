package com.novastore.app.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "installed_apps")
data class InstalledAppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val versionName: String?,
    val versionCode: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val installerSource: String?,
    val signingCertDigest: String?,
    val isSystemApp: Boolean,
    /** PNG bytes of the launcher icon, nullable. */
    val icon: ByteArray?,
    val scannedAt: Long,
)

@Entity(tableName = "remote_apps", primaryKeys = ["packageName", "source"])
data class RemoteAppEntity(
    val packageName: String,
    val name: String,
    val summary: String?,
    val developer: String?,
    val iconUrl: String?,
    val license: String?,
    val description: String?,
    val changelog: String?,
    val website: String?,
    val sourceCodeUrl: String?,
    val categories: String,
    val source: String,
    val addedAt: Long?,
    val lastUpdatedAt: Long?,
)

@Entity(tableName = "app_versions", primaryKeys = ["packageName", "versionCode", "source"])
data class AppVersionEntity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val source: String,
    val size: Long?,
    val downloadUrl: String,
    val sha256: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val addedAt: Long?,
    val artifactType: String,
    val signer: String?,
    /** Pipe separated ABI list; empty when the APK has no native code. */
    val nativeCode: String,
)

@Entity(tableName = "updates")
data class UpdateEntity(
    @PrimaryKey val packageName: String,
    val installedVersionCode: Long,
    val installedVersionName: String?,
    val availableVersionCode: Long,
    val availableVersionName: String?,
    val source: String,
    val downloadUrl: String,
    val sha256: String?,
    val size: Long?,
    val state: String,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** EXACT (real versionCode known) or DISCOVERY (freshness signal only). */
    val confidence: String = "EXACT",
)

@Entity(tableName = "update_history")
data class UpdateHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val oldVersion: String?,
    val newVersion: String?,
    val timestamp: Long,
    val source: String,
    val result: String,
    val error: String?,
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val taskId: Long = 0,
    val packageName: String,
    val appName: String,
    val versionCode: Long,
    val versionName: String?,
    val url: String,
    val fileName: String,
    val localPath: String,
    val sha256: String?,
    val size: Long?,
    val downloadedBytes: Long,
    val state: String,
    val attempts: Int,
    val lastError: String?,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
)


/**
 * Play Web Watch — the last "Updated on" date observed on the public Play
 * listing of a package, used by the DISCOVERY tier of the update engine:
 * the date changed since the previous scan → the app probably has a new
 * release, even when no mirror or Play session is available.
 */
@Entity(tableName = "play_freshness")
data class PlayFreshnessEntity(
    @PrimaryKey val packageName: String,
    /** Epoch millis of the listing's "Updated on" date; null when unknown. */
    val playUpdatedMillis: Long?,
    val checkedAt: Long,
)

/**
 * Local trust cache — the signing certificate and source observed for a
 * package at the time of its last successful Nova install/update. Future
 * versions can consult this chain: a sudden certificate change from an
 * unknown source must never auto-update silently.
 */
@Entity(tableName = "package_trust")
data class PackageTrustEntity(
    @PrimaryKey val packageName: String,
    val certSha256: String?,
    val source: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val installs: Long = 0,
)

@Entity(tableName = "repositories")
data class RepositoryEntity(
    @PrimaryKey val repositoryId: String,
    val name: String,
    val baseUrl: String,
    val metadataUrl: String,
    val trust: String,
    val enabled: Boolean,
    val isBuiltIn: Boolean,
    val lastRefreshAt: Long?,
    val lastRefreshError: String?,
    /** Lower wins when several repositories offer the same package. */
    val priority: Int,
    /** Last-Modified / ETag of the last downloaded index, for conditional requests. */
    val httpLastModified: String? = null,
    val httpEtag: String? = null,
)
