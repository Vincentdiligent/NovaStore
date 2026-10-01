package com.novastore.app.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.model.PlayStoreException
import com.novastore.app.domain.repository.AccountRepository
import com.novastore.app.domain.repository.AccountState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountUiState(
    val accountState: AccountState = AccountState.NotSignedIn,
    val busy: Boolean = false,
    val error: String? = null,
    /** Device identity used for the Play session ("px_10_pro.properties"). */
    val deviceProfile: String = "",
    /** Google accounts present on this device (passwordless login). */
    val deviceAccounts: List<String> = emptyList(),
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    settingsDataStore: SettingsDataStore,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val deviceAccounts = MutableStateFlow<List<String>>(emptyList())

    private val deviceProfile = settingsDataStore.playDeviceProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    init {
        refreshDeviceAccounts()
    }

    val uiState: StateFlow<AccountUiState> = combine(
        accountRepository.accountState,
        busy,
        error,
        deviceProfile,
        deviceAccounts,
    ) { state, isBusy, errorMessage, profile, accounts ->
        AccountUiState(
            accountState = state,
            busy = isBusy,
            error = errorMessage,
            deviceProfile = profile,
            deviceAccounts = accounts,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AccountUiState(accountState = accountRepository.start()),
    )

    /** Google accounts registered in Android — one tap, no password. */
    fun refreshDeviceAccounts() {
        viewModelScope.launch {
            deviceAccounts.value = runCatching {
                accountRepository.availableDeviceAccounts()
            }.getOrDefault(emptyList())
        }
    }

    /** Passwordless login through the account already on the device. */
    fun loginWithDeviceAccount(email: String) {
        runSignIn { accountRepository.signInWithDeviceAccount(email) }
    }

    /** Finishes Google's web sign-in with the cookie token it produced. */
    fun loginWithWebToken(email: String, oauthToken: String) {
        runSignIn { accountRepository.signInWithWebToken(email, oauthToken) }
    }

    fun reportError(message: String) {
        error.value = message
    }

    /** Google login with email + (App) password. */
    fun login(email: String, password: String) {
        runSignIn { accountRepository.signInWithGoogle(email.trim(), password) }
    }

    /**
     * Nova Anonymous Engine — the primary, recommended flow. The repository
     * guarantees this never fails: it either activates a genuine Play session
     * (optional session provider) or the account-less anonymous tiers.
     * The UI therefore treats any unexpected throwable as a no-op, never as
     * an error.
     */
    fun loginAnonymously() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                accountRepository.signInAnonymously()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // Anonymous sign-in cannot fail in v4; ignore unexpected noise.
            } finally {
                busy.value = false
            }
        }
    }

    private fun runSignIn(block: suspend () -> AccountState) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                block()
            } catch (e: PlayStoreException) {
                error.value = e.error.userMessage
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                error.value = t.message ?: "Sign-in failed."
            } finally {
                busy.value = false
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            accountRepository.signOut()
        }
    }

    fun dismissError() {
        error.value = null
    }
}
