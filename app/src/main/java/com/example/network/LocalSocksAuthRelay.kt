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
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LocalSocksAuthRelay(
    context: Context,
    private val logger: ProxyLogContract = VpnSocketProtector,
    private val dnsResolver: DnsResolverContract = InternalDnsResolver,
    private val killSwitch: ProxyKillSwitchContract = ProxySocketKillSwitch(logger),
    private val localIpPool: LocalIpPoolContract = DynamicLocalIpPoolManager(killSwitch, logger),
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

    companion object {
        const val WIFI_LOCAL_IP = "127.0.0.1"
        const val CELLULAR_LOCAL_IP = "127.0.0.2"
        const val DEFAULT_WIFI_PORT = 10808
        const val DEFAULT_CELLULAR_PORT = 10809
        const val MAX_POOL_USERS = LocalIpPoolContract.MAX_LOCAL_IP_SLOTS
    }

    private val lifecycleMutex = Mutex()
    private val leaseSwitchLock = Any()
    private val isRunning = AtomicBoolean(false)
    private val relaySessionId = AtomicLong(0L)
    private val turboModeEnabled = AtomicBoolean(true)

    @Volatile
    private var activeLease: LocalIpLease? = null

    @Volatile
    private var activeAcceptJob: Job? = null

    @Volatile
    private var watchdogJob: Job? = null

    @Volatile
    private var handoverJob: Job? = null

    @Volatile
    private var autoReconnectJob: Job? = null

    @Volatile
    private var relayScope: CoroutineScope? = null

    @Volatile
    private var currentConfig: Socks5Config = Socks5Config()

    override val activeLocalBindHost: String
        get() = activeLease?.localIp ?: WIFI_LOCAL_IP

    override val boundPort: Int
        get() = activeLease?.localPort ?: DEFAULT_WIFI_PORT

    override val activeLocalSlotIndex: Int
        get() = activeLease?.slotIndex ?: 1

    override val wifiLocalBindAddress: String
        get() = activeLease?.bindAddress ?: "$WIFI_LOCAL_IP:$DEFAULT_WIFI_PORT"

    override val cellularLocalBindAddress: String
        get() = activeLease?.bindAddress ?: "$CELLULAR_LOCAL_IP:$DEFAULT_CELLULAR_PORT"

    override fun isRelayActive(): Boolean {
        val srv = activeLease?.serverSocket
        return isRunning.get() && srv != null && !srv.isClosed
    }

    override fun currentSessionId(): Long = relaySessionId.get()

    override fun currentSocketChannel(): ActiveSocketChannel = socketChannel.currentSocketChannel()

    override fun detectActiveSocketChannel(): ActiveSocketChannel = socketChannel.detectActiveSocketChannel()

    override fun setTurboPrewarmEnabled(enabled: Boolean) {
        turboModeEnabled.set(enabled)
    }

    override fun triggerPrewarmPoolReplenish(count: Int) = Unit

    fun getLocalIpPoolManager(): LocalIpPoolContract = localIpPool

    override fun killAllSocketsNow(): Int {
        isRunning.set(false)
        relaySessionId.incrementAndGet()
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        handoverJob?.cancel()
        handoverJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        activeAcceptJob?.cancel()
        activeAcceptJob = null
        activeLease = null
        localIpPool.releaseAllSlots()
        return killSwitch.killAllSocketsNow(reasonTag = "KILL-SWITCH", logIfKilled = true)
    }

    override fun rotateToNextAvailableLocalIp(reasonTag: String): LocalIpLease? {
        val scope = relayScope ?: return null
        val sessionId = relaySessionId.get()
        if (!isRunning.get()) return null

        synchronized(leaseSwitchLock) {
            if (!isRunning.get() || relaySessionId.get() != sessionId) return null

            val previousLease = activeLease
            val previousAcceptJob = activeAcceptJob
            val basePort = currentConfig.localRelayPort.coerceIn(1025, 65000)

            val newLease = localIpPool.acquireNextAvailableLocalIp(
                basePort = basePort,
                excludeSlotIndex = previousLease?.slotIndex ?: -1
            )

            activeLease = newLease
            activeAcceptJob = launchServerAcceptLoop(
                scope = scope,
                lease = newLease,
                sessionId = sessionId
            )

            scope.launch {
                WebViewProxyConfigurator.applyLocalRelayProxy(
                    localPort = newLease.localPort,
                    forceReapply = true,
                    localHost = newLease.localIp
                )
            }

            previousAcceptJob?.cancel()
            if (previousLease != null && previousLease.slotIndex != newLease.slotIndex) {
                localIpPool.releaseAndDisconnectSlot(previousLease.slotIndex)
            }

            logger.updateTelemetry {
                it.copy(
                    localRelayRunning = true,
                    localRelayBindAddress = newLease.bindAddress,
                    wifiLocalRelayAddress = newLease.bindAddress,
                    cellularLocalRelayAddress = newLease.bindAddress
                )
            }

            return newLease
        }
    }

    fun reclaimPrimaryLocalIpIfDisconnected(): Boolean {
        val current = activeLease ?: return false
        if (current.slotIndex == 1) return false
        if (!isRunning.get()) return false

        if (localIpPool.isSlotCompletelyFree(1) &&
            localIpPool.getActiveSocketCountForSlot(current.slotIndex) == 0
        ) {
            val rotated = rotateToNextAvailableLocalIp(reasonTag = "RECLAIM-PRIMARY-IP")
            return rotated?.slotIndex == 1
        }
        return false
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
                    val preferredBasePort = config.localRelayPort.coerceIn(1025, 65000)

                    val initialLease = localIpPool.acquireNextAvailableLocalIp(
                        basePort = preferredBasePort,
                        excludeSlotIndex = -1
                    )
                    activeLease = initialLease

                    isRunning.set(true)

                    logger.updateTelemetry {
                        it.copy(
                            localRelayRunning = true,
                            activeSocketChannel = activeChannel,
                            localRelayBindAddress = initialLease.bindAddress,
                            wifiLocalRelayAddress = initialLease.bindAddress,
                            cellularLocalRelayAddress = initialLease.bindAddress
                        )
                    }

                    socketChannel.registerNetworkMonitor { _, newChannel ->
                        handleActualChannelSwitch(newChannel, sessionId)
                    }

                    logger.log(
                        "LOCAL-IP-POOL",
                        "تم تفعيل مجمع الآيبيات المحلية الديناميكي (سعة $MAX_POOL_USERS مستخدم/آيبي) — القناة النشطة: [${activeChannel.shortBadge}]",
                        LogLevel.SUCCESS
                    )

                    activeAcceptJob = launchServerAcceptLoop(
                        scope = newScope,
                        lease = initialLease,
                        sessionId = sessionId
                    )

                    startWatchdogMonitor(newScope, sessionId)
                    Result.success(initialLease.localPort)
                } catch (e: Exception) {
                    stopRelayInternalLocked(logKillSwitch = false)
                    logger.log("RELAY-ERR", "فشل تشغيل مجمع الجسر المحلي: ${e.localizedMessage}", LogLevel.ERROR)
                    Result.failure(e)
                }
            }
        }

    private fun launchServerAcceptLoop(
        scope: CoroutineScope,
        lease: LocalIpLease,
        sessionId: Long
    ): Job = scope.launch {
        val server: ServerSocket = lease.serverSocket
        while (isActive && isRunning.get() && relaySessionId.get() == sessionId && !server.isClosed) {
            val clientSocket = try {
                server.accept()
            } catch (_: Exception) {
                if (!isRunning.get() || relaySessionId.get() != sessionId || server.isClosed) break
                continue
            }

            if (!isRunning.get() || relaySessionId.get() != sessionId) {
                localIpPool.unregisterSocketOnSlot(lease.slotIndex, clientSocket)
                break
            }

            localIpPool.registerSocketOnSlot(lease.slotIndex, clientSocket)
            launch {
                handleClientConnection(
                    clientSocket = clientSocket,
                    lease = lease,
                    sessionId = sessionId
                )
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

        autoReconnectJob?.cancel()
        autoReconnectJob = null

        handoverJob?.cancel()
        handoverJob = null

        watchdogJob?.cancel()
        watchdogJob = null

        activeAcceptJob?.cancel()
        activeAcceptJob = null

        relayScope?.let { scope ->
            try { scope.coroutineContext[Job]?.cancel() } catch (_: Exception) {}
        }
        relayScope = null

        activeLease = null
        localIpPool.releaseAllSlots()
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

    override fun startBackgroundAutoReconnect(
        config: Socks5Config,
        reasonTag: String
    ) {
        val scope = relayScope ?: return
        val sessionId = relaySessionId.get()
        if (!isRunning.get()) return

        val cfg = if (config.isValid) config else currentConfig
        if (!cfg.isValid) return

        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch {
            var attempt = 1
            while (isActive && isRunning.get() && relaySessionId.get() == sessionId) {
                if (attempt > 1 && (attempt % 2 == 0 || (activeLocalSlotIndex > 1 && localIpPool.isSlotCompletelyFree(1)))) {
                    rotateToNextAvailableLocalIp(reasonTag = "AUTO-IP-POOL")
                }

                val activeChan = socketChannel.detectActiveSocketChannel()
                val activeBindAddr = "$activeLocalBindHost:$boundPort"

                WebViewProxyConfigurator.applyLocalRelayProxy(
                    localPort = boundPort,
                    forceReapply = (attempt == 1),
                    localHost = activeLocalBindHost
                )

                logger.updateTelemetry {
                    it.copy(
                        status = ConnectionStatus.RECONNECTING,
                        localRelayRunning = true,
                        activeSocketChannel = activeChan,
                        localRelayBindAddress = activeBindAddr,
                        wifiLocalRelayAddress = activeBindAddr,
                        cellularLocalRelayAddress = activeBindAddr,
                        watchdogHealthy = false,
                        statusDetail = "إعادة ربط تلقائية ذكية (#$attempt) عبر [${activeChan.shortBadge}] — بانتظار استجابة مسار ZeroTier..."
                    )
                }

                val res = testUpstreamHandshake(
                    config = cfg,
                    timeoutMs = 4_200
                )

                if (!isRunning.get() || relaySessionId.get() != sessionId) break

                if (res.isSuccess) {
                    val rtt = res.getOrDefault(1L)
                    markRelayConnectedNow(rtt = rtt, channel = activeChan, sourceTag = reasonTag)
                    break
                }

                attempt++
                delay(2_000L)
            }
        }
    }

    private suspend fun markRelayConnectedNow(
        rtt: Long,
        channel: ActiveSocketChannel,
        sourceTag: String
    ) {
        autoReconnectJob?.cancel()
        autoReconnectJob = null

        reclaimPrimaryLocalIpIfDisconnected()

        val activeBindAddr = "$activeLocalBindHost:$boundPort"
        WebViewProxyConfigurator.applyLocalRelayProxy(
            localPort = boundPort,
            forceReapply = true,
            localHost = activeLocalBindHost
        )

        logger.updateTelemetry {
            it.copy(
                status = ConnectionStatus.CONNECTED,
                localRelayRunning = true,
                activeSocketChannel = channel,
                localRelayBindAddress = activeBindAddr,
                wifiLocalRelayAddress = activeBindAddr,
                cellularLocalRelayAddress = activeBindAddr,
                lastWatchdogPingMs = rtt,
                watchdogHealthy = true,
                statusDetail = "متصل فعلياً: ${currentConfig.proxyIp}:${currentConfig.proxyPort} (${rtt}ms) عبر [${channel.shortBadge}]"
            )
        }

        logger.log(
            sourceTag,
            "تم الاتصال الفعلي بالبروكسي ${currentConfig.proxyIp}:${currentConfig.proxyPort} (${rtt}ms) عبر [${channel.shortBadge}]",
            LogLevel.SUCCESS
        )
    }

    private fun handleActualChannelSwitch(newChannel: ActiveSocketChannel, sessionId: Long) {
        val scope = relayScope ?: return
        if (!isRunning.get() || relaySessionId.get() != sessionId) return

        handoverJob?.cancel()
        handoverJob = scope.launch {
            if (!isRunning.get() || relaySessionId.get() != sessionId) return@launch

            socketChannel.clearStaleProcessNetworkBinding()
            dnsResolver.clearCache()

            val newLease = rotateToNextAvailableLocalIp(reasonTag = "NET-HANDOVER")
            val newBindAddr = newLease?.bindAddress ?: "$activeLocalBindHost:$boundPort"

            logger.updateTelemetry {
                it.copy(
                    activeSocketChannel = newChannel,
                    localRelayRunning = true,
                    localRelayBindAddress = newBindAddr,
                    wifiLocalRelayAddress = newBindAddr,
                    cellularLocalRelayAddress = newBindAddr,
                    status = ConnectionStatus.RECONNECTING,
                    statusDetail = "تم التحول الفوري إلى قناة [${newChannel.shortBadge}] — جاري ربط مسار ZeroTier..."
                )
            }

            delay(350L)
            if (!isRunning.get() || relaySessionId.get() != sessionId) return@launch

            val verifyResult = verifyUpstreamConnectionWithRetry(
                config = currentConfig,
                maxAttempts = 2,
                perAttemptTimeoutMs = 4_500,
                retryDelayMs = 400L
            )

            if (!isRunning.get() || relaySessionId.get() != sessionId) return@launch

            verifyResult.fold(
                onSuccess = { rtt ->
                    markRelayConnectedNow(rtt = rtt, channel = newChannel, sourceTag = "NET-HANDOVER")
                },
                onFailure = {
                    logger.log(
                        "AUTO-RECONNECT",
                        "مسار ZeroTier يعيد التموضع على 4G — تفعيل حلقة الربط التلقائي الذكي كل ثانيتين",
                        LogLevel.INFO
                    )
                    startBackgroundAutoReconnect(config = currentConfig, reasonTag = "AUTO-RECOVER")
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
                if (autoReconnectJob?.isActive == true || logger.telemetry.value.activeConnections > 0) {
                    consecutiveFailures = 0
                    delay(18_000L)
                    continue
                }

                reclaimPrimaryLocalIpIfDisconnected()

                val activeChan = socketChannel.detectActiveSocketChannel()
                val pingRes = testUpstreamHandshake(
                    config = currentConfig,
                    timeoutMs = socketChannel.recommendedConnectTimeoutMs(5_000)
                )

                if (!isRunning.get() || relaySessionId.get() != sessionId) break

                pingRes.fold(
                    onSuccess = { rtt ->
                        consecutiveFailures = 0
                        val activeBindAddr = "$activeLocalBindHost:$boundPort"
                        logger.updateTelemetry {
                            it.copy(
                                status = ConnectionStatus.CONNECTED,
                                localRelayRunning = true,
                                activeSocketChannel = activeChan,
                                localRelayBindAddress = activeBindAddr,
                                wifiLocalRelayAddress = activeBindAddr,
                                cellularLocalRelayAddress = activeBindAddr,
                                lastWatchdogPingMs = rtt,
                                watchdogHealthy = true,
                                statusDetail = "متصل فعلياً: ${currentConfig.proxyIp}:${currentConfig.proxyPort} (${rtt}ms) عبر [${activeChan.shortBadge}]"
                            )
                        }
                    },
                    onFailure = {
                        consecutiveFailures++
                        if (consecutiveFailures >= 2 && isRunning.get() && relaySessionId.get() == sessionId) {
                            socketChannel.clearStaleProcessNetworkBinding()
                            startBackgroundAutoReconnect(config = currentConfig, reasonTag = "WATCHDOG-RECOVER")
                        }
                    }
                )
                delay(20_000L)
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

    private suspend fun handleClientConnection(
        clientSocket: Socket,
        lease: LocalIpLease,
        sessionId: Long
    ) = withContext(Dispatchers.IO) {
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

            val connectStartNs = System.nanoTime()
            upstreamSocket = when (firstByte) {
                0x05 -> handleLocalSocks5Client(
                    clientIn = DataInputStream(bufferedClientIn),
                    clientOut = clientOut,
                    localBindIpBytes = lease.localIpBytes,
                    localPort = lease.localPort,
                    sessionId = sessionId
                )
                0x04 -> handleLocalSocks4Client(
                    clientIn = DataInputStream(bufferedClientIn),
                    clientOut = clientOut,
                    sessionId = sessionId
                )
                else -> handleLocalHttpClient(
                    clientIn = bufferedClientIn,
                    clientOut = clientOut,
                    sessionId = sessionId
                )
            }

            if (upstreamSocket != null) {
                localIpPool.registerSocketOnSlot(lease.slotIndex, upstreamSocket)
            }

            if (!isRunning.get() || relaySessionId.get() != sessionId) {
                if (upstreamSocket != null) {
                    localIpPool.unregisterSocketOnSlot(lease.slotIndex, upstreamSocket)
                }
                return@withContext
            }

            if (logger.telemetry.value.status != ConnectionStatus.CONNECTED) {
                val rtt = ((System.nanoTime() - connectStartNs) / 1_000_000L).coerceAtLeast(1L)
                markRelayConnectedNow(
                    rtt = rtt,
                    channel = socketChannel.currentSocketChannel(),
                    sourceTag = "LIVE-TRAFFIC-CONNECTED"
                )
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
            if (upstreamSocket != null) {
                localIpPool.unregisterSocketOnSlot(lease.slotIndex, upstreamSocket)
            }
            localIpPool.unregisterSocketOnSlot(lease.slotIndex, clientSocket)

            if (lease.slotIndex > 1 && localIpPool.getActiveSocketCountForSlot(lease.slotIndex) == 0) {
                reclaimPrimaryLocalIpIfDisconnected()
            }
        }
    }

    private fun handleLocalSocks5Client(
        clientIn: DataInputStream,
        clientOut: OutputStream,
        localBindIpBytes: ByteArray,
        localPort: Int,
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
        clientIn.readUnsignedByte()
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
                localBindIpBytes[0], localBindIpBytes[1], localBindIpBytes[2], localBindIpBytes[3],
                ((localPort shr 8) and 0xFF).toByte(),
                (localPort and 0xFF).toByte()
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
        while (clientIn.read() > 0) {}

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
        val connectTimeout = socketChannel.recommendedConnectTimeoutMs(5_500)

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
                while (readAsciiLine(upIn).isNotEmpty()) {}
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
                try { upstreamSocket.shutdownOutput() } catch (_: Exception) {}
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
                try { clientSocket.shutdownOutput() } catch (_: Exception) {}
            }
        }

        upstreamToClient.join()
        try { clientSocket.close() } catch (_: Exception) {}
        clientToUpstream.cancel()
    }

    private fun readAsciiLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) {
                sb.append(b.toChar())
            }
        }
        return sb.toString()
    }
}
