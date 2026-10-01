package com.novastore.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.novastore.app.ui.NovaStoreRoot
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @javax.inject.Inject
    lateinit var catalogRepository: com.novastore.app.domain.repository.CatalogRepository

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Pending notification action (open Updates / update all), consumed by the UI. */
    private val launchAction = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            NovaStoreRoot(
                launchAction = launchAction.value,
                onLaunchActionConsumed = { launchAction.value = null },
            )
        }
        if (savedInstanceState == null) requestNotificationPermissionOnce()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // App already open: the notification tap / link must still navigate.
        handleIntent(intent)
    }

    /**
     * Notification actions apply at once; store links and shared text are
     * resolved first (short links followed through their redirects).
     */
    private fun handleIntent(intent: Intent?) {
        val raw = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        android.util.Log.i(
            LOG_TAG,
            "incoming action=${intent?.action} data=${intent?.dataString} text=${intent?.getStringExtra(Intent.EXTRA_TEXT)} referrer=${referrer}",
        )
        if (raw == null) {
            actionOf(intent)?.let { launchAction.value = it }
            return
        }
        // Instant answer for plain store links, network only for short links.
        val quick = com.novastore.app.core.model.StoreLinks.parse(raw)
        // Browsers turn a Play page's "open in Play Store" into a link to the
        // Play Store app ITSELF (com.android.vending / microG Companion) —
        // the real app id is already lost. Never show that page by mistake.
        if (quick is com.novastore.app.core.model.StoreLink.App &&
            quick.packageName in STORE_SELF_PACKAGES &&
            referrer?.host != packageName
        ) {
            android.util.Log.i(LOG_TAG, "browser store-redirect without app id; ignored")
            android.widget.Toast.makeText(this, getString(R.string.links_browser_lost_app), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (quick is com.novastore.app.core.model.StoreLink.App) {
            android.util.Log.i(LOG_TAG, "resolved ${quick.packageName}")
            launchAction.value = "$PREFIX_DETAILS${quick.packageName}"
            return
        }
        lifecycleScope.launch {
            val link = runCatching { catalogRepository.resolveStoreLink(raw) }.getOrNull() ?: quick
            android.util.Log.i(LOG_TAG, "resolved $link")
            launchAction.value = when (link) {
                is com.novastore.app.core.model.StoreLink.App -> "$PREFIX_DETAILS${link.packageName}"
                is com.novastore.app.core.model.StoreLink.Search -> "$PREFIX_SEARCH${link.query}"
                null -> null
            }
        }
    }

    private fun actionOf(intent: Intent?): String? = when {
        intent?.action == Intent.ACTION_VIEW && intent.data != null -> linkAction(intent.dataString)
        intent?.action == Intent.ACTION_SEND -> linkAction(intent.getStringExtra(Intent.EXTRA_TEXT))
        intent?.action == ACTION_UPDATE_ALL -> ACTION_UPDATE_ALL
        intent?.action == ACTION_OPEN_UPDATES || intent?.getBooleanExtra(EXTRA_OPEN_UPDATES, false) == true ->
            ACTION_OPEN_UPDATES
        else -> null
    }

    /** Asks once on Android 13+; a refusal is respected and not asked again. */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (granted || prefs.getBoolean(KEY_ASKED, false)) return
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** A store link / shared text → "details:<pkg>" or "search:<query>". */
    private fun linkAction(raw: String?): String? =
        when (val link = com.novastore.app.core.model.StoreLinks.parse(raw)) {
            is com.novastore.app.core.model.StoreLink.App -> "$PREFIX_DETAILS${link.packageName}"
            is com.novastore.app.core.model.StoreLink.Search -> "$PREFIX_SEARCH${link.query}"
            null -> null
        }

    companion object {
        private const val LOG_TAG = "NovaLinks"

        /** Links to these mean "open a store", not "show this app". */
        private val STORE_SELF_PACKAGES = setOf("com.android.vending", "com.google.android.gms")
        const val PREFIX_DETAILS = "details:"
        const val PREFIX_SEARCH = "search:"
        const val EXTRA_OPEN_UPDATES = "com.novastore.app.OPEN_UPDATES"
        const val ACTION_OPEN_UPDATES = "com.novastore.app.action.OPEN_UPDATES"
        const val ACTION_UPDATE_ALL = "com.novastore.app.action.UPDATE_ALL"
        private const val PREFS = "nova_permissions"
        private const val KEY_ASKED = "notifications_asked"
    }
}
