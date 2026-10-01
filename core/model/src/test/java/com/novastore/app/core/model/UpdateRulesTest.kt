package com.novastore.app.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {

    private fun installed(name: String?, code: Long) = InstalledApp(
        packageName = "com.example",
        appName = "Example",
        versionName = name,
        versionCode = code,
        firstInstallTime = 0,
        lastUpdateTime = 0,
        installerSource = null,
        signingCertDigest = null,
        isSystemApp = false,
    )

    private fun version(name: String?, code: Long, source: String = SOURCE_PLAY) = AppVersion(
        packageName = "com.example",
        versionCode = code,
        versionName = name,
        source = source,
        size = null,
        downloadUrl = "",
        sha256 = null,
        minSdk = null,
        targetSdk = null,
        addedAt = null,
    )

    @Test
    fun sameNameDifferentVariantCodeIsNotAnUpdate() {
        // "11.0.3 → 11.0.3": Play reports another ABI/density variant code.
        assertFalse(UpdateRules.isRealUpdate(installed("11.0.3", 110301), version("11.0.3", 110303)))
        assertFalse(UpdateRules.isRealUpdate(installed("v11.0.3 ", 1), version("11.0.3", 2)))
    }

    @Test
    fun higherCodeAndNewerNameIsAnUpdate() {
        assertTrue(UpdateRules.isRealUpdate(installed("11.0.3", 110301), version("11.0.4", 110401)))
    }

    @Test
    fun lowerOrEqualCodeIsNeverAnUpdate() {
        assertFalse(UpdateRules.isRealUpdate(installed("2.0", 20), version("2.1", 20)))
        assertFalse(UpdateRules.isRealUpdate(installed("2.0", 20), version("1.9", 19)))
    }

    @Test
    fun mirrorRowsDecideByName() {
        // Synthetic APKCombo code (~900M) must not fake an update.
        assertFalse(UpdateRules.isRealUpdate(installed("11.11.3", 34194), version("11.11.3", 899_999_999, SOURCE_APKCOMBO)))
        assertFalse(UpdateRules.isRealUpdate(installed("5.0", 50), version("4.9", 899_999_998, SOURCE_APKPURE)))
        assertTrue(UpdateRules.isRealUpdate(installed("5.0", 50), version("5.1", 899_999_999, SOURCE_APKCOMBO)))
        assertFalse(UpdateRules.isRealUpdate(installed("5.0", 50), version("v899999999", 899_999_999, SOURCE_APKCOMBO)))
    }

    @Test
    fun missingNamesFallBackToCodeForPlay() {
        assertTrue(UpdateRules.isRealUpdate(installed(null, 1), version(null, 2)))
    }
}
