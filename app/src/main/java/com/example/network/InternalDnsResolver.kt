package com.example.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * InternalDnsResolver (Built-in Anti-Block Fast DNS Engine for YemenNet & Weak Connections)
 * -----------------------------------------------------------------------------------------
 * 1. Instant 0.0ms IPv4 Byte Parser:
 *    Parses IPv4 literals (e.g. `10.147.17.1`, `192.168.88.1`, `11.11.1.48`) directly into raw
 *    `InetAddress.getByAddress(host, bytes)` objects WITHOUT calling Android's `netd` DNS resolver,
 *    preventing 3-5s stalls when ISP DNS is blocked or throttled on YemenNet / Mobile Data.
 * 2. In-Memory High-Speed DNS Cache:
 *    Caches resolved hosts in memory so subsequent socket connections take 0ms for DNS lookup.
 * 3. Anti-Block Multi-Channel Resolution (DoH + TCP DNS + UDP DNS):
 *    - Encrypted DNS-over-HTTPS (DoH) on TCP port 443 (`1.1.1.1`, `8.8.8.8`, `1.0.0.1`) to bypass
 *      ISP port-53 DNS blocking/hijacking (common on YemenNet and mobile carriers).
 *    - Raw DNS wire-format over TCP/UDP (`1.1.1.1:53`, `8.8.8.8:53`, `9.9.9.9:53`, `208.67.222.222:5353`).
 */
object InternalDnsResolver : DnsResolverContract {

    data class CachedDnsEntry(
        val address: InetAddress,
        val expiresAtMillis: Long,
        val sourceLabel: String
    )

    private val enabled = AtomicBoolean(true)
    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()
    private val resolvedCount = AtomicInteger(0)

    private const val CACHE_TTL_MS = 15 * 60 * 1000L // 15 minutes

    override fun isEnabled(): Boolean = enabled.get()

    override fun setEnabled(value: Boolean) {
        enabled.set(value)
    }

    override fun getCacheSize(): Int = dnsCache.size

    override fun getResolvedCount(): Int = resolvedCount.get()

    override fun clearCache() {
        dnsCache.clear()
    }

    /**
     * Checks if `host` is an IPv4 literal (e.g. `10.147.x.x` or `192.168.88.1`) and returns raw
     * 4-byte representation immediately without touching system DNS.
     */
    override fun parseIpv4LiteralBytes(host: String): ByteArray? {
        val clean = host.trim()
        val parts = clean.split(".")
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val num = parts[i].toIntOrNull() ?: return null
            if (num !in 0..255) return null
            bytes[i] = num.toByte()
        }
        return bytes
    }

    /**
     * Resolves a host or IPv4 literal to an `InetSocketAddress` with zero system DNS blocking for IPs,
     * and anti-block DoH/TCP/UDP resolution for domains when internal DNS is enabled.
     */
    override fun resolveSocketAddressFastSync(host: String, port: Int, timeoutMs: Int): InetSocketAddress {
        val cleanHost = host.trim()
        // 1. Fast path: IPv4 literal -> 0ms direct construction (never touches YemenNet/Android DNS!)
        val ipv4Bytes = parseIpv4LiteralBytes(cleanHost)
        if (ipv4Bytes != null) {
            return InetSocketAddress(InetAddress.getByAddress(cleanHost, ipv4Bytes), port)
        }

        if (!enabled.get()) {
            return InetSocketAddress(cleanHost, port)
        }

        // 2. Check in-memory cache
        val now = System.currentTimeMillis()
        val cached = dnsCache[cleanHost.lowercase()]
        if (cached != null && cached.expiresAtMillis > now) {
            return InetSocketAddress(cached.address, port)
        }

        // 3. Try fast raw UDP/TCP DNS + DoH anti-block resolution
        val resolved = resolveDomainAntiBlockSync(cleanHost, timeoutMs)
        if (resolved != null) {
            return InetSocketAddress(resolved, port)
        }

        // 4. Fallback to system resolver
        return try {
            val sysAddr = InetAddress.getByName(cleanHost)
            cacheAddress(cleanHost, sysAddr, "System-DNS")
            InetSocketAddress(sysAddr, port)
        } catch (_: Exception) {
            InetSocketAddress.createUnresolved(cleanHost, port)
        }
    }

    override suspend fun resolveHostFast(host: String, timeoutMs: Int): InetAddress? = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
        val ipv4Bytes = parseIpv4LiteralBytes(cleanHost)
        if (ipv4Bytes != null) {
            return@withContext InetAddress.getByAddress(cleanHost, ipv4Bytes)
        }

        val now = System.currentTimeMillis()
        val cached = dnsCache[cleanHost.lowercase()]
        if (cached != null && cached.expiresAtMillis > now) {
            return@withContext cached.address
        }

        if (!enabled.get()) {
            return@withContext try {
                InetAddress.getByName(cleanHost)
            } catch (_: Exception) {
                null
            }
        }

        val winner = withTimeoutOrNull(timeoutMs.toLong()) {
            coroutineScope {
                val dohCloudflare = async { queryDoHJson(cleanHost, "1.1.1.1", timeoutMs) }
                val dohGoogle = async { queryDoHJson(cleanHost, "8.8.8.8", timeoutMs) }
                val tcpCloudflare = async { queryRawTcpDns(cleanHost, "1.1.1.1", 53, timeoutMs) }
                val udpOpenDnsAltPort = async { queryRawUdpDns(cleanHost, "208.67.222.222", 5353, timeoutMs) }
                val udpQuad9 = async { queryRawUdpDns(cleanHost, "9.9.9.9", 53, timeoutMs) }

                dohCloudflare.await()
                    ?: tcpCloudflare.await()
                    ?: udpOpenDnsAltPort.await()
                    ?: dohGoogle.await()
                    ?: udpQuad9.await()
            }
        }

        if (winner != null) {
            return@withContext winner
        }

        return@withContext try {
            InetAddress.getByName(cleanHost)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveDomainAntiBlockSync(domain: String, timeoutMs: Int): InetAddress? {
        // 1. Try OpenDNS on non-standard port 5353 (bypasses standard port 53 UDP filters)
        queryRawUdpDns(domain, "208.67.222.222", 5353, (timeoutMs / 2).coerceAtLeast(900))?.let { return it }
        // 2. Try Cloudflare TCP DNS on port 53
        queryRawTcpDns(domain, "1.1.1.1", 53, (timeoutMs / 2).coerceAtLeast(1000))?.let { return it }
        // 3. Try Encrypted DNS-over-HTTPS (DoH) on port 443 (bypasses all DNS port blocking)
        queryDoHJson(domain, "1.1.1.1", timeoutMs)?.let { return it }
        queryDoHJson(domain, "8.8.8.8", timeoutMs)?.let { return it }
        return null
    }

    /**
     * Queries DNS-over-HTTPS (DoH) using direct IPv4 literal URL (`https://1.1.1.1/dns-query?name=...&type=A`)
     * so no bootstrap DNS lookup is needed, bypassing ISP port-53 DNS blocking completely.
     */
    private fun queryDoHJson(domain: String, serverIp: String, timeoutMs: Int): InetAddress? {
        var conn: HttpsURLConnection? = null
        return try {
            val urlStr = if (serverIp == "8.8.8.8" || serverIp == "8.8.4.4") {
                "https://$serverIp/resolve?name=$domain&type=A"
            } else {
                "https://$serverIp/dns-query?name=$domain&type=A"
            }
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/dns-json")
                setRequestProperty("User-Agent", "MC-ProxyTier-DNS/2.0")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return null
            for (i in 0 until answers.length()) {
                val item = answers.optJSONObject(i) ?: continue
                if (item.optInt("type") == 1) { // A record
                    val data = item.optString("data", "").trim()
                    val bytes = parseIpv4LiteralBytes(data)
                    if (bytes != null) {
                        val addr = InetAddress.getByAddress(domain, bytes)
                        cacheAddress(domain, addr, "DoH-$serverIp")
                        return addr
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Sends a standard RFC 1035 DNS A-record query over TCP (useful when UDP 53 is dropped/spoofed).
     */
    private fun queryRawTcpDns(domain: String, dnsServerIp: String, port: Int, timeoutMs: Int): InetAddress? {
        val serverBytes = parseIpv4LiteralBytes(dnsServerIp) ?: return null
        val serverAddr = InetSocketAddress(InetAddress.getByAddress(dnsServerIp, serverBytes), port)
        val queryPayload = buildDnsWireQuery(domain)
        var sock: Socket? = null
        return try {
            sock = Socket()
            sock.tcpNoDelay = true
            sock.soTimeout = timeoutMs
            sock.connect(serverAddr, timeoutMs)
            val out = DataOutputStream(sock.getOutputStream())
            val input = DataInputStream(sock.getInputStream())
            out.writeShort(queryPayload.size)
            out.write(queryPayload)
            out.flush()

            val respLen = input.readUnsignedShort()
            if (respLen <= 12 || respLen > 4096) return null
            val resp = ByteArray(respLen)
            input.readFully(resp)
            val ipBytes = parseDnsWireResponseIpv4(resp) ?: return null
            val addr = InetAddress.getByAddress(domain, ipBytes)
            cacheAddress(domain, addr, "TCP-DNS-$dnsServerIp")
            addr
        } catch (_: Exception) {
            null
        } finally {
            try {
                sock?.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Sends a standard RFC 1035 DNS A-record query over UDP (supports alternate ports like 5353).
     */
    private fun queryRawUdpDns(domain: String, dnsServerIp: String, port: Int, timeoutMs: Int): InetAddress? {
        val serverBytes = parseIpv4LiteralBytes(dnsServerIp) ?: return null
        val serverInet = InetAddress.getByAddress(dnsServerIp, serverBytes)
        val queryPayload = buildDnsWireQuery(domain)
        var udpSocket: DatagramSocket? = null
        return try {
            udpSocket = DatagramSocket()
            udpSocket.soTimeout = timeoutMs
            val reqPacket = DatagramPacket(queryPayload, queryPayload.size, serverInet, port)
            udpSocket.send(reqPacket)

            val buf = ByteArray(512)
            val respPacket = DatagramPacket(buf, buf.size)
            udpSocket.receive(respPacket)
            val ipBytes = parseDnsWireResponseIpv4(buf.copyOf(respPacket.length)) ?: return null
            val addr = InetAddress.getByAddress(domain, ipBytes)
            cacheAddress(domain, addr, "UDP-DNS-$dnsServerIp:$port")
            addr
        } catch (_: Exception) {
            null
        } finally {
            try {
                udpSocket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildDnsWireQuery(domain: String): ByteArray {
        val baos = ByteArrayOutputStream(64)
        val out = DataOutputStream(baos)
        out.writeShort(0x1337) // Transaction ID
        out.writeShort(0x0100) // Flags: Standard query, Recursion Desired
        out.writeShort(1)      // QDCOUNT = 1
        out.writeShort(0)      // ANCOUNT = 0
        out.writeShort(0)      // NSCOUNT = 0
        out.writeShort(0)      // ARCOUNT = 0

        for (label in domain.trim().trimEnd('.').split(".")) {
            val labelBytes = label.toByteArray(Charsets.UTF_8)
            out.writeByte(labelBytes.size)
            out.write(labelBytes)
        }
        out.writeByte(0)       // End of QNAME
        out.writeShort(1)      // QTYPE = A (IPv4)
        out.writeShort(1)      // QCLASS = IN
        out.flush()
        return baos.toByteArray()
    }

    private fun parseDnsWireResponseIpv4(resp: ByteArray): ByteArray? {
        if (resp.size < 12) return null
        val anCount = ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)
        if (anCount <= 0) return null

        var idx = 12
        // Skip QNAME
        while (idx < resp.size) {
            val len = resp[idx].toInt() and 0xFF
            if (len == 0) {
                idx += 1
                break
            }
            if ((len and 0xC0) == 0xC0) {
                idx += 2
                break
            }
            idx += 1 + len
        }
        idx += 4 // Skip QTYPE + QCLASS

        for (i in 0 until anCount) {
            if (idx >= resp.size) break
            // Skip NAME
            val nameByte = resp[idx].toInt() and 0xFF
            if ((nameByte and 0xC0) == 0xC0) {
                idx += 2
            } else {
                while (idx < resp.size && (resp[idx].toInt() and 0xFF) != 0) {
                    idx += 1 + (resp[idx].toInt() and 0xFF)
                }
                idx += 1
            }
            if (idx + 10 > resp.size) break
            val type = ((resp[idx].toInt() and 0xFF) shl 8) or (resp[idx + 1].toInt() and 0xFF)
            val rdLength = ((resp[idx + 8].toInt() and 0xFF) shl 8) or (resp[idx + 9].toInt() and 0xFF)
            idx += 10
            if (type == 1 && rdLength == 4 && idx + 4 <= resp.size) {
                val ipv4 = resp.copyOfRange(idx, idx + 4)
                if (ipv4.any { it.toInt() != 0 }) {
                    return ipv4
                }
            }
            idx += rdLength
        }
        return null
    }

    private fun cacheAddress(domain: String, addr: InetAddress, source: String) {
        val hostAddr = addr.hostAddress ?: return
        if (hostAddr == "0.0.0.0" || hostAddr == "127.0.0.1") return
        dnsCache[domain.lowercase()] = CachedDnsEntry(
            address = addr,
            expiresAtMillis = System.currentTimeMillis() + CACHE_TTL_MS,
            sourceLabel = source
        )
        resolvedCount.incrementAndGet()
        VpnSocketProtector.log(
            "INTERNAL-DNS",
            "تم فك النطاق $domain ➔ $hostAddr عبر [$source]",
            LogLevel.SUCCESS
        )
    }
}
