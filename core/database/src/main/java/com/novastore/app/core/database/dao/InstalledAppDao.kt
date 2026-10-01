package com.novastore.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.novastore.app.core.database.entity.InstalledAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InstalledAppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(apps: List<InstalledAppEntity>)

    @Query("DELETE FROM installed_apps")
    suspend fun clear()

    /** Replaces the cache so uninstalled apps disappear. */
    @Transaction
    suspend fun replaceAll(apps: List<InstalledAppEntity>) {
        clear()
        upsertAll(apps)
    }

    @Query("DELETE FROM installed_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("SELECT * FROM installed_apps ORDER BY appName COLLATE NOCASE")
    fun observeAll(): Flow<List<InstalledAppEntity>>

    @Query("SELECT * FROM installed_apps WHERE packageName = :packageName")
    suspend fun get(packageName: String): InstalledAppEntity?

    @Query("SELECT packageName FROM installed_apps")
    suspend fun allPackageNames(): List<String>

    @Query("SELECT COUNT(*) FROM installed_apps")
    suspend fun count(): Int
}
