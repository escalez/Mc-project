package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.network.LogLevel
import com.example.network.RouterIpCacheVault
import com.example.network.Socks5Config
import com.example.network.TunnelTelemetry
import com.example.network.VpnSocketProtector
import com.example.ui.theme.AmberPulse
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberObsidian
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldTunnel
import com.example.ui.theme.RobotoMonoFontFamily
import com.example.ui.theme.TextSecondary
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

private data class HttpAuthChallenge(
    val host: String,
    val realm: String,
    val handler: HttpAuthHandler
)

fun clearAllRouterPageCacheAndSession(context: Context, webView: WebView?) {
    try {
        webView?.stopLoading()
        webView?.clearCache(true)
        webView?.clearHistory()
        webView?.clearFormData()
        webView?.clearSslPreferences()
    } catch (_: Exception) {
    }

    try {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
    } catch (_: Exception) {
    }

    try {
        WebStorage.getInstance().deleteAllData()
    } catch (_: Exception) {
    }

    try {
        WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
    } catch (_: Exception) {
    }

    VpnSocketProtector.log(
        "CACHE-CLEANER",
        "تم مسح الكاش يدوياً بناءً على طلب المستخدم",
        LogLevel.INFO
    )
}

private fun buildTurboRequestHeaders(turboEnabled: Boolean): Map<String, String> =
    if (turboEnabled) {
        mapOf(
            "Connection" to "keep-alive",
            "Accept-Encoding" to "gzip, deflate"
        )
    } else {
        mapOf(
            "Connection" to "keep-alive"
        )
    }

private fun shouldShortCircuitExternalTrackers(requestUrl: String, mainRouterHost: String): Boolean {
    val lower = requestUrl.lowercase()
    val blockedExternalDomains = listOf(
        "fonts.googleapis.com",
        "fonts.gstatic.com",
        "www.google-analytics.com",
        "googletagmanager.com",
        "doubleclick.net",
        "connect.facebook.net"
    )
    if (mainRouterHost.isNotEmpty() && !lower.contains(mainRouterHost.lowercase())) {
        if (blockedExternalDomains.any { lower.contains(it) }) {
            return true
        }
    }
    return false
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun QuickRouterBrowserDialog(
    initialUrl: String,
    socksConfig: Socks5Config,
    telemetry: TunnelTelemetry,
    isProxyActive: Boolean = telemetry.localRelayRunning && socksConfig.isValid,
    weakNetTurboEnabled: Boolean = true,
    onToggleWeakNetTurbo: (Boolean) -> Unit = {},
    onPageAndCacheUpdated: (url: String, title: String) -> Unit = { _, _ -> },
    onManualClearIpCache: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember(initialUrl) { mutableStateOf(initialUrl) }
    var addressBarInput by remember(initialUrl) {
        mutableStateOf(RouterIpCacheVault.normalizeIpKey(initialUrl).ifEmpty { initialUrl })
    }
    var pageTitle by remember { mutableStateOf("متصفح المايكروتيك السريع — MC ProxyTier") }
    var loadProgress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var sslBypassedForSession by remember { mutableStateOf(false) }
    var httpAuthChallenge by remember { mutableStateOf<HttpAuthChallenge?>(null) }
    var cacheNoticeBanner by remember { mutableStateOf<String?>(null) }
    var autoRetryCount by remember(initialUrl) { mutableIntStateOf(0) }

    val activeIpKey = remember(currentUrl) {
        RouterIpCacheVault.normalizeIpKey(currentUrl).ifEmpty {
            RouterIpCacheVault.normalizeIpKey(initialUrl)
        }
    }

    val initialStats = remember(activeIpKey) {
        RouterIpCacheVault.warmIpCacheIntoRam(context, activeIpKey)
    }

    var cachedImagesCount by remember(activeIpKey) { mutableIntStateOf(initialStats.cachedImagesCount) }
    var cachedFilesCount by remember(activeIpKey) { mutableIntStateOf(initialStats.cachedFilesCount) }
    var cachedBytesSize by remember(activeIpKey) { mutableLongStateOf(initialStats.cachedBytesSize) }
    val loadedFromSavedCache = remember(activeIpKey) {
        initialStats.cachedImagesCount > 0 || initialStats.cachedFilesCount > 0
    }

    val turboAtomicFlag = remember { AtomicBoolean(weakNetTurboEnabled) }
    LaunchedEffect(weakNetTurboEnabled) {
        turboAtomicFlag.set(weakNetTurboEnabled)
    }

    val activeRelayPort = remember(telemetry.localRelayBindAddress, socksConfig.localRelayPort) {
        telemetry.localRelayBindAddress.substringAfter(":", "").toIntOrNull()
            ?: socksConfig.localRelayPort
    }

    val closePreservingCache = {
        try {
            webViewRef?.stopLoading()
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
        }
        onPageAndCacheUpdated(currentUrl, pageTitle)
        onDismiss()
    }

    Dialog(
        onDismissRequest = closePreservingCache,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            BackHandler {
                val wv = webViewRef
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    closePreservingCache()
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CyberObsidian)
                    .windowInsetsPadding(WindowInsets.systemBars),
                color = CyberObsidian
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CyberSurface)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Router,
                                    contentDescription = "أيقونة الراوتر",
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = pageTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (isProxyActive) {
                                            "SOCKS5 سريع عبر (${socksConfig.proxyIp}:${socksConfig.proxyPort}) • حفظ الكاش والصور مفعل ⚡"
                                        } else {
                                            "اتصال مباشر (بدون بروكسي) • حفظ الكاش والصور مفعل ⚡"
                                        },
                                        fontFamily = RobotoMonoFontFamily,
                                        fontSize = 10.sp,
                                        color = if (isProxyActive) EmeraldTunnel else ElectricCyan,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (sslBypassedForSession) {
                                Surface(
                                    color = AmberPulse.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LockOpen,
                                            contentDescription = "تجاوز SSL المحلي",
                                            tint = AmberPulse,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = "SSL OK",
                                            fontFamily = RobotoMonoFontFamily,
                                            fontSize = 9.sp,
                                            color = AmberPulse
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = closePreservingCache,
                                modifier = Modifier.testTag("browser_close_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "إغلاق المتصفح مع الاحتفاظ بالكاش المحفوظ",
                                    tint = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = if (weakNetTurboEnabled) EmeraldTunnel.copy(alpha = 0.18f) else CyberCard,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        1.dp,
                                        if (weakNetTurboEnabled) EmeraldTunnel else CyberBorder,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        val nextTurboState = !weakNetTurboEnabled
                                        turboAtomicFlag.set(nextTurboState)
                                        onToggleWeakNetTurbo(nextTurboState)
                                        webViewRef?.settings?.apply {
                                            offscreenPreRaster = nextTurboState
                                        }
                                        cacheNoticeBanner = if (nextTurboState) {
                                            "⚡ تسريع الإنترنت الضعيف + تحميل الصور الفوري من الكاش مفعل"
                                        } else {
                                            "تم إيقاف وضع تسريع الإنترنت الضعيف (مع بقاء الكاش المحفوظ)"
                                        }
                                    }
                                    .testTag("toggle_weak_internet_turbo_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bolt,
                                        contentDescription = null,
                                        tint = if (weakNetTurboEnabled) EmeraldTunnel else TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (weakNetTurboEnabled) {
                                            "تسريع الإنترنت + كاش الصور: مفعل ⚡"
                                        } else {
                                            "تسريع الإنترنت: متوقف"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (weakNetTurboEnabled) EmeraldTunnel else TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Surface(
                                color = CrimsonAlert.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, CrimsonAlert.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        onManualClearIpCache(activeIpKey)
                                        clearAllRouterPageCacheAndSession(context, webViewRef)
                                        cachedImagesCount = 0
                                        cachedFilesCount = 0
                                        cachedBytesSize = 0L
                                        cacheNoticeBanner = "🧹 تم مسح كاش هذا الـ IP ($activeIpKey) يدوياً وإعادة تحميله"
                                        val normalized = normalizeRouterUrl(addressBarInput)
                                        webViewRef?.loadUrl(normalized, buildTurboRequestHeaders(turboAtomicFlag.get()))
                                    }
                                    .testTag("manual_clear_cache_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteSweep,
                                        contentDescription = "مسح كاش هذا الـ IP يدوياً",
                                        tint = CrimsonAlert,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "مسح كاش الـ IP",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = !cacheNoticeBanner.isNullOrBlank()) {
                            Text(
                                text = cacheNoticeBanner.orEmpty(),
                                fontSize = 11.sp,
                                color = ElectricCyan,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { webViewRef?.goBack() },
                                enabled = canGoBack,
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("browser_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "رجوع",
                                    tint = if (canGoBack) Color.White else TextSecondary.copy(alpha = 0.4f)
                                )
                            }

                            IconButton(
                                onClick = { webViewRef?.goForward() },
                                enabled = canGoForward,
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("browser_forward_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "تقدم",
                                    tint = if (canGoForward) Color.White else TextSecondary.copy(alpha = 0.4f)
                                )
                            }

                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                OutlinedTextField(
                                    value = addressBarInput,
                                    onValueChange = { addressBarInput = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 12.sp,
                                        color = Color.White
                                    ),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(
                                        onGo = {
                                            val targetIpKey = RouterIpCacheVault.normalizeIpKey(addressBarInput)
                                            val normalized = RouterIpCacheVault.normalizeFullUrl(targetIpKey)
                                            val warmed = RouterIpCacheVault.warmIpCacheIntoRam(context, targetIpKey)
                                            cachedImagesCount = warmed.cachedImagesCount
                                            cachedFilesCount = warmed.cachedFilesCount
                                            cachedBytesSize = warmed.cachedBytesSize
                                            addressBarInput = targetIpKey
                                            currentUrl = normalized
                                            autoRetryCount = 0
                                            onPageAndCacheUpdated(targetIpKey, pageTitle)
                                            webViewRef?.settings?.cacheMode =
                                                if (warmed.cachedImagesCount > 0 || warmed.cachedFilesCount > 0) {
                                                    WebSettings.LOAD_CACHE_ELSE_NETWORK
                                                } else {
                                                    WebSettings.LOAD_DEFAULT
                                                }
                                            webViewRef?.loadUrl(
                                                normalized,
                                                buildTurboRequestHeaders(turboAtomicFlag.get())
                                            )
                                        }
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("browser_url_input"),
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            IconButton(
                                onClick = {
                                    val targetIpKey = RouterIpCacheVault.normalizeIpKey(addressBarInput)
                                    val currentIpKey = RouterIpCacheVault.normalizeIpKey(currentUrl)
                                    val normalized = RouterIpCacheVault.normalizeFullUrl(targetIpKey)
                                    autoRetryCount = 0
                                    if (targetIpKey.isNotEmpty() && !targetIpKey.equals(currentIpKey, ignoreCase = true)) {
                                        val warmed = RouterIpCacheVault.warmIpCacheIntoRam(context, targetIpKey)
                                        cachedImagesCount = warmed.cachedImagesCount
                                        cachedFilesCount = warmed.cachedFilesCount
                                        cachedBytesSize = warmed.cachedBytesSize
                                        addressBarInput = targetIpKey
                                        currentUrl = normalized
                                        onPageAndCacheUpdated(targetIpKey, pageTitle)
                                        webViewRef?.loadUrl(
                                            normalized,
                                            buildTurboRequestHeaders(turboAtomicFlag.get())
                                        )
                                    } else {
                                        webViewRef?.reload()
                                    }
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("browser_reload_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "تحديث الصفحة",
                                    tint = ElectricCyan
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = isLoading) {
                        LinearProgressIndicator(
                            progress = { (loadProgress / 100f).coerceIn(0.05f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = ElectricCyan,
                            trackColor = CyberCard
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .border(0.5.dp, CyberBorder)
                    ) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("router_webview"),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    setLayerType(View.LAYER_TYPE_HARDWARE, null)

                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        databaseEnabled = true
                                        loadsImagesAutomatically = true
                                        blockNetworkImage = false
                                        useWideViewPort = true
                                        loadWithOverviewMode = true
                                        builtInZoomControls = true
                                        displayZoomControls = false
                                        setSupportZoom(true)
                                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                        cacheMode = if (loadedFromSavedCache) {
                                            WebSettings.LOAD_CACHE_ELSE_NETWORK
                                        } else {
                                            WebSettings.LOAD_DEFAULT
                                        }
                                        offscreenPreRaster = weakNetTurboEnabled
                                    }

                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            loadProgress = newProgress
                                            isLoading = newProgress < 100
                                        }

                                        override fun onReceivedTitle(view: WebView?, title: String?) {
                                            if (!title.isNullOrBlank()) {
                                                pageTitle = title
                                                onPageAndCacheUpdated(currentUrl, title)
                                            }
                                        }
                                    }

                                    webViewClient = object : WebViewClient() {
                                        override fun shouldInterceptRequest(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): WebResourceResponse? {
                                            if (request == null) return null
                                            val reqUrl = request.url?.toString().orEmpty()
                                            val currentRouterKey = RouterIpCacheVault.normalizeIpKey(currentUrl)
                                                .ifEmpty { RouterIpCacheVault.normalizeIpKey(initialUrl) }
                                            val routerHostOnly = currentRouterKey.substringBefore(":")

                                            if (turboAtomicFlag.get() && !request.isForMainFrame) {
                                                if (shouldShortCircuitExternalTrackers(reqUrl, routerHostOnly)) {
                                                    return WebResourceResponse(
                                                        "text/plain",
                                                        "UTF-8",
                                                        200,
                                                        "OK",
                                                        mapOf("Cache-Control" to "max-age=3600"),
                                                        ByteArrayInputStream(ByteArray(0))
                                                    )
                                                }
                                            }

                                            if (!request.isForMainFrame && currentRouterKey.isNotEmpty()) {
                                                val vaultResponse = RouterIpCacheVault.interceptOrFetchAndCache(
                                                    context = ctx.applicationContext,
                                                    normalizedIpKey = currentRouterKey,
                                                    request = request,
                                                    isProxyActive = isProxyActive,
                                                    localRelayPort = activeRelayPort,
                                                    onNewResourceCached = {
                                                        val stats = RouterIpCacheVault.getCacheStats(
                                                            ctx.applicationContext,
                                                            currentRouterKey
                                                        )
                                                        cachedImagesCount = stats.cachedImagesCount
                                                        cachedFilesCount = stats.cachedFilesCount
                                                        cachedBytesSize = stats.cachedBytesSize
                                                    }
                                                )
                                                if (vaultResponse != null) {
                                                    return vaultResponse
                                                }
                                            }

                                            return super.shouldInterceptRequest(view, request)
                                        }

                                        override fun onPageStarted(
                                            view: WebView?,
                                            url: String?,
                                            favicon: Bitmap?
                                        ) {
                                            isLoading = true
                                            url?.let {
                                                currentUrl = it
                                                addressBarInput = RouterIpCacheVault.normalizeIpKey(it).ifEmpty { it }
                                            }
                                            canGoBack = view?.canGoBack() == true
                                            canGoForward = view?.canGoForward() == true
                                        }

                                        override fun onPageCommitVisible(view: WebView?, url: String?) {
                                            super.onPageCommitVisible(view, url)
                                            if (turboAtomicFlag.get()) {
                                                view?.evaluateJavascript(
                                                    "(function(){try{var s=document.createElement('style');s.innerHTML='*,*::before,*::after{transition-duration:0s !important;animation-duration:0s !important;} img{content-visibility:auto;}';document.head.appendChild(s);}catch(e){}})();",
                                                    null
                                                )
                                            }
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            isLoading = false
                                            url?.let {
                                                currentUrl = it
                                                addressBarInput = RouterIpCacheVault.normalizeIpKey(it).ifEmpty { it }
                                            }
                                            canGoBack = view?.canGoBack() == true
                                            canGoForward = view?.canGoForward() == true

                                            val key = RouterIpCacheVault.normalizeIpKey(currentUrl)
                                                .ifEmpty { RouterIpCacheVault.normalizeIpKey(initialUrl) }
                                            val latestStats = RouterIpCacheVault.getCacheStats(ctx.applicationContext, key)
                                            cachedImagesCount = latestStats.cachedImagesCount
                                            cachedFilesCount = latestStats.cachedFilesCount
                                            cachedBytesSize = latestStats.cachedBytesSize

                                            onPageAndCacheUpdated(key, pageTitle)
                                        }

                                        @SuppressLint("WebViewClientOnReceivedSslError")
                                        override fun onReceivedSslError(
                                            view: WebView?,
                                            handler: SslErrorHandler?,
                                            error: SslError?
                                        ) {
                                            sslBypassedForSession = true
                                            VpnSocketProtector.log(
                                                "ROUTER-SSL",
                                                "تم تجاوز خطأ شهادة SSL الذاتية للراوتر (${error?.url ?: currentUrl})",
                                                LogLevel.WARN
                                            )
                                            handler?.proceed()
                                        }

                                        override fun onReceivedHttpAuthRequest(
                                            view: WebView?,
                                            handler: HttpAuthHandler?,
                                            host: String?,
                                            realm: String?
                                        ) {
                                            if (handler != null) {
                                                httpAuthChallenge = HttpAuthChallenge(
                                                    host = host.orEmpty(),
                                                    realm = realm.orEmpty(),
                                                    handler = handler
                                                )
                                            } else {
                                                super.onReceivedHttpAuthRequest(view, null, host, realm)
                                            }
                                        }

                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?
                                        ) {
                                            if (request?.isForMainFrame == true) {
                                                VpnSocketProtector.log(
                                                    "WEBVIEW-ERR",
                                                    "خطأ تحميل ${request.url}: ${error?.description}",
                                                    LogLevel.WARN
                                                )
                                                if (autoRetryCount < 2 && view != null) {
                                                    autoRetryCount += 1
                                                    val retryUrl = request.url?.toString() ?: initialUrl
                                                    val retryDelayMs = if (autoRetryCount == 1) 400L else 900L
                                                    view.postDelayed({
                                                        view.loadUrl(
                                                            retryUrl,
                                                            buildTurboRequestHeaders(turboAtomicFlag.get())
                                                        )
                                                    }, retryDelayMs)
                                                }
                                            }
                                        }
                                    }

                                    loadUrl(initialUrl, buildTurboRequestHeaders(weakNetTurboEnabled))
                                    webViewRef = this
                                }
                            }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CyberSurface)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = null,
                                tint = EmeraldTunnel,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "كاش الـ IP المحفوظ: $cachedImagesCount صورة • $cachedFilesCount ملف (${formatBytesShort(cachedBytesSize)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldTunnel,
                                fontSize = 10.sp
                            )
                        }
                        Text(
                            text = "TX: ${formatBytesShort(telemetry.bytesSent)} | RX: ${formatBytesShort(telemetry.bytesReceived)}",
                            fontFamily = RobotoMonoFontFamily,
                            fontSize = 10.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            httpAuthChallenge?.let { challenge ->
                var routerUser by remember { mutableStateOf("admin") }
                var routerPass by remember { mutableStateOf("") }

                AlertDialog(
                    onDismissRequest = {
                        challenge.handler.cancel()
                        httpAuthChallenge = null
                    },
                    containerColor = CyberCard,
                    title = {
                        Text(
                            text = "تسجيل دخول الراوتر (${challenge.host})",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (challenge.realm.isNotBlank()) {
                                Text(
                                    text = "المنطقة (Realm): ${challenge.realm}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                            OutlinedTextField(
                                value = routerUser,
                                onValueChange = { routerUser = it },
                                label = { Text("اسم مستخدم الراوتر") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = routerPass,
                                onValueChange = { routerPass = it },
                                label = { Text("كلمة مرور الراوتر") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                challenge.handler.proceed(routerUser, routerPass)
                                httpAuthChallenge = null
                            }
                        ) {
                            Text("دخول")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                challenge.handler.cancel()
                                httpAuthChallenge = null
                            }
                        ) {
                            Text("إلغاء")
                        }
                    }
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewRef?.stopLoading()
                CookieManager.getInstance().flush()
                webViewRef?.destroy()
            } catch (_: Exception) {
            }
            webViewRef = null
        }
    }
}

private fun normalizeRouterUrl(raw: String): String {
    val t = raw.trim()
    return if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) {
        t
    } else {
        "http://$t"
    }
}

fun formatBytesShort(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.2f MB", mb)
}
