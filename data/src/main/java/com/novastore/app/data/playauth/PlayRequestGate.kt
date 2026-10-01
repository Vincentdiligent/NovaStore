package com.novastore.app.data.playauth

import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Traffic shaper for the native Play protocol. Google throttles a session
 * (HTTP 429) when it sees bursts — e.g. a details page, a search and an
 * update scan firing a dozen requests in the same second. The gate keeps
 * Nova's traffic looking like a normal Play client:
 *
 *  - at most [MAX_PARALLEL] requests in flight;
 *  - request starts spaced by at least [MIN_SPACING_MS];
 *  - after a 429 every request waits out a growing, jittered cool-down
 *    before the next attempt (instead of hammering and making it worse).
 */
class PlayRequestGate {
    private val permits = Semaphore(MAX_PARALLEL)
    private val slotLock = Mutex()

    @Volatile
    private var nextSlotAt = 0L

    @Volatile
    private var cooldownUntil = 0L

    suspend fun <T> run(block: suspend () -> T): T = permits.withPermit {
        val wait = slotLock.withLock {
            val now = System.currentTimeMillis()
            val start = maxOf(now, nextSlotAt, cooldownUntil)
            nextSlotAt = start + MIN_SPACING_MS
            start - now
        }
        if (wait > 0) delay(wait)
        block()
    }

    /** Called on HTTP 429; [attempt] starts at 0 for the first retry. */
    fun onRateLimited(attempt: Int) {
        val pause = BASE_COOLDOWN_MS * (attempt + 1) + Random.nextLong(0, JITTER_MS)
        cooldownUntil = maxOf(cooldownUntil, System.currentTimeMillis() + pause)
    }

    private companion object {
        const val MAX_PARALLEL = 2
        const val MIN_SPACING_MS = 150L
        const val BASE_COOLDOWN_MS = 1_500L
        const val JITTER_MS = 800L
    }
}
