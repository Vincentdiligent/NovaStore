package com.novastore.app.core.installer

import com.novastore.app.core.model.RootAccessResult
import com.novastore.app.core.model.RootAccessState
import com.novastore.app.core.model.RootCommand
import com.novastore.app.core.model.RootCommandResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Root detection based on the actual ability to execute a safe privileged
 * operation ("id") — never on the mere existence of a `su` binary.
 */
@Singleton
class SuRootAccessProvider @Inject constructor(
    private val executor: RootCommandExecutor,
) : RootAccessProvider {

    private val state = MutableStateFlow(RootAccessState.UNAVAILABLE)

    override fun observeState(): Flow<RootAccessState> = state.asStateFlow()

    override suspend fun currentState(): RootAccessState = state.value

    override suspend fun isRootAvailable(): Boolean {
        if (!hasSuBinary()) {
            state.value = RootAccessState.UNAVAILABLE
            return false
        }
        // Executing any su command triggers the root management authorization
        // prompt. "id" is safe and proves actual privileged execution.
        val result = executor.execute(ROOT_PROBE_COMMAND)
        val authorized = result.isSuccess && result.stdout.contains("uid=0")
        state.value = when {
            authorized -> RootAccessState.AUTHORIZED
            result.timedOut -> RootAccessState.AUTHORIZATION_REQUIRED
            else -> RootAccessState.DENIED
        }
        return authorized
    }

    override suspend fun requestRootAccess(): RootAccessResult {
        if (!hasSuBinary()) {
            state.value = RootAccessState.UNAVAILABLE
            return RootAccessResult.Unavailable
        }
        val result = executor.execute(ROOT_PROBE_COMMAND)
        return when {
            result.isSuccess && result.stdout.contains("uid=0") -> {
                state.value = RootAccessState.AUTHORIZED
                RootAccessResult.Authorized
            }
            result.timedOut -> {
                state.value = RootAccessState.AUTHORIZATION_REQUIRED
                RootAccessResult.Denied
            }
            else -> {
                state.value = RootAccessState.DENIED
                RootAccessResult.Denied
            }
        }
    }

    override suspend fun execute(command: RootCommand): RootCommandResult {
        if (state.value == RootAccessState.REVOKED) {
            return RootCommandResult(
                exitCode = null,
                stdout = "",
                stderr = "Root authorization was revoked",
            )
        }
        if (!hasSuBinary()) {
            state.value = RootAccessState.UNAVAILABLE
            return RootCommandResult(
                exitCode = null,
                stdout = "",
                stderr = "su binary not present on this device",
            )
        }
        val result = executor.execute(command)
        if (looksDenied(result)) {
            state.value = RootAccessState.REVOKED
        }
        return result
    }

    private fun looksDenied(result: RootCommandResult): Boolean =
        result.exitCode != 0 && (
            result.stderr.contains("denied", ignoreCase = true) ||
                result.stderr.contains("not authorized", ignoreCase = true) ||
                result.stderr.contains("permission", ignoreCase = true)
            )

    private fun hasSuBinary(): Boolean {
        val pathDirs = System.getenv("PATH").orEmpty().split(':').filter { it.isNotBlank() }.map { "$it/su" }
        return (SU_BINARY_PATHS + pathDirs).any { File(it).exists() }
    }

    companion object {
        // Presence of these paths is NOT treated as proof of root — only the
        // successful privileged "id" execution above is.
        private val SU_BINARY_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/bin/.ext/su",
            "/debug_ramdisk/su", // Magisk (Android 11+)
            "/data/adb/magisk/su",
            "/data/adb/ksu/bin/su", // KernelSU
            "/data/adb/ap/bin/su", // APatch
            "/su/bin/su",
        )
        private val ROOT_PROBE_COMMAND = RootCommand(
            program = "id",
            arguments = emptyList(),
            timeoutMillis = 20_000,
        )
    }
}
