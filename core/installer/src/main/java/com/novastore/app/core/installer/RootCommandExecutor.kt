package com.novastore.app.core.installer

import android.content.Context
import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.RootCommand
import com.novastore.app.core.model.RootCommandResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Executes validated root commands with timeout, output capture and
 * cancellation. Commands are built exclusively from structured, validated
 * arguments; remote metadata can never reach the root shell unfiltered.
 */
@Singleton
class RootCommandExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) {
    suspend fun execute(command: RootCommand): RootCommandResult = withContext(dispatcherProvider.io) {
        // Reject anything that failed strict validation.
        if (!RootCommand.validate(command.program, command.arguments)) {
            return@withContext RootCommandResult(
                exitCode = null,
                stdout = "",
                stderr = "Command rejected: arguments failed safety validation",
            )
        }

        // Local artifact paths must live inside the app's own private cache.
        command.arguments
            .filter { it.startsWith("/") }
            .forEach { path ->
                val file = File(path)
                val canonical = try { file.canonicalPath } catch (_: Exception) { path }
                val allowedRoot = try {
                    File(context.cacheDir, "downloads").canonicalPath
                } catch (_: Exception) {
                    return@withContext RootCommandResult(
                        exitCode = null,
                        stdout = "",
                        stderr = "Command rejected: unable to resolve private cache directory",
                    )
                }
                if (!canonical.startsWith(allowedRoot)) {
                    return@withContext RootCommandResult(
                        exitCode = null,
                        stdout = "",
                        stderr = "Command rejected: artifact path outside the app cache directory",
                    )
                }
            }

        var process: Process? = null
        try {
            process = ProcessBuilder(listOf("su", "-c", command.rendered))
                .redirectErrorStream(false)
                .start()

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outReader = Thread { process!!.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } }
            val errReader = Thread { process!!.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
            outReader.isDaemon = true
            errReader.isDaemon = true
            outReader.start()
            errReader.start()

            val timedOut = withTimeoutOrNull(command.timeoutMillis) {
                process!!.waitFor()
            } == null

            if (timedOut) {
                process!!.destroyForcibly()
            }
            // Give reader threads a moment to drain output.
            outReader.join(2000)
            errReader.join(2000)

            RootCommandResult(
                exitCode = if (timedOut) null else process!!.exitValue(),
                stdout = stdout.toString().trim(),
                stderr = stderr.toString().trim(),
                timedOut = timedOut,
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                process?.destroyForcibly()
                throw t
            }
            RootCommandResult(
                exitCode = null,
                stdout = "",
                stderr = t.message ?: "Failed to start the root process",
            )
        } finally {
            process?.destroyForcibly()
        }
    }
}
