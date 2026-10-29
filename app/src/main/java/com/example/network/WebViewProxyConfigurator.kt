package com.example.network

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

object WebViewProxyConfigurator : WebViewProxyContract {

    private val directExecutor = Executor { command -> command.run() }

    @Volatile
    private var lastAppliedEndpoint: String = ""

    override fun isProxyOverrideSupported(): Boolean {
        return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
    }

    override suspend fun applyLocalRelayProxy(
        localPort: Int,
        forceReapply: Boolean,
        localHost: String
    ): Result<String> =
        suspendCancellableCoroutine { cont ->
            if (!isProxyOverrideSupported()) {
                val msg = "جهاز الأندرويد الحالي لا يدعم WebViewFeature.PROXY_OVERRIDE"
                VpnSocketProtector.log("WEBVIEW-PROXY", msg, LogLevel.ERROR)
                if (cont.isActive) cont.resume(Result.failure(UnsupportedOperationException(msg)))
                return@suspendCancellableCoroutine
            }

            val cleanHost = localHost.trim().ifEmpty { "127.0.0.1" }
            val endpointKey = "$cleanHost:$localPort"

            if (!forceReapply && lastAppliedEndpoint == endpointKey) {
                if (cont.isActive) {
                    cont.resume(Result.success("ProxyController مربوط مسبقاً على $endpointKey"))
                }
                return@suspendCancellableCoroutine
            }

            try {
                val socks5RelayRule = "socks5://$cleanHost:$localPort"
                val httpRelayRule = "$cleanHost:$localPort"

                val proxyConfig = ProxyConfig.Builder()
                    .addProxyRule(socks5RelayRule)
                    .addProxyRule(httpRelayRule)
                    .build()

                ProxyController.getInstance().setProxyOverride(
                    proxyConfig,
                    directExecutor
                ) {
                    lastAppliedEndpoint = endpointKey
                    val msg = "تم تفعيل نفق SOCKS5 السريع على الآيبي المحلي المخصص $endpointKey"
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
            lastAppliedEndpoint = ""
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
