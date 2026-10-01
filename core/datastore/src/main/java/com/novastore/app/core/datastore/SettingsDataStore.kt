package com.novastore.app.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.AccentPalette
import com.novastore.app.core.model.AppLanguage
import com.novastore.app.core.model.HomeLayoutStyle
import com.novastore.app.core.model.IconSize
import com.novastore.app.core.model.InstallationMode
import com.novastore.app.core.model.ThemeMode
import com.novastore.app.core.model.UpdateSchedule
import com.novastore.app.core.model.UpdateSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nova_settings",
)

/**
 * Persistence for user settings via Preferences DataStore.
 * No secrets are ever stored here — only UI/engine preferences.
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) {
    private object Keys {
        val AUTOMATIC_UPDATES = booleanPreferencesKey("automatic_updates")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val CHARGING_ONLY = booleanPreferencesKey("charging_only")
        val BATTERY_THRESHOLD = intPreferencesKey("battery_threshold")
        val SCHEDULE = stringPreferencesKey("schedule")
        val CONFIRM_BEFORE_INSTALL = booleanPreferencesKey("confirm_before_install")
        val ALLOW_DOWNGRADE = booleanPreferencesKey("allow_downgrade")
        val INSTALLATION_MODE = stringPreferencesKey("installation_mode")
        val MOBILE_DATA_ALLOWED = booleanPreferencesKey("mobile_data_allowed")
        val DOWNLOAD_WHILE_CHARGING = booleanPreferencesKey("download_while_charging")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val ANONYMOUS_MODE = booleanPreferencesKey("anonymous_mode")
        val PLAY_UPDATES_ENABLED = booleanPreferencesKey("play_updates_enabled")
        val LAST_SCAN_TIMESTAMP = longPreferencesKey("last_scan_timestamp")

        // --- Google Play integration (Task 4) ---
        val PLAY_DEVICE_PROFILE = stringPreferencesKey("play_device_profile")
        val TOKEN_DISPENSER_URL = stringPreferencesKey("token_dispenser_url")
        /** Serialized Play auth session (see data/playauth/PlayAuthSession). Private. */
        val PLAY_AUTH_SESSION = stringPreferencesKey("play_auth_session")
        /** Anonymous Play session (JSON, short-lived pooled token) reused across launches. */
        val ANON_PLAY_SESSION = stringPreferencesKey("anon_play_session")

        // --- Appearance & personalization ---
        val APP_THEME = stringPreferencesKey("app_theme")
        val ACCENT_PALETTE = stringPreferencesKey("accent_palette")
        val APP_LANGUAGE = stringPreferencesKey("app_language")

        // --- Home layout ---
        val HOME_GRID_COLUMNS = intPreferencesKey("home_grid_columns")
        val HOME_ICON_SIZE = stringPreferencesKey("home_icon_size")
        val HOME_LAYOUT_STYLE = stringPreferencesKey("home_layout_style")

        // --- Update ignore list ("pkg" or "pkg@versionCode") ---
        val IGNORED_UPDATES = stringSetPreferencesKey("ignored_updates")

        /** Home search bar stays visible (true) or hides while scrolling (false). */
        val HOME_SEARCH_PINNED = booleanPreferencesKey("home_search_pinned")

        /** User-picked update source per app ("pkg=sourceId"). */
        val PREFERRED_SOURCES = stringSetPreferencesKey("preferred_update_sources")

        /** Update versions already announced in a notification ("pkg@versionCode"). */
        val NOTIFIED_UPDATES = stringSetPreferencesKey("notified_updates")

        /** Favorite apps: "pkg<SOH>name<SOH>iconUrl" entries (SOH = U+0001). */
        val FAVORITES = stringSetPreferencesKey("favorites")

        /** Try the built-in community dispensers when no custom URL is set. */
        val TRY_COMMUNITY_DISPENSERS = booleanPreferencesKey("try_community_dispensers")

        // --- Nova anonymous access tiers (own engine, no servers) ---
        /** Nova Web Catalog: public play.google.com pages — search/details/screenshots without an account. */
        val PLAY_WEB_CATALOG_ENABLED = booleanPreferencesKey("play_web_catalog_enabled")
        /** Community mirror (APKPure): anonymous versions + downloads fallback. */
        val APKPURE_MIRROR_ENABLED = booleanPreferencesKey("apkpure_mirror_enabled")
        /** Second mirror stage (APKCombo): version history + download attempts. */
        val APKCOMBO_MIRROR_ENABLED = booleanPreferencesKey("apkcombo_mirror_enabled")
        /** GitHub releases catalog in search and details. */
        val GITHUB_CATALOG_ENABLED = booleanPreferencesKey("github_catalog_enabled")
        /** GitLab releases catalog in search and details. */
        val GITLAB_CATALOG_ENABLED = booleanPreferencesKey("gitlab_catalog_enabled")
        /** Search results layout (GRID/LIST) and columns, shared with Home. */
        val SEARCH_LIST_STYLE = stringPreferencesKey("search_list_style")
        /** "See all" category pages: list rows (true) or grid (false). */
        val CATEGORY_LIST_MODE = booleanPreferencesKey("category_list_mode")
        val SEARCH_SORT = stringPreferencesKey("search_sort")

        // --- Downloads housekeeping ---
        /**
         * Auto-cleanup of completed downloads, in days. 0 = keep forever
         * (manual "Clear completed" still works). Positive = delete the
         * APK files once they are that old.
         */
        val DOWNLOADS_AUTO_CLEAN_DAYS = intPreferencesKey("downloads_auto_clean_days")
    }

    /** Completed-download auto-cleanup age in days (0 = off). */
    val downloadsAutoCleanDays: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        (prefs[Keys.DOWNLOADS_AUTO_CLEAN_DAYS] ?: 0).coerceIn(0, 30)
    }

    suspend fun setDownloadsAutoCleanDays(days: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DOWNLOADS_AUTO_CLEAN_DAYS] = days.coerceIn(0, 30)
        }
    }

    suspend fun downloadsAutoCleanDaysSnapshot(): Int =
        withContext(dispatcherProvider.io) { downloadsAutoCleanDays.first() }

    val settings: Flow<UpdateSettings> = context.settingsDataStore.data.map { prefs -> prefs.toUpdateSettings() }

    private fun Preferences.toUpdateSettings(): UpdateSettings = UpdateSettings(
        automaticUpdates = this[Keys.AUTOMATIC_UPDATES] ?: false,
        wifiOnly = this[Keys.WIFI_ONLY] ?: true,
        chargingOnly = this[Keys.CHARGING_ONLY] ?: false,
        batteryThreshold = this[Keys.BATTERY_THRESHOLD] ?: 20,
        schedule = enumOrDefault(this[Keys.SCHEDULE], UpdateSchedule.DAILY),
        confirmBeforeInstallation = this[Keys.CONFIRM_BEFORE_INSTALL] ?: true,
        allowDowngrade = this[Keys.ALLOW_DOWNGRADE] ?: false,
        installationMode = enumOrDefault(this[Keys.INSTALLATION_MODE], InstallationMode.AUTOMATIC),
        mobileDataAllowed = this[Keys.MOBILE_DATA_ALLOWED] ?: true,
        downloadWhileCharging = this[Keys.DOWNLOAD_WHILE_CHARGING] ?: true,
        maxConcurrentDownloads = (this[Keys.MAX_CONCURRENT_DOWNLOADS] ?: 2).coerceIn(1, 6),
        notificationsEnabled = this[Keys.NOTIFICATIONS_ENABLED] ?: true,
        anonymousMode = this[Keys.ANONYMOUS_MODE] ?: true,
        playUpdatesEnabled = this[Keys.PLAY_UPDATES_ENABLED] ?: true,
    )

    suspend fun update(transform: (UpdateSettings) -> UpdateSettings) = withContext(dispatcherProvider.io) {
        context.settingsDataStore.edit { prefs ->
            // Must read the settings actually persisted so far — building a
            // fresh UpdateSettings() here would silently reset every other
            // field to its default on each single-field toggle.
            val current = prefs.toUpdateSettings()
            val next = transform(current)
            prefs[Keys.AUTOMATIC_UPDATES] = next.automaticUpdates
            prefs[Keys.WIFI_ONLY] = next.wifiOnly
            prefs[Keys.CHARGING_ONLY] = next.chargingOnly
            prefs[Keys.BATTERY_THRESHOLD] = next.batteryThreshold
            prefs[Keys.SCHEDULE] = next.schedule.name
            prefs[Keys.CONFIRM_BEFORE_INSTALL] = next.confirmBeforeInstallation
            prefs[Keys.ALLOW_DOWNGRADE] = next.allowDowngrade
            prefs[Keys.INSTALLATION_MODE] = next.installationMode.name
            prefs[Keys.MOBILE_DATA_ALLOWED] = next.mobileDataAllowed
            prefs[Keys.DOWNLOAD_WHILE_CHARGING] = next.downloadWhileCharging
            prefs[Keys.MAX_CONCURRENT_DOWNLOADS] = next.maxConcurrentDownloads
            prefs[Keys.NOTIFICATIONS_ENABLED] = next.notificationsEnabled
            prefs[Keys.ANONYMOUS_MODE] = next.anonymousMode
            prefs[Keys.PLAY_UPDATES_ENABLED] = next.playUpdatesEnabled
        }
    }

    /** Timestamp of the last completed update scan. */
    val lastScanTimestamp: Flow<Long> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.LAST_SCAN_TIMESTAMP] ?: 0L
    }

    suspend fun setLastScanTimestamp(timestampMillis: Long) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.LAST_SCAN_TIMESTAMP] = timestampMillis
        }
    }

    suspend fun lastScanSnapshot(): Long = withContext(dispatcherProvider.io) {
        lastScanTimestamp.first()
    }

    /** Reads the persisted snapshot once (needed by workers and use cases). */
    suspend fun snapshot(): UpdateSettings = withContext(dispatcherProvider.io) {
        settings.first()
    }

    // ------------------------------------------------------------------
    // Google Play settings
    // ------------------------------------------------------------------

    /** Device identity used for the Play session (bundled .properties profile). */
    val playDeviceProfile: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.PLAY_DEVICE_PROFILE]?.takeIf { it.isNotBlank() } ?: DEFAULT_PLAY_DEVICE_PROFILE
    }

    suspend fun setPlayDeviceProfile(fileName: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.PLAY_DEVICE_PROFILE] = fileName
        }
    }

    suspend fun playDeviceProfileSnapshot(): String = withContext(dispatcherProvider.io) {
        playDeviceProfile.first()
    }

    /** Anonymous token dispenser service URL (empty = anonymous F-Droid only mode). */
    val tokenDispenserUrl: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.TOKEN_DISPENSER_URL] ?: ""
    }

    suspend fun setTokenDispenserUrl(url: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.TOKEN_DISPENSER_URL] = url.trim()
        }
    }

    suspend fun tokenDispenserUrlSnapshot(): String = withContext(dispatcherProvider.io) {
        tokenDispenserUrl.first()
    }

    /** Whether updates may also be resolved through Google Play. */
    val playUpdatesEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.PLAY_UPDATES_ENABLED] ?: true
    }

    suspend fun setPlayUpdatesEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.PLAY_UPDATES_ENABLED] = enabled
        }
    }

    // ------------------------------------------------------------------
    // Play auth session persistence (opaque JSON blob, written by the data layer)
    // ------------------------------------------------------------------

    val playAuthSession: Flow<String?> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.PLAY_AUTH_SESSION]?.takeIf { it.isNotBlank() }
    }

    suspend fun setPlayAuthSession(json: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.PLAY_AUTH_SESSION] = json
        }
    }

    suspend fun playAuthSessionSnapshot(): String? = withContext(dispatcherProvider.io) {
        playAuthSession.first()
    }

    suspend fun anonPlaySessionSnapshot(): String? = withContext(dispatcherProvider.io) {
        context.settingsDataStore.data.first()[Keys.ANON_PLAY_SESSION]?.takeIf { it.isNotBlank() }
    }

    suspend fun setAnonPlaySession(json: String?) {
        context.settingsDataStore.edit { prefs ->
            if (json.isNullOrBlank()) prefs.remove(Keys.ANON_PLAY_SESSION) else prefs[Keys.ANON_PLAY_SESSION] = json
        }
    }

    suspend fun clearPlayAuthSession() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(Keys.PLAY_AUTH_SESSION)
        }
    }

    // ------------------------------------------------------------------
    // Nova anonymous access tiers (own engine, no servers)
    // ------------------------------------------------------------------

    /** Nova Web Catalog — public Play pages: anonymous search/details/screenshots. */
    val playWebCatalogEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.PLAY_WEB_CATALOG_ENABLED] ?: true
    }

    suspend fun setPlayWebCatalogEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.PLAY_WEB_CATALOG_ENABLED] = enabled
        }
    }

    suspend fun playWebCatalogEnabledSnapshot(): Boolean = withContext(dispatcherProvider.io) {
        playWebCatalogEnabled.first()
    }

    /** Community mirror (APKPure) — anonymous versions + download fallback. */
    val apkPureMirrorEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.APKPURE_MIRROR_ENABLED] ?: true
    }

    suspend fun setApkPureMirrorEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.APKPURE_MIRROR_ENABLED] = enabled
        }
    }

    suspend fun apkPureMirrorEnabledSnapshot(): Boolean = withContext(dispatcherProvider.io) {
        apkPureMirrorEnabled.first()
    }

    /** Community mirror stage two (APKCombo) — anonymous version history + downloads. */
    val apkComboMirrorEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.APKCOMBO_MIRROR_ENABLED] ?: true
    }

    suspend fun setApkComboMirrorEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.APKCOMBO_MIRROR_ENABLED] = enabled
        }
    }

    suspend fun apkComboMirrorEnabledSnapshot(): Boolean = withContext(dispatcherProvider.io) {
        apkComboMirrorEnabled.first()
    }

    /** GitHub releases catalog — searchable apps installable from GitHub Releases. */
    val githubCatalogEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.GITHUB_CATALOG_ENABLED] ?: true
    }

    suspend fun setGithubCatalogEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.GITHUB_CATALOG_ENABLED] = enabled
        }
    }

    suspend fun githubCatalogEnabledSnapshot(): Boolean = withContext(dispatcherProvider.io) {
        githubCatalogEnabled.first()
    }

    /** GitLab releases catalog — searchable apps installable from GitLab Releases. */
    val gitlabCatalogEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.GITLAB_CATALOG_ENABLED] ?: true
    }

    suspend fun setGitlabCatalogEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.GITLAB_CATALOG_ENABLED] = enabled
        }
    }

    suspend fun gitlabCatalogEnabledSnapshot(): Boolean = withContext(dispatcherProvider.io) {
        gitlabCatalogEnabled.first()
    }

    /** Search results presentation: list or grid (default follows Home). */
    /** "See all" pages: null = follow the Home layout, else the user's own pick. */
    val categoryListMode: Flow<Boolean?> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.CATEGORY_LIST_MODE]
    }

    suspend fun setCategoryListMode(list: Boolean) {
        context.settingsDataStore.edit { it[Keys.CATEGORY_LIST_MODE] = list }
    }

    val searchListStyle: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.SEARCH_LIST_STYLE] ?: "auto"
    }

    suspend fun setSearchListStyle(style: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SEARCH_LIST_STYLE] = style
        }
    }

    /** Sort order of search results: relevance | rating | downloads | name. */
    val searchSort: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.SEARCH_SORT] ?: "relevance"
    }

    suspend fun setSearchSort(sort: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SEARCH_SORT] = sort
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    private companion object {
        /** "native" = this device's real identity (see data/playauth/PlayDeviceProperties). */
        const val DEFAULT_PLAY_DEVICE_PROFILE = "native"
    }

    // ------------------------------------------------------------------
    // Appearance: theme mode, accent, language
    // ------------------------------------------------------------------

    val appTheme: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        enumOrDefault(prefs[Keys.APP_THEME], ThemeMode.SYSTEM)
    }

    suspend fun setAppTheme(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.APP_THEME] = mode.name }
    }

    val accentPalette: Flow<AccentPalette> = context.settingsDataStore.data.map { prefs ->
        enumOrDefault(prefs[Keys.ACCENT_PALETTE], AccentPalette.EMERALD)
    }

    suspend fun setAccentPalette(palette: AccentPalette) {
        context.settingsDataStore.edit { it[Keys.ACCENT_PALETTE] = palette.name }
    }

    val appLanguage: Flow<AppLanguage> = context.settingsDataStore.data.map { prefs ->
        AppLanguage.entries.firstOrNull { it.name == prefs[Keys.APP_LANGUAGE] } ?: AppLanguage.SYSTEM
    }

    suspend fun setAppLanguage(language: AppLanguage) {
        context.settingsDataStore.edit { it[Keys.APP_LANGUAGE] = language.name }
    }

    // ------------------------------------------------------------------
    // Home layout
    // ------------------------------------------------------------------

    /** Browse grid column count (2..4). */
    val homeGridColumns: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        (prefs[Keys.HOME_GRID_COLUMNS] ?: 3).coerceIn(2, 4)
    }

    suspend fun setHomeGridColumns(columns: Int) {
        context.settingsDataStore.edit { it[Keys.HOME_GRID_COLUMNS] = columns.coerceIn(2, 4) }
    }

    val homeIconSize: Flow<IconSize> = context.settingsDataStore.data.map { prefs ->
        enumOrDefault(prefs[Keys.HOME_ICON_SIZE], IconSize.MEDIUM)
    }

    suspend fun setHomeIconSize(size: IconSize) {
        context.settingsDataStore.edit { it[Keys.HOME_ICON_SIZE] = size.name }
    }

    val homeLayoutStyle: Flow<HomeLayoutStyle> = context.settingsDataStore.data.map { prefs ->
        enumOrDefault(prefs[Keys.HOME_LAYOUT_STYLE], HomeLayoutStyle.SHELVES)
    }

    suspend fun setHomeLayoutStyle(style: HomeLayoutStyle) {
        context.settingsDataStore.edit { it[Keys.HOME_LAYOUT_STYLE] = style.name }
    }

    // ------------------------------------------------------------------
    // Ignored updates ("pkg" = all versions, "pkg@versionCode" = one version)
    // ------------------------------------------------------------------

    val ignoredUpdates: Flow<Set<String>> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.IGNORED_UPDATES] ?: emptySet()
    }

    suspend fun ignoreAllVersions(packageName: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.IGNORED_UPDATES] = (prefs[Keys.IGNORED_UPDATES] ?: emptySet()) + packageName
        }
    }

    suspend fun ignoreVersion(packageName: String, versionCode: Long) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.IGNORED_UPDATES] = (prefs[Keys.IGNORED_UPDATES] ?: emptySet()) + "$packageName@$versionCode"
        }
    }

    suspend fun unignore(packageName: String) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[Keys.IGNORED_UPDATES] ?: emptySet()
            prefs[Keys.IGNORED_UPDATES] = current.filterNot {
                it == packageName || it.startsWith("$packageName@")
            }.toSet()
        }
    }

    // ------------------------------------------------------------------
    // Favorites
    // ------------------------------------------------------------------

    /** Favorite apps as (packageName, name, iconUrl), newest first is not guaranteed. */
    val favorites: Flow<List<FavoriteApp>> = context.settingsDataStore.data.map { prefs ->
        (prefs[Keys.FAVORITES] ?: emptySet()).mapNotNull { FavoriteApp.decode(it) }.sortedBy { it.name.lowercase() }
    }

    suspend fun setFavorite(app: FavoriteApp, favorite: Boolean) {
        context.settingsDataStore.edit { prefs ->
            val current = (prefs[Keys.FAVORITES] ?: emptySet()).filterNot {
                FavoriteApp.decode(it)?.packageName == app.packageName
            }.toSet()
            prefs[Keys.FAVORITES] = if (favorite) current + app.encode() else current
        }
    }

    /** Home search bar pinned (always visible) — default: hides while scrolling. */
    val homeSearchPinned: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.HOME_SEARCH_PINNED] ?: false }

    suspend fun setHomeSearchPinned(pinned: Boolean) {
        context.settingsDataStore.edit { it[Keys.HOME_SEARCH_PINNED] = pinned }
    }

    /** pkg → source the user chose to update it from. */
    val preferredSources: Flow<Map<String, String>> = context.settingsDataStore.data.map { prefs ->
        (prefs[Keys.PREFERRED_SOURCES] ?: emptySet()).mapNotNull { entry ->
            val pkg = entry.substringBefore('=', "")
            val source = entry.substringAfter('=', "")
            if (pkg.isBlank() || source.isBlank()) null else pkg to source
        }.toMap()
    }

    suspend fun setPreferredSource(packageName: String, source: String) {
        context.settingsDataStore.edit { prefs ->
            val rest = (prefs[Keys.PREFERRED_SOURCES] ?: emptySet()).filterNot { it.startsWith("$packageName=") }
            prefs[Keys.PREFERRED_SOURCES] = rest.toSet() + "$packageName=$source"
        }
    }

    suspend fun notifiedUpdatesSnapshot(): Set<String> = withContext(dispatcherProvider.io) {
        context.settingsDataStore.data.first()[Keys.NOTIFIED_UPDATES] ?: emptySet()
    }

    suspend fun setNotifiedUpdates(keys: Set<String>) {
        context.settingsDataStore.edit { it[Keys.NOTIFIED_UPDATES] = keys }
    }

    suspend fun ignoredUpdatesSnapshot(): Set<String> = withContext(dispatcherProvider.io) {
        ignoredUpdates.first()
    }

    // ------------------------------------------------------------------
    // Community dispensers
    // ------------------------------------------------------------------

    /** Whether anonymous login may also try the built-in community dispensers. */
    val tryCommunityDispensers: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.TRY_COMMUNITY_DISPENSERS] ?: true
    }

    suspend fun setTryCommunityDispensers(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.TRY_COMMUNITY_DISPENSERS] = enabled }
    }

    /**
     * Anonymous Google Play: the native Play protocol (search, details, exact
     * versions, original APK delivery) without any account, through the
     * built-in anonymous session. On by default.
     */
    val anonymousPlayEnabled: Flow<Boolean> get() = tryCommunityDispensers

    suspend fun setAnonymousPlayEnabled(enabled: Boolean) = setTryCommunityDispensers(enabled)

    suspend fun anonymousPlayEnabledSnapshot(): Boolean = withContext(dispatcherProvider.io) {
        tryCommunityDispensers.first()
    }
}
