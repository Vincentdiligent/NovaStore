package com.novastore.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.novastore.app.core.database.entity.PackageTrustEntity
import com.novastore.app.core.database.entity.PlayFreshnessEntity

/** Play Web Watch persistence (DISCOVERY tier of the update engine). */
@Dao
interface PlayFreshnessDao {

    @Query("SELECT * FROM play_freshness WHERE packageName = :packageName")
    suspend fun get(packageName: String): PlayFreshnessEntity?

    @Query("SELECT * FROM play_freshness WHERE packageName IN (:packageNames)")
    suspend fun getAll(packageNames: Collection<String>): List<PlayFreshnessEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlayFreshnessEntity)

    @Query("DELETE FROM play_freshness WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM play_freshness")
    suspend fun clear()
}

/** Local trust cache: certificate/source history per installed package. */
@Dao
interface PackageTrustDao {

    @Query("SELECT * FROM package_trust WHERE packageName = :packageName")
    suspend fun get(packageName: String): PackageTrustEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PackageTrustEntity)

    @Query("UPDATE package_trust SET installs = installs + 1, lastSeenAt = :now, certSha256 = :certSha256, source = :source WHERE packageName = :packageName")
    suspend fun recordInstall(packageName: String, certSha256: String?, source: String?, now: Long)

    @Query("SELECT * FROM package_trust")
    suspend fun all(): List<PackageTrustEntity>

    @Query("DELETE FROM package_trust")
    suspend fun clear()
}
