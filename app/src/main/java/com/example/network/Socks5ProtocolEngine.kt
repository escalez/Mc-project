package com.example.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

class Socks5ProtocolEngine(
    private val socketChannel: SocketChannelContract,
    private val killSwitch: ProxyKillSwitchContract,
    private val logger: ProxyLogContract = VpnSocketProtector
) : Socks5HandshakeContract {

    override suspend fun verifyUpstreamConnectionWithRetry(
        config: Socks5Config,
        maxAttempts: Int,
        perAttemptTimeoutMs: Int,
        retryDelayMs: Long,
        isAllowedToContinue: () -> Boolean
    ): Result<Long> = withContext(Dispatchers.IO) {
        val totalAttempts = maxAttempts.coerceIn(1, 3)
        var lastError: Throwable = IOException("تعذر الوصول إلى البروكسي ${config.proxyIp}:${config.proxyPort}")

        for (attempt in 1..totalAttempts) {
            if (!isAllowedToContinue()) {
                return@withContext Result.failure(IOException("تم إيقاف البروكسي من قبل المستخدم"))
            }

            val channel = socketChannel.detectActiveSocketChannel()
            val effectiveTimeout = socketChannel.recommendedConnectTimeoutMs(perAttemptTimeoutMs)

            val res = testUpstreamHandshake(
                config = config,
                timeoutMs = effectiveTimeout,
                isAllowedToContinue = isAllowedToContinue
            )
            if (res.isSuccess) {
                return@withContext res
            }

            lastError = res.exceptionOrNull() ?: lastError

            if (attempt < totalAttempts && isAllowedToContinue()) {
                socketChannel.clearStaleProcessNetworkBinding()
                logger.log(
                    "SOCKET-RETRY",
                    "إعادة محاولة الاتصال ($attempt/$totalAttempts) بالبروكسي ${config.proxyIp}:${config.proxyPort} عبر [${channel.shortBadge}]...",
                    LogLevel.WARN
                )
                delay(retryDelayMs)
            }
        }

        Result.failure(lastError)
    }

    override suspend fun testUpstreamHandshake(
        config: Socks5Config,
        timeoutMs: Int,
        isAllowedToContinue: () -> Boolean
    ): Result<Long> = withContext(Dispatchers.IO) {
        val ip = config.proxyIp.trim()
        val port = config.proxyPort
        if (ip.isEmpty() || port !in 1..65535) {
            return@withContext Result.failure(IllegalArgumentException("يرجى إدخال عنوان الـ IP والبورت بشكل صحيح"))
        }

        val startNs = System.nanoTime()
        var sock: Socket? = null
        try {
            val effectiveTimeout = socketChannel.recommendedConnectTimeoutMs(timeoutMs)
            sock = socketChannel.openDedicatedChannelSocket(
                host = ip,
                port = port,
                timeoutMs = effectiveTimeout,
                isAllowedToConnect = isAllowedToContinue
            )
            sock.soTimeout = effectiveTimeout

            val input = DataInputStream(sock.getInputStream())
            val output = sock.getOutputStream()

            if (config.authEnabled && config.username.isNotBlank()) {
                output.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            } else {
                output.write(byteArrayOf(0x05, 0x01, 0x00))
            }
            output.flush()

            val ver = try {
                input.readUnsignedByte()
            } catch (_: Exception) {
                val rtt = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                return@withContext Result.success(rtt)
            }

            if (ver == 0x05) {
                val method = input.readUnsignedByte()
                if (method == 0x02) {
                    if (!config.authEnabled || config.username.isBlank()) {
                        return@withContext Result.failure(
                            IOException("البروكسي يطلب اسم مستخدم وكلمة مرور (RFC 1929) — فعّل خيار المصادقة")
                        )
                    }
                    val uBytes = config.username.toByteArray(StandardCharsets.UTF_8)
                    val pBytes = config.password.toByteArray(StandardCharsets.UTF_8)
                    val authReq = ByteArray(3 + uBytes.size + pBytes.size)
                    authReq[0] = 0x01
                    authReq[1] = uBytes.size.toByte()
                    System.arraycopy(uBytes, 0, authReq, 2, uBytes.size)
                    authReq[2 + uBytes.size] = pBytes.size.toByte()
                    System.arraycopy(pBytes, 0, authReq, 3 + uBytes.size, pBytes.size)

                    output.write(authReq)
                    output.flush()

                    val authVer = input.readUnsignedByte()
                    val authStatus = input.readUnsignedByte()
                    if (authVer != 0x01 || authStatus != 0x00) {
                        return@withContext Result.failure(
                            IOException("فشل مصادقة SOCKS5: اسم المستخدم أو كلمة المرور غير صحيحة")
                        )
                    }
                } else if (method == 0xFF) {
                    return@withContext Result.failure(IOException("سيرفر SOCKS5 رفض طرق المصادقة (0xFF)"))
                }
            }

            val rtt = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            Result.success(rtt)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            killSwitch.untrackAndCloseSocket(sock)
        }
    }

    override fun performUpstreamSocks5GreetingAndAuth(
        upIn: DataInputStream,
        upOut: OutputStream,
        cfg: Socks5Config
    ) {
        if (cfg.authEnabled && cfg.username.isNotBlank()) {
            upOut.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            upOut.write(byteArrayOf(0x05, 0x01, 0x00))
        }
        upOut.flush()

        val serverVer = upIn.readUnsignedByte()
        val chosenMethod = upIn.readUnsignedByte()

        if (serverVer != 0x05) {
            throw IOException("رد غير متوافق من سيرفر SOCKS5 (VER=$serverVer)")
        }

        when (chosenMethod) {
            0x00 -> Unit
            0x02 -> {
                if (!cfg.authEnabled || cfg.username.isBlank()) {
                    throw IOException("البروكسي يطلب اسم مستخدم وكلمة مرور (RFC 1929) — قم بتفعيل خيار المصادقة")
                }
                val userBytes = cfg.username.toByteArray(StandardCharsets.UTF_8)
                val passBytes = cfg.password.toByteArray(StandardCharsets.UTF_8)
                if (userBytes.size > 255 || passBytes.size > 255) {
                    throw IOException("طول اسم المستخدم أو كلمة المرور يتجاوز حد RFC 1929")
                }

                val authPacket = ByteArray(3 + userBytes.size + passBytes.size)
                authPacket[0] = 0x01
                authPacket[1] = userBytes.size.toByte()
                System.arraycopy(userBytes, 0, authPacket, 2, userBytes.size)
                authPacket[2 + userBytes.size] = passBytes.size.toByte()
                System.arraycopy(passBytes, 0, authPacket, 3 + userBytes.size, passBytes.size)

                upOut.write(authPacket)
                upOut.flush()

                val authVer = upIn.readUnsignedByte()
                val authStatus = upIn.readUnsignedByte()
                if (authVer != 0x01 || authStatus != 0x00) {
                    throw IOException("فشل مصادقة SOCKS5 (اسم المستخدم أو كلمة المرور غير صحيحة)")
                }
            }
            0xFF -> throw IOException("رفض سيرفر SOCKS5 طرق الاتصال (0xFF)")
            else -> throw IOException("طريقة مصادقة SOCKS5 غير مدعومة: $chosenMethod")
        }
    }

    override fun sendUpstreamSocks5Connect(
        upIn: DataInputStream,
        upOut: OutputStream,
        atyp: Int,
        rawAddressBytes: ByteArray,
        targetPort: Int,
        targetHostReadable: String
    ) {
        val req = ByteArray(4 + rawAddressBytes.size + 2)
        req[0] = 0x05
        req[1] = 0x01
        req[2] = 0x00
        req[3] = atyp.toByte()
        System.arraycopy(rawAddressBytes, 0, req, 4, rawAddressBytes.size)
        val portOffset = 4 + rawAddressBytes.size
        req[portOffset] = ((targetPort shr 8) and 0xFF).toByte()
        req[portOffset + 1] = (targetPort and 0xFF).toByte()

        upOut.write(req)
        upOut.flush()

        val respVer = upIn.readUnsignedByte()
        val rep = upIn.readUnsignedByte()
        upIn.readUnsignedByte()
        val bndAtyp = upIn.readUnsignedByte()

        when (bndAtyp) {
            0x01 -> upIn.readFully(ByteArray(4))
            0x03 -> {
                val len = upIn.readUnsignedByte()
                upIn.readFully(ByteArray(len))
            }
            0x04 -> upIn.readFully(ByteArray(16))
        }
        upIn.readFully(ByteArray(2))

        if (respVer != 0x05 || rep != 0x00) {
            throw IOException("فشل فتح الهدف $targetHostReadable:$targetPort عبر البروكسي (REP=0x${rep.toString(16)})")
        }
    }
}
