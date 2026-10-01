package com.novastore.app.domain.usecase

import com.novastore.app.core.common.AppResult
import com.novastore.app.domain.repository.RepositoriesRepository
import javax.inject.Inject

/** Refreshes metadata of all enabled repositories. */
class RefreshRepositoriesUseCase @Inject constructor(
    private val repositoriesRepository: RepositoriesRepository,
) {
    suspend operator fun invoke(force: Boolean = false): AppResult<Unit> = repositoriesRepository.refreshAll(force)
}
