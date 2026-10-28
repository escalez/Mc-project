package com.example.network

import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * واجهة مستقلة خاصة بمحرك الـ DNS الداخلي وفك عناوين الـ IPv4 الفوري (DNS Resolver Contract).
 * تفصل منطق الـ DNS وفك الـ IP عن ملفات السوكيت والبروكسي.
 */
interface DnsResolverContract {
    fun isEnabled(): Boolean
    fun setEnabled(value: Boolean)
    fun getCacheSize(): Int
    fun getResolvedCount(): Int
    fun clearCache()

    /**
     * يحول عنوان IPv4 النصي (مثل 172.22.22.22 أو 192.168.88.1) في 0ms إلى 4 بايتات مباشرة
     * دون استدعاء نظام DNS الأندرويد نهائياً لمنع أي تعليق على بيانات الهاتف.
     */
    fun parseIpv4LiteralBytes(host: String): ByteArray?

    /**
     * يحل اسم النطاق أو الـ IP إلى InetSocketAddress بسرعة فائقة.
     */
    fun resolveSocketAddressFastSync(host: String, port: Int, timeoutMs: Int = 2_500): InetSocketAddress

    /**
     * يحل النطاق بشكل غير متزامن عبر DoH و TCP/UDP DNS لتجاوز الحظر.
     */
    suspend fun resolveHostFast(host: String, timeoutMs: Int = 3_000): InetAddress?
}
