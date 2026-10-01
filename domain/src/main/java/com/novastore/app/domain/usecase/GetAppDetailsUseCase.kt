package com.novastore.app.domain.usecase

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.model.RemoteAppDetails
import com.novastore.app.domain.repository.CatalogRepository
import javax.inject.Inject

class GetAppDetailsUseCase @Inject constructor(
    private val catalogRepository: CatalogRepository,
) {
    suspend operator fun invoke(packageName: String): AppResult<RemoteAppDetails?> =
        catalogRepository.getAppDetails(packageName)
}
