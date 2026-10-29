package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.network.ActiveSocketChannel
import com.example.network.ConnectionStatus
import com.example.network.DnsResolverContract
import com.example.network.InternalDnsResolver
import com.example.network.LocalSocksAuthRelay
import com.example.network.LogLevel
import com.example.network.PacketLogEntry
import com.example.network.ProxyLogContract
import com.example.network.ProxyPreferencesContract
import com.example.network.ProxyPreferencesRepository
import com.example.network.ProxyRelayEngineContract
import com.example.network.RouterIpCacheContract
import com.example.network.RouterIpCacheVault
import com.example.network.RoutingMode
import com.example.network.SavedProxyProfile
import com.example.network.SavedRouterIpEntry
import com.example.network.Socks5Config
import com.example.network.TunnelTelemetry
import com.example.network.VpnSocketProtector
import com.example.network.WebViewProxyConfigurator
import com.example.network.WebViewProxyContract
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

data class MainUiState(
    val formNameInput: String = "",
    val socksConfig: Socks5Config = Socks5Config(),
    val editingProxyId: String? = null,
    val savedProxies: List<SavedProxyProfile> = emptyList(),
    val activeRunningProxyId: String? = null,
    val selectedProxyId: String? = null,
    val routingMode: RoutingMode = RoutingMode.STANDALONE_PROXY_ZEROTIER,
    val savedRouterIps: List<SavedRouterIpEntry> = emptyList(),
    val quickIpInput: String = "",
    val activeBrowserUrl: String? = null,
    val weakNetTurboEnabled: Boolean = true,
    val internalDnsEnabled: Boolean = true,
    val isTestingHandshake: Boolean = false,
    val handshakeBannerMessage: String? = null,
    val handshakeBannerSuccess: Boolean = true,
    val snackbarMessage: String? = null
) {
    val activeProxyProfile: SavedProxyProfile?
        get() = savedProxies.find { it.id == activeRunningProxyId }

    val selectedOrFirstProfile: SavedProxyProfile?
        get() = savedProxies.find { it.id == activeRunningProxyId }
            ?: savedProxies.find { it.id == selectedProxyId }
            ?: savedProxies.firstOrNull()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appCtx = application.applicationContext
    private val prefsRepo: ProxyPreferencesContract = ProxyPreferencesRepository(appCtx)
    private val localRelay: ProxyRelayEngineContract = LocalSocksAuthRelay(appCtx)
    private val dnsResolver: DnsResolverContract = InternalDnsResolver
    private val logger: ProxyLogContract = VpnSocketProtector
    private val webViewProxy: WebViewProxyContract = WebViewProxyConfigurator
    private val ipCacheVault: RouterIpCacheContract = RouterIpCacheVault

    private var proxyControlJob: Job? = null

    private val _uiState: MutableStateFlow<MainUiState>

    init {
        val initialSaved = prefsRepo.loadSavedProxies()
        val initialSelectedId = prefsRepo.loadSelectedProxyId() ?: initialSaved.firstOrNull()?.id
        val initialWeakNetTurbo = prefsRepo.loadWeakNetTurboEnabled()
        val initialInternalDns = prefsRepo.loadInternalDnsEnabled()
        val initialSavedIps = prefsRepo.loadSavedRouterIps().mapNotNull { entry ->
            val cleanIpKey = RouterIpCacheVault.normalizeIpKey(entry.normalizedKey.ifBlank { entry.displayIp })
            if (cleanIpKey.isEmpty()) return@mapNotNull null
            val cleanRootUrl = RouterIpCacheVault.normalizeFullUrl(cleanIpKey)
            val diskStats = RouterIpCacheVault.getCacheStats(appCtx, cleanIpKey)
            entry.copy(
                normalizedKey = cleanIpKey,
                displayIp = cleanIpKey,
                fullUrl = cleanRootUrl,
                cachedImagesCount = if (diskStats.cachedImagesCount > 0 || diskStats.cachedFilesCount > 0) {
                    diskStats.cachedImagesCount
                } else {
                    entry.cachedImagesCount
                },
                cachedFilesCount = if (diskStats.cachedImagesCount > 0 || diskStats.cachedFilesCount > 0) {
                    diskStats.cachedFilesCount
                } else {
                    entry.cachedFilesCount
                },
                cachedBytesSize = if (diskStats.cachedImagesCount > 0 || diskStats.cachedFilesCount > 0) {
                    diskStats.cachedBytesSize
                } else {
                    entry.cachedBytesSize
                }
            )
        }.distinctBy { it.normalizedKey }

        val initialCleanQuickIp = RouterIpCacheVault.normalizeIpKey(prefsRepo.loadQuickIp())

        InternalDnsResolver.setEnabled(initialInternalDns)
        localRelay.setTurboPrewarmEnabled(initialWeakNetTurbo)

        _uiState = MutableStateFlow(
            MainUiState(
                formNameInput = "",
                socksConfig = Socks5Config(),
                editingProxyId = null,
                savedProxies = initialSaved,
                activeRunningProxyId = null,
                selectedProxyId = initialSelectedId,
                routingMode = RoutingMode.STANDALONE_PROXY_ZEROTIER,
                savedRouterIps = initialSavedIps,
                quickIpInput = initialCleanQuickIp,
                weakNetTurboEnabled = initialWeakNetTurbo,
                internalDnsEnabled = initialInternalDns
            )
        )

        VpnSocketProtector.updateTelemetry {
            it.copy(
                internalDnsEnabled = initialInternalDns,
                activeSocketChannel = ActiveSocketChannel.STANDBY
            )
        }

        viewModelScope.launch {
            WebViewProxyConfigurator.clearProxyOverride()
        }

        VpnSocketProtector.log(
            "ZEROTIER-MODE",
            "وضع البروكسي المستقل جاهز + نظام حفظ الايبيات مع كاش الصور الفوري مفعل",
            LogLevel.SUCCESS
        )
    }

    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    val telemetry: StateFlow<TunnelTelemetry> = VpnSocketProtector.telemetry
    val packetLogs: StateFlow<List<PacketLogEntry>> = VpnSocketProtector.logs

    fun updateFormName(name: String) {
        _uiState.update { it.copy(formNameInput = name) }
    }

    fun updateSocksConfig(transform: (Socks5Config) -> Socks5Config) {
        _uiState.update { current ->
            current.copy(socksConfig = transform(current.socksConfig))
        }
    }

    fun setWeakNetTurboEnabled(enabled: Boolean) {
        prefsRepo.saveWeakNetTurboEnabled(enabled)
        localRelay.setTurboPrewarmEnabled(enabled)
        _uiState.update { it.copy(weakNetTurboEnabled = enabled) }
    }

    fun setInternalDnsEnabled(enabled: Boolean) {
        prefsRepo.saveInternalDnsEnabled(enabled)
        InternalDnsResolver.setEnabled(enabled)
        if (!enabled) {
            InternalDnsResolver.clearCache()
        }
        _uiState.update {
            it.copy(
                internalDnsEnabled = enabled,
                snackbarMessage = if (enabled) {
                    "تم تفعيل الـ DNS الداخلي المضاد للحظر (تسريع الاتصال وتجاوز حظر يمن نت)"
                } else {
                    "تم إيقاف الـ DNS الداخلي واستخدام DNS النظام"
                }
            )
        }
        VpnSocketProtector.updateTelemetry {
            it.copy(internalDnsEnabled = enabled)
        }
    }

    fun startEditingProxy(profile: SavedProxyProfile) {
        _uiState.update {
            it.copy(
                editingProxyId = profile.id,
                formNameInput = profile.name,
                socksConfig = profile.toSocks5Config(),
                handshakeBannerMessage = null,
                snackbarMessage = "جاري تعديل البروكسي (${profile.displayTitle}) — عدّل البيانات ثم اضغط (حفظ التعديل)"
            )
        }
    }

    fun cancelEditingOrClearForm() {
        _uiState.update {
            it.copy(
                editingProxyId = null,
                formNameInput = "",
                socksConfig = Socks5Config(),
                handshakeBannerMessage = null
            )
        }
    }

    fun saveProxyToProfileList(startImmediately: Boolean = false) {
        val state = _uiState.value
        val cfg = state.socksConfig
        if (!cfg.isValid) {
            _uiState.update {
                it.copy(
                    snackbarMessage = "يرجى إدخال عنوان الـ IP والبورت بشكل صحيح قبل الحفظ"
                )
            }
            return
        }

        val editingId = state.editingProxyId
        val cleanIp = cfg.proxyIp.trim()
        val cleanPort = cfg.proxyPort
        val cleanName = state.formNameInput.trim()

        if (editingId != null) {
            val updatedProfile = SavedProxyProfile(
                id = editingId,
                name = cleanName,
                proxyIp = cleanIp,
                proxyPort = cleanPort,
                authEnabled = cfg.authEnabled,
                username = cfg.username.trim(),
                password = cfg.password
            )
            val updatedList = state.savedProxies.map {
                if (it.id == editingId) updatedProfile else it
            }
            prefsRepo.saveProxiesList(updatedList)
            prefsRepo.saveSelectedProxyId(editingId)

            VpnSocketProtector.log(
                "PROXY-SAVE",
                "تم حفظ تعديلات البروكسي: ${updatedProfile.displayTitle}",
                LogLevel.SUCCESS
            )

            val wasRunning = state.activeRunningProxyId == editingId
            _uiState.update {
                it.copy(
                    savedProxies = updatedList,
                    selectedProxyId = editingId,
                    editingProxyId = null,
                    formNameInput = "",
                    socksConfig = Socks5Config(),
                    snackbarMessage = "تم حفظ التعديل على البروكسي (${updatedProfile.displayTitle})"
                )
            }

            if (wasRunning || startImmediately) {
                startSavedProxy(updatedProfile)
            }
        } else {
            val newId = "proxy_${UUID.randomUUID().toString().take(8)}"
            val newProfile = SavedProxyProfile(
                id = newId,
                name = cleanName,
                proxyIp = cleanIp,
                proxyPort = cleanPort,
                authEnabled = cfg.authEnabled,
                username = cfg.username.trim(),
                password = cfg.password
            )
            val updatedList = state.savedProxies + newProfile
            prefsRepo.saveProxiesList(updatedList)
            prefsRepo.saveSelectedProxyId(newId)

            VpnSocketProtector.log(
                "PROXY-ADD",
                "تمت إضافة وحفظ بروكسي جديد: ${newProfile.displayTitle}",
                LogLevel.SUCCESS
            )

            _uiState.update {
                it.copy(
                    savedProxies = updatedList,
                    selectedProxyId = newId,
                    editingProxyId = null,
                    formNameInput = "",
                    socksConfig = Socks5Config(),
                    snackbarMessage = "تم حفظ البروكسي (${newProfile.displayTitle}) في قائمتك المحفوظة"
                )
            }

            if (startImmediately) {
                startSavedProxy(newProfile)
            }
        }
    }

    fun deleteSavedProxy(profile: SavedProxyProfile) {
        val state = _uiState.value
        val wasRunning = state.activeRunningProxyId == profile.id
        if (wasRunning) {
            stopTunnelEngine()
        }

        val updatedList = state.savedProxies.filterNot { it.id == profile.id }
        prefsRepo.saveProxiesList(updatedList)

        val nextSelected = if (state.selectedProxyId == profile.id) {
            updatedList.firstOrNull()?.id
        } else {
            state.selectedProxyId
        }
        prefsRepo.saveSelectedProxyId(nextSelected)

        _uiState.update {
            it.copy(
                savedProxies = updatedList,
                selectedProxyId = nextSelected,
                editingProxyId = if (it.editingProxyId == profile.id) null else it.editingProxyId,
                snackbarMessage = "تم حذف البروكسي (${profile.displayTitle})"
            )
        }
    }

    fun startSavedProxy(profile: SavedProxyProfile) {
        val previousJob = proxyControlJob
        previousJob?.cancel()

        val previousRunningId = _uiState.value.activeRunningProxyId
        val previousProfile = _uiState.value.savedProxies.find { it.id == previousRunningId }

        prefsRepo.saveSelectedProxyId(profile.id)
        val cfg = profile.toSocks5Config()
        val detectedChannel = localRelay.detectActiveSocketChannel()

        _uiState.update {
            it.copy(
                activeRunningProxyId = profile.id,
                selectedProxyId = profile.id,
                handshakeBannerMessage = null
            )
        }

        logger.updateTelemetry {
            it.copy(
                status = ConnectionStatus.CONNECTING,
                activeMode = RoutingMode.STANDALONE_PROXY_ZEROTIER,
                activeSocketChannel = detectedChannel,
                localRelayRunning = false,
                watchdogHealthy = false,
                lastWatchdogPingMs = -1L,
                statusDetail = "جاري فحص والاتصال الفعلي بالبروكسي (${profile.displayTitle}) عبر [${detectedChannel.shortBadge}]..."
            )
        }

        proxyControlJob = viewModelScope.launch {
            if (previousRunningId != null && previousRunningId != profile.id) {
                logger.log(
                    "MUTEX-GUARD",
                    "حماية التعارض: إيقاف البروكسي السابق (${previousProfile?.displayTitle ?: previousRunningId}) وإغلاق سوكيتاته تلقائياً",
                    LogLevel.WARN
                )
            }

            if (!isActive || _uiState.value.activeRunningProxyId != profile.id) return@launch

            val relayResult = localRelay.startRelay(config = cfg)
            relayResult.getOrElse { err ->
                if (_uiState.value.activeRunningProxyId == profile.id) {
                    _uiState.update {
                        it.copy(
                            activeRunningProxyId = null,
                            snackbarMessage = "فشل تهيئة السوكيت المحلي: ${err.localizedMessage}"
                        )
                    }
                    logger.updateTelemetry {
                        it.copy(
                            status = ConnectionStatus.ERROR,
                            localRelayRunning = false,
                            activeSocketChannel = ActiveSocketChannel.STANDBY,
                            statusDetail = "فشل تشغيل الجسر المحلي: ${err.localizedMessage}"
                        )
                    }
                }
                return@launch
            }

            val sessionBeforeVerify = localRelay.currentSessionId()

            val handshakeCheck = localRelay.verifyUpstreamConnectionWithRetry(
                config = cfg,
                maxAttempts = 2,
                perAttemptTimeoutMs = 4_200,
                retryDelayMs = 350L
            )

            if (!isActive ||
                _uiState.value.activeRunningProxyId != profile.id ||
                !localRelay.isRelayActive() ||
                localRelay.currentSessionId() != sessionBeforeVerify
            ) {
                return@launch
            }

            handshakeCheck.fold(
                onSuccess = { rtt ->
                    val activeHost = localRelay.activeLocalBindHost
                    val activePort = localRelay.boundPort
                    val activeBindAddr = "$activeHost:$activePort"
                    WebViewProxyConfigurator.applyLocalRelayProxy(
                        localPort = activePort,
                        forceReapply = true,
                        localHost = activeHost
                    )
                    val activeChan = localRelay.currentSocketChannel()

                    VpnSocketProtector.updateTelemetry {
                        it.copy(
                            status = ConnectionStatus.CONNECTED,
                            activeMode = RoutingMode.STANDALONE_PROXY_ZEROTIER,
                            activeSocketChannel = activeChan,
                            localRelayRunning = true,
                            localRelayBindAddress = activeBindAddr,
                            wifiLocalRelayAddress = localRelay.wifiLocalBindAddress,
                            cellularLocalRelayAddress = localRelay.cellularLocalBindAddress,
                            lastWatchdogPingMs = rtt,
                            watchdogHealthy = true,
                            statusDetail = "متصل فعلياً: ${profile.displayTitle} (${rtt}ms) عبر [${activeChan.shortBadge}]"
                        )
                    }

                    VpnSocketProtector.log(
                        "PROXY-CONNECTED",
                        "تم الاتصال الفعلي بالبروكسي ${profile.displayTitle} (${rtt}ms) (${activeChan.labelAr})",
                        LogLevel.SUCCESS
                    )

                    val switchNotice = if (previousRunningId != null && previousRunningId != profile.id) {
                        "تم إيقاف البروكسي السابق والاتصال الفعلي بـ (${profile.displayTitle}) عبر [${activeChan.shortBadge}] — ${rtt}ms"
                    } else {
                        "تم الاتصال الفعلي بالبروكسي (${profile.displayTitle}) عبر [${activeChan.shortBadge}] — ${rtt}ms"
                    }

                    _uiState.update {
                        it.copy(snackbarMessage = switchNotice)
                    }
                },
                onFailure = {
                    val activeHost = localRelay.activeLocalBindHost
                    val activePort = localRelay.boundPort
                    val activeBindAddr = "$activeHost:$activePort"
                    val activeChan = localRelay.currentSocketChannel()

                    WebViewProxyConfigurator.applyLocalRelayProxy(
                        localPort = activePort,
                        forceReapply = true,
                        localHost = activeHost
                    )

                    VpnSocketProtector.log(
                        "AUTO-RECONNECT",
                        "بدء حلقة الربط التلقائي الذكي في الخلفية للبروكسي (${profile.displayTitle}) عبر [${activeChan.shortBadge}] حتى يكتمل مسار ZeroTier",
                        LogLevel.INFO
                    )

                    VpnSocketProtector.updateTelemetry {
                        it.copy(
                            status = ConnectionStatus.RECONNECTING,
                            activeMode = RoutingMode.STANDALONE_PROXY_ZEROTIER,
                            activeSocketChannel = activeChan,
                            localRelayRunning = true,
                            localRelayBindAddress = activeBindAddr,
                            wifiLocalRelayAddress = localRelay.wifiLocalBindAddress,
                            cellularLocalRelayAddress = localRelay.cellularLocalBindAddress,
                            watchdogHealthy = false,
                            lastWatchdogPingMs = -1L,
                            statusDetail = "جاري الربط التلقائي الذكي بـ (${profile.displayTitle}) عبر [${activeChan.shortBadge}] فور جاهزية مسار ZeroTier..."
                        )
                    }

                    _uiState.update {
                        it.copy(
                            snackbarMessage = "تم تفعيل الربط التلقائي الذكي عبر (${activeChan.shortBadge}) — سيرتبط تلقائياً فور جاهزية مسار ZeroTier ⚡"
                        )
                    }

                    localRelay.startBackgroundAutoReconnect(
                        config = cfg,
                        reasonTag = "AUTO-RECONNECT"
                    )
                }
            )
        }
    }

    fun toggleSavedProxy(profile: SavedProxyProfile) {
        val isThisRunningOrConnecting = _uiState.value.activeRunningProxyId == profile.id &&
            (telemetry.value.status == ConnectionStatus.CONNECTED ||
                telemetry.value.status == ConnectionStatus.CONNECTING ||
                telemetry.value.status == ConnectionStatus.RECONNECTING ||
                telemetry.value.localRelayRunning)
        if (isThisRunningOrConnecting) {
            stopTunnelEngine()
        } else {
            startSavedProxy(profile)
        }
    }

    fun startTunnelEngine() {
        val state = _uiState.value
        if (state.socksConfig.isValid) {
            saveProxyToProfileList(startImmediately = true)
            return
        }
        val targetProfile = state.selectedOrFirstProfile
        if (targetProfile != null) {
            startSavedProxy(targetProfile)
        } else {
            _uiState.update {
                it.copy(snackbarMessage = "أضف بيانات البروكسي أولاً في الحقول بالأسفل ثم اضغط حفظ وتشغيل")
            }
        }
    }

    fun stopTunnelEngine() {
        val stoppedProfile = _uiState.value.activeProxyProfile

        proxyControlJob?.cancel()
        localRelay.killAllSocketsNow()

        _uiState.update {
            it.copy(
                activeRunningProxyId = null,
                snackbarMessage = if (stoppedProfile != null) {
                    "تم إيقاف البروكسي (${stoppedProfile.displayTitle}) وإغلاق السوكيت بالكامل"
                } else {
                    "تم إيقاف البروكسي وإغلاق السوكيت بالكامل"
                }
            )
        }

        logger.updateTelemetry {
            it.copy(
                status = ConnectionStatus.DISCONNECTED,
                localRelayRunning = false,
                activeSocketChannel = ActiveSocketChannel.STANDBY,
                activeConnections = 0,
                watchdogHealthy = false,
                lastWatchdogPingMs = -1L,
                statusDetail = "تم قطع اتصال البروكسي وإغلاق السوكيت بالكامل — الاتصال المباشر مفعل"
            )
        }

        proxyControlJob = viewModelScope.launch {
            localRelay.stopRelay()
            webViewProxy.clearProxyOverride()
        }
    }

    fun testSocks5Handshake() {
        val state = _uiState.value
        val cfgToTest = when {
            state.socksConfig.isValid -> state.socksConfig
            state.selectedOrFirstProfile != null -> state.selectedOrFirstProfile!!.toSocks5Config()
            else -> null
        }

        if (cfgToTest == null || !cfgToTest.isValid) {
            _uiState.update {
                it.copy(
                    handshakeBannerMessage = "يرجى إدخال عنوان الـ IP والبورت أو اختيار بروكسي محفوظ للفحص",
                    handshakeBannerSuccess = false
                )
            }
            return
        }

        val activeChan = localRelay.detectActiveSocketChannel()
        _uiState.update {
            it.copy(
                isTestingHandshake = true,
                handshakeBannerMessage = "جاري فحص الاتصال الفعلي بالبروكسي ${cfgToTest.proxyIp}:${cfgToTest.proxyPort} عبر [${activeChan.shortBadge}]..."
            )
        }

        viewModelScope.launch {
            val res = localRelay.verifyUpstreamConnectionWithRetry(
                config = cfgToTest,
                maxAttempts = 3,
                perAttemptTimeoutMs = 7_500,
                retryDelayMs = 450L
            )
            res.fold(
                onSuccess = { rtt ->
                    val authText = if (cfgToTest.authEnabled) "مع مصادقة RFC 1929" else "No-Auth"
                    val msg = "✅ البروكسي ${cfgToTest.proxyIp}:${cfgToTest.proxyPort} متصل فعلياً ($authText) عبر [${activeChan.shortBadge}] — ${rtt}ms"
                    VpnSocketProtector.log("PROXY-TEST", msg, LogLevel.SUCCESS)
                    _uiState.update {
                        it.copy(
                            isTestingHandshake = false,
                            handshakeBannerMessage = msg,
                            handshakeBannerSuccess = true,
                            snackbarMessage = msg
                        )
                    }
                },
                onFailure = { err ->
                    val msg = "❌ تعذر الاتصال بالبروكسي ${cfgToTest.proxyIp}:${cfgToTest.proxyPort} (${err.localizedMessage ?: "تأكد من تشغيل ZeroTier وصحة الـ IP والبورت"})"
                    VpnSocketProtector.log("PROXY-TEST", msg, LogLevel.ERROR)
                    _uiState.update {
                        it.copy(
                            isTestingHandshake = false,
                            handshakeBannerMessage = msg,
                            handshakeBannerSuccess = false
                        )
                    }
                }
            )
        }
    }

    fun updateQuickIpInput(ip: String) {
        _uiState.update { it.copy(quickIpInput = ip) }
        prefsRepo.saveQuickIp(ip)
    }

    private fun upsertSavedRouterIp(
        rawIpOrUrl: String,
        pageTitle: String = "",
        incrementOpenCount: Boolean = false
    ): Pair<List<SavedRouterIpEntry>, Boolean> {
        val normalizedKey = RouterIpCacheVault.normalizeIpKey(rawIpOrUrl)
        if (normalizedKey.isEmpty()) {
            return _uiState.value.savedRouterIps to false
        }
        val cleanRootUrl = RouterIpCacheVault.normalizeFullUrl(normalizedKey)
        val stats = RouterIpCacheVault.warmIpCacheIntoRam(appCtx, normalizedKey)
        val now = System.currentTimeMillis()

        val currentList = _uiState.value.savedRouterIps
        val existingIndex = currentList.indexOfFirst {
            it.normalizedKey.equals(normalizedKey, ignoreCase = true)
        }

        val alreadyExisted = existingIndex >= 0
        val updatedList = if (alreadyExisted) {
            val existing = currentList[existingIndex]
            val updatedEntry = existing.copy(
                normalizedKey = normalizedKey,
                displayIp = normalizedKey,
                fullUrl = cleanRootUrl,
                pageTitle = if (pageTitle.isNotBlank() && !pageTitle.startsWith("متصفح المايكروتيك")) {
                    pageTitle
                } else {
                    existing.pageTitle
                },
                cachedImagesCount = stats.cachedImagesCount,
                cachedFilesCount = stats.cachedFilesCount,
                cachedBytesSize = stats.cachedBytesSize,
                openCount = if (incrementOpenCount) existing.openCount + 1 else existing.openCount,
                lastOpenedTimestamp = now
            )
            currentList.toMutableList().apply {
                set(existingIndex, updatedEntry)
            }
        } else {
            val newEntry = SavedRouterIpEntry(
                id = "router_ip_${UUID.randomUUID().toString().take(8)}",
                normalizedKey = normalizedKey,
                displayIp = normalizedKey,
                fullUrl = cleanRootUrl,
                pageTitle = if (pageTitle.isNotBlank() && !pageTitle.startsWith("متصفح المايكروتيك")) {
                    pageTitle
                } else {
                    "صفحة راوتر ($normalizedKey)"
                },
                cachedImagesCount = stats.cachedImagesCount,
                cachedFilesCount = stats.cachedFilesCount,
                cachedBytesSize = stats.cachedBytesSize,
                openCount = 1,
                lastOpenedTimestamp = now
            )
            listOf(newEntry) + currentList
        }

        prefsRepo.saveRouterIpsList(updatedList)
        return updatedList to alreadyExisted
    }

    fun openQuickBrowser(targetIpOrUrl: String = _uiState.value.quickIpInput) {
        val cleanIpOnly = RouterIpCacheVault.normalizeIpKey(targetIpOrUrl)
        if (cleanIpOnly.isEmpty()) {
            _uiState.update {
                it.copy(snackbarMessage = "يرجى كتابة عنوان الـ IP أولاً في مربع فتح الصفحة")
            }
            return
        }

        prefsRepo.saveQuickIp(cleanIpOnly)

        val cleanRootUrl = RouterIpCacheVault.normalizeFullUrl(cleanIpOnly)
        val (updatedSavedIps, alreadyExisted) = upsertSavedRouterIp(
            rawIpOrUrl = cleanIpOnly,
            incrementOpenCount = true
        )
        val entryStats = updatedSavedIps.find { it.normalizedKey == cleanIpOnly }

        if (alreadyExisted) {
            VpnSocketProtector.log(
                "CACHE-HIT-OPEN",
                "⚡ الـ IP ($cleanIpOnly) محفوظ مسبقاً — سحب الكاش المحفوظ (${entryStats?.cachedImagesCount ?: 0} صورة • ${entryStats?.cachedFilesCount ?: 0} ملف) بدون تكرار",
                LogLevel.SUCCESS
            )
        } else {
            VpnSocketProtector.log(
                "IP-AUTO-SAVED",
                "💾 تم حفظ الـ IP الجديد ($cleanIpOnly) تلقائياً في خانة الايبيات المحفوظة مع الكاش",
                LogLevel.SUCCESS
            )
        }

        val state = _uiState.value
        val runningProfile = state.savedProxies.find { it.id == state.activeRunningProxyId }

        _uiState.update {
            it.copy(
                quickIpInput = cleanIpOnly,
                savedRouterIps = updatedSavedIps,
                activeBrowserUrl = cleanRootUrl
            )
        }

        viewModelScope.launch {
            if (runningProfile != null && localRelay.isRelayActive()) {
                localRelay.triggerPrewarmPoolReplenish(4)
                WebViewProxyConfigurator.applyLocalRelayProxy(
                    localPort = localRelay.boundPort,
                    forceReapply = false,
                    localHost = localRelay.activeLocalBindHost
                )
                VpnSocketProtector.log(
                    "QUICK-OPEN",
                    "فتح $cleanRootUrl عبر البروكسي المشغّل (${runningProfile.proxyIp}:${runningProfile.proxyPort}) [${localRelay.activeLocalBindHost}:${localRelay.boundPort}]",
                    LogLevel.SUCCESS
                )
            } else {
                localRelay.stopRelay()
                WebViewProxyConfigurator.clearProxyOverride()
                VpnSocketProtector.log(
                    "DIRECT-OPEN",
                    "فتح $cleanRootUrl باتصال مباشر بدون بروكسي (مع تفعيل كاش الصور الفوري)",
                    LogLevel.INFO
                )
            }
        }
    }

    fun onBrowserPageAndCacheUpdated(url: String, title: String) {
        if (url.isBlank()) return
        val (updatedList, _) = upsertSavedRouterIp(
            rawIpOrUrl = url,
            pageTitle = title,
            incrementOpenCount = false
        )
        _uiState.update {
            it.copy(savedRouterIps = updatedList)
        }
    }

    fun closeQuickBrowser() {
        val currentUrl = _uiState.value.activeBrowserUrl.orEmpty()
        val refreshedList = _uiState.value.savedRouterIps.map { entry ->
            val stats = RouterIpCacheVault.getCacheStats(appCtx, entry.normalizedKey)
            entry.copy(
                cachedImagesCount = stats.cachedImagesCount,
                cachedFilesCount = stats.cachedFilesCount,
                cachedBytesSize = stats.cachedBytesSize
            )
        }
        prefsRepo.saveRouterIpsList(refreshedList)

        val closedKey = RouterIpCacheVault.normalizeIpKey(currentUrl)
        val closedStats = refreshedList.find { it.normalizedKey == closedKey }

        _uiState.update {
            it.copy(
                activeBrowserUrl = null,
                savedRouterIps = refreshedList,
                snackbarMessage = if (closedStats != null) {
                    "تم حفظ كاش (${closedStats.displayIp}): ${closedStats.cachedImagesCount} صورة و ${closedStats.cachedFilesCount} ملف لفتحها بسرعة فائقة ⚡"
                } else {
                    "تم إغلاق الصفحة مع الاحتفاظ بالكاش والصور المحفوظة ⚡"
                }
            )
        }
    }

    fun deleteSavedRouterIp(entry: SavedRouterIpEntry) {
        RouterIpCacheVault.clearCacheForIp(appCtx, entry.normalizedKey)
        val updatedList = _uiState.value.savedRouterIps.filterNot {
            it.normalizedKey.equals(entry.normalizedKey, ignoreCase = true)
        }
        prefsRepo.saveRouterIpsList(updatedList)
        VpnSocketProtector.log(
            "IP-CACHE-DEL",
            "تم حذف الـ IP المحفوظ (${entry.displayIp}) ومسح الكاش الخاص به",
            LogLevel.INFO
        )
        _uiState.update {
            it.copy(
                savedRouterIps = updatedList,
                snackbarMessage = "تم حذف الـ IP (${entry.displayIp}) مع الكاش الخاص به"
            )
        }
    }

    fun clearSingleIpCacheOnly(normalizedKey: String) {
        val cleanKey = RouterIpCacheVault.normalizeIpKey(normalizedKey)
        RouterIpCacheVault.clearCacheForIp(appCtx, cleanKey)
        val updatedList = _uiState.value.savedRouterIps.map { entry ->
            if (entry.normalizedKey.equals(cleanKey, ignoreCase = true)) {
                entry.copy(
                    cachedImagesCount = 0,
                    cachedFilesCount = 0,
                    cachedBytesSize = 0L
                )
            } else {
                entry
            }
        }
        prefsRepo.saveRouterIpsList(updatedList)
        _uiState.update {
            it.copy(
                savedRouterIps = updatedList,
                snackbarMessage = "تم تصفير كاش ($cleanKey) — سيتم تحميل وحفظ كاش جديد الآن"
            )
        }
    }

    fun clearAllSavedRouterIpsAndCache() {
        RouterIpCacheVault.clearAllIpsCache(appCtx)
        prefsRepo.saveRouterIpsList(emptyList())
        VpnSocketProtector.log(
            "IP-CACHE-CLEAR-ALL",
            "تم مسح جميع الايبيات المحفوظة وكاش الصور بالكامل",
            LogLevel.INFO
        )
        _uiState.update {
            it.copy(
                savedRouterIps = emptyList(),
                snackbarMessage = "تم حذف جميع الايبيات المحفوظة ومسح الكاش بالكامل"
            )
        }
    }

    fun clearLogs() {
        VpnSocketProtector.clearLogs()
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
