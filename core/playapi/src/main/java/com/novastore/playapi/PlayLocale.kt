package com.novastore.playapi

import java.util.Locale

/**
 * Language that Google Play uses to pick an app's language split APKs
 * (config.ru, config.fr…). It must be the DEVICE language, not the store's
 * own in-app language: with Nova set to French on a Russian phone, Play would
 * deliver config.fr and installed apps would fall back to English.
 *
 * The app installs a provider of the system locale at startup; plain JVM
 * (tests) falls back to the default locale.
 */
object PlayLocale {
    @Volatile
    var provider: () -> Locale = { Locale.getDefault() }

    fun current(): Locale = runCatching { provider() }.getOrNull() ?: Locale.getDefault()
}
