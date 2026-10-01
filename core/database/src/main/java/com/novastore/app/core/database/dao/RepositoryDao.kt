package com.novastore.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.novastore.app.core.database.entity.RepositoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RepositoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(repository: RepositoryEntity)

    @Query("SELECT * FROM repositories ORDER BY priority, name COLLATE NOCASE")
    fun observeAll(): Flow<List<RepositoryEntity>>

    @Query("SELECT * FROM repositories WHERE repositoryId = :repositoryId")
    suspend fun get(repositoryId: String): RepositoryEntity?

    @Query("SELECT * FROM repositories")
    suspend fun all(): List<RepositoryEntity>

    @Query("UPDATE repositories SET enabled = :enabled WHERE repositoryId = :repositoryId")
    suspend fun setEnabled(repositoryId: String, enabled: Boolean)

    @Query("UPDATE repositories SET lastRefreshAt = :timestamp, lastRefreshError = :error WHERE repositoryId = :repositoryId")
    suspend fun setRefreshResult(repositoryId: String, timestamp: Long, error: String?)

    @Query(
        """
        UPDATE repositories SET lastRefreshAt = :timestamp, lastRefreshError = NULL, metadataUrl = :indexUrl,
            httpLastModified = :lastModified, httpEtag = :etag
        WHERE repositoryId = :repositoryId
        """,
    )
    suspend fun setRefreshSuccess(
        repositoryId: String,
        timestamp: Long,
        indexUrl: String,
        lastModified: String?,
        etag: String?,
    )

    @Query("DELETE FROM repositories WHERE repositoryId = :repositoryId")
    suspend fun delete(repositoryId: String)

    @Query("SELECT COUNT(*) FROM repositories")
    suspend fun count(): Int
}
