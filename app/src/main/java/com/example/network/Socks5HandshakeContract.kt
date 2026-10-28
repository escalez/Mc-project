package com.example.network

import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket

/**
 * واجهة مستقلة خاصة ببروتوكول ومصافحة SOCKS5 / SOCKS4 / HTTP CONNECT (SOCKS5 Handshake Contract).
 * تفصل منطق المصافحة والمصادقة (RFC 1929) وفحص الاتصال الفعلي عن إدارة السوكيت والجسر المحلي.
 */
interface Socks5HandshakeContract {
    /**
     * يفحص الوصول الفعلي إلى البروكسي مع إعادة المحاولة الذكية عبر قناة السوكيت المخصصة.
     */
    suspend fun verifyUpstreamConnectionWithRetry(
        config: Socks5Config,
        maxAttempts: Int = 2,
        perAttemptTimeoutMs: Int = 7_500,
        retryDelayMs: Long = 500L,
        isAllowedToContinue: () -> Boolean = { true }
    ): Result<Long>

    /**
     * يختبر مصافحة البروكسي الفعلي (SOCKS5 Greeting + RFC 1929 Auth) على سوكيت واحد نظيف ويعيد البنق بالـ ms.
     */
    suspend fun testUpstreamHandshake(
        config: Socks5Config,
        timeoutMs: Int = 7_500,
        isAllowedToContinue: () -> Boolean = { true }
    ): Result<Long>

    /**
     * ينفذ مصافحة الترحيب والمصادقة (No-Auth أو RFC 1929 Username/Password) مع سيرفر SOCKS5.
     */
    fun performUpstreamSocks5GreetingAndAuth(
        upIn: DataInputStream,
        upOut: OutputStream,
        cfg: Socks5Config
    )

    /**
     * يرسل أمر CONNECT عبر نفق SOCKS5 إلى الهدف النهائي (الراوتر أو الموقع) ويتحقق من الرد.
     */
    fun sendUpstreamSocks5Connect(
        upIn: DataInputStream,
        upOut: OutputStream,
        atyp: Int,
        rawAddressBytes: ByteArray,
        targetPort: Int,
        targetHostReadable: String
    )
}
