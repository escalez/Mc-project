package com.example.network

interface WebViewProxyContract {
    fun isProxyOverrideSupported(): Boolean
    suspend fun applyLocalRelayProxy(
        localPort: Int,
        forceReapply: Boolean = false,
        localHost: String = "127.0.0.1"
    ): Result<String>
    suspend fun clearProxyOverride(): Result<Unit>
}
