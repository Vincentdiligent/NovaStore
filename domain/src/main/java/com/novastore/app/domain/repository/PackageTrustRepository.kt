package com.novastore.app.domain.repository

/**
 * Nova Resolver v8 — local trust cache.
 *
 * Records the signing certificate and the delivering source observed when
 * Nova successfully installed or updated a package. The chain (version →
 * certificate → source) is the foundation of release reputation: a later
 * version from an unknown source with an unexpected certificate must not
 * auto-update silently.
 */
interface PackageTrustRepository {

    /** Remembers the certificate/source of a successful Nova install. */
    suspend fun recordInstall(packageName: String, certSha256: String?, source: String?)
}
