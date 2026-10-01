package com.novastore.app.data.playauth

import com.novastore.playapi.data.models.AuthData
import com.novastore.app.core.model.AuthMethod

/**
 * Serializable snapshot of a Google Play session persisted in DataStore.
 *
 * Gson is deliberately not used here (the :data module has no gson dependency):
 * the document is a flat, hand-rolled JSON object with a strict field list.
 */
data class PlayAuthSession(
    val email: String,
    val aasToken: String,
    val authToken: String? = null,
    val gsfId: String? = null,
    val ac2dmToken: String? = null,
    val gcmToken: String? = null,
    val deviceConfigToken: String? = null,
    val deviceCheckInConsistencyToken: String? = null,
    val dfeCookie: String? = null,
    val deviceProfile: String,
    val authMethod: AuthMethod = AuthMethod.GOOGLE_PASSWORD,
) {

    fun toJson(): String = buildString {
        append('{')
        appendField("email", email)
        appendField("aasToken", aasToken)
        authToken?.let { appendField("authToken", it) }
        gsfId?.let { appendField("gsfId", it) }
        ac2dmToken?.let { appendField("ac2dmToken", it) }
        gcmToken?.let { appendField("gcmToken", it) }
        deviceConfigToken?.let { appendField("deviceConfigToken", it) }
        deviceCheckInConsistencyToken?.let { appendField("deviceCheckInConsistencyToken", it) }
        dfeCookie?.let { appendField("dfeCookie", it) }
        appendField("deviceProfile", deviceProfile)
        appendField("authMethod", authMethod.name)
        append('}')
    }

    private fun StringBuilder.appendField(key: String, value: String) {
        if (isNotEmpty() && this[length - 1] != '{') append(',')
        append('"').append(key).append("\":")
        appendJsonString(value)
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    companion object {

        fun from(auth: AuthData, method: AuthMethod, deviceProfile: String): PlayAuthSession = PlayAuthSession(
            email = auth.email,
            aasToken = auth.aasToken,
            authToken = auth.authToken.takeIf { it.isNotBlank() },
            gsfId = auth.gsfId.takeIf { it.isNotBlank() },
            ac2dmToken = auth.ac2dmToken.takeIf { it.isNotBlank() },
            gcmToken = auth.gcmToken.takeIf { it.isNotBlank() },
            deviceConfigToken = auth.deviceConfigToken.takeIf { it.isNotBlank() },
            deviceCheckInConsistencyToken = auth.deviceCheckInConsistencyToken.takeIf { it.isNotBlank() },
            dfeCookie = auth.dfeCookie.takeIf { it.isNotBlank() },
            deviceProfile = deviceProfile,
            authMethod = method,
        )

        fun fromJson(json: String): PlayAuthSession? = runCatching {
            val email = requireNotNull(json.stringField("email")) { "missing email" }
            val aasToken = requireNotNull(json.stringField("aasToken")) { "missing aasToken" }
            val deviceProfile = json.stringField("deviceProfile") ?: "px_3a.properties"
            PlayAuthSession(
                email = email,
                aasToken = aasToken,
                authToken = json.stringField("authToken"),
                gsfId = json.stringField("gsfId"),
                ac2dmToken = json.stringField("ac2dmToken"),
                gcmToken = json.stringField("gcmToken"),
                deviceConfigToken = json.stringField("deviceConfigToken"),
                deviceCheckInConsistencyToken = json.stringField("deviceCheckInConsistencyToken"),
                dfeCookie = json.stringField("dfeCookie"),
                deviceProfile = deviceProfile,
                authMethod = json.stringField("authMethod")
                    ?.let { name -> AuthMethod.entries.firstOrNull { it.name == name } }
                    ?: AuthMethod.GOOGLE_PASSWORD,
            )
        }.getOrNull()

        private fun String.stringField(key: String): String? {
            val regex = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            val raw = regex.find(this)?.groupValues?.get(1) ?: return null
            return raw
                .replace("\\\\", "\u0000")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\b", "\b")
                .replace("\\f", "\u000C")
                .replace("\u0000", "\\")
        }
    }
}
