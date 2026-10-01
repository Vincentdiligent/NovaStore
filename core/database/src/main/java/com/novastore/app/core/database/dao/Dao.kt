package com.novastore.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.novastore.app.core.database.entity.DownloadEntity
import com.novastore.app.core.database.entity.UpdateEntity
import com.novastore.app.core.database.entity.UpdateHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UpdateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(update: UpdateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(updates: List<UpdateEntity>)

    @Query("SELECT * FROM updates ORDER BY packageName")
    fun observeAll(): Flow<List<UpdateEntity>>

    @Query("SELECT * FROM updates ORDER BY packageName")
    suspend fun all(): List<UpdateEntity>

    @Query("SELECT * FROM updates WHERE packageName = :packageName")
    suspend fun get(packageName: String): UpdateEntity?

    @Query("UPDATE updates SET state = :state, updatedAt = :now, lastError = :error WHERE packageName = :packageName")
    suspend fun updateState(packageName: String, state: String, now: Long, error: String? = null)

    @Query("DELETE FROM updates WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM updates WHERE state IN ('CONFIRMED', 'FAILED', 'CANCELLED')")
    suspend fun clearFinished()

    @Query("SELECT * FROM updates WHERE state = :state")
    suspend fun byState(state: String): List<UpdateEntity>
}

@Dao
interface UpdateHistoryDao {
    @Insert
    suspend fun insert(entry: UpdateHistoryEntity)

    @Query("SELECT * FROM update_history ORDER BY timestamp DESC LIMIT 500")
    fun observe(): Flow<List<UpdateHistoryEntity>>

    @Query("DELETE FROM update_history")
    suspend fun clear()
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadEntity): Long

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE packageName = :packageName ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestForPackage(packageName: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE packageName = :packageName AND versionCode = :versionCode ORDER BY createdAt DESC LIMIT 1")
    suspend fun getForPackage(packageName: String, versionCode: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED', 'DOWNLOADING', 'PAUSED', 'VERIFYING') ORDER BY createdAt ASC")
    suspend fun activeTasks(): List<DownloadEntity>

    @Query("UPDATE downloads SET state = :state, downloadedBytes = :bytes, updatedAt = :now, lastError = :error, attempts = :attempts WHERE taskId = :taskId")
    suspend fun updateProgress(taskId: Long, state: String, bytes: Long, now: Long, error: String?, attempts: Int)

    @Query("UPDATE downloads SET state = :state, updatedAt = :now, lastError = :error WHERE taskId = :taskId")
    suspend fun updateState(taskId: Long, state: String, now: Long, error: String? = null)

    @Query("DELETE FROM downloads WHERE state IN ('COMPLETED', 'CANCELLED')")
    suspend fun clearTerminal()

    @Query("SELECT * FROM downloads WHERE state = 'COMPLETED' AND packageName = :packageName AND versionCode = :versionCode ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getCompleted(packageName: String, versionCode: Long): DownloadEntity?

    @Query("DELETE FROM downloads WHERE packageName = :packageName")
    suspend fun deleteForPackage(packageName: String)

    @Query("SELECT * FROM downloads WHERE state = 'COMPLETED'")
    suspend fun completedTasks(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE state IN ('FAILED', 'CANCELLED')")
    suspend fun finishedTasks(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE state = 'COMPLETED' AND updatedAt < :olderThan")
    suspend fun completedOlderThan(olderThan: Long): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE taskId = :taskId")
    suspend fun deleteById(taskId: Long)
}
