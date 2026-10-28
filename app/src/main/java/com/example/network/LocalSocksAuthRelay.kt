package com.example.network

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * LocalSocksAuthRelay (Modular Interface-Driven Architecture)
 * -----------------------------------------------------------
 * منسق الجسر المحلي للبروكسي (`127.0.0.1:localPort`) المبني على واجهات مستقلة لكل ميزة:
 * - [SocketChannelContract] ([DedicatedSocketChannelEngine]): إدارة سوكيت الواي فاي وسوكيت بيانات الهاتف + ZeroTier.
 * - [ProxyKillSwitchContract] ([ProxySocketKillSwitch]): نظام قطع الاتصال وإغلاق جميع السوكيتات بضربة واحدة نظيفة.
 * - [Socks5HandshakeContract] ([Socks5ProtocolEngine]): بروتوكول ومصافحة SOCKS5 و RFC 1929 وفحص الاتصال الفعلي.
 * - [DnsResolverContract] ([InternalDnsResolver]): محرك الـ DNS الداخلي وفك عناوين IPv4 في 0ms.
 * - [ProxyLogContract] ([VpnSocketProtector]): نظام السجل والإحصائيات الحية.
 */
class LocalSocksAuthRelay(
    context: Context,
    private val logger: ProxyLogContract = VpnSocketProtector,
    private val dnsResolver: DnsResolverContract = InternalDnsResolver,
    private val killSwitch: ProxyKillSwitchContract = ProxySocketKillSwitch(logger),
    private val socketChannel: SocketChannelContract = DedicatedSocketChannelEngine(
        context = context,
        killSwitch = killSwitch,
        dnsResolver = dnsResolver,
        logger = logger
    ),
    private val handshakeEngine: Socks5HandshakeContract = Socks5ProtocolEngine(
        socketChannel = socketChannel,
        killSwitch = killSwitch,
        logger = logger
    )
) : ProxyRelayEngineContract {

    private val lifecycleMutex = Mutex()
    private val isRunning = AtomicBoolean(false)
    private val relaySessionId = AtomicLong(0L)
    private val turboModeEnabled = AtomicBoolean(true)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    @Volatile
    private var watchdogJob: Job? = null

    @Volatile
    private var handoverJob: Job? = null

    @Volatile
    private var relayScope: CoroutineScope? = null

    @Volatile
    private var currentConfig: Socks5Config = Socks5Config()

    @Volatile
    override var boundPort: Int = 10808
        private set

    override fun isRelayActive(): Boolean {
        val srv = serverSocket
        return isRunning.get() && srv != null && !srv.isClosed
    }

    override fun currentSessionId(): Long = relaySessionId.get()

    override fun currentSocketChannel(): ActiveSocketChannel = socketChannel.currentSocketChannel()

    override fun detectActiveSocketChannel(): ActiveSocketChannel = socketChannel.detectActiveSocketChannel()

    override fun setTurboPrewarmEnabled(enabled: Boolean) {
        turboModeEnabled.set(enabled)
    }

    override fun triggerPrewarmPoolReplenish(count: Int) {
        // Deliberately avoids opening multiple background sockets simultaneously on Mobile Data + ZeroTier
        // so that ZeroTier's virtual interface is never flooded with parallel idle sockets.
    }

    override fun killAllSocketsNow(): Int {
        isRunning.set(false)
        relaySessionId.incrementAndGet()
        handoverJob?.cancel()
        handoverJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        acceptJob?.cancel()
        acceptJob = null
        return killSwitch.killAllSocketsNow(reasonTag = "KILL-SWITCH", logIfKilled = true)
    }

    override suspend fun startRelay(config: Socks5Config): Result<Int> =
        lifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                stopRelayInternalLocked(logKillSwitch = false)

                if (!config.isValid) {
                    return@withContext Result.failure(
                        IllegalArgumentException("يرجى إدخال عنوان الـ IP والبورت بشكل صحيح")
                    )
                }

                currentConfig = config
                val sessionId = relaySessionId.incrementAndGet()
                val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                relayScope = newScope

                try {
                    socketChannel.clearStaleProcessNetworkBinding()
                    dnsResolver.clearCache()
                    val activeChannel = socketChannel.detectActiveSocketChannel()

                    val preferredPort = config.localRelayPort.coerceIn(1025, 65534)
                    val server = ServerSocket()
                    server.reuseAddress = true
                    try {
                        server.bind(InetSocketAddress("127.0.0.1", preferredPort), 256)
                    } catch (_: Exception) {
                        server.bind(InetSocketAddress("127.0.0.1", 0), 256)
                    }

                    boundPort = server.localPort
                    serverSocket = server
                    killSwitch.attachServerSocket(server)
                    isRunning.set(true)

                    socketChannel.registerNetworkMonitor { _, newChannel ->
                        handleActualChannelSwitch(newChannel, sessionId)
                    }

                    logger.log(
                        "SOCKET-ENGINE",
                        "تم تفعيل [${activeChannel.labelAr}] على 127.0.0.1:$boundPort ➔ ${config.proxyIp}:${config.proxyPort}",
                        LogLevel.SUCCESS
                    )

                    acceptJob = newScope.launch {
                        while (isActive && isRunning.get() && relaySessionId.get() == sessionId) {
                            val clientSocket = try {
                                server.accept()
                            } catch (_: Exception) {
                                if (!isRunning.get() || relaySessionId.get() != sessionId) break
                                continue
                            }

                            if (!isRunning.get() || relaySessionId.get() != sessionId) {
                                killSwitch.untrackAndCloseSocket(clientSocket)
                                break
                            }

                            killSwitch.trackSocket(clientSocket)
                            launch {
                                handleClientConnection(clientSocket, sessionId)
                            }
                        }
                    }

                    startWatchdogMonitor(newScope, sessionId)
                    Result.success(boundPort)
                } catch (e: Exception) {
                    stopRelayInternalLocked(logKillSwitch = false)
                    logger.log("RELAY-ERR", "فشل تشغيل الجسر المحلي: ${e.localizedMessage}", LogLevel.ERROR)
                    Result.failure(e)
                }
            }
        }

    override suspend fun stopRelay() {
        lifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                stopRelayInternalLocked(logKillSwitch = true)
            }
        }
    }

    private fun stopRelayInternalLocked(logKillSwitch: Boolean) {
        val wasActive = isRunning.getAndSet(false)
        relaySessionId.incrementAndGet()

        socketChannel.unregisterNetworkMonitor()

        handoverJob?.cancel()
        handoverJob = null

        watchdogJob?.cancel()
        watchdogJob = null

        acceptJob?.cancel()
        acceptJob = null

        relayScope?.let { scope ->
            try {
                scope.coroutineContext[Job]?.cancel()
            } catch (_: Exception) {
            }
        }
        relayScope = null

        serverSocket = null
        killSwitch.killAllSocketsNow(
            reasonTag = "KILL-SWITCH",
            logIfKilled = logKillSwitch && wasActive
        )

        logger.updateTelemetry {
            it.copy(
                localRelayRunning = false,
                activeSocketChannel = ActiveSocketChannel.STANDBY,
                activeConnections = 0,
                watchdogHealthy = false
            )
        }
    }

    private fun handleActualChannelSwitch(newChannel: ActiveSocketChannel, sessionId: Long) {
        val scope = relayScope ?: return
        if (!isRunning.get() || relaySessionId.get() != sessionId) return

        handoverJob?.cancel()
        handoverJob = scope.launch {
            if (!isRunning.get() || relaySessionId.get() != sessionId) return@launch

            socketChannel.clearStaleProcessNetworkBinding()
            dnsResolver.clearCache()
            killSwitch.flushActiveSocketsPreserveServer("NET-HANDOVER")

            logger.updateTelemetry {
                it.copy(
                    activeSocketChannel = newChannel,
                    status = ConnectionStatus.RECONNECTING,
                    statusDetail = "تم التحويل إلى [${newChannel.labelAr}] — جاري تحديث مسار البروكسي..."
                )
            }

            delay(750L)
            if (!isRunning.get() || relaySessionId.get() != sessionId) return@launch

            val verifyResult = verifyUpstreamConnectionWithRetry(
                config = currentConfig,
                maxAttempts = 3,
                perAttemptTimeoutMs = socketChannel.recommendedConnectTimeoutMs(7_500),
                retryDelayMs = 600L
            )

            if (!isRunning.get() || relaySessionId.get() != sessionId) return@launch

            verifyResult.fold(
                onSuccess = { rtt ->
                    WebViewProxyConfigurator.applyLocalRelayProxy(boundPort, forceReapply = true)
                    logger.updateTelemetry {
                        it.copy(
                            status = ConnectionStatus.CONNECTED,
                            localRelayRunning = true,
                            activeSocketChannel = newChannel,
                            lastWatchdogPingMs = rtt,
                            watchdogHealthy = true,
                            statusDetail = "متصل فعلياً: ${currentConfig.proxyIp}:${currentConfig.proxyPort} (${rtt}ms) عبر [${newChannel.labelAr}]"
                        )
                    }
                    logger.log(
                        "NET-HANDOVER",
                        "نجح تحويل مسار البروكسي إلى [${newChannel.labelAr}] (${rtt}ms)",
                        LogLevel.SUCCESS
                    )
                },
                onFailure = { err ->
                    logger.updateTelemetry {
                        it.copy(
                            watchdogHealthy = false,
                            activeSocketChannel = newChannel,
                            statusDetail = "انتظار استقرار مسار ZeroTier عبر [${newChannel.shortBadge}]: ${err.localizedMessage ?: ""}"
                        )
                    }
                }
            )
        }
    }

    private fun startWatchdogMonitor(scope: CoroutineScope, sessionId: Long) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(15_000L)
            var consecutiveFailures = 0
            while (isActive && isRunning.get() && relaySessionId.get() == sessionId) {
                // Do not open watchdog sockets while active transfers are running
                val activeConns = logger.telemetry.value.activeConnections
                if (activeConns > 0) {
                    consecutiveFailures = 0
                    delay(20_000L)
                    continue
                }

                val activeChan = socketChannel.detectActiveSocketChannel()
                val pingRes = testUpstreamHandshake(
                    config = currentConfig,
                    timeoutMs = socketChannel.recommendedConnectTimeoutMs(6_000)
                )

                if (!isRunning.get() || relaySessionId.get() != sessionId) break

                pingRes.fold(
                    onSuccess = { rtt ->
                        consecutiveFailures = 0
                        logger.updateTelemetry {
                            it.copy(
                                status = ConnectionStatus.CONNECTED,
                                localRelayRunning = true,
                                activeSocketChannel = activeChan,
                                lastWatchdogPingMs = rtt,
                                watchdogHealthy = true,
                                statusDetail = "متصل فعلياً: ${currentConfig.proxyIp}:${currentConfig.proxyPort} (${rtt}ms) عبر [${activeChan.shortBadge}]"
                            )
                        }
                    },
                    onFailure = {
                        consecutiveFailures++
                        if (consecutiveFailures >= 3 && isRunning.get() && relaySessionId.get() == sessionId) {
                            socketChannel.clearStaleProcessNetworkBinding()
                            logger.updateTelemetry {
                                it.copy(
                                    watchdogHealthy = false,
                                    activeSocketChannel = activeChan,
                                    statusDetail = "تنبيه: استجابة البروكسي بطيئة عبر [${activeChan.shortBadge}]"
                                )
                            }
                        }
                    }
                )
                delay(22_000L)
            }
        }
    }

    override suspend fun verifyUpstreamConnectionWithRetry(
        config: Socks5Config,
        maxAttempts: Int,
        perAttemptTimeoutMs: Int,
        retryDelayMs: Long
    ): Result<Long> {
        val cfg = if (config.isValid) config else currentConfig
        val startSession = relaySessionId.get()
        val wasRunningAtStart = isRunning.get()
        return handshakeEngine.verifyUpstreamConnectionWithRetry(
            config = cfg,
            maxAttempts = maxAttempts,
            perAttemptTimeoutMs = perAttemptTimeoutMs,
            retryDelayMs = retryDelayMs,
            isAllowedToContinue = {
                relaySessionId.get() == startSession && (!wasRunningAtStart || isRunning.get())
            }
        )
    }

    override suspend fun testUpstreamHandshake(
        config: Socks5Config,
        timeoutMs: Int
    ): Result<Long> {
        val cfg = if (config.isValid) config else currentConfig
        val startSession = relaySessionId.get()
        val wasRunningAtStart = isRunning.get()
        return handshakeEngine.testUpstreamHandshake(
            config = cfg,
            timeoutMs = timeoutMs,
            isAllowedToContinue = {
                relaySessionId.get() == startSession && (!wasRunningAtStart || isRunning.get())
            }
        )
    }

    private suspend fun handleClientConnection(clientSocket: Socket, sessionId: Long) = withContext(Dispatchers.IO) {
        var upstreamSocket: Socket? = null
        logger.adjustActiveConnections(1)
        try {
            if (!isRunning.get() || relaySessionId.get() != sessionId) return@withContext

            clientSocket.tcpNoDelay = true
            clientSocket.keepAlive = true
            clientSocket.soTimeout = 30_000

            val bufferedClientIn = BufferedInputStream(clientSocket.getInputStream(), 64 * 1024)
            val clientOut = clientSocket.getOutputStream()

            bufferedClientIn.mark(4)
            val firstByte = bufferedClientIn.read()
            if (firstByte == -1) return@withContext
            bufferedClientIn.reset()

            upstreamSocket = when (firstByte) {
                0x05 -> handleLocalSocks5Client(DataInputStream(bufferedClientIn), clientOut, sessionId)
                0x04 -> handleLocalSocks4Client(DataInputStream(bufferedClientIn), clientOut, sessionId)
                else -> handleLocalHttpClient(bufferedClientIn, clientOut, sessionId)
            }

            if (!isRunning.get() || relaySessionId.get() != sessionId) {
                killSwitch.untrackAndCloseSocket(upstreamSocket)
                return@withContext
            }

            logger.incrementHandshakes()
            pipeBidirectional(
                clientSocket = clientSocket,
                upstreamSocket = upstreamSocket,
                clientIn = bufferedClientIn,
                clientOut = clientOut,
                sessionId = sessionId
            )
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: e.javaClass.simpleName
            if (isRunning.get() &&
                relaySessionId.get() == sessionId &&
                !msg.contains("Socket closed", ignoreCase = true) &&
                !msg.contains("Broken pipe", ignoreCase = true) &&
                !msg.contains("Connection reset", ignoreCase = true)
            ) {
                logger.log("PROXY-PIPE", "تنبيه توجيه: $msg", LogLevel.WARN)
            }
        } finally {
            logger.adjustActiveConnections(-1)
            killSwitch.untrackAndCloseSocket(upstreamSocket)
            killSwitch.untrackAndCloseSocket(clientSocket)
        }
    }

    private fun handleLocalSocks5Client(
        clientIn: DataInputStream,
        clientOut: OutputStream,
        sessionId: Long
    ): Socket {
        val ver = clientIn.readUnsignedByte()
        if (ver != 0x05) throw IOException("إصدار SOCKS محلي غير مدعوم: $ver")

        val nMethods = clientIn.readUnsignedByte()
        val methods = ByteArray(nMethods)
        clientIn.readFully(methods)

        clientOut.write(byteArrayOf(0x05, 0x00))
        clientOut.flush()

        val cmdVer = clientIn.readUnsignedByte()
        if (cmdVer != 0x05) throw IOException("إصدار طلب SOCKS5 غير صالح: $cmdVer")
        val cmd = clientIn.readUnsignedByte()
        clientIn.readUnsignedByte() // RSV
        val atyp = clientIn.readUnsignedByte()

        val targetHost: String
        val rawAddressBytes: ByteArray

        when (atyp) {
            0x01 -> {
                rawAddressBytes = ByteArray(4)
                clientIn.readFully(rawAddressBytes)
                targetHost = InetAddress.getByAddress(rawAddressBytes).hostAddress ?: "0.0.0.0"
            }
            0x03 -> {
                val len = clientIn.readUnsignedByte()
                val domainBytes = ByteArray(len)
                clientIn.readFully(domainBytes)
                targetHost = String(domainBytes, StandardCharsets.UTF_8)
                val ipv4Literal = dnsResolver.parseIpv4LiteralBytes(targetHost)
                rawAddressBytes = if (ipv4Literal != null) {
                    ipv4Literal
                } else {
                    byteArrayOf(len.toByte()) + domainBytes
                }
            }
            0x04 -> {
                rawAddressBytes = ByteArray(16)
                clientIn.readFully(rawAddressBytes)
                targetHost = InetAddress.getByAddress(rawAddressBytes).hostAddress ?: "::1"
            }
            else -> throw IOException("نوع عنوان SOCKS5 غير معروف: $atyp")
        }

        val effectiveAtyp = if (atyp == 0x03 && rawAddressBytes.size == 4) 0x01 else atyp
        val targetPort = clientIn.readUnsignedShort()

        if (cmd != 0x01) {
            clientOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            clientOut.flush()
            throw IOException("الأمر غير مدعوم (فقط CONNECT مدعوم): $cmd")
        }

        val upstream = try {
            openAndNegotiateUserProxy(
                targetHost = targetHost,
                targetPort = targetPort,
                atyp = effectiveAtyp,
                rawAddressBytes = rawAddressBytes,
                sessionId = sessionId
            )
        } catch (e: Exception) {
            try {
                clientOut.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
            } catch (_: Exception) {
            }
            throw e
        }

        clientOut.write(
            byteArrayOf(
                0x05, 0x00, 0x00, 0x01,
                127, 0, 0, 1,
                ((boundPort shr 8) and 0xFF).toByte(),
                (boundPort and 0xFF).toByte()
            )
        )
        clientOut.flush()
        return upstream
    }

    private fun handleLocalSocks4Client(
        clientIn: DataInputStream,
        clientOut: OutputStream,
        sessionId: Long
    ): Socket {
        val ver = clientIn.readUnsignedByte()
        if (ver != 0x04) throw IOException("إصدار SOCKS4 غير صالح")
        val cmd = clientIn.readUnsignedByte()
        val dstPort = clientIn.readUnsignedShort()
        val ipBytes = ByteArray(4)
        clientIn.readFully(ipBytes)
        while (clientIn.read() > 0) {
            // Drain user ID
        }

        val targetHost = if (ipBytes[0].toInt() == 0 && ipBytes[1].toInt() == 0 &&
            ipBytes[2].toInt() == 0 && ipBytes[3].toInt() != 0
        ) {
            val domainBuf = mutableListOf<Byte>()
            while (true) {
                val b = clientIn.read()
                if (b <= 0) break
                domainBuf.add(b.toByte())
            }
            String(domainBuf.toByteArray(), StandardCharsets.UTF_8)
        } else {
            InetAddress.getByAddress(ipBytes).hostAddress ?: "0.0.0.0"
        }

        if (cmd != 0x01) throw IOException("أمر SOCKS4 غير مدعوم")

        val ipv4Bytes = dnsResolver.parseIpv4LiteralBytes(targetHost)
        val effectiveAtyp = if (ipv4Bytes != null) 0x01 else 0x03
        val rawAddr = if (ipv4Bytes != null) {
            ipv4Bytes
        } else {
            val domainBytes = targetHost.toByteArray(StandardCharsets.UTF_8)
            byteArrayOf(domainBytes.size.toByte()) + domainBytes
        }

        val upstream = openAndNegotiateUserProxy(
            targetHost = targetHost,
            targetPort = dstPort,
            atyp = effectiveAtyp,
            rawAddressBytes = rawAddr,
            sessionId = sessionId
        )

        clientOut.write(byteArrayOf(0x00, 0x5A, 0x00, 0x00, 0, 0, 0, 0))
        clientOut.flush()
        return upstream
    }

    private fun handleLocalHttpClient(
        clientIn: BufferedInputStream,
        clientOut: OutputStream,
        sessionId: Long
    ): Socket {
        val headerLines = mutableListOf<String>()
        val firstLine = readAsciiLine(clientIn)
        if (firstLine.isBlank()) throw IOException("طلب HTTP فارغ")
        headerLines.add(firstLine)

        while (true) {
            val line = readAsciiLine(clientIn)
            if (line.isEmpty()) break
            headerLines.add(line)
        }

        val parts = firstLine.split(" ")
        if (parts.size < 2) throw IOException("سطر HTTP غير صالح: $firstLine")
        val method = parts[0].uppercase()
        val uri = parts[1]
        val httpVersion = if (parts.size >= 3) parts[2] else "HTTP/1.1"

        var hostHeader = ""
        for (h in headerLines.drop(1)) {
            if (h.startsWith("Host:", ignoreCase = true)) {
                hostHeader = h.substringAfter(":").trim()
                break
            }
        }

        val targetHost: String
        val targetPort: Int
        var relativePath = "/"

        if (method == "CONNECT") {
            val authority = uri.trim()
            targetHost = authority.substringBefore(":")
            targetPort = authority.substringAfter(":", "443").toIntOrNull() ?: 443
        } else {
            val withoutScheme = when {
                uri.startsWith("http://", ignoreCase = true) -> uri.substring(7)
                uri.startsWith("https://", ignoreCase = true) -> uri.substring(8)
                else -> ""
            }
            if (withoutScheme.isNotEmpty()) {
                val authority = withoutScheme.substringBefore("/")
                val pathPart = withoutScheme.substringAfter("/", "")
                relativePath = "/$pathPart"
                targetHost = authority.substringBefore(":")
                targetPort = authority.substringAfter(":", "80").toIntOrNull() ?: 80
            } else {
                targetHost = hostHeader.substringBefore(":")
                targetPort = hostHeader.substringAfter(":", "80").toIntOrNull() ?: 80
                relativePath = if (uri.startsWith("/")) uri else "/$uri"
            }
        }

        if (targetHost.isBlank()) {
            throw IOException("تعذر تحديد عنوان الهدف من طلب HTTP: $firstLine")
        }

        val ipv4Bytes = dnsResolver.parseIpv4LiteralBytes(targetHost)
        val atyp: Int
        val rawAddressBytes: ByteArray
        if (ipv4Bytes != null) {
            atyp = 0x01
            rawAddressBytes = ipv4Bytes
        } else {
            atyp = 0x03
            val domainBytes = targetHost.toByteArray(StandardCharsets.UTF_8)
            rawAddressBytes = byteArrayOf(domainBytes.size.toByte()) + domainBytes
        }

        if (method == "CONNECT") {
            val upstreamSocket = openAndNegotiateUserProxy(
                targetHost = targetHost,
                targetPort = targetPort,
                atyp = atyp,
                rawAddressBytes = rawAddressBytes,
                sessionId = sessionId
            )
            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
            clientOut.flush()
            return upstreamSocket
        } else {
            val upstreamSocket = openAndNegotiateUserProxy(
                targetHost = targetHost,
                targetPort = targetPort,
                atyp = atyp,
                rawAddressBytes = rawAddressBytes,
                sessionId = sessionId
            )
            val outStream = upstreamSocket.getOutputStream()
            val headerBuilder = StringBuilder()
            headerBuilder.append("$method $relativePath $httpVersion\r\n")

            var hasHost = false
            for (h in headerLines.drop(1)) {
                if (h.startsWith("Host:", ignoreCase = true)) {
                    hasHost = true
                }
                if (!h.startsWith("Proxy-Connection:", ignoreCase = true) &&
                    !h.startsWith("Proxy-Authorization:", ignoreCase = true) &&
                    !h.startsWith("Connection:", ignoreCase = true)
                ) {
                    headerBuilder.append(h).append("\r\n")
                }
            }
            if (!hasHost) {
                val hostVal = if (targetPort == 80) targetHost else "$targetHost:$targetPort"
                headerBuilder.append("Host: $hostVal\r\n")
            }
            headerBuilder.append("Connection: keep-alive\r\n\r\n")
            outStream.write(headerBuilder.toString().toByteArray(StandardCharsets.UTF_8))
            outStream.flush()
            return upstreamSocket
        }
    }

    private fun openAndNegotiateUserProxy(
        targetHost: String,
        targetPort: Int,
        atyp: Int,
        rawAddressBytes: ByteArray,
        sessionId: Long
    ): Socket {
        val cfg = currentConfig
        val proxyIp = cfg.proxyIp.trim()
        val proxyPort = cfg.proxyPort
        val connectTimeout = socketChannel.recommendedConnectTimeoutMs(6_000)

        var effectiveAtyp = atyp
        var effectiveRawBytes = rawAddressBytes

        if (effectiveAtyp == 0x03) {
            val ipv4Literal = dnsResolver.parseIpv4LiteralBytes(targetHost)
            if (ipv4Literal != null) {
                effectiveAtyp = 0x01
                effectiveRawBytes = ipv4Literal
            } else if (dnsResolver.isEnabled() && !cfg.remoteDnsResolution) {
                val resolvedAddr = dnsResolver.resolveSocketAddressFastSync(targetHost, targetPort, 2_000).address
                val addrBytes = resolvedAddr?.address
                if (addrBytes != null && addrBytes.size == 4) {
                    effectiveAtyp = 0x01
                    effectiveRawBytes = addrBytes
                }
            }
        }

        var sock: Socket? = null
        try {
            sock = socketChannel.openDedicatedChannelSocket(
                host = proxyIp,
                port = proxyPort,
                timeoutMs = connectTimeout,
                isAllowedToConnect = { isRunning.get() && relaySessionId.get() == sessionId }
            )

            val upIn = DataInputStream(sock.getInputStream())
            val upOut = sock.getOutputStream()

            handshakeEngine.performUpstreamSocks5GreetingAndAuth(upIn, upOut, cfg)
            handshakeEngine.sendUpstreamSocks5Connect(
                upIn = upIn,
                upOut = upOut,
                atyp = effectiveAtyp,
                rawAddressBytes = effectiveRawBytes,
                targetPort = targetPort,
                targetHostReadable = targetHost
            )
            return sock
        } catch (socksErr: Exception) {
            killSwitch.untrackAndCloseSocket(sock)

            // Fallback: HTTP CONNECT proxy if the user entered an HTTP proxy port
            var httpSock: Socket? = null
            try {
                httpSock = socketChannel.openDedicatedChannelSocket(
                    host = proxyIp,
                    port = proxyPort,
                    timeoutMs = connectTimeout,
                    isAllowedToConnect = { isRunning.get() && relaySessionId.get() == sessionId }
                )
                val upIn = httpSock.getInputStream()
                val upOut = httpSock.getOutputStream()

                val connectBuilder = StringBuilder()
                connectBuilder.append("CONNECT $targetHost:$targetPort HTTP/1.1\r\n")
                connectBuilder.append("Host: $targetHost:$targetPort\r\n")
                if (cfg.authEnabled && cfg.username.isNotBlank()) {
                    val creds = "${cfg.username}:${cfg.password}"
                    val b64 = Base64.getEncoder().encodeToString(creds.toByteArray(StandardCharsets.UTF_8))
                    connectBuilder.append("Proxy-Authorization: Basic $b64\r\n")
                }
                connectBuilder.append("Proxy-Connection: keep-alive\r\n\r\n")

                upOut.write(connectBuilder.toString().toByteArray(StandardCharsets.UTF_8))
                upOut.flush()

                val statusLine = readAsciiLine(upIn)
                if (!statusLine.contains(" 200 ")) {
                    throw IOException("رد البروكسي: $statusLine")
                }
                while (readAsciiLine(upIn).isNotEmpty()) {
                    // Drain headers
                }
                return httpSock
            } catch (_: Exception) {
                killSwitch.untrackAndCloseSocket(httpSock)
                throw socksErr
            }
        }
    }

    private suspend fun pipeBidirectional(
        clientSocket: Socket,
        upstreamSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        sessionId: Long
    ) = withContext(Dispatchers.IO) {
        val upIn = upstreamSocket.getInputStream()
        val upOut = upstreamSocket.getOutputStream()

        val clientToUpstream = launch {
            val buf = ByteArray(64 * 1024)
            try {
                while (isActive && isRunning.get() && relaySessionId.get() == sessionId) {
                    val n = clientIn.read(buf)
                    if (n <= 0) break
                    upOut.write(buf, 0, n)
                    upOut.flush()
                    logger.recordBytes(sentDelta = n.toLong())
                }
            } catch (_: Exception) {
            } finally {
                try {
                    upstreamSocket.shutdownOutput()
                } catch (_: Exception) {
                }
            }
        }

        val upstreamToClient = launch {
            val buf = ByteArray(64 * 1024)
            try {
                while (isActive && isRunning.get() && relaySessionId.get() == sessionId) {
                    val n = upIn.read(buf)
                    if (n <= 0) break
                    clientOut.write(buf, 0, n)
                    clientOut.flush()
                    logger.recordBytes(receivedDelta = n.toLong())
                }
            } catch (_: Exception) {
            } finally {
                try {
                    clientSocket.shutdownOutput()
                } catch (_: Exception) {
                }
            }
        }

        upstreamToClient.join()
        try {
            clientSocket.close()
        } catch (_: Exception) {
        }
        clientToUpstream.cancel()
    }

    private fun readAsciiLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                sb.append(b.toChar())
            }
        }
        return sb.toString()
    }
}
