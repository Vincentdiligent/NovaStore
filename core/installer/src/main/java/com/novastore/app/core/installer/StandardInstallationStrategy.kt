package com.novastore.app.core.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.InstallResult
import com.novastore.app.core.model.NovaError
import com.novastore.app.core.model.PackageInstallationPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Broadcast action used by the install status callback. */
const val ACTION_INSTALL_STATUS = "com.novastore.app.INSTALL_STATUS"

/** Terminal install events, consumed by notifications and the UI. */
data class InstallEvent(
    val sessionId: Int,
    val packageName: String,
    val succeeded: Boolean,
    val message: String?,
)

/**
 * Shared PackageInstaller session logic. Used by the standard strategy
 * (user-facing Android confirmation flow) and the managed strategy
 * (silent install when Nova Store is device owner).
 */
@Singleton
class PackageInstallerSessionDelegate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) {
    private val pendingSessions = ConcurrentHashMap<Int, CompletableDeferred<InstallResult>>()
    private val planBySession = ConcurrentHashMap<Int, PackageInstallationPlan>()
    private val events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 16)

    fun observeEvents(): SharedFlow<InstallEvent> = events.asSharedFlow()

    suspend fun install(
        plan: PackageInstallationPlan,
        requireUserConsentCapability: Boolean,
        timeoutMillis: Long = DEFAULT_TIMEOUT,
    ): InstallResult = withContext(dispatcherProvider.io) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(plan.packageName)
            setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
            plan.artifacts.sumOf { it.size ?: 0L }.takeIf { it > 0 }?.let(::setSize)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Lets Android skip the confirmation for updates of apps Nova Store
                // installed before; Android still asks whenever it has to.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        val deferred = CompletableDeferred<InstallResult>()
        pendingSessions[sessionId] = deferred
        planBySession[sessionId] = plan

        try {
            val session = installer.openSession(sessionId)
            try {
                for (artifact in plan.artifacts) {
                    val localPath = artifact.localPath
                        ?: return@withContext fail(sessionId, NovaError.InvalidPackage)
                    val file = File(localPath)
                    if (!file.exists()) {
                        return@withContext fail(sessionId, NovaError.InvalidPackage)
                    }
                    session.openWrite(artifact.fileName, 0, file.length()).use { output ->
                        file.inputStream().use { input -> input.copyTo(output) }
                    }
                }

                val statusIntent = Intent(context, InstallStatusReceiver::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    flags,
                )
                session.commit(pendingIntent.intentSender)
            } finally {
                session.close()
            }

            val result = withTimeoutOrNull(timeoutMillis) { deferred.await() }
            if (result == null) {
                installer.abandonSession(sessionId)
                InstallResult.Failure(NovaError.Timeout)
            } else {
                result
            }
        } finally {
            pendingSessions.remove(sessionId)
            planBySession.remove(sessionId)
        }
    }

    /** Called by [InstallStatusReceiver] when the system reports a session status. */
    fun onSessionStatus(sessionId: Int, status: Int, message: String?) {
        val deferred = pendingSessions[sessionId]
        val plan = planBySession[sessionId]
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The receiver has shown the system dialog; keep waiting for the
                // user's decision (the final SUCCESS/FAILURE status follows).
            }
            PackageInstaller.STATUS_SUCCESS -> {
                deferred?.complete(
                    InstallResult.Success(
                        packageName = plan?.packageName ?: "",
                        versionCode = plan?.versionCode ?: -1,
                    ),
                )
                emitEvent(sessionId, plan, true, null)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // The user pressed "Cancel" on the system dialog — a neutral
                // outcome, not a failure to report in red.
                deferred?.complete(InstallResult.Cancelled)
                emitEvent(sessionId, plan, false, message)
            }
            else -> {
                deferred?.complete(
                    InstallResult.Failure(
                        NovaError.InstallationFailed(detail = describeStatus(status, message)),
                    ),
                )
                emitEvent(sessionId, plan, false, message)
            }
        }
    }

    private fun describeStatus(status: Int, message: String?): String {
        val reason = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation was cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation was blocked by the system or a policy"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "The update is signed with a different key than the installed app, or conflicts with it"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "The app is not compatible with this device"
            PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is invalid or corrupted"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage space"
            else -> "Installation failed (status $status)"
        }
        return if (message.isNullOrBlank()) reason else "$reason: $message"
    }

    /** The confirmation dialog could not be shown (e.g. app in background). */
    fun onUserActionUnavailable(sessionId: Int) {
        pendingSessions[sessionId]?.complete(InstallResult.UserActionRequired)
    }

    private fun emitEvent(sessionId: Int, plan: PackageInstallationPlan?, succeeded: Boolean, message: String?) {
        if (plan != null) {
            events.tryEmit(InstallEvent(sessionId, plan.packageName, succeeded, message))
        }
    }

    private fun fail(sessionId: Int, error: NovaError): InstallResult {
        try {
            context.packageManager.packageInstaller.abandonSession(sessionId)
        } catch (_: Exception) {
            // Session may already be gone.
        }
        return InstallResult.Failure(error)
    }

    companion object {
        const val DEFAULT_TIMEOUT = 10 * 60_000L
    }
}

/**
 * Standard installation via the official Android PackageInstaller.
 * The system confirmation dialog is never bypassed.
 */
@Singleton
class StandardInstallationStrategy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionDelegate: PackageInstallerSessionDelegate,
) : InstallationStrategy {

    override suspend fun isAvailable(): Boolean {
        // Below API 26 REQUEST_INSTALL_PACKAGES is not needed for sideloading
        // initiated from a visible app; the system flow still confirms.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    override suspend fun install(plan: PackageInstallationPlan): InstallResult {
        if (!isAvailable()) {
            return InstallResult.Failure(NovaError.PermissionDenied)
        }
        return sessionDelegate.install(plan, requireUserConsentCapability = true)
    }
}

/**
 * Silent installation available only when Nova Store is the device owner
 * (enterprise / fully managed scenarios). Honest unavailable state otherwise.
 */
@Singleton
class ManagedDeviceInstallationStrategy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionDelegate: PackageInstallerSessionDelegate,
) : InstallationStrategy {

    override suspend fun isAvailable(): Boolean {
        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java) ?: return false
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    override suspend fun install(plan: PackageInstallationPlan): InstallResult {
        if (!isAvailable()) {
            return InstallResult.Failure(
                NovaError.InstallationFailed(
                    detail = "Managed installation requires Device Owner mode; Nova Store is not the device owner.",
                ),
            )
        }
        return sessionDelegate.install(plan, requireUserConsentCapability = false)
    }
}
