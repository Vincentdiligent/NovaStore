package com.novastore.app.core.model

/**
 * How the Google Play session was established.
 */
enum class AuthMethod {
    /** Direct login with a Google account (password or App Password). */
    GOOGLE_PASSWORD,

    /** Signed in on Google's own web sign-in page (EmbeddedSetup) inside Nova. */
    GOOGLE_WEB,

    /** Credentials from an external anonymous token dispenser service. */
    ANONYMOUS_POOL,

    /**
     * Passwordless login through the Google account already present on this
     * device: Nova Store borrows an OAuth token from Android's
     * AccountManager and exchanges it for a Play (AAS) token. No password
     * is ever typed, stored or seen by Nova Store.
     */
    DEVICE_ACCOUNT,
}
