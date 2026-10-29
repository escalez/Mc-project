package com.example.network

import java.net.InetAddress
import java.net.InetSocketAddress

interface DnsResolverContract {
    fun isEnabled(): Boolean
    fun setEnabled(value: Boolean)
    fun getCacheSize(): Int
    fun getResolvedCount(): Int
    fun clearCache()
    fun parseIpv4LiteralBytes(host: String): ByteArray?
    fun resolveSocketAddressFastSync(host: String, port: Int, timeoutMs: Int = 2_500): InetSocketAddress
    suspend fun resolveHostFast(host: String, timeoutMs: Int = 3_000): InetAddress?
}
