package com.example.network

import java.io.DataInputStream
import java.io.OutputStream

interface Socks5HandshakeContract {
    suspend fun verifyUpstreamConnectionWithRetry(
        config: Socks5Config,
        maxAttempts: Int = 2,
        perAttemptTimeoutMs: Int = 7_500,
        retryDelayMs: Long = 500L,
        isAllowedToContinue: () -> Boolean = { true }
    ): Result<Long>

    suspend fun testUpstreamHandshake(
        config: Socks5Config,
        timeoutMs: Int = 7_500,
        isAllowedToContinue: () -> Boolean = { true }
    ): Result<Long>

    fun performUpstreamSocks5GreetingAndAuth(
        upIn: DataInputStream,
        upOut: OutputStream,
        cfg: Socks5Config
    )

    fun sendUpstreamSocks5Connect(
        upIn: DataInputStream,
        upOut: OutputStream,
        atyp: Int,
        rawAddressBytes: ByteArray,
        targetPort: Int,
        targetHostReadable: String
    )
}
