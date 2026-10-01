package com.novastore.app.core.common

/**
 * Central dispatcher provider for structured concurrency.
 * All heavy work (network, database, hashing, package scanning) runs off the main thread.
 */
interface DispatcherProvider {
    val io: kotlinx.coroutines.CoroutineDispatcher
    val default: kotlinx.coroutines.CoroutineDispatcher
    val main: kotlinx.coroutines.CoroutineDispatcher
}
