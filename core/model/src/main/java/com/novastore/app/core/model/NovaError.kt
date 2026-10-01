package com.novastore.app.core.model

/**
 * Typed application error hierarchy. User-facing messages are derived from
 * these types; stack traces are never shown to the user.
 */
sealed class NovaError(
    open val userMessage: String,
) {
    data class Network(
        override val userMessage: String = "A network error occurred. Check your connection and try again.",
        val cause: Throwable? = null,
    ) : NovaError(userMessage)

    data class Repository(
        override val userMessage: String = "The repository could not be read.",
        val repositoryId: String? = null,
    ) : NovaError(userMessage)

    data class Metadata(
        override val userMessage: String = "The source returned incomplete metadata.",
        val packageName: String? = null,
    ) : NovaError(userMessage)

    data object ChecksumMismatch : NovaError(
        "The downloaded file failed checksum verification. The download was corrupted or tampered with; installation is blocked.",
    )

    data object SignatureMismatch : NovaError(
        "The signing certificate does not match the installed version. Automatic update was blocked for security.",
    )

    data object InvalidPackage : NovaError(
        "The downloaded file is not a valid Android package.",
    )

    data object IncompatibleDevice : NovaError(
        "This application is not compatible with your device.",
    )

    data object InsufficientStorage : NovaError(
        "Not enough free storage to download or install this update.",
    )

    data class DownloadFailed(
        override val userMessage: String = "The download failed.",
        val cause: Throwable? = null,
    ) : NovaError(userMessage)

    data object DownloadCancelled : NovaError("The download was cancelled.")

    /** Google Play sign-in / session failure. */
    data class Account(
        override val userMessage: String = "Google Play sign-in failed.",
        val cause: Throwable? = null,
    ) : NovaError(userMessage)

    data class InstallationFailed(
        override val userMessage: String = "Installation failed.",
        val detail: String? = null,
    ) : NovaError(userMessage)

    /**
     * The APKCombo mirror hides the final file link behind in-page
     * JavaScript. The UI reacts by opening the built-in mirror browser
     * (which intercepts the download automatically), not with a raw error.
     */
    data object MirrorJsGated : NovaError(
        "The mirror produces this download link in-page (JavaScript). " +
            "The built-in mirror browser will finish it automatically.",
    )

    data object PermissionDenied : NovaError(
        "A required permission is missing. Enable \"Install unknown apps\" for Nova Store in system settings.",
    )

    data object RootUnavailable : NovaError(
        "Root mode is enabled, but this device does not provide root access.",
    )

    data object RootDenied : NovaError(
        "Root permission was denied by the root management app.",
    )

    data object RootRevoked : NovaError(
        "Root permission is no longer available. The privileged operation was stopped.",
    )

    data object UserActionRequired : NovaError(
        "Android requires your confirmation to continue installation.",
    )

    data object Timeout : NovaError(
        "The operation timed out.",
    )

    data class Unknown(
        override val userMessage: String = "An unexpected error occurred.",
        val cause: Throwable? = null,
    ) : NovaError(userMessage)
}
