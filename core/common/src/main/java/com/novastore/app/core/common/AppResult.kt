package com.novastore.app.core.common

import com.novastore.app.core.model.NovaError

/**
 * Functional result type used across layer boundaries.
 */
sealed class AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>()
    data class Failure(val error: NovaError) : AppResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun onSuccess(block: (T) -> Unit): AppResult<T> {
        if (this is Success) block(value)
        return this
    }

    inline fun onFailure(block: (NovaError) -> Unit): AppResult<T> {
        if (this is Failure) block(error)
        return this
    }

    fun getOrNull(): T? = (this as? Success)?.value

    companion object {
        fun <T> success(value: T): AppResult<T> = Success(value)
        fun failure(error: NovaError): AppResult<Nothing> = Failure(error)
    }
}

inline fun <T> runCatchingApp(errorMapper: (Throwable) -> NovaError, block: () -> T): AppResult<T> =
    try {
        AppResult.Success(block())
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        AppResult.Failure(errorMapper(t))
    }
