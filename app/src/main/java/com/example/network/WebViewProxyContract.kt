package com.example.network

/**
 * واجهة مستقلة خاصة بربط متصفح WebView الداخلي بنفق البروكسي المحلي (WebView Proxy Contract).
 */
interface WebViewProxyContract {
    fun isProxyOverrideSupported(): Boolean
    suspend fun applyLocalRelayProxy(localPort: Int, forceReapply: Boolean = false): Result<String>
    suspend fun clearProxyOverride(): Result<Unit>
}
