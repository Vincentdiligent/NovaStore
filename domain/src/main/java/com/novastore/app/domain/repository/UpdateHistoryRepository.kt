package com.novastore.app.domain.repository

import com.novastore.app.core.model.UpdateHistoryRecord
import kotlinx.coroutines.flow.Flow

interface UpdateHistoryRepository {
    suspend fun record(entry: UpdateHistoryRecord)
    fun observe(): Flow<List<UpdateHistoryRecord>>
    suspend fun clear()
}
