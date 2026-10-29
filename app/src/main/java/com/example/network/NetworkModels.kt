package com.example.network

data class SavedProxyProfile(
    val id: String,
    val name: String = "",
    val proxyIp: String,
    val proxyPort: Int,
    val authEnabled: Boolean = false,
    val username: String = "",
    val password: String = ""
) {
    val displayTitle: String
        get() = if (name.isNotBlank()) name else "$proxyIp:$proxyPort"

    fun toSocks5Config(localRelayPort: Int = 10808): Socks5Config {
        return Socks5Config(
            proxyIp = proxyIp,
            proxyPortInput = proxyPort.toString(),
            authEnabled = authEnabled,
            username = username,
            password = password,
            localRelayPort = localRelayPort
        )
    }
}

data class Socks5Config(
    val proxyIp: String = "",
    val proxyPortInput: String = "",
    val authEnabled: Boolean = false,
    val username: String = "",
    val password: String = "",
    val localRelayPort: Int = 10808,
    val remoteDnsResolution: Boolean = true
) {
    val proxyPort: Int
        get() = proxyPortInput.trim().toIntOrNull() ?: 0

    val isValid: Boolean
        get() = proxyIp.isNotBlank() && proxyPort in 1..65535 &&
            (!authEnabled || username.isNotBlank())
}

enum class RoutingMode(
    val id: String,
    val titleAr: String,
    val subtitleAr: String,
    val badge: String
) {
    STANDALONE_PROXY_ZEROTIER(
        id = "STANDALONE_PROXY_ZEROTIER",
        titleAr = "وضع البروكسي المستقل (ZeroTier Mode)",
        subtitleAr = "تشغيل البروكسي والجسر المحلي بدون VpnService للعمل بجانب تطبيق ZeroTier الخارجي والوصول للمايكروتيك",
        badge = "ZEROTIER SAFE"
    )
}

enum class ActiveSocketChannel(
    val labelAr: String,
    val shortBadge: String,
    val dedicatedLocalIp: String,
    val defaultLocalPort: Int
) {
    STANDBY("مغلق تماماً (وضع الاستعداد)", "STANDBY", "127.0.0.1", 10808),
    WIFI_DEDICATED("سوكيت الوايفاي المستقل (Wi-Fi Socket)", "WIFI-SOCK", "127.0.0.1", 10808),
    CELLULAR_ZEROTIER("سوكيت بيانات الهاتف + ZeroTier المستقل", "DATA+ZT-SOCK", "127.0.0.2", 10809)
}

enum class ConnectionStatus(val labelAr: String) {
    DISCONNECTED("غير متصل"),
    CONNECTING("جاري فحص والاتصال بالبروكسي..."),
    CONNECTED("متصل (وضع البروكسي المستقل)"),
    RECONNECTING("إعادة ربط تلقائية ذكية (Auto-Reconnect)..."),
    ERROR("فشل الاتصال بالبروكسي")
}

data class TunnelTelemetry(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val activeMode: RoutingMode = RoutingMode.STANDALONE_PROXY_ZEROTIER,
    val activeSocketChannel: ActiveSocketChannel = ActiveSocketChannel.STANDBY,
    val internalDnsEnabled: Boolean = true,
    val killedSocketsCount: Int = 0,
    val localRelayRunning: Boolean = false,
    val localRelayBindAddress: String = "127.0.0.1:10808",
    val wifiLocalRelayAddress: String = "127.0.0.1:10808",
    val cellularLocalRelayAddress: String = "127.0.0.2:10809",
    val activeConnections: Int = 0,
    val totalHandshakes: Int = 0,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val lastWatchdogPingMs: Long = -1L,
    val watchdogHealthy: Boolean = false,
    val statusDetail: String = "جاهز للتشغيل كـ بروكسي مستقل متوافق مع تطبيق ZeroTier الخارجي"
)

enum class LogLevel { INFO, SUCCESS, WARN, ERROR }

data class PacketLogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: String,
    val tag: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO
)

data class IpCacheStats(
    val cachedImagesCount: Int = 0,
    val cachedFilesCount: Int = 0,
    val cachedBytesSize: Long = 0L
)

data class SavedRouterIpEntry(
    val id: String,
    val normalizedKey: String,
    val displayIp: String,
    val fullUrl: String,
    val pageTitle: String = "",
    val cachedImagesCount: Int = 0,
    val cachedFilesCount: Int = 0,
    val cachedBytesSize: Long = 0L,
    val openCount: Int = 1,
    val lastOpenedTimestamp: Long = System.currentTimeMillis()
)

