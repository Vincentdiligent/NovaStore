package com.novastore.app.core.model

/**
 * Result of an installation attempt.
 */
sealed class InstallResult {
    data class Success(
        val packageName: String,
        val versionCode: Long,
    ) : InstallResult()

    /** Android requires an interactive confirmation; the flow has been surfaced to the user. */
    data object UserActionRequired : InstallResult()

    /** The user dismissed the system install confirmation — not an error. */
    data object Cancelled : InstallResult()

    data class Failure(
        val error: NovaError,
    ) : InstallResult()
}

/**
 * Result of a root access request.
 */
sealed class RootAccessResult {
    data object Authorized : RootAccessResult()
    data object Denied : RootAccessResult()
    data object Unavailable : RootAccessResult()
    data object Revoked : RootAccessResult()
    data class Error(val message: String) : RootAccessResult()
}

/**
 * A command to be executed with root privileges. Built exclusively from
 * validated structured arguments inside the application; never from raw
 * remote data.
 */
data class RootCommand(
    /** Program to execute, e.g. "pm". */
    val program: String,
    /** Pre-validated arguments. */
    val arguments: List<String>,
    val timeoutMillis: Long = 30_000,
) {
    /** Renders the command. Every argument must pass [RootCommand.isSafe]. */
    val rendered: String
        get() = (listOf(program) + arguments).joinToString(" ")

    companion object {
        private val SAFE_ARG = Regex("^[A-Za-z0-9._/+@,=-]+$")

        /**
         * Strict argument whitelist: no shell metacharacters are ever allowed,
         * so remote metadata can never be smuggled into a root shell.
         */
        fun isSafeArgument(arg: String): Boolean = arg.isNotEmpty() && SAFE_ARG.matches(arg)

        fun validate(program: String, arguments: List<String>): Boolean =
            isSafeArgument(program) && arguments.all { isSafeArgument(it) }
    }
}

data class RootCommandResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val isSuccess: Boolean get() = exitCode == 0 && !timedOut
}
