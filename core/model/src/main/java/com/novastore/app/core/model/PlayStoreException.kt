package com.novastore.app.core.model

/**
 * Carries a typed [NovaError] across a throwing boundary (Google Play login,
 * purchase resolution). Callers catch this and map it to an [AppResult] failure.
 */
class PlayStoreException(
    val error: NovaError,
) : Exception(error.userMessage)
