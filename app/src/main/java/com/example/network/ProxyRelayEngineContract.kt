package com.example.network

interface ProxyRelayEngineContract {
    val activeLocalBindHost: String
    val boundPort: Int
    val activeLocalSlotIndex: Int
    val wifiLocalBindAddress: String
    val cellularLocalBindAddress: String

    fun isRelayActive(): Boolean
    fun currentSessionId(): Long
    fun currentSocketChannel(): ActiveSocketChannel
    fun detectActiveSocketChannel(): ActiveSocketChannel
    fun setTurboPrewarmEnabled(enabled: Boolean)
    fun triggerPrewarmPoolReplenish(count: Int = 1)
    fun rotateToNextAvailableLocalIp(reasonTag: String = "IP-POOL-ROTATE"): LocalIpLease?

    suspend fun startRelay(config: Socks5Config): Result<Int>
    suspend fun stopRelay()
    fun killAllSocketsNow(): Int

    fun startBackgroundAutoReconnect(
        config: Socks5Config = Socks5Config(),
        reasonTag: String = "AUTO-RECONNECT"
    )

    suspend fun verifyUpstreamConnectionWithRetry(
        config: Socks5Config = Socks5Config(),
        maxAttempts: Int = 2,
        perAttemptTimeoutMs: Int = 5_500,
        retryDelayMs: Long = 450L
    ): Result<Long>

    suspend fun testUpstreamHandshake(
        config: Socks5Config = Socks5Config(),
        timeoutMs: Int = 5_500
    ): Result<Long>
}
