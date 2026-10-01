/*
 *     AnonymousAuth — Nova Store addition to the vendored GPlayApi
 *     Copyright (C) 2025  Nova Store contributors
 *     Portions Copyright (C) 2020  Aurora OSS (GPlayApi)
 *
 *     Anonymous Google Play session via an anonymous token dispenser.
 *
 *     Two dispenser protocols are understood:
 *
 *      1. Profile protocol (default, the built-in public dispenser):
 *           POST <device properties as a flat JSON object>
 *         → a full AuthData JSON: email, authToken (oauth2 "ya29." token for
 *           the googleplay scope), gsfId, deviceCheckInConsistencyToken,
 *           deviceConfigToken, dfeCookie. The session is usable as-is; when
 *           Play rejects it for this client, a local check-in +
 *           device-config upload with the same token repairs it.
 *
 *      2. Legacy protocol: GET → {"email":"...","token":"<aasToken>"}; the
 *         aasToken is exchanged for Play tokens by [AuthHelper.build].
 *
 *     [login] tries the URLs in order and returns the first working session.
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

package com.novastore.playapi

import com.novastore.playapi.data.models.AuthData
import com.novastore.playapi.data.providers.DeviceInfoProvider
import com.novastore.playapi.helpers.AuthHelper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.Properties
import java.util.concurrent.TimeUnit

object AnonymousAuth {

    /** Built-in public anonymous dispenser (external service address). */
    const val PUBLIC_DISPENSER_URL = "https://auroraoss.com/api/auth"

    /** Built-in dispensers tried after any user-configured URL. */
    val DEFAULT_DISPENSERS: List<String> = listOf(PUBLIC_DISPENSER_URL)

    /**
     * Client identifier the public dispenser requires (protocol value of the
     * external service, not a name used by Nova Store).
     */
    private const val DISPENSER_USER_AGENT = "com.aurora.store-4.6.4-66"

    private val httpClient: okhttp3.OkHttpClient = okhttp3.OkHttpClient().newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    /**
     * Tries every dispenser URL in order and returns the session from the
     * first one that responds with usable credentials.
     *
     * @throws DispenserException when every URL failed; [DispenserException.attempts]
     * describes each failure so the caller can build a helpful message.
     */
    @Throws(DispenserException::class)
    fun login(dispenserUrls: List<String>, deviceName: String = "px_3a.properties"): AuthData {
        val properties = DeviceManager.loadProperties(deviceName)
                ?: DeviceManager.loadProperties("px_3a.properties")
                ?: throw DispenserException(listOf(deviceName to "Unknown device profile"))
        return login(dispenserUrls, properties)
    }

    /** Same as [login] with an explicit device identity (e.g. the native device). */
    @Throws(DispenserException::class)
    fun login(dispenserUrls: List<String>, properties: Properties): AuthData {
        val urls = dispenserUrls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        require(urls.isNotEmpty()) { "No dispenser URL configured" }
        val attempts = mutableListOf<Pair<String, String>>()
        for (url in urls) {
            try {
                return loginSingle(url, properties)
            } catch (e: Exception) {
                attempts += url to (e.message ?: e.javaClass.simpleName)
            }
        }
        throw DispenserException(attempts)
    }

    @Throws(Exception::class)
    private fun loginSingle(dispenserUrl: String, properties: Properties): AuthData {
        // Profile protocol first; legacy GET dispensers answer a POST with 404/405.
        val body = runCatching { postProperties(dispenserUrl, properties) }
                .recoverCatching { getLegacy(dispenserUrl) }
                .getOrThrow()

        val email = field(body, "email", "user", "account")
                ?: throw Exception("Response missing email")
        val authToken = field(body, "authToken")?.takeIf { it.isNotBlank() }
        val aasToken = field(body, "aasToken", "aas_token", "token")
                ?.takeIf { it.isNotBlank() && !it.equals("REDACTED", ignoreCase = true) }

        val authData = when {
            authToken != null -> buildFromAuthToken(email, authToken, body, properties)
            aasToken != null -> AuthHelper.build(email, aasToken, properties)
            else -> throw Exception("Response missing token")
        }
        authData.tokenDispenserUrl = dispenserUrl
        return authData
    }

    /**
     * Session from a ready oauth2 Play token. The dispenser's own check-in
     * (gsfId + consistency/config tokens + cookie) is used first — that is the
     * device the token was minted for. If Play refuses it, the device is
     * checked in locally with the bundled profile and the session retried.
     */
    private fun buildFromAuthToken(
            email: String,
            authToken: String,
            body: String,
            properties: Properties,
    ): AuthData {
        val locale = Locale.getDefault()
        fun fresh(): AuthData = AuthData(email, String()).apply {
            this.authToken = authToken
            this.locale = locale
            this.deviceInfoProvider = DeviceInfoProvider(properties, locale.toString())
        }

        val remote = fresh().apply {
            gsfId = field(body, "gsfId").orEmpty()
            deviceCheckInConsistencyToken = field(body, "deviceCheckInConsistencyToken").orEmpty()
            deviceConfigToken = field(body, "deviceConfigToken").orEmpty()
            dfeCookie = field(body, "dfeCookie").orEmpty()
        }
        if (remote.gsfId.isNotBlank() && runCatching { GooglePlayApi(remote).toc() }.isSuccess) {
            return remote
        }

        val local = fresh()
        val api = GooglePlayApi(local)
        local.gsfId = api.generateGsfId()
        local.deviceConfigToken = api.uploadDeviceConfig().uploadDeviceConfigToken
        api.toc()
        return local
    }

    private fun postProperties(url: String, properties: Properties): String {
        val json = propertiesToJson(properties)
        val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", DISPENSER_USER_AGENT)
                .header("Accept", "application/json")
                .post(json.toByteArray().toRequestBody("application/json".toMediaType()))
                .build()
        return execute(request)
    }

    private fun getLegacy(url: String): String {
        val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", DISPENSER_USER_AGENT)
                .header("Accept", "application/json")
                .build()
        return execute(request)
    }

    private fun execute(request: okhttp3.Request): String =
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) throw Exception("Empty response")
                if (!text.trimStart().startsWith("{")) throw Exception("Not a JSON response")
                text
            }

    private fun propertiesToJson(properties: Properties): String =
            properties.stringPropertyNames().sorted().joinToString(",", "{", "}") { key ->
                "\"${escape(key)}\":\"${escape(properties.getProperty(key).orEmpty())}\""
            }

    private fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
    }

    /** First top-level-ish string value for any of [keys] (tiny JSON reader, no Gson). */
    private fun field(json: String, vararg keys: String): String? {
        // Hand-rolled scan: a backtracking regex over multi-KB token strings
        // overflows the JVM regex stack.
        for (key in keys) {
            val needle = "\"$key\""
            var from = 0
            while (true) {
                val at = json.indexOf(needle, from)
                if (at < 0) break
                var i = at + needle.length
                while (i < json.length && json[i].isWhitespace()) i++
                if (i < json.length && json[i] == ':') {
                    i++
                    while (i < json.length && json[i].isWhitespace()) i++
                    if (i < json.length && json[i] == '"') {
                        val start = ++i
                        while (i < json.length && json[i] != '"') {
                            if (json[i] == '\\') i++
                            i++
                        }
                        if (i <= json.length) return unescape(json.substring(start, minOf(i, json.length)))
                    }
                }
                from = at + needle.length
            }
        }
        return null
    }

    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val n = value[i + 1]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> if (i + 5 < value.length) {
                        out.append(value.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    }
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** All configured dispensers failed; carries per-URL failure reasons. */
    class DispenserException(val attempts: List<Pair<String, String>>) :
        Exception(attempts.joinToString("; ") { "${it.first}: ${it.second}" })
}
