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

/**
 * تطبيق مستقل لمحرك قنوات السوكيت (DedicatedSocketChannelEngine implements SocketChannelContract).
 *
 * الحل الجذري لمشكلة التحويل من الواي فاي إلى بيانات الهاتف مع ZeroTier:
 * 1. فصل فحص الشبكة الفيزيائية (`WIFI` / `CELLULAR`) عن شبكة الـ VPN الخاصة بـ ZeroTier (`!caps.hasTransport(TRANSPORT_VPN)`):
 *    لأن أندرويد ينسخ علامة `TRANSPORT_WIFI` داخل خصائص شبكة `VPN` عند تشغيل ZeroTier على الواي فاي ويبقيها
 *    حتى بعد إطفاء الواي فاي وتشغيل البيانات! تجاهل شبكات `TRANSPORT_VPN` عند فحص الشبكة الفيزيائية يضمن اكتشاف
 *    التحول إلى بيانات الهاتف فوراً بنسبة 100% بدون الحاجة لإعادة تشغيل ZeroTier.
 * 2. إيقاظ مسار ZeroTier (`tun0`) تلقائياً عبر نبضة UDP محلية (`wakeUpZeroTierPeerPath`) فور التحول إلى البيانات
 *    لدفع محرك ZeroTier لتحديث مسار الـ UDP عبر 4G وحل جدول ARP الافتراضي قبل إرسال مصافحة TCP.
 * 3. التوجيه المتدرج الذكي عبر `tun0`: يجرب أولاً مسار النواة القياسي، وإذا ابتلع ZeroTier أول حزمة SYN أثناء
 *    تبديل المسار من الواي فاي إلى 4G أو وجه النظام السوكيت لشبكة الشريحة مباشرة، يفتح سوكيت مرتبط بعنوان
 *    واجهة ZeroTier (`tun0`) لإجبار النواة على التوجيه داخل نفق ZeroTier فوراً.
 */
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
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        try {
            Security.setProperty("networkaddress.cache.ttl", "0")
            Security.setProperty("networkaddress.cache.negative.ttl", "0")
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val SOCKET_BUFFER_BYTES = 256 * 1024
        private const val CELLULAR_ZEROTIER_MIN_TIMEOUT_MS = 7_500
        private const val WIFI_MIN_TIMEOUT_MS = 4_500
    }

    private data class PhysicalNetworkSnapshot(
        val hasPhysicalWifi: Boolean,
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

    /**
     * يفحص الشبكات الفيزيائية الحقيقية مع استبعاد شبكة `TRANSPORT_VPN` من حساب الواي فاي/البيانات،
     * لأن Android VpnService يحتفظ بعلامة `TRANSPORT_WIFI` القديمة داخل كائن الـ VPN بعد إطفاء الواي فاي!
     */
    private fun inspectPhysicalAndVpnNetworks(): PhysicalNetworkSnapshot {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return PhysicalNetworkSnapshot(
                hasPhysicalWifi = false,
                hasPhysicalCellular = true,
                hasVpn = findActiveZeroTierLocalAddress() != null,
                activePhysicalHandle = 0L,
                channel = ActiveSocketChannel.CELLULAR_ZEROTIER
            )

        var hasPhysicalWifi = false
        var hasPhysicalCellular = false
        var hasVpn = false
        var wifiHandle = 0L
        var cellularHandle = 0L

        try {
            @Suppress("DEPRECATION")
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                if (isVpn) {
                    hasVpn = true
                    continue
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    hasPhysicalWifi = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        wifiHandle = net.networkHandle
                    }
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    hasPhysicalCellular = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        cellularHandle = net.networkHandle
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (!hasPhysicalWifi && !hasPhysicalCellular) {
            try {
                val activeNet = cm.activeNetwork
                val caps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
                if (activeNet != null && caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        hasPhysicalWifi = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            wifiHandle = activeNet.networkHandle
                        }
                    }
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        hasPhysicalCellular = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            cellularHandle = activeNet.networkHandle
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (!hasVpn && findActiveZeroTierLocalAddress() != null) {
            hasVpn = true
        }

        val resolvedChannel: ActiveSocketChannel
        val resolvedHandle: Long

        when {
            hasPhysicalCellular && !hasPhysicalWifi -> {
                resolvedChannel = ActiveSocketChannel.CELLULAR_ZEROTIER
                resolvedHandle = cellularHandle
            }
            hasPhysicalWifi && !hasPhysicalCellular -> {
                resolvedChannel = ActiveSocketChannel.WIFI_DEDICATED
                resolvedHandle = wifiHandle
            }
            hasPhysicalWifi && hasPhysicalCellular -> {
                val activeNet = try { cm.activeNetwork } catch (_: Exception) { null }
                val activeCaps = try {
                    if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
                } catch (_: Exception) {
                    null
                }
                if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
                    !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) {
                    resolvedChannel = ActiveSocketChannel.CELLULAR_ZEROTIER
                    resolvedHandle = cellularHandle
                } else {
                    resolvedChannel = ActiveSocketChannel.WIFI_DEDICATED
                    resolvedHandle = wifiHandle
                }
            }
            else -> {
                resolvedChannel = ActiveSocketChannel.CELLULAR_ZEROTIER
                resolvedHandle = 0L
            }
        }

        return PhysicalNetworkSnapshot(
            hasPhysicalWifi = hasPhysicalWifi,
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
            timeoutMs = 2_500
        )

        val localZtIp = findActiveZeroTierLocalAddress(targetAddr.address)

        // If ZeroTier interface (tun0 / zt*) is active, send a fast UDP wake-up pulse from tun0 to targetAddr.
        // This forces ZeroTier's C++ engine to immediately switch its VL1 UDP path from dead Wi-Fi to 4G Mobile Data!
        if (localZtIp != null) {
            wakeUpZeroTierPeerPath(localZtIp, targetAddr)
        }

        // Step 1: Primary OS Socket
        // On Mobile Data + ZeroTier when localZtIp is present, we allocate 3400ms to Step 1 so that if ZeroTier
        // swallowed the initial t=0ms SYN while punching the 4G UDP hole, Step 2 immediately sends a fresh SYN
        // bound to tun0 (`localZtIp`) without waiting 8s for Linux exponential SYN backoff!
        val primaryTimeoutMs = if (localZtIp != null && channel == ActiveSocketChannel.CELLULAR_ZEROTIER) {
            (effectiveTimeoutMs / 2).coerceIn(3_000, 3_800)
        } else {
            effectiveTimeoutMs
        }

        val primarySock = Socket()
        killSwitch.trackSocket(primarySock)
        try {
            configureLowLatencySocket(primarySock, channel)
            primarySock.connect(targetAddr, primaryTimeoutMs)

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

            // Step 2: Explicit ZeroTier `tun0` Source-IP Binding (`sock.bind(localZtIp, 0)`)
            // Fixes the exact Wi-Fi -> Mobile Data bug where Android routes unbound sockets to `rmnet_data0` (4G carrier)
            // instead of `tun0` OR where ZeroTier dropped the first SYN while waking up its 4G peer path!
            if (localZtIp != null) {
                wakeUpZeroTierPeerPath(localZtIp, targetAddr)
                val ztSock = Socket()
                killSwitch.trackSocket(ztSock)
                try {
                    configureLowLatencySocket(ztSock, channel)
                    ztSock.bind(InetSocketAddress(localZtIp, 0))
                    val ztTimeout = (effectiveTimeoutMs - primaryTimeoutMs).coerceAtLeast(4_200)
                    ztSock.connect(targetAddr, ztTimeout)

                    if (!isAllowedToConnect()) {
                        killSwitch.untrackAndCloseSocket(ztSock)
                        throw IOException("تم إيقاف البروكسي أثناء فتح السوكيت")
                    }
                    return ztSock
                } catch (_: Exception) {
                    killSwitch.untrackAndCloseSocket(ztSock)
                }
            }

            // Step 3: Explicit VPN Network Binding (`vpnNet.bindSocket`) if available
            val vpnNet = findVpnNetwork()
            if (isAllowedToConnect() && vpnNet != null) {
                val vpnSock = Socket()
                killSwitch.trackSocket(vpnSock)
                try {
                    configureLowLatencySocket(vpnSock, channel)
                    vpnNet.bindSocket(vpnSock)
                    vpnSock.connect(targetAddr, 3_800)
                    if (!isAllowedToConnect()) {
                        killSwitch.untrackAndCloseSocket(vpnSock)
                        throw IOException("تم إيقاف البروكسي أثناء فتح السوكيت")
                    }
                    return vpnSock
                } catch (_: Exception) {
                    killSwitch.untrackAndCloseSocket(vpnSock)
                }
            }

            // Step 4: Physical Wi-Fi LAN Router fallback (only for 192.168.x.x on Wi-Fi)
            if (isAllowedToConnect() &&
                channel == ActiveSocketChannel.WIFI_DEDICATED &&
                isLanRouterNonZeroTierIp(cleanHost)
            ) {
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

    /**
     * يرسل نبضة UDP خفيفة غير حاجزة عبر عنوان واجهة ZeroTier (`tun0`) نحو عنوان البروكسي
     * لإيقاظ مسار VL1/VL2 الخاص بـ ZeroTier فوراً بعد التحول من الواي فاي إلى بيانات الهاتف.
     */
    private fun wakeUpZeroTierPeerPath(localZtIp: InetAddress, targetAddr: InetSocketAddress) {
        var udpSock: DatagramSocket? = null
        try {
            val targetInet = targetAddr.address ?: return
            udpSock = DatagramSocket(InetSocketAddress(localZtIp, 0))
            udpSock.soTimeout = 80
            val wakeBytes = byteArrayOf(0x00)
            val pkt = DatagramPacket(wakeBytes, wakeBytes.size, targetInet, targetAddr.port)
            udpSock.send(pkt)
        } catch (_: Exception) {
        } finally {
            try {
                udpSock?.close()
            } catch (_: Exception) {
            }
        }
    }

    override fun registerNetworkMonitor(
        onActualChannelChanged: (oldChannel: ActiveSocketChannel, newChannel: ActiveSocketChannel) -> Unit
    ) {
        unregisterNetworkMonitor()
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val callback = object : ConnectivityManager.NetworkCallback() {
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

        networkCallback = callback
        try {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(req, callback)
        } catch (_: Exception) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    cm.registerDefaultNetworkCallback(callback)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun checkAndNotifyChannelChange(
        onActualChannelChanged: (oldChannel: ActiveSocketChannel, newChannel: ActiveSocketChannel) -> Unit
    ) {
        val snapshot = inspectPhysicalAndVpnNetworks()

        // If both Wi-Fi and Mobile Data are currently off (e.g. mid-switch when user just turned off Wi-Fi
        // and hasn't turned on Mobile Data yet), flush dead sockets from the lost network and wait until
        // the new physical network (`hasPhysicalCellular` or `hasPhysicalWifi`) actually comes online!
        if (!snapshot.hasPhysicalWifi && !snapshot.hasPhysicalCellular) {
            killSwitch.flushActiveSocketsPreserveServer("NET-TRANSITION")
            clearStaleProcessNetworkBinding()
            return
        }

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
                "تحول فعلي لمسار الشبكة من [${previousChannel.shortBadge}] إلى [${currentChannel.shortBadge}] — تم تنظيف السوكيتات القديمة",
                LogLevel.INFO
            )
            onActualChannelChanged(previousChannel, currentChannel)
        }
    }

    override fun unregisterNetworkMonitor() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
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
        try {
            sock.tcpNoDelay = true
        } catch (_: Exception) {
        }
        try {
            sock.keepAlive = true
        } catch (_: Exception) {
        }
        try {
            sock.reuseAddress = true
        } catch (_: Exception) {
        }
        try {
            sock.receiveBufferSize = SOCKET_BUFFER_BYTES
            sock.sendBufferSize = SOCKET_BUFFER_BYTES
        } catch (_: Exception) {
        }
        try {
            sock.trafficClass = 0x10 // IPTOS_LOWDELAY
        } catch (_: Exception) {
        }
        try {
            sock.soTimeout = if (channel == ActiveSocketChannel.CELLULAR_ZEROTIER) 30_000 else 22_000
        } catch (_: Exception) {
        }
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

    /**
     * يبحث عن عنوان IPv4 المحلي لواجهة ZeroTier (`tun0` / `zt*`)، مع إعطاء الأولوية للواجهة التي تقع
     * في نفس النطاق الفرعي لعنوان البروكسي الهدف (مثل `172.22.x.x` أو `10.147.x.x`).
     */
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
        val clean = host.trim()
        return clean.startsWith("192.168.")
    }
}
