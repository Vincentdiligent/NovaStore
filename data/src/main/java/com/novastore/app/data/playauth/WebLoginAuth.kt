package com.novastore.app.data.playauth

import com.novastore.playapi.GooglePlayApi
import com.novastore.playapi.data.models.AuthData
import com.novastore.playapi.data.providers.DeviceInfoProvider
import com.novastore.playapi.helpers.AuthHelper
import java.util.Locale
import java.util.Properties

/**
 * Google sign-in through Google's own web page (accounts.google.com
 * EmbeddedSetup, shown in a WebView). Google handles the password, 2-Step
 * Verification and "Is it you?" prompts itself; when it succeeds it sets an
 * `oauth_token` cookie. That token is exchanged here for the long-lived Play
 * (AAS) token — the same exchange the Android account system performs. No
 * password and no App Password ever pass through Nova Store.
 */
object WebLoginAuth {

    /** Google's sign-in page for adding an account to a device. */
    const val EMBEDDED_SETUP_URL = "https://accounts.google.com/EmbeddedSetup"

    /** Cookie Google sets once the account is signed in. */
    const val OAUTH_COOKIE = "oauth_token"

    @Throws(Exception::class)
    fun login(email: String, oauthToken: String, properties: Properties): AuthData {
        val authData = AuthData(email, String()).apply {
            deviceInfoProvider = DeviceInfoProvider(properties, Locale.getDefault().toString())
            locale = Locale.getDefault()
        }
        val api = GooglePlayApi(authData)
        authData.gsfId = api.generateGsfId()
        val aasToken = api.generateAASToken(email, oauthToken)
            ?: throw Exception("Google did not return a Play token for this account")
        return AuthHelper.build(email, aasToken, properties)
    }
}
