package com.novastore.app.data.repository

import android.content.Context
import com.novastore.playapi.AnonymousAuth
import com.novastore.app.data.playauth.DeviceAccountAuth
import com.novastore.playapi.exceptions.AuthException
import com.novastore.playapi.helpers.AuthHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.model.AuthMethod
import com.novastore.app.core.model.NovaError
import com.novastore.app.core.model.PlayStoreException
import com.novastore.app.data.playauth.PlayAuthSession
import com.novastore.app.domain.repository.AccountRepository
import com.novastore.app.domain.repository.AccountState
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Google Play account state machine, persisted in DataStore.
 *
 * Sign-in methods, in order of preference:
 * 1. `signInWithDeviceAccount` — passwordless, borrows the Google account
 *    already on the phone via Android's AccountManager. No server, no
 *    password, no 2FA hassle.
 * 2. `signInWithGoogle` — e-mail + (App) password via ClientLogin.
 * 3. `signInAnonymously` — a pooled account borrowed from a token dispenser
 *    service (custom URL first, then built-in community dispensers);
 *    falls back to F-Droid-only anonymous mode when nothing works.
 */
@Singleton
class AccountRepositoryImpl @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsDataStore: SettingsDataStore,
    private val playStoreRepository: PlayStoreRepositoryImpl,
    private val dispatcherProvider: DispatcherProvider,
    private val deviceProperties: com.novastore.app.data.playauth.PlayDeviceProperties,
) : AccountRepository {

    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)
    private val state = MutableStateFlow<AccountState>(AccountState.NotSignedIn)
    private val restoreMutex = Mutex()

    @Volatile
    private var restoreStarted = false

    init {
        playStoreRepository.sessionListener = PlaySessionListener {
            state.value = AccountState.NotSignedIn
        }
    }

    override val accountState: StateFlow<AccountState> = state.asStateFlow()

    override fun start(): AccountState {
        if (!restoreStarted) {
            restoreStarted = true
            scope.launch { restore() }
        }
        return state.value
    }

    private suspend fun restore() {
        restoreMutex.withLock {
            if (state.value != AccountState.NotSignedIn) return
            val auth = runCatching { playStoreRepository.restoreSession() }.getOrNull()
            if (auth != null) {
                state.value = AccountState.SignedIn(auth.email, playStoreRepository.currentAuthMethod())
            } else if (settingsDataStore.snapshot().anonymousMode) {
                state.value = AccountState.Anonymous
            }
        }
    }

    override suspend fun availableDeviceAccounts(): List<String> =
        withContext(dispatcherProvider.default) {
            DeviceAccountAuth.listAccounts(appContext)
        }

    override suspend fun signInWithDeviceAccount(email: String): AccountState =
        withContext(dispatcherProvider.io) {
            val profile = settingsDataStore.playDeviceProfileSnapshot()
            val auth = try {
                DeviceAccountAuth.login(appContext, email.trim(), deviceProperties.resolve(profile))
            } catch (e: AuthException) {
                throw PlayStoreException(
                    NovaError.Account(
                        userMessage = "Google declined the device-account token for ${email.trim()}: ${e.message}. " +
                            "Try again — Android may need to re-confirm access.",
                        cause = e,
                    ),
                )
            } catch (e: IOException) {
                throw PlayStoreException(
                    NovaError.Network(
                        userMessage = "Could not reach Google while using the device account: ${e.message}",
                        cause = e,
                    ),
                )
            } catch (t: Throwable) {
                throw PlayStoreException(
                    NovaError.Account(
                        userMessage = t.message ?: "Device account sign-in failed.",
                        cause = t,
                    ),
                )
            }
            persist(auth, AuthMethod.DEVICE_ACCOUNT, profile)
            AccountState.SignedIn(email.trim(), AuthMethod.DEVICE_ACCOUNT).also { state.value = it }
        }

    override suspend fun signInWithWebToken(email: String, oauthToken: String): AccountState =
        withContext(dispatcherProvider.io) {
            val trimmed = email.trim()
            val profile = settingsDataStore.playDeviceProfileSnapshot()
            val auth = try {
                com.novastore.app.data.playauth.WebLoginAuth.login(trimmed, oauthToken, deviceProperties.resolve(profile))
            } catch (e: AuthException) {
                throw PlayStoreException(
                    NovaError.Account(
                        userMessage = "Google did not accept the sign-in token (${e.message}). Try signing in again.",
                        cause = e,
                    ),
                )
            } catch (e: IOException) {
                throw PlayStoreException(
                    NovaError.Network(userMessage = "Could not reach Google: ${e.message}", cause = e),
                )
            } catch (t: Throwable) {
                throw PlayStoreException(
                    NovaError.Account(userMessage = t.message ?: "Google sign-in failed.", cause = t),
                )
            }
            persist(auth, AuthMethod.GOOGLE_WEB, profile)
            AccountState.SignedIn(trimmed, AuthMethod.GOOGLE_WEB).also { state.value = it }
        }

    override suspend fun signInWithGoogle(email: String, password: String): AccountState =
        withContext(dispatcherProvider.io) {
            val trimmed = email.trim()
            if (trimmed.isBlank() || password.isBlank()) {
                throw PlayStoreException(
                    NovaError.Account(userMessage = "Email and password are required."),
                )
            }
            val profile = settingsDataStore.playDeviceProfileSnapshot()
            val auth = try {
                AuthHelper.login(trimmed, password, deviceProperties.resolve(profile))
            } catch (e: AuthException) {
                throw PlayStoreException(
                    NovaError.Account(
                        userMessage = "Google rejected the login: ${e.message}. " +
                            "With 2-Step Verification enabled Google only accepts a 16-character App " +
                            "Password here — or use the passwordless device-account login instead.",
                        cause = e,
                    ),
                )
            } catch (e: IOException) {
                throw PlayStoreException(
                    NovaError.Network(
                        userMessage = "Could not reach Google during login: ${e.message}",
                        cause = e,
                    ),
                )
            } catch (t: Throwable) {
                throw PlayStoreException(
                    NovaError.Account(userMessage = t.message ?: "Google Play login failed.", cause = t),
                )
            }
            persist(auth, AuthMethod.GOOGLE_PASSWORD, profile)
            AccountState.SignedIn(trimmed, AuthMethod.GOOGLE_PASSWORD).also { state.value = it }
        }

    /**
     * Nova Anonymous Engine — Nova Store's own anonymous access scheme.
     * No servers, no pooled accounts, no third-party login service:
     *
     *  1. **Nova Web Catalog** — the public play.google.com store pages are
     *     read directly from the device: search, details, screenshots and
     *     ratings for every Play app, anonymously.
     *  2. **Community mirror** — anonymous version histories and APK
     *     downloads, so updates and installs work without an account.
     *  3. **F-Droid repositories** — the complete native catalog.
     *
     * Optional (advanced): a user-configured session provider URL can mint
     * a genuine Play session; when it fails the engine simply stays on the
     * account-less tiers — anonymous sign-in never fails.
     */
    override suspend fun signInAnonymously(): AccountState = withContext(dispatcherProvider.io) {
        val profile = settingsDataStore.playDeviceProfileSnapshot()

        // Optional power-user tier: a session provider (token dispenser)
        // configured in Settings → Sources. Never a hard requirement.
        val customProvider = settingsDataStore.tokenDispenserUrlSnapshot().trim()
        if (customProvider.isNotBlank()) {
            val providerAuth = runCatching {
                AnonymousAuth.login(listOf(customProvider), deviceProperties.resolve(profile))
            }.getOrNull()
            if (providerAuth != null) {
                persist(providerAuth, AuthMethod.ANONYMOUS_POOL, profile)
                return@withContext AccountState.SignedIn(providerAuth.email, AuthMethod.ANONYMOUS_POOL)
            }
        }

        // Account-less engine: the anonymous Play session (minted on demand
        // by the Play repository) gives genuine Play search, versions and
        // delivery; mirrors and repositories cover the rest.
        settingsDataStore.setAnonymousPlayEnabled(true)
        settingsDataStore.update { it.copy(anonymousMode = true) }
        state.value = AccountState.Anonymous
        AccountState.Anonymous
    }

    override suspend fun signOut(): AccountState {
        settingsDataStore.clearPlayAuthSession()
        playStoreRepository.setAuthData(null, AuthMethod.GOOGLE_PASSWORD)
        state.value = AccountState.NotSignedIn
        return AccountState.NotSignedIn
    }

    private suspend fun persist(
        auth: com.novastore.playapi.data.models.AuthData,
        method: AuthMethod,
        profile: String,
    ) {
        settingsDataStore.setPlayAuthSession(PlayAuthSession.from(auth, method, profile).toJson())
        playStoreRepository.setAuthData(auth, method)
        settingsDataStore.update { it.copy(anonymousMode = false) }
    }

    private companion object {
        const val TAG = "AccountRepository"
    }
}
