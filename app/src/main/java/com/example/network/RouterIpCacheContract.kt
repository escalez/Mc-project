package com.example.network

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/**
 * واجهة مستقلة خاصة بخزنة حفظ الايبيات وكاش الصور والملفات (Router IP Cache Contract).
 */
interface RouterIpCacheContract {
    fun normalizeIpKey(rawIpOrUrl: String): String
    fun normalizeFullUrl(rawIpOrUrl: String): String
    fun isCacheableRouterResource(requestUrl: String, acceptHeader: String = ""): Boolean
    fun isImageResource(requestUrl: String, mimeType: String = "", acceptHeader: String = ""): Boolean
    fun warmIpCacheIntoRam(context: Context, normalizedIpKey: String): IpCacheStats
    fun getCacheStats(context: Context, normalizedIpKey: String): IpCacheStats
    fun clearCacheForIp(context: Context, normalizedIpKey: String)
    fun clearAllIpsCache(context: Context)
    fun interceptOrFetchAndCache(
        context: Context,
        normalizedIpKey: String,
        request: WebResourceRequest,
        isProxyActive: Boolean,
        localRelayPort: Int,
        onNewResourceCached: (RouterIpCacheVault.CachedAsset) -> Unit = {}
    ): WebResourceResponse?
}
