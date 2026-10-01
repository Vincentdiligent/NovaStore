package com.novastore.app.core.model

enum class SourceTrust {
    TRUSTED,
    UNKNOWN,
    DISABLED,
    INVALID,
}

/**
 * Description of a user-configurable repository (source).
 */
data class RepositoryConfig(
    val repositoryId: String,
    val name: String,
    val baseUrl: String,
    val metadataUrl: String,
    val trust: SourceTrust,
    val enabled: Boolean,
    val isBuiltIn: Boolean = false,
    val lastRefreshAt: Long? = null,
    val lastRefreshError: String? = null,
)
