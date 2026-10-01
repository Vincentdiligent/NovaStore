package com.novastore.app.domain.usecase

import com.novastore.app.core.model.AppReview
import com.novastore.app.domain.repository.CatalogRepository
import javax.inject.Inject

/**
 * Loads real user reviews of one app (anonymous public Play review feed).
 * Newest first; empty for apps without reviews.
 */
class GetAppReviewsUseCase @Inject constructor(
    private val catalogRepository: CatalogRepository,
) {
    suspend operator fun invoke(packageName: String): List<AppReview> =
        catalogRepository.getReviews(packageName)
}
