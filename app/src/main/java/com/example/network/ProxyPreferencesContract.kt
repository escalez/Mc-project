package com.example.network

/**
 * واجهة مستقلة خاصة بحفظ واسترجاع إعدادات البروكسيات والايبيات المحفوظة (Preferences Contract).
 */
interface ProxyPreferencesContract {
    fun loadSavedRouterIps(): List<SavedRouterIpEntry>
    fun saveRouterIpsList(ips: List<SavedRouterIpEntry>)
    fun loadSavedProxies(): List<SavedProxyProfile>
    fun saveProxiesList(proxies: List<SavedProxyProfile>)
    fun loadSelectedProxyId(): String?
    fun saveSelectedProxyId(id: String?)
    fun loadQuickIp(): String
    fun saveQuickIp(ip: String)
    fun loadWeakNetTurboEnabled(): Boolean
    fun saveWeakNetTurboEnabled(enabled: Boolean)
    fun loadInternalDnsEnabled(): Boolean
    fun saveInternalDnsEnabled(enabled: Boolean)
}
