package com.novastore.app.core.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives the PackageInstaller status callback for sessions committed by
 * the standard and managed strategies. PENDING_USER_ACTION forwards the
 * system confirmation dialog to the user (never bypassed).
 */
@AndroidEntryPoint
class InstallStatusReceiver : BroadcastReceiver() {

    @Inject
    lateinit var sessionDelegate: PackageInstallerSessionDelegate

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmIntent: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                val shown = confirmIntent != null && try {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                    true
                } catch (_: Exception) {
                    // Android refuses to open the dialog from the background.
                    false
                }
                if (shown) {
                    sessionDelegate.onSessionStatus(sessionId, status, message)
                } else {
                    sessionDelegate.onUserActionUnavailable(sessionId)
                }
            }
            else -> {
                sessionDelegate.onSessionStatus(sessionId, status, message)
            }
        }
    }
}
