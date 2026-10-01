package com.novastore.app.core.model

/**
 * Canonical source identifiers used across the catalog, update engine and
 * download/install pipeline.
 */

/** Google Play, resolved through the vendored GPlayApi module. */
const val SOURCE_PLAY = "play"

/** The built-in F-Droid repository (id used in the catalog database). */
const val SOURCE_FDROID = "fdroid"

/** Installer package reported by PackageManager for apps installed from Google Play. */
const val PLAY_INSTALLER_PACKAGE = "com.android.vending"

/**
 * Nova Web Catalog — public play.google.com store pages read without any
 * account or server. Provides search, details and screenshots anonymously.
 */
const val SOURCE_PLAY_WEB = "play"

/**
 * Nova community mirror — APKPure, used as an account-less fallback for
 * versions and downloads when no Google Play session is signed in.
 */
const val SOURCE_APKPURE = "apkpure"

/**
 * Nova community mirror — APKCombo (same catalog family, different domain;
 * serves a different edge network and survives some blocks). Second stage
 * of the account-less mirror chain.
 */
const val SOURCE_APKCOMBO = "apkcombo"

/**
 * Nova GitHub catalog — release-tracked repositories whose APK assets are
 * installed straight from GitHub Releases.
 */
const val SOURCE_GITHUB = "github"

/**
 * Nova GitLab catalog — release-tracked projects whose APK assets are
 * installed straight from GitLab Releases.
 */
const val SOURCE_GITLAB = "gitlab"
