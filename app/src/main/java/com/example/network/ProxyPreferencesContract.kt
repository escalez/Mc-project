package com.example.network

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
