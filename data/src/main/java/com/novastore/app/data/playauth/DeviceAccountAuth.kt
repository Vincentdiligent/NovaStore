/*
 *     DeviceAccountAuth — Nova Store addition to the vendored GPlayApi
 *     Copyright (C) 2025  Nova Store contributors
 *     Portions Copyright (C) 2020  Aurora OSS (GPlayApi)
 *
 *     Passwordless Google Play login through the account already present on
 *     this device. Nova Store asks Android's AccountManager for a scoped
 *     OAuth token (the user may see a one-time system consent dialog), then
 *     exchanges that token for a Play AAS token via the same /auth endpoint
 *     used by ClientLogin. No password is ever typed, stored or seen by
 *     Nova Store — authentication is delegated entirely to the OS.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.novastore.app.data.playauth

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import com.novastore.playapi.DeviceManager
import com.novastore.playapi.GooglePlayApi
import com.novastore.playapi.data.models.AuthData
import com.novastore.playapi.helpers.AuthHelper
import java.util.Locale

object DeviceAccountAuth {

    /** Scope of the OAuth token requested from the system AccountManager. */
    private const val OAUTH_SCOPE = "oauth2:https://www.google.com/accounts/OAuthLogin"

    /** Google account type on Android. */
    private const val ACCOUNT_TYPE = "com.google"

    /** E-mail addresses of the Google accounts present on this device. */
    fun listAccounts(context: Context): List<String> =
        runCatching {
            AccountManager.get(context)
                .getAccountsByType(ACCOUNT_TYPE)
                .map { it.name }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

    /**
     * Full login with a Google account stored on this device.
     *
     * @param context   any context (the Application context is enough)
     * @param email     account e-mail, must be one of [listAccounts]
     * @param deviceName device profile properties file used for checkin
     */
    @Throws(Exception::class)
    fun login(context: Context, email: String, properties: java.util.Properties): AuthData {
        val deviceInfoProvider = com.novastore.playapi.data.providers.DeviceInfoProvider(
            properties,
            Locale.getDefault().toString(),
        )
        val accountManager = AccountManager.get(context)
        val account = accountManager.getAccountsByType(ACCOUNT_TYPE)
            .firstOrNull { it.name == email }
            ?: throw Exception("Account $email is not present on this device")
        val oauthToken = blockingOAuthToken(context, account)
            ?: throw Exception("Android did not grant an access token for $email")

        val authData = AuthData(email, String())
        authData.deviceInfoProvider = deviceInfoProvider
        authData.locale = Locale.getDefault()

        val api = GooglePlayApi(authData)
        authData.gsfId = api.generateGsfId()

        val aasToken = try {
            api.generateAASToken(email, oauthToken)
        } catch (e: Exception) {
            // The OAuth token may be stale/limited — invalidate and retry once
            // with a fresh one before giving up.
            accountManager.invalidateAuthToken(ACCOUNT_TYPE, oauthToken)
            val fresh = blockingOAuthToken(context, account)
            if (fresh != null && fresh != oauthToken) {
                api.generateAASToken(email, fresh)
            } else {
                throw e
            }
        } ?: throw Exception("Google did not return a Play token for this account")

        return AuthHelper.build(email, aasToken, properties)
    }

    /**
     * Fetches the OAuth token. Must be called from a background thread.
     * [AccountManager.blockingGetAuthToken] handles consent dialogs and
     * network retries internally.
     */
    private fun blockingOAuthToken(context: Context, account: Account): String? =
        runCatching {
            AccountManager.get(context)
                .blockingGetAuthToken(account, OAUTH_SCOPE, true)
        }.getOrNull()
}
