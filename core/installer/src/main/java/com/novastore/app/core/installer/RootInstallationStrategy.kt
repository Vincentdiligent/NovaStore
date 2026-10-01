package com.novastore.app.core.installer

import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.ArtifactType
import com.novastore.app.core.model.InstallResult
import com.novastore.app.core.model.NovaError
import com.novastore.app.core.model.PackageInstallationPlan
import com.novastore.app.core.model.RootAccessResult
import com.novastore.app.core.model.RootAccessState
import com.novastore.app.core.model.RootCommand
import com.novastore.app.core.model.RootCommandResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Root-based installation backend, fully separated from the standard
 * PackageInstaller flow. Root only changes the installation mechanism —
 * the artifact verification pipeline stays mandatory (prompt #107).
 */
@Singleton
class RootInstallationStrategy @Inject constructor(
    private val rootAccessProvider: RootAccessProvider,
    private val dispatcherProvider: DispatcherProvider,
) : InstallationStrategy {

    override suspend fun isAvailable(): Boolean {
        val state = rootAccessProvider.currentState()
        return when (state) {
            RootAccessState.AUTHORIZED -> true
            RootAccessState.UNAVAILABLE, RootAccessState.DENIED, RootAccessState.REVOKED, RootAccessState.ERROR ->
                rootAccessProvider.isRootAvailable()
            RootAccessState.AVAILABLE, RootAccessState.AUTHORIZATION_REQUIRED ->
                rootAccessProvider.isRootAvailable()
        }
    }

    override suspend fun install(plan: PackageInstallationPlan): InstallResult = withContext(dispatcherProvider.io) {
        // 1. Root access must be verified before every privileged operation.
        when (val access = rootAccessProvider.requestRootAccess()) {
            RootAccessResult.Authorized -> Unit
            RootAccessResult.Unavailable -> return@withContext InstallResult.Failure(NovaError.RootUnavailable)
            RootAccessResult.Denied -> return@withContext InstallResult.Failure(NovaError.RootDenied)
            RootAccessResult.Revoked -> return@withContext InstallResult.Failure(NovaError.RootRevoked)
            is RootAccessResult.Error -> return@withContext InstallResult.Failure(
                NovaError.InstallationFailed(detail = access.message),
            )
        }

        // 2. Validate local artifact files first (never pass URLs to the shell).
        val files = plan.artifacts.map { artifact ->
            val path = artifact.localPath
                ?: return@withContext InstallResult.Failure(NovaError.InvalidPackage)
            val file = File(path)
            if (!file.exists() || file.length() == 0L) {
                return@withContext InstallResult.Failure(NovaError.InvalidPackage)
            }
            file
        }

        // 3. Execute the privileged installation with structured commands.
        val result = if (files.size == 1 && plan.artifacts.first().artifactType != ArtifactType.MULTI_APK) {
            installSingleApk(files.first())
        } else {
            installMultiFileSession(plan, files)
        }

        // 4. Exit code 0 alone is never treated as success — the caller
        //    (InstallPackageUseCase) re-checks the installed version via PackageManager.
        when {
            result.isSuccess -> InstallResult.Success(plan.packageName, plan.versionCode)
            else -> InstallResult.Failure(
                NovaError.InstallationFailed(detail = result.stderr.ifBlank { "Root installation failed (exit=${result.exitCode})" }),
            )
        }
    }

    /** `pm install -r <path>` for a single, already verified APK. */
    private suspend fun installSingleApk(file: File): RootCommandResult {
        val command = RootCommand(
            program = "pm",
            arguments = listOf("install", "-r", file.absolutePath),
            timeoutMillis = INSTALL_TIMEOUT,
        )
        return rootAccessProvider.execute(command)
    }

    /**
     * Multi-artifact installation (split APK sets) via the pm session protocol:
     * install-create → install-write per file → install-commit. Nova Store is
     * recorded as the installer so PackageManager reports it as the source.
     */
    private suspend fun installMultiFileSession(
        plan: PackageInstallationPlan,
        files: List<File>,
    ): RootCommandResult {
        val create = rootAccessProvider.execute(
            RootCommand(
                program = "pm",
                arguments = listOf("install-create", "-r", "-i", "com.novastore.app"),
                timeoutMillis = INSTALL_TIMEOUT,
            ),
        )
        val sessionId = parseSessionId(create.stdout)
            ?: return create

        files.forEachIndexed { index, file ->
            val name = plan.artifacts.getOrNull(index)?.fileName ?: "base_${index}.apk"
            val write = rootAccessProvider.execute(
                RootCommand(
                    program = "pm",
                    arguments = listOf(
                        "install-write",
                        "-S",
                        file.length().toString(),
                        sessionId,
                        name,
                        file.absolutePath,
                    ),
                    timeoutMillis = INSTALL_TIMEOUT,
                ),
            )
            if (!write.isSuccess) return write
        }

        return rootAccessProvider.execute(
            RootCommand(
                program = "pm",
                arguments = listOf("install-commit", sessionId),
                timeoutMillis = INSTALL_TIMEOUT,
            ),
        )
    }

    private fun parseSessionId(output: String): String? {
        // Output looks like: "Success: created session id [123456]"
        val regex = Regex("(\\d+)")
        return regex.find(output.substringAfter("session", ""))?.groupValues?.get(1)
    }

    companion object {
        private const val INSTALL_TIMEOUT = 120_000L
    }
}
