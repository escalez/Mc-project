package com.example.network

/**
 * واجهة مستقلة خاصة بمحرك الجسر المحلي للبروكسي (Proxy Relay Engine Contract).
 * تجمع وتنسق بين واجهة السوكيت، وواجهة قاطع الإغلاق، وواجهة مصافحة SOCKS5، وواجهة الـ DNS.
 */
interface ProxyRelayEngineContract {
    val boundPort: Int

    fun isRelayActive(): Boolean
    fun currentSessionId(): Long
    fun currentSocketChannel(): ActiveSocketChannel
    fun detectActiveSocketChannel(): ActiveSocketChannel
    fun setTurboPrewarmEnabled(enabled: Boolean)
    fun triggerPrewarmPoolReplenish(count: Int = 1)

    suspend fun startRelay(config: Socks5Config): Result<Int>
    suspend fun stopRelay()
    fun killAllSocketsNow(): Int

    suspend fun verifyUpstreamConnectionWithRetry(
        config: Socks5Config = Socks5Config(),
        maxAttempts: Int = 2,
        perAttemptTimeoutMs: Int = 7_500,
        retryDelayMs: Long = 500L
    ): Result<Long>

    suspend fun testUpstreamHandshake(
        config: Socks5Config = Socks5Config(),
        timeoutMs: Int = 7_500
    ): Result<Long>
}
