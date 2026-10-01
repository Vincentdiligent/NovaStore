package com.novastore.app.core.model

enum class InstallationMode {
    AUTOMATIC,
    STANDARD,
    ROOT,
    MANAGED,
}

enum class RootAccessState {
    UNAVAILABLE,
    AVAILABLE,
    AUTHORIZATION_REQUIRED,
    AUTHORIZED,
    DENIED,
    REVOKED,
    ERROR,
}

enum class UpdateSchedule {
    IMMEDIATELY,
    DAILY,
    WEEKLY,
}
