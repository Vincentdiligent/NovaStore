package com.novastore.app.domain.usecase

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.model.RemoteApp
import com.novastore.app.domain.repository.CatalogRepository
import javax.inject.Inject

/** Searches the locally cached catalog of all enabled sources. */
class SearchAppsUseCase @Inject constructor(
    private val catalogRepository: CatalogRepository,
) {
    suspend operator fun invoke(query: String): AppResult<List<RemoteApp>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return AppResult.success(emptyList())
        }
        return catalogRepository.search(trimmed)
    }

    /** Local-catalog results only — shown instantly while the network sources run. */
    suspend fun local(query: String): List<RemoteApp> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching { catalogRepository.searchLocal(trimmed) }.getOrDefault(emptyList())
    }
}
