package com.example.network

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * WebViewProxyConfigurator (FoxyProxy-Equivalent Native SOCKS5 Mode)
 * ------------------------------------------------------------------
 * Configures Android's Chromium `ProxyController` to route traffic through our local
 * `LocalSocksAuthRelay` (`127.0.0.1:localPort`) using native `socks5://127.0.0.1:localPort`
 * as the primary transport (identical to Firefox + FoxyProxy), with HTTP/CONNECT as fallback.
 *
 * Why `socks5://` primary rule makes router pages load dramatically faster on Mobile Data + ZeroTier:
 * - Chromium WebView treats a SOCKS5 tunnel as a persistent transport-layer TCP connection to the
 *   router IP (`192.168.x.x`, `10.x.x.x`, `11.11.x.x`, `172.x.x.x`).
 * - Chromium's native HTTP/1.1 Keep-Alive connection pool reuses the same open SOCKS5 socket for all
 *   20-35 MikroTik WebFig JS/CSS/image/AJAX requests instead of opening a new TCP + SOCKS5 connection
 *   for every single file!
 */
object WebViewProxyConfigurator : WebViewProxyContract {

    private val directExecutor = Executor { command -> command.run() }

    @Volatile
    private var lastAppliedPort: Int = -1

    override fun isProxyOverrideSupported(): Boolean {
        return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
    }

    /**
     * Applies `ProxyController` override pointing to `socks5://127.0.0.1:localPort` (primary)
     * and `127.0.0.1:localPort` (fallback) without any local-subnet bypass rules so all private
     * ZeroTier / MikroTik IPs are routed through the pre-warmed SOCKS5 tunnel.
     */
    override suspend fun applyLocalRelayProxy(localPort: Int, forceReapply: Boolean): Result<String> =
        suspendCancellableCoroutine { cont ->
            if (!isProxyOverrideSupported()) {
                val msg = "جهاز الأندرويد الحالي لا يدعم WebViewFeature.PROXY_OVERRIDE"
                VpnSocketProtector.log("WEBVIEW-PROXY", msg, LogLevel.ERROR)
                if (cont.isActive) cont.resume(Result.failure(UnsupportedOperationException(msg)))
                return@suspendCancellableCoroutine
            }

            // Avoid resetting Chromium's active Keep-Alive socket pool if the same relay port is already applied
            if (!forceReapply && lastAppliedPort == localPort) {
                if (cont.isActive) {
                    cont.resume(Result.success("ProxyController مربوط مسبقاً على 127.0.0.1:$localPort"))
                }
                return@suspendCancellableCoroutine
            }

            try {
                val socks5RelayRule = "socks5://127.0.0.1:$localPort"
                val httpRelayRule = "127.0.0.1:$localPort"

                val proxyConfig = ProxyConfig.Builder()
                    .addProxyRule(socks5RelayRule)
                    .addProxyRule(httpRelayRule)
                    .build()

                ProxyController.getInstance().setProxyOverride(
                    proxyConfig,
                    directExecutor
                ) {
                    lastAppliedPort = localPort
                    val msg = "تم تفعيل نفق SOCKS5 السريع (FoxyProxy Mode) على 127.0.0.1:$localPort"
                    VpnSocketProtector.log("WEBVIEW-PROXY", msg, LogLevel.SUCCESS)
                    if (cont.isActive) {
                        cont.resume(Result.success(msg))
                    }
                }
            } catch (e: Exception) {
                val err = "فشل ضبط ProxyController: ${e.localizedMessage}"
                VpnSocketProtector.log("WEBVIEW-PROXY", err, LogLevel.ERROR)
                if (cont.isActive) {
                    cont.resume(Result.failure(e))
                }
            }
        }

    override suspend fun clearProxyOverride(): Result<Unit> =
        suspendCancellableCoroutine { cont ->
            lastAppliedPort = -1
            if (!isProxyOverrideSupported()) {
                if (cont.isActive) cont.resume(Result.success(Unit))
                return@suspendCancellableCoroutine
            }
            try {
                ProxyController.getInstance().clearProxyOverride(directExecutor) {
                    VpnSocketProtector.log("WEBVIEW-PROXY", "تم إلغاء توجيه ProxyController (اتصال مباشر)", LogLevel.INFO)
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(Result.failure(e))
            }
        }
}
