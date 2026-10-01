package com.novastore.app.core.datastore

/** A favorited app, stored compactly so Home can render it without lookups. */
data class FavoriteApp(
    val packageName: String,
    val name: String,
    val iconUrl: String?,
) {
    fun encode(): String = listOf(packageName, name, iconUrl.orEmpty()).joinToString(SEP)

    companion object {
        private const val SEP = "\u0001"

        fun decode(raw: String): FavoriteApp? {
            val parts = raw.split(SEP)
            val pkg = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
            return FavoriteApp(
                packageName = pkg,
                name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: pkg,
                iconUrl = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
            )
        }
    }
}
