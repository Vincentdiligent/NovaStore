package com.novastore.app.core.model

/**
 * A device identity spoofed towards Google Play, backed by a bundled
 * .properties profile (see DeviceManager in :core:playapi).
 */
data class DeviceProfile(
    /** File name inside the module resources, e.g. "px_10_pro.properties". */
    val fileName: String,
    /** Human readable name parsed from the UserReadableName= line. */
    val displayName: String,
)
