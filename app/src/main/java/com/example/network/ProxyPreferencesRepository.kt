package com.example.network

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * ProxyPreferencesRepository (implements ProxyPreferencesContract)
 * Persists multiple saved SOCKS5 proxy profiles, the selected proxy ID, and saved router IPs with cache stats.
 */
class ProxyPreferencesRepository(context: Context) : ProxyPreferencesContract {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "nettunnel_standalone_proxies_v2"
        private const val KEY_SAVED_PROXIES_JSON = "saved_proxies_json"
        private const val KEY_SELECTED_PROXY_ID = "selected_proxy_id"
        private const val KEY_QUICK_IP = "quick_router_ip_v2"
        private const val KEY_WEAK_NET_TURBO = "weak_net_turbo_enabled_v1"
        private const val KEY_INTERNAL_DNS_ENABLED = "internal_dns_enabled_v1"
        private const val KEY_SAVED_ROUTER_IPS_JSON = "saved_router_ips_with_cache_v1"
    }

    override fun loadSavedRouterIps(): List<SavedRouterIpEntry> {
        val rawJson = prefs.getString(KEY_SAVED_ROUTER_IPS_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            val mapByNormalizedKey = LinkedHashMap<String, SavedRouterIpEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val rawDisplay = obj.optString("displayIp", "").trim()
                val normalizedKey = obj.optString(
                    "normalizedKey",
                    RouterIpCacheVault.normalizeIpKey(rawDisplay)
                ).trim().lowercase()
                if (normalizedKey.isEmpty()) continue
                val fullUrl = obj.optString("fullUrl", RouterIpCacheVault.normalizeFullUrl(normalizedKey))
                if (!mapByNormalizedKey.containsKey(normalizedKey)) {
                    mapByNormalizedKey[normalizedKey] = SavedRouterIpEntry(
                        id = obj.optString("id", "ip_${normalizedKey.hashCode()}"),
                        normalizedKey = normalizedKey,
                        displayIp = rawDisplay.ifEmpty { normalizedKey },
                        fullUrl = fullUrl,
                        pageTitle = obj.optString("pageTitle", ""),
                        cachedImagesCount = obj.optInt("cachedImagesCount", 0),
                        cachedFilesCount = obj.optInt("cachedFilesCount", 0),
                        cachedBytesSize = obj.optLong("cachedBytesSize", 0L),
                        openCount = obj.optInt("openCount", 1).coerceAtLeast(1),
                        lastOpenedTimestamp = obj.optLong("lastOpenedTimestamp", System.currentTimeMillis())
                    )
                }
            }
            mapByNormalizedKey.values.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun saveRouterIpsList(ips: List<SavedRouterIpEntry>) {
        val deduplicated = LinkedHashMap<String, SavedRouterIpEntry>()
        for (item in ips) {
            val key = item.normalizedKey.trim().lowercase()
            if (key.isNotEmpty() && !deduplicated.containsKey(key)) {
                deduplicated[key] = item
            }
        }
        val arr = JSONArray()
        for (entry in deduplicated.values) {
            val obj = JSONObject().apply {
                put("id", entry.id)
                put("normalizedKey", entry.normalizedKey)
                put("displayIp", entry.displayIp)
                put("fullUrl", entry.fullUrl)
                put("pageTitle", entry.pageTitle)
                put("cachedImagesCount", entry.cachedImagesCount)
                put("cachedFilesCount", entry.cachedFilesCount)
                put("cachedBytesSize", entry.cachedBytesSize)
                put("openCount", entry.openCount)
                put("lastOpenedTimestamp", entry.lastOpenedTimestamp)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_SAVED_ROUTER_IPS_JSON, arr.toString()).apply()
    }

    override fun loadSavedProxies(): List<SavedProxyProfile> {
        val rawJson = prefs.getString(KEY_SAVED_PROXIES_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            val list = ArrayList<SavedProxyProfile>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val ip = obj.optString("proxyIp", "").trim()
                val port = obj.optInt("proxyPort", 0)
                if (ip.isNotEmpty() && port in 1..65535) {
                    list.add(
                        SavedProxyProfile(
                            id = obj.optString("id", "proxy_${System.nanoTime()}_$i"),
                            name = obj.optString("name", ""),
                            proxyIp = ip,
                            proxyPort = port,
                            authEnabled = obj.optBoolean("authEnabled", false),
                            username = obj.optString("username", ""),
                            password = obj.optString("password", "")
                        )
                    )
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun saveProxiesList(proxies: List<SavedProxyProfile>) {
        val arr = JSONArray()
        for (p in proxies) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name.trim())
                put("proxyIp", p.proxyIp.trim())
                put("proxyPort", p.proxyPort)
                put("authEnabled", p.authEnabled)
                put("username", p.username.trim())
                put("password", p.password)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_SAVED_PROXIES_JSON, arr.toString()).apply()
    }

    override fun loadSelectedProxyId(): String? {
        return prefs.getString(KEY_SELECTED_PROXY_ID, null)
    }

    override fun saveSelectedProxyId(id: String?) {
        prefs.edit().putString(KEY_SELECTED_PROXY_ID, id).apply()
    }

    override fun loadQuickIp(): String {
        return prefs.getString(KEY_QUICK_IP, "") ?: ""
    }

    override fun saveQuickIp(ip: String) {
        prefs.edit().putString(KEY_QUICK_IP, ip.trim()).apply()
    }

    override fun loadWeakNetTurboEnabled(): Boolean {
        return prefs.getBoolean(KEY_WEAK_NET_TURBO, true)
    }

    override fun saveWeakNetTurboEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WEAK_NET_TURBO, enabled).apply()
    }

    override fun loadInternalDnsEnabled(): Boolean {
        return prefs.getBoolean(KEY_INTERNAL_DNS_ENABLED, true)
    }

    override fun saveInternalDnsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INTERNAL_DNS_ENABLED, enabled).apply()
    }
}
