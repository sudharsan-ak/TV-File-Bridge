package com.tvfilebridge.app.connection

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val host: String) : ConnectionState
    data class AwaitingAuthorization(val host: String) : ConnectionState
    data class Connected(val host: String, val port: Int) : ConnectionState
    data class Failed(val host: String, val reason: FailureReason) : ConnectionState
}

enum class FailureReason {
    CONNECTION_REFUSED,
    TIMEOUT,
    AUTH_REJECTED,
    UNKNOWN_HOST,
    ALREADY_IN_USE,
    UNKNOWN,
}
