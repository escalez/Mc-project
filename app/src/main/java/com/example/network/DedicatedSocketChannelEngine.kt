package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.Security
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DedicatedSocketChannelEngine(
    context: Context,
    private val killSwitch: ProxyKillSwitchContract,
    private val dnsResolver: DnsResolverContract = InternalDnsResolver,
    private val logger: ProxyLogContract = VpnSocketProtector
) : SocketChannelContract {

    private val appContext: Context = context.applicationContext
    private val activeChannelRef = AtomicReference(ActiveSocketChannel.STANDBY)
    private val lastPhysicalNetworkHandle = AtomicLong(0L)

    @Volatile
    private var transportNetworkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        try {
            Security.setProperty("networkaddress.cache.ttl", "0")
            Security.setProperty("networkaddress.cache.negative.ttl", "0")
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val SOCKET_BUFFER_BYTES = 256 * 1024
        private const val CELLULAR_ZEROTIER_MIN_TIMEOUT_MS = 5_500
        private const val WIFI_MIN_TIMEOUT_MS = 4_000
        private const val ZEROTIER_DEFAULT_UDP_PORT = 9993
    }

    private data class PhysicalNetworkSnapshot(
        val hasValidatedWifi: Boolean,
        val hasAnyPhysicalWifi: Boolean,
        val hasPhysicalCellular: Boolean,
        val hasVpn: Boolean,
        val activePhysicalHandle: Long,
        val channel: ActiveSocketChannel
    )

    override fun currentSocketChannel(): ActiveSocketChannel = activeChannelRef.get()

    override fun detectActiveSocketChannel(): ActiveSocketChannel {
        val snapshot = inspectPhysicalAndVpnNetworks()
        activeChannelRef.set(snapshot.channel)
        if (snapshot.activePhysicalHandle != 0L) {
            lastPhysicalNetworkHandle.set(snapshot.activePhysicalHandle)
        }
        return snapshot.channel
    }

    private fun inspectPhysicalAndVpnNetworks(): PhysicalNetworkSnapshot {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return PhysicalNetworkSnapshot(
                hasValidatedWifi = false,
                hasAnyPhysicalWifi = false,
                hasPhysicalCellular = true,
                hasVpn = findActiveZeroTierLocalAddress() != null,
                activePhysicalHandle = 0L,
                channel = ActiveSocketChannel.CELLULAR_ZEROTIER
            )

        var hasValidatedWifi = false
        var hasAnyPhysicalWifi = false
        var hasPhysicalCellular = false
        var hasVpn = false
        var wifiHandle = 0L
        var cellularHandle = 0L

        try {
            @Suppress("DEPRECATION")
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    hasVpn = true
                    continue
                }

                val isNotSuspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                } else {
                    true
                }
                val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val linkProps = try { cm.getLinkProperties(net) } catch (_: Exception) { null }
                val hasLiveAddresses = linkProps?.linkAddresses?.isNotEmpty() == true

                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && isNotSuspended && hasLiveAddresses) {
                    hasAnyPhysicalWifi = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        wifiHandle = net.networkHandle
                    }
                    if (isValidated) {
                        hasValidatedWifi = true
                    }
                }

                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && isNotSuspended) {
                    hasPhysicalCellular = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        cellularHandle = net.networkHandle
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (!hasVpn && findActiveZeroTierLocalAddress() != null) {
            hasVpn = true
        }

        val resolvedChannel: ActiveSocketChannel
        val resolvedHandle: Long

        when {
            hasValidatedWifi -> {
                resolvedChannel = ActiveSocketChannel.WIFI_DEDICATED
                resolvedHandle = wifiHandle
            }
            hasPhysicalCellular -> {
                resolvedChannel = ActiveSocketChannel.CELLULAR_ZEROTIER
                resolvedHandle = cellularHandle
            }
            hasAnyPhysicalWifi -> {
                resolvedChannel = ActiveSocketChannel.WIFI_DEDICATED
                resolvedHandle = wifiHandle
            }
            else -> {
                resolvedChannel = ActiveSocketChannel.CELLULAR_ZEROTIER
                resolvedHandle = cellularHandle
            }
        }

        return PhysicalNetworkSnapshot(
            hasValidatedWifi = hasValidatedWifi,
            hasAnyPhysicalWifi = hasAnyPhysicalWifi,
            hasPhysicalCellular = hasPhysicalCellular,
            hasVpn = hasVpn,
            activePhysicalHandle = resolvedHandle,
            channel = resolvedChannel
        )
    }

    override fun recommendedConnectTimeoutMs(requestedTimeoutMs: Int): Int {
        val channel = detectActiveSocketChannel()
        return if (channel == ActiveSocketChannel.CELLULAR_ZEROTIER) {
            requestedTimeoutMs.coerceAtLeast(CELLULAR_ZEROTIER_MIN_TIMEOUT_MS)
        } else {
            requestedTimeoutMs.coerceAtLeast(WIFI_MIN_TIMEOUT_MS)
        }
    }

    override fun openDedicatedChannelSocket(
        host: String,
        port: Int,
        timeoutMs: Int,
        isAllowedToConnect: () -> Boolean
    ): Socket {
        if (!isAllowedToConnect()) {
            throw IOException("تم إلغاء فتح السوكيت لأن البروكسي متوقف")
        }

        clearStaleProcessNetworkBinding()

        val channel = detectActiveSocketChannel()
        val effectiveTimeoutMs = if (channel == ActiveSocketChannel.CELLULAR_ZEROTIER) {
            timeoutMs.coerceAtLeast(CELLULAR_ZEROTIER_MIN_TIMEOUT_MS)
        } else {
            timeoutMs.coerceAtLeast(WIFI_MIN_TIMEOUT_MS)
        }

        val cleanHost = host.trim()
        val targetAddr: InetSocketAddress = dnsResolver.resolveSocketAddressFastSync(
            host = cleanHost,
            port = port,
            timeoutMs = 2_000
        )

        val vpnNet = findVpnNetwork()
        val localZtIp = findActiveZeroTierLocalAddress(targetAddr.address)

        if (vpnNet != null || localZtIp != null) {
            wakeUpZeroTierPeerPath(
                localZtIp = localZtIp,
                vpnNet = vpnNet,
                targetAddr = targetAddr
            )
        }

        if (channel == ActiveSocketChannel.CELLULAR_ZEROTIER && (vpnNet != null || localZtIp != null)) {
            var lastError: Exception? = null

            if (vpnNet != null) {
                val vpnSock = Socket()
                killSwitch.trackSocket(vpnSock)
                try {
                    configureLowLatencySocket(vpnSock, channel)
                    vpnNet.bindSocket(vpnSock)
                    val directVpnTimeout = (effectiveTimeoutMs * 2 / 3).coerceIn(2_800, 4_200)
                    vpnSock.connect(targetAddr, directVpnTimeout)

                    if (!isAllowedToConnect()) {
                        killSwitch.untrackAndCloseSocket(vpnSock)
                        throw IOException("تم إيقاف البروكسي أثناء فتح السوكيت")
                    }
                    return vpnSock
                } catch (e: Exception) {
                    lastError = e
                    killSwitch.untrackAndCloseSocket(vpnSock)
                    if (!isAllowedToConnect()) throw e
                }
            }

            if (localZtIp != null && isAllowedToConnect()) {
                wakeUpZeroTierPeerPath(localZtIp = localZtIp, vpnNet = vpnNet, targetAddr = targetAddr)
                val ztSock = Socket()
                killSwitch.trackSocket(ztSock)
                try {
                    configureLowLatencySocket(ztSock, channel)
                    if (vpnNet != null) {
                        try {
                            vpnNet.bindSocket(ztSock)
                        } catch (_: Exception) {
                        }
                    }
                    ztSock.bind(InetSocketAddress(localZtIp, 0))
                    ztSock.connect(targetAddr, 3_500)

                    if (!isAllowedToConnect()) {
                        killSwitch.untrackAndCloseSocket(ztSock)
                        throw IOException("تم إيقاف البروكسي أثناء فتح السوكيت")
                    }
                    return ztSock
                } catch (e: Exception) {
                    lastError = e
                    killSwitch.untrackAndCloseSocket(ztSock)
                    if (!isAllowedToConnect()) throw e
                }
            }

            if (isAllowedToConnect()) {
                val osSock = Socket()
                killSwitch.trackSocket(osSock)
                try {
                    configureLowLatencySocket(osSock, channel)
                    osSock.connect(targetAddr, 2_800)
                    if (!isAllowedToConnect()) {
                        killSwitch.untrackAndCloseSocket(osSock)
                        throw IOException("تم إيقاف البروكسي أثناء فتح السوكيت")
                    }
                    return osSock
                } catch (e: Exception) {
                    lastError = e
                    killSwitch.untrackAndCloseSocket(osSock)
                }
            }

            throw lastError ?: IOException("تعذر فتح سوكيت نفق ZeroTier نحو $cleanHost:$port")
        }

        val primarySock = Socket()
        killSwitch.trackSocket(primarySock)
        try {
            configureLowLatencySocket(primarySock, channel)
            if (vpnNet != null && !isLanRouterNonZeroTierIp(cleanHost)) {
                try {
                    vpnNet.bindSocket(primarySock)
                } catch (_: Exception) {
                }
            }
            primarySock.connect(targetAddr, effectiveTimeoutMs)

            if (!isAllowedToConnect()) {
                killSwitch.untrackAndCloseSocket(primarySock)
                throw IOException("تم إيقاف البروكسي أثناء فتح السوكيت")
            }
            return primarySock
        } catch (firstErr: Exception) {
            killSwitch.untrackAndCloseSocket(primarySock)
            if (!isAllowedToConnect()) {
                throw firstErr
            }

            if (localZtIp != null) {
                val ztSock = Socket()
                killSwitch.trackSocket(ztSock)
                try {
                    configureLowLatencySocket(ztSock, channel)
                    ztSock.bind(InetSocketAddress(localZtIp, 0))
                    ztSock.connect(targetAddr, 3_500)
                    if (!isAllowedToConnect()) {
                        killSwitch.untrackAndCloseSocket(ztSock)
                        throw IOException("تم إيقاف البروكسي أثناء فتح السوكيت")
                    }
                    return ztSock
                } catch (_: Exception) {
                    killSwitch.untrackAndCloseSocket(ztSock)
                }
            }

            if (isAllowedToConnect() && isLanRouterNonZeroTierIp(cleanHost)) {
                val wifiNet = findPhysicalTransportNetwork(NetworkCapabilities.TRANSPORT_WIFI)
                if (wifiNet != null) {
                    val wifiSock = Socket()
                    killSwitch.trackSocket(wifiSock)
                    try {
                        configureLowLatencySocket(wifiSock, channel)
                        wifiNet.bindSocket(wifiSock)
                        wifiSock.connect(targetAddr, effectiveTimeoutMs)
                        if (!isAllowedToConnect()) {
                            killSwitch.untrackAndCloseSocket(wifiSock)
                            throw IOException("تم إيقاف البروكسي")
                        }
                        return wifiSock
                    } catch (_: Exception) {
                        killSwitch.untrackAndCloseSocket(wifiSock)
                    }
                }
            }

            throw firstErr
        }
    }

    private fun wakeUpZeroTierPeerPath(
        localZtIp: InetAddress?,
        vpnNet: Network?,
        targetAddr: InetSocketAddress
    ) {
        val targetInet = targetAddr.address ?: return
        val wakeBytes = byteArrayOf(0x00)

        if (vpnNet != null) {
            var vpnUdp: DatagramSocket? = null
            try {
                vpnUdp = DatagramSocket()
                vpnUdp.soTimeout = 60
                vpnNet.bindSocket(vpnUdp)
                vpnUdp.send(DatagramPacket(wakeBytes, wakeBytes.size, targetInet, targetAddr.port))
                if (targetAddr.port != ZEROTIER_DEFAULT_UDP_PORT) {
                    vpnUdp.send(DatagramPacket(wakeBytes, wakeBytes.size, targetInet, ZEROTIER_DEFAULT_UDP_PORT))
                }
            } catch (_: Exception) {
            } finally {
                try {
                    vpnUdp?.close()
                } catch (_: Exception) {
                }
            }
        }

        if (localZtIp != null) {
            var ztUdp: DatagramSocket? = null
            try {
                ztUdp = DatagramSocket(InetSocketAddress(localZtIp, 0))
                ztUdp.soTimeout = 60
                ztUdp.send(DatagramPacket(wakeBytes, wakeBytes.size, targetInet, targetAddr.port))
                if (targetAddr.port != ZEROTIER_DEFAULT_UDP_PORT) {
                    ztUdp.send(DatagramPacket(wakeBytes, wakeBytes.size, targetInet, ZEROTIER_DEFAULT_UDP_PORT))
                }
            } catch (_: Exception) {
            } finally {
                try {
                    ztUdp?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun registerNetworkMonitor(
        onActualChannelChanged: (oldChannel: ActiveSocketChannel, newChannel: ActiveSocketChannel) -> Unit
    ) {
        unregisterNetworkMonitor()
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val createCallback = {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    checkAndNotifyChannelChange(onActualChannelChanged)
                }

                override fun onLost(network: Network) {
                    checkAndNotifyChannelChange(onActualChannelChanged)
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    checkAndNotifyChannelChange(onActualChannelChanged)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val defCb = createCallback()
            defaultNetworkCallback = defCb
            try {
                cm.registerDefaultNetworkCallback(defCb)
            } catch (_: Exception) {
                defaultNetworkCallback = null
            }
        }

        val transCb = createCallback()
        transportNetworkCallback = transCb
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(req, transCb)
        } catch (_: Exception) {
            transportNetworkCallback = null
        }
    }

    private fun checkAndNotifyChannelChange(
        onActualChannelChanged: (oldChannel: ActiveSocketChannel, newChannel: ActiveSocketChannel) -> Unit
    ) {
        val snapshot = inspectPhysicalAndVpnNetworks()

        val previousChannel = activeChannelRef.get()
        val previousHandle = lastPhysicalNetworkHandle.get()
        val currentChannel = snapshot.channel
        val currentHandle = snapshot.activePhysicalHandle

        activeChannelRef.set(currentChannel)
        if (currentHandle != 0L) {
            lastPhysicalNetworkHandle.set(currentHandle)
        }

        val channelChanged = previousChannel != ActiveSocketChannel.STANDBY && previousChannel != currentChannel
        val physicalNetworkChanged = previousHandle != 0L && currentHandle != 0L && previousHandle != currentHandle

        if (channelChanged || physicalNetworkChanged) {
            clearStaleProcessNetworkBinding()
            dnsResolver.clearCache()
            killSwitch.flushActiveSocketsPreserveServer("CHANNEL-SWITCH")
            logger.log(
                "CHANNEL-SWITCH",
                "تحول فوري للقناة من [${previousChannel.shortBadge} (${previousChannel.dedicatedLocalIp})] إلى [${currentChannel.shortBadge} (${currentChannel.dedicatedLocalIp})]",
                LogLevel.INFO
            )
            onActualChannelChanged(previousChannel, currentChannel)
        }
    }

    override fun unregisterNetworkMonitor() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val defCb = defaultNetworkCallback
        defaultNetworkCallback = null
        if (cm != null && defCb != null) {
            try {
                cm.unregisterNetworkCallback(defCb)
            } catch (_: Exception) {
            }
        }

        val transCb = transportNetworkCallback
        transportNetworkCallback = null
        if (cm != null && transCb != null) {
            try {
                cm.unregisterNetworkCallback(transCb)
            } catch (_: Exception) {
            }
        }
    }

    override fun clearStaleProcessNetworkBinding() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
    }

    private fun configureLowLatencySocket(sock: Socket, channel: ActiveSocketChannel) {
        try { sock.tcpNoDelay = true } catch (_: Exception) {}
        try { sock.keepAlive = true } catch (_: Exception) {}
        try { sock.reuseAddress = true } catch (_: Exception) {}
        try {
            sock.receiveBufferSize = SOCKET_BUFFER_BYTES
            sock.sendBufferSize = SOCKET_BUFFER_BYTES
        } catch (_: Exception) {}
        try { sock.trafficClass = 0x10 } catch (_: Exception) {}
        try {
            sock.soTimeout = if (channel == ActiveSocketChannel.CELLULAR_ZEROTIER) 30_000 else 22_000
        } catch (_: Exception) {}
    }

    private fun findPhysicalTransportNetwork(transportType: Int): Network? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return try {
            @Suppress("DEPRECATION")
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasTransport(transportType)) {
                    return net
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun findVpnNetwork(): Network? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return try {
            @Suppress("DEPRECATION")
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return net
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun findActiveZeroTierLocalAddress(preferredTarget: InetAddress? = null): InetAddress? {
        return try {
            val targetBytes = preferredTarget?.address
            var firstCandidate: InetAddress? = null
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                val isTunOrZt = name.startsWith("tun") ||
                    name.startsWith("zt") ||
                    name.startsWith("tap") ||
                    name.contains("zerotier") ||
                    nif.isPointToPoint
                if (isTunOrZt) {
                    for (addr in Collections.list(nif.inetAddresses)) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val addrBytes = addr.address
                            if (targetBytes != null &&
                                targetBytes.size == 4 &&
                                addrBytes.size == 4 &&
                                addrBytes[0] == targetBytes[0] &&
                                addrBytes[1] == targetBytes[1]
                            ) {
                                return addr
                            }
                            if (firstCandidate == null) {
                                firstCandidate = addr
                            }
                        }
                    }
                }
            }
            firstCandidate
        } catch (_: Exception) {
            null
        }
    }

    private fun isLanRouterNonZeroTierIp(host: String): Boolean {
        return host.trim().startsWith("192.168.")
    }
}
