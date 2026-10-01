package com.novastore.app.core.security

import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Streams a file through SHA-256. Large APKs are never fully loaded into memory.
 */
@Singleton
class HashVerifier @Inject constructor(
    private val dispatcherProvider: com.novastore.app.core.common.DispatcherProvider,
) {
    suspend fun sha256(file: File): String = withContext(dispatcherProvider.io) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun sha256OrThrow(file: File): String = sha256(file)

    companion object {
        fun isValidSha256Hex(value: String): Boolean =
            value.length == 64 && value.all { it in "0123456789abcdefABCDEF" }
    }
}
