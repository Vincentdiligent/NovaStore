package com.novastore.app.domain.repository

import com.novastore.app.core.model.AuthMethod
import kotlinx.coroutines.flow.StateFlow

/**
 * Account state.
 *
 * - [NotSignedIn]: nothing chosen yet.
 * - [SignedIn]: a Google Play session is active (direct Google login or an
 *   anonymous token dispenser pool).
 * - [Anonymous]: F-Droid only mode — no Google Play session is used.
 */
sealed class AccountState {
    data object NotSignedIn : AccountState()

    data class SignedIn(
        val email: String,
        val method: AuthMethod,
    ) : AccountState()

    data object Anonymous : AccountState()
}

interface AccountRepository {
    val accountState: StateFlow<AccountState>

    /**
     * Restores a persisted session (if any) asynchronously and returns the
     * currently known state. Call once at app start.
     */
    fun start(): AccountState

    /**
     * E-mail addresses of the Google accounts present on this device —
     * candidates for the passwordless [signInWithDeviceAccount] flow.
     */
    suspend fun availableDeviceAccounts(): List<String>

    /**
     * Passwordless sign-in through a Google account already registered on
     * this device (Android AccountManager → OAuth token → Play token).
     * No password is typed, stored or handled by Nova Store.
     */
    suspend fun signInWithDeviceAccount(email: String): AccountState

    /**
     * Signs in with a Google account. Throws [com.novastore.app.core.model.PlayStoreException]
     * (carrying a typed [com.novastore.app.core.model.NovaError]) on failure.
     */
    suspend fun signInWithGoogle(email: String, password: String): AccountState

    /**
     * Completes Google's web sign-in: [oauthToken] is the `oauth_token`
     * cookie Google set after the user signed in on its own page.
     */
    suspend fun signInWithWebToken(email: String, oauthToken: String): AccountState

    /**
     * Signs in anonymously: tries the user-configured dispenser URL, then
     * the built-in community dispensers (unless disabled). With nothing
     * available this switches to F-Droid only mode ([AccountState.Anonymous])
     * and throws an explanatory [com.novastore.app.core.model.PlayStoreException].
     */
    suspend fun signInAnonymously(): AccountState

    suspend fun signOut(): AccountState
}

/** Connectivity observation used for offline states and download constraints. */
interface NetworkMonitor {
    val isOnline: kotlinx.coroutines.flow.Flow<Boolean>
    fun isCurrentlyOnline(): Boolean
    fun isCurrentlyUnmetered(): Boolean
}
