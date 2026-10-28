package com.example.network

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

/**
 * RouterIpCacheVault
 *
 * Persistent Disk + RAM Cache Engine per Router IP:
 * - Automatically saves images (.png, .jpg, .jpeg, .gif, .webp, .svg, .ico, .bmp) and static
 *   router assets (.css, .js, .woff, .woff2, .ttf) for every IP opened in the WebView.
 * - When an already-saved IP is opened again, serves all cached images and assets in 0ms
 *   directly from RAM/Disk without re-downloading over Mobile Data + ZeroTier.
 * - Strictly deduplicates IPs using `normalizeIpKey(rawIpOrUrl)` so opening the same IP
 *   never creates duplicate entries in the Saved IPs list.
 */
object RouterIpCacheVault : RouterIpCacheContract {

    data class CachedAsset(
        val url: String,
        val mimeType: String,
        val encoding: String,
        val bytes: ByteArray,
        val isImage: Boolean,
        val savedAt: Long
    )

    private const val VAULT_ROOT_DIR = "router_ip_cache_v1"
    private const val MAX_SINGLE_ASSET_BYTES = 8 * 1024 * 1024 // 8 MB max per single file

    // Fast 0ms in-memory cache indexed by "$normalizedIpKey|$normalizedAssetKey"
    private val ramVault = ConcurrentHashMap<String, CachedAsset>()

    private val IMAGE_EXTENSIONS = listOf(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".bmp", ".avif"
    )

    private val STATIC_ASSET_EXTENSIONS = listOf(
        ".css", ".js", ".woff", ".woff2", ".ttf", ".eot", ".otf"
    )

    /**
     * Normalizes any user-entered IP or URL into a canonical deduplication key (`host` or `host:port`).
     * Examples:
     * - "192.168.88.1" -> "192.168.88.1"
     * - "http://192.168.88.1/webfig/#Wireless" -> "192.168.88.1"
     * - "http://10.147.17.1:8080/login" -> "10.147.17.1:8080"
     */
    override fun normalizeIpKey(rawIpOrUrl: String): String {
        var clean = rawIpOrUrl.trim()
        if (clean.isEmpty()) return ""
        clean = clean.removePrefix("http://").removePrefix("https://")
            .removePrefix("HTTP://").removePrefix("HTTPS://")
        val authority = clean
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore("#")
            .trim()
            .lowercase()

        return when {
            authority.endsWith(":80") -> authority.removeSuffix(":80")
            authority.endsWith(":443") -> authority.removeSuffix(":443")
            else -> authority
        }
    }

    /**
     * Normalizes user input into a clean root HTTP/HTTPS URL containing ONLY the IP/host
     * without any sub-paths, query strings, or hash fragments (e.g. "http://192.168.88.1").
     */
    override fun normalizeFullUrl(rawIpOrUrl: String): String {
        val trimmed = rawIpOrUrl.trim()
        if (trimmed.isEmpty()) return ""
        val isHttps = trimmed.startsWith("https://", ignoreCase = true)
        val cleanIpKey = normalizeIpKey(trimmed)
        if (cleanIpKey.isEmpty()) return ""
        return if (isHttps) "https://$cleanIpKey" else "http://$cleanIpKey"
    }

    /**
     * Determines whether a request is for a router image or static asset that should be
     * persistently cached in the IP Vault.
     */
    override fun isCacheableRouterResource(requestUrl: String, acceptHeader: String): Boolean {
        val pathOnly = requestUrl
            .substringBefore("?")
            .substringBefore("#")
            .lowercase()

        if (IMAGE_EXTENSIONS.any { pathOnly.endsWith(it) }) return true
        if (STATIC_ASSET_EXTENSIONS.any { pathOnly.endsWith(it) }) return true

        val lowerAccept = acceptHeader.lowercase()
        if (lowerAccept.startsWith("image/") ||
            lowerAccept.contains("image/webp") ||
            lowerAccept.contains("image/apng") ||
            lowerAccept.contains("text/css") ||
            lowerAccept.contains("application/javascript")
        ) {
            // Avoid caching dynamic HTML documents as images just because Accept contains */*
            if (!lowerAccept.contains("text/html") && !lowerAccept.contains("application/xhtml+xml")) {
                return true
            }
        }
        return false
    }

    override fun isImageResource(requestUrl: String, mimeType: String, acceptHeader: String): Boolean {
        val pathOnly = requestUrl
            .substringBefore("?")
            .substringBefore("#")
            .lowercase()
        if (IMAGE_EXTENSIONS.any { pathOnly.endsWith(it) }) return true
        if (mimeType.lowercase().startsWith("image/")) return true
        val lowerAccept = acceptHeader.lowercase()
        if (lowerAccept.startsWith("image/") && !lowerAccept.contains("text/html")) return true
        return false
    }

    /**
     * Normalizes an asset URL so that transient query timestamps on static images/scripts
     * still hit the cached asset for that IP.
     */
    fun normalizeAssetCacheKey(requestUrl: String): String {
        val noFragment = requestUrl.substringBefore("#").trim()
        val pathOnly = noFragment.substringBefore("?")
        val lowerPath = pathOnly.lowercase()
        return if (IMAGE_EXTENSIONS.any { lowerPath.endsWith(it) } ||
            STATIC_ASSET_EXTENSIONS.any { lowerPath.endsWith(it) }
        ) {
            pathOnly
        } else {
            noFragment
        }
    }

    private fun getIpVaultDir(context: Context, normalizedIpKey: String): File {
        val safeFolder = normalizedIpKey
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "default_ip" }
        val dir = File(File(context.filesDir, VAULT_ROOT_DIR), safeFolder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /**
     * Stores an image or static resource into both 0ms RAM cache and persistent disk vault for `normalizedIpKey`.
     */
    fun putResource(
        context: Context,
        normalizedIpKey: String,
        requestUrl: String,
        mimeType: String,
        encoding: String = "UTF-8",
        bytes: ByteArray
    ): CachedAsset? {
        if (normalizedIpKey.isBlank() || bytes.isEmpty() || bytes.size > MAX_SINGLE_ASSET_BYTES) {
            return null
        }
        val cleanMime = mimeType.substringBefore(";").trim().ifBlank {
            guessMimeTypeFromUrl(requestUrl)
        }
        val isImg = isImageResource(requestUrl, cleanMime)
        val assetKey = normalizeAssetCacheKey(requestUrl)
        val ramKey = "$normalizedIpKey|$assetKey"
        val now = System.currentTimeMillis()

        val asset = CachedAsset(
            url = assetKey,
            mimeType = cleanMime,
            encoding = encoding.ifBlank { "UTF-8" },
            bytes = bytes,
            isImage = isImg,
            savedAt = now
        )
        ramVault[ramKey] = asset

        try {
            val dir = getIpVaultDir(context, normalizedIpKey)
            val hash = sha256Hex(assetKey)
            val binFile = File(dir, "$hash.bin")
            val metaFile = File(dir, "$hash.meta")

            binFile.writeBytes(bytes)
            val metaJson = JSONObject().apply {
                put("url", assetKey)
                put("mimeType", asset.mimeType)
                put("encoding", asset.encoding)
                put("isImage", asset.isImage)
                put("size", bytes.size)
                put("savedAt", now)
            }
            metaFile.writeText(metaJson.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }

        return asset
    }

    /**
     * Retrieves a cached image or static asset in 0ms from RAM, or loads it from the disk vault into RAM.
     */
    fun getResource(
        context: Context,
        normalizedIpKey: String,
        requestUrl: String
    ): CachedAsset? {
        if (normalizedIpKey.isBlank()) return null
        val assetKey = normalizeAssetCacheKey(requestUrl)
        val ramKey = "$normalizedIpKey|$assetKey"

        ramVault[ramKey]?.let { return it }

        return try {
            val dir = getIpVaultDir(context, normalizedIpKey)
            val hash = sha256Hex(assetKey)
            val binFile = File(dir, "$hash.bin")
            val metaFile = File(dir, "$hash.meta")

            if (!binFile.exists() || !metaFile.exists()) return null

            val bytes = binFile.readBytes()
            if (bytes.isEmpty()) return null

            val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
            val mimeType = meta.optString("mimeType", guessMimeTypeFromUrl(requestUrl))
            val encoding = meta.optString("encoding", "UTF-8")
            val isImage = meta.optBoolean("isImage", isImageResource(requestUrl, mimeType))
            val savedAt = meta.optLong("savedAt", binFile.lastModified())

            val loaded = CachedAsset(
                url = assetKey,
                mimeType = mimeType,
                encoding = encoding,
                bytes = bytes,
                isImage = isImage,
                savedAt = savedAt
            )
            ramVault[ramKey] = loaded
            loaded
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Warms all disk-cached resources for `normalizedIpKey` into RAM so opening a saved IP
     * serves every image in 0.0ms without even waiting for disk I/O.
     */
    override fun warmIpCacheIntoRam(context: Context, normalizedIpKey: String): IpCacheStats {
        if (normalizedIpKey.isBlank()) return IpCacheStats()
        var imagesCount = 0
        var filesCount = 0
        var totalBytes = 0L

        try {
            val dir = getIpVaultDir(context, normalizedIpKey)
            val metaFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".meta") } ?: emptyArray()
            for (metaFile in metaFiles) {
                try {
                    val baseHash = metaFile.name.removeSuffix(".meta")
                    val binFile = File(dir, "$baseHash.bin")
                    if (!binFile.exists()) continue

                    val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
                    val assetUrl = meta.optString("url", "")
                    val mimeType = meta.optString("mimeType", "application/octet-stream")
                    val encoding = meta.optString("encoding", "UTF-8")
                    val isImage = meta.optBoolean("isImage", isImageResource(assetUrl, mimeType))
                    val savedAt = meta.optLong("savedAt", binFile.lastModified())
                    val bytes = binFile.readBytes()

                    if (bytes.isNotEmpty() && assetUrl.isNotEmpty()) {
                        val ramKey = "$normalizedIpKey|$assetUrl"
                        ramVault[ramKey] = CachedAsset(
                            url = assetUrl,
                            mimeType = mimeType,
                            encoding = encoding,
                            bytes = bytes,
                            isImage = isImage,
                            savedAt = savedAt
                        )
                        if (isImage) imagesCount++ else filesCount++
                        totalBytes += bytes.size
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        return IpCacheStats(
            cachedImagesCount = imagesCount,
            cachedFilesCount = filesCount,
            cachedBytesSize = totalBytes
        )
    }

    /**
     * Returns current cache statistics (images count, static files count, total bytes) for `normalizedIpKey`.
     */
    override fun getCacheStats(context: Context, normalizedIpKey: String): IpCacheStats {
        if (normalizedIpKey.isBlank()) return IpCacheStats()
        var imagesCount = 0
        var filesCount = 0
        var totalBytes = 0L

        try {
            val dir = getIpVaultDir(context, normalizedIpKey)
            val metaFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".meta") } ?: emptyArray()
            for (metaFile in metaFiles) {
                try {
                    val baseHash = metaFile.name.removeSuffix(".meta")
                    val binFile = File(dir, "$baseHash.bin")
                    if (!binFile.exists()) continue
                    val meta = JSONObject(metaFile.readText(Charsets.UTF_8))
                    val assetUrl = meta.optString("url", "")
                    val mimeType = meta.optString("mimeType", "")
                    val isImage = meta.optBoolean("isImage", isImageResource(assetUrl, mimeType))
                    val len = binFile.length()
                    if (len > 0L) {
                        if (isImage) imagesCount++ else filesCount++
                        totalBytes += len
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        return IpCacheStats(
            cachedImagesCount = imagesCount,
            cachedFilesCount = filesCount,
            cachedBytesSize = totalBytes
        )
    }

    /**
     * Deletes all cached images and files for a single IP key.
     */
    override fun clearCacheForIp(context: Context, normalizedIpKey: String) {
        if (normalizedIpKey.isBlank()) return
        val prefix = "$normalizedIpKey|"
        val keysToRemove = ramVault.keys.filter { it.startsWith(prefix) }
        keysToRemove.forEach { ramVault.remove(it) }

        try {
            val dir = getIpVaultDir(context, normalizedIpKey)
            dir.deleteRecursively()
        } catch (_: Exception) {
        }
    }

    /**
     * Deletes all cached router IP vaults on disk and RAM.
     */
    override fun clearAllIpsCache(context: Context) {
        ramVault.clear()
        try {
            val root = File(context.filesDir, VAULT_ROOT_DIR)
            if (root.exists()) {
                root.deleteRecursively()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Intercepts WebView sub-resource requests for images and static assets:
     * 1. If already cached in `RouterIpCacheVault` -> returns 0ms `WebResourceResponse` from RAM/Disk!
     * 2. If not cached yet -> fetches through the active SOCKS5 local relay (or direct if proxy is off),
     *    stores it in `RouterIpCacheVault` (RAM + Disk) so images never re-download again, and returns it.
     */
    override fun interceptOrFetchAndCache(
        context: Context,
        normalizedIpKey: String,
        request: WebResourceRequest,
        isProxyActive: Boolean,
        localRelayPort: Int,
        onNewResourceCached: (CachedAsset) -> Unit
    ): WebResourceResponse? {
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val reqUrl = request.url?.toString().orEmpty()
        if (reqUrl.isBlank() || (!reqUrl.startsWith("http://", true) && !reqUrl.startsWith("https://", true))) {
            return null
        }

        val acceptHeader = request.requestHeaders["Accept"] ?: request.requestHeaders["accept"] ?: ""
        if (!isCacheableRouterResource(reqUrl, acceptHeader)) {
            return null
        }

        // 1. Check 0ms RAM / Disk Cache Hit first!
        val cached = getResource(context, normalizedIpKey, reqUrl)
        if (cached != null && cached.bytes.isNotEmpty()) {
            return buildImmutableWebResponse(cached)
        }

        // 2. Cache Miss on first visit -> Fetch via active route (SOCKS5 relay or Direct) and persist to Vault
        return try {
            val downloaded = fetchResourceBytes(
                requestUrl = reqUrl,
                requestHeaders = request.requestHeaders,
                isProxyActive = isProxyActive,
                localRelayPort = localRelayPort
            ) ?: return null

            val saved = putResource(
                context = context,
                normalizedIpKey = normalizedIpKey,
                requestUrl = reqUrl,
                mimeType = downloaded.mimeType,
                encoding = downloaded.encoding,
                bytes = downloaded.bytes
            )
            if (saved != null) {
                onNewResourceCached(saved)
                buildImmutableWebResponse(saved)
            } else {
                null
            }
        } catch (_: Exception) {
            // Fall back to WebView's native loader if custom fetch fails
            null
        }
    }

    private fun buildImmutableWebResponse(asset: CachedAsset): WebResourceResponse {
        val responseHeaders = mapOf(
            "Cache-Control" to "public, max-age=31536000, immutable",
            "Access-Control-Allow-Origin" to "*",
            "X-ProxyTier-Cache" to "HIT-0MS"
        )
        return WebResourceResponse(
            asset.mimeType,
            asset.encoding,
            200,
            "OK",
            responseHeaders,
            ByteArrayInputStream(asset.bytes)
        )
    }

    private data class DownloadedPayload(
        val bytes: ByteArray,
        val mimeType: String,
        val encoding: String
    )

    private fun fetchResourceBytes(
        requestUrl: String,
        requestHeaders: Map<String, String>?,
        isProxyActive: Boolean,
        localRelayPort: Int
    ): DownloadedPayload? {
        val urlObj = URL(requestUrl)
        val proxy = if (isProxyActive && localRelayPort in 1..65535) {
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localRelayPort))
        } else {
            Proxy.NO_PROXY
        }

        val conn = (urlObj.openConnection(proxy) as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 5_000
            readTimeout = 8_000
            requestMethod = "GET"
            useCaches = true

            if (this is HttpsURLConnection) {
                 configureTrustAllSsl(this)
            }

            setRequestProperty("Connection", "keep-alive")
            setRequestProperty("Accept-Encoding", "gzip, deflate")

            requestHeaders?.forEach { (k, v) ->
                if (!k.equals("Cache-Control", ignoreCase = true) &&
                    !k.equals("Pragma", ignoreCase = true) &&
                    !k.equals("If-Modified-Since", ignoreCase = true) &&
                    !k.equals("If-None-Match", ignoreCase = true)
                ) {
                    setRequestProperty(k, v)
                }
            }

            try {
                val cookie = CookieManager.getInstance().getCookie(requestUrl)
                if (!cookie.isNullOrBlank()) {
                    setRequestProperty("Cookie", cookie)
                }
            } catch (_: Exception) {
            }
        }

        return try {
            val status = conn.responseCode
            if (status !in 200..299) {
                return null
            }

            val contentEncoding = conn.contentEncoding?.lowercase().orEmpty()
            val rawStream = conn.inputStream
            val decodedStream = when {
                contentEncoding.contains("gzip") -> GZIPInputStream(rawStream)
                contentEncoding.contains("deflate") -> InflaterInputStream(rawStream)
                else -> rawStream
            }

            val bytes = decodedStream.use { it.readBytes() }
            if (bytes.isEmpty() || bytes.size > MAX_SINGLE_ASSET_BYTES) {
                return null
            }

            val rawContentType = conn.contentType.orEmpty()
            val mimeType = rawContentType.substringBefore(";").trim()
                .ifBlank { guessMimeTypeFromUrl(requestUrl) }
            val charset = if (rawContentType.contains("charset=", ignoreCase = true)) {
                rawContentType.substringAfter("charset=", "UTF-8").substringBefore(";").trim()
            } else {
                "UTF-8"
            }

            DownloadedPayload(
                bytes = bytes,
                mimeType = mimeType,
                encoding = charset
            )
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun configureTrustAllSsl(https: HttpsURLConnection) {
        try {
            val trustAllCerts = arrayOf<TrustManager>(
                @Suppress("CustomX509TrustManager")
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
            )
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            https.sslSocketFactory = sslContext.socketFactory
            https.hostnameVerifier = HostnameVerifier { _, _ -> true }
        } catch (_: Exception) {
        }
    }

    fun guessMimeTypeFromUrl(url: String): String {
        val path = url.substringBefore("?").substringBefore("#").lowercase()
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".ico") -> "image/x-icon"
            path.endsWith(".bmp") -> "image/bmp"
            path.endsWith(".avif") -> "image/avif"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".ttf") -> "font/ttf"
            else -> "application/octet-stream"
        }
    }
}
