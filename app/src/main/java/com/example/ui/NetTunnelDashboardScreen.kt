package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.network.ActiveSocketChannel
import com.example.network.ConnectionStatus
import com.example.network.PacketLogEntry
import com.example.network.RouterIpCacheVault
import com.example.network.SavedProxyProfile
import com.example.network.SavedRouterIpEntry
import com.example.network.Socks5Config
import com.example.network.TunnelTelemetry
import com.example.ui.theme.AmberPulse
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberObsidian
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldTunnel
import com.example.ui.theme.RobotoMonoFontFamily
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

@Composable
fun NetTunnelDashboardScreen(
    uiState: MainUiState,
    telemetry: TunnelTelemetry,
    packetLogs: List<PacketLogEntry>,
    onToggleEngine: () -> Unit,
    onUpdateFormName: (String) -> Unit,
    onUpdateSocksConfig: ((Socks5Config) -> Socks5Config) -> Unit,
    onSaveProxyToProfileList: (Boolean) -> Unit,
    onStartEditingProxy: (SavedProxyProfile) -> Unit,
    onCancelEditingProxy: () -> Unit,
    onToggleSavedProxy: (SavedProxyProfile) -> Unit,
    onDeleteSavedProxy: (SavedProxyProfile) -> Unit,
    onTestSocksHandshake: () -> Unit,
    onQuickIpChange: (String) -> Unit,
    onOpenQuickBrowser: (String) -> Unit,
    onCloseQuickBrowser: () -> Unit,
    onBrowserPageAndCacheUpdated: (String, String) -> Unit = { _, _ -> },
    onDeleteSavedRouterIp: (SavedRouterIpEntry) -> Unit = {},
    onClearSingleIpCache: (String) -> Unit = {},
    onClearAllSavedIpsAndCache: () -> Unit = {},
    onToggleWeakNetTurbo: (Boolean) -> Unit = {},
    onToggleInternalDns: (Boolean) -> Unit = {},
    onClearLogs: () -> Unit,
    onClearSnackbar: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            onClearSnackbar()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(CyberObsidian),
            containerColor = CyberObsidian,
            contentWindowInsets = WindowInsets.systemBars,
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                TopCommandHeader(
                    uiState = uiState,
                    telemetry = telemetry,
                    onToggleEngine = onToggleEngine
                )

                val tabs = listOf(
                    "البروكسيات (${uiState.savedProxies.size}) وفتح الصفحة" to Icons.Default.Hub,
                    "الايبيات المحفوظة + الكاش (${uiState.savedRouterIps.size})" to Icons.Default.Save,
                    "السجل (${packetLogs.size})" to Icons.Default.Terminal
                )

                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = CyberSurface,
                    contentColor = ElectricCyan,
                    indicator = { tabPositions ->
                        if (selectedTabIndex < tabPositions.size) {
                            SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                                height = 3.dp,
                                color = ElectricCyan
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabs.forEachIndexed { index, (title, icon) ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            modifier = Modifier.testTag("main_tab_$index"),
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = title,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = title,
                                        fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (selectedTabIndex) {
                        0 -> StandaloneProxyAndPageTab(
                            uiState = uiState,
                            telemetry = telemetry,
                            onUpdateFormName = onUpdateFormName,
                            onUpdateSocksConfig = onUpdateSocksConfig,
                            onSaveProxyToProfileList = onSaveProxyToProfileList,
                            onStartEditingProxy = onStartEditingProxy,
                            onCancelEditingProxy = onCancelEditingProxy,
                            onToggleSavedProxy = onToggleSavedProxy,
                            onDeleteSavedProxy = onDeleteSavedProxy,
                            onTestSocksHandshake = onTestSocksHandshake,
                            onQuickIpChange = onQuickIpChange,
                            onOpenQuickBrowser = onOpenQuickBrowser,
                            onToggleInternalDns = onToggleInternalDns
                        )

                        1 -> SavedIpsWithCacheTab(
                            savedRouterIps = uiState.savedRouterIps,
                            isProxyActive = uiState.activeRunningProxyId != null &&
                                (telemetry.status == ConnectionStatus.CONNECTED || telemetry.localRelayRunning),
                            onOpenSavedIpWithCache = onOpenQuickBrowser,
                            onClearSingleIpCache = onClearSingleIpCache,
                            onDeleteSavedIp = onDeleteSavedRouterIp,
                            onClearAllSavedIps = onClearAllSavedIpsAndCache
                        )

                        2 -> PacketLogsAndAuditTab(
                            logs = packetLogs,
                            onClearLogs = onClearLogs
                        )
                    }
                }
            }

            uiState.activeBrowserUrl?.let { targetUrl ->
                val runningProfile = uiState.activeProxyProfile
                val activeCfg = runningProfile?.toSocks5Config() ?: Socks5Config()
                val isProxyRunning = runningProfile != null &&
                    (telemetry.status == ConnectionStatus.CONNECTED || telemetry.localRelayRunning)
                QuickRouterBrowserDialog(
                    initialUrl = targetUrl,
                    socksConfig = activeCfg,
                    telemetry = telemetry,
                    isProxyActive = isProxyRunning,
                    weakNetTurboEnabled = uiState.weakNetTurboEnabled,
                    onToggleWeakNetTurbo = onToggleWeakNetTurbo,
                    onPageAndCacheUpdated = onBrowserPageAndCacheUpdated,
                    onManualClearIpCache = onClearSingleIpCache,
                    onDismiss = onCloseQuickBrowser
                )
            }
        }
    }
}

@Composable
private fun TopCommandHeader(
    uiState: MainUiState,
    telemetry: TunnelTelemetry,
    onToggleEngine: () -> Unit
) {
    val activeProfile = uiState.activeProxyProfile
    val isStrictlyConnected = activeProfile != null && telemetry.status == ConnectionStatus.CONNECTED
    val isConnectingOrReconnecting = activeProfile != null &&
        (telemetry.status == ConnectionStatus.CONNECTING || telemetry.status == ConnectionStatus.RECONNECTING)
    val isActiveOrConnecting = isStrictlyConnected || isConnectingOrReconnecting

    val statusColor = when {
        isStrictlyConnected -> EmeraldTunnel
        isConnectingOrReconnecting -> AmberPulse
        telemetry.status == ConnectionStatus.ERROR -> CrimsonAlert
        else -> TextMuted
    }

    Surface(
        color = CyberSurface,
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, CyberBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                1.5.dp,
                                when {
                                    isStrictlyConnected -> EmeraldTunnel
                                    telemetry.status == ConnectionStatus.ERROR -> CrimsonAlert
                                    else -> AmberPulse
                                },
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.mc_proxytier_logo),
                            contentDescription = "شعار MC ProxyTier",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "MC ProxyTier",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = statusColor.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = when {
                                        isStrictlyConnected -> "متصل (${telemetry.activeSocketChannel.shortBadge})"
                                        telemetry.status == ConnectionStatus.RECONNECTING -> "ربط تلقائي ذكي (${telemetry.activeSocketChannel.shortBadge})"
                                        isConnectingOrReconnecting -> "جاري الفحص والربط..."
                                        else -> telemetry.status.labelAr
                                    },
                                    color = statusColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = when {
                                isStrictlyConnected && activeProfile != null ->
                                    "نشط: ${activeProfile.displayTitle} (${activeProfile.proxyIp}:${activeProfile.proxyPort})"
                                isConnectingOrReconnecting ->
                                    telemetry.statusDetail
                                else ->
                                    telemetry.statusDetail
                            },
                            fontFamily = RobotoMonoFontFamily,
                            fontSize = 11.sp,
                            color = when {
                                isStrictlyConnected -> ElectricCyan
                                telemetry.status == ConnectionStatus.ERROR -> CrimsonAlert
                                else -> TextSecondary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Button(
                    onClick = onToggleEngine,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isActiveOrConnecting) CrimsonAlert else EmeraldTunnel
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .height(44.dp)
                        .testTag("master_tunnel_toggle_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = if (isActiveOrConnecting) Color.White else Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isActiveOrConnecting) "إيقاف" else "تشغيل",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (isActiveOrConnecting) Color.White else Color.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberObsidian, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricPill(
                    label = "السوكيت النشط",
                    value = telemetry.activeSocketChannel.shortBadge,
                    valueColor = when (telemetry.activeSocketChannel) {
                        ActiveSocketChannel.WIFI_DEDICATED -> ElectricCyan
                        ActiveSocketChannel.CELLULAR_ZEROTIER -> EmeraldTunnel
                        ActiveSocketChannel.STANDBY -> TextMuted
                    }
                )
                MetricPill(
                    label = "DNS الداخلي",
                    value = if (uiState.internalDnsEnabled) "مفعل ⚡" else "النظام",
                    valueColor = if (uiState.internalDnsEnabled) EmeraldTunnel else TextMuted
                )
                MetricPill(
                    label = "البنق الفعلي",
                    value = if (telemetry.lastWatchdogPingMs >= 0 && isStrictlyConnected) "${telemetry.lastWatchdogPingMs} ms" else "—",
                    valueColor = if (telemetry.lastWatchdogPingMs in 0..350 && isStrictlyConnected) EmeraldTunnel else TextSecondary
                )
                MetricPill(
                    label = "TX / RX",
                    value = "${formatBytesShort(telemetry.bytesSent)}/${formatBytesShort(telemetry.bytesReceived)}",
                    valueColor = AmberPulse
                )
            }
        }
    }
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    valueColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = TextMuted
        )
        Text(
            text = value,
            fontFamily = RobotoMonoFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

private fun Modifier.scrollAboveKeyboardOnFocus(
    scrollState: ScrollState,
    currentValue: String,
    getViewportTopY: () -> Float
): Modifier = composed {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var fieldTopInRoot by remember { mutableFloatStateOf(0f) }
    var isFocused by remember { mutableStateOf(false) }

    LaunchedEffect(isFocused, currentValue) {
        if (isFocused) {
            val topMarginPx = with(density) { 24.dp.toPx() }
            val maxVisibleOffsetPx = with(density) { 120.dp.toPx() }
            val currentOffsetFromTop = fieldTopInRoot - getViewportTopY()
            if (currentOffsetFromTop > maxVisibleOffsetPx || currentOffsetFromTop < 0f) {
                val deltaY = currentOffsetFromTop - topMarginPx
                val target = (scrollState.value + deltaY).toInt().coerceAtLeast(0)
                scrollState.animateScrollTo(target)
            }
        }
    }

    this
        .onGloballyPositioned { coords ->
            fieldTopInRoot = coords.positionInRoot().y
        }
        .onFocusEvent { focusState ->
            val becameFocused = focusState.isFocused && !isFocused
            isFocused = focusState.isFocused
            if (becameFocused) {
                scope.launch {
                    delay(80)
                    val topMarginPx = with(density) { 24.dp.toPx() }
                    val deltaY = fieldTopInRoot - getViewportTopY() - topMarginPx
                    val target = (scrollState.value + deltaY).toInt().coerceAtLeast(0)
                    scrollState.animateScrollTo(target)
                }
            }
        }
}

@Composable
private fun StandaloneProxyAndPageTab(
    uiState: MainUiState,
    telemetry: TunnelTelemetry,
    onUpdateFormName: (String) -> Unit,
    onUpdateSocksConfig: ((Socks5Config) -> Socks5Config) -> Unit,
    onSaveProxyToProfileList: (Boolean) -> Unit,
    onStartEditingProxy: (SavedProxyProfile) -> Unit,
    onCancelEditingProxy: () -> Unit,
    onToggleSavedProxy: (SavedProxyProfile) -> Unit,
    onDeleteSavedProxy: (SavedProxyProfile) -> Unit,
    onTestSocksHandshake: () -> Unit,
    onQuickIpChange: (String) -> Unit,
    onOpenQuickBrowser: (String) -> Unit,
    onToggleInternalDns: (Boolean) -> Unit = {}
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val cfg = uiState.socksConfig
    val isEditing = uiState.editingProxyId != null
    val isEngineStrictlyConnected = uiState.activeRunningProxyId != null &&
        telemetry.status == ConnectionStatus.CONNECTED
    val isEngineActive = isEngineStrictlyConnected
    val scrollState = rememberScrollState()
    var viewportTopY by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                viewportTopY = coords.positionInRoot().y
            }
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            color = CyberCard,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, EmeraldTunnel.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(EmeraldTunnel.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lan,
                                contentDescription = null,
                                tint = EmeraldTunnel,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "تطبيق بروكسي تاير خاص بسيرفرات MC",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "ربط مباشر مع سوكيت مستقل للوايفاي وسوكيت مستقل لبيانات الهاتف + ZeroTier",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 11.sp,
                                color = EmeraldTunnel
                            )
                        }
                    }
                }

                Surface(
                    color = CyberSurface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "حالة السوكيت الحالي:",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = telemetry.activeSocketChannel.labelAr,
                            fontFamily = RobotoMonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (telemetry.activeSocketChannel) {
                                ActiveSocketChannel.WIFI_DEDICATED -> ElectricCyan
                                ActiveSocketChannel.CELLULAR_ZEROTIER -> EmeraldTunnel
                                ActiveSocketChannel.STANDBY -> AmberPulse
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CyberSurface, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "DNS داخلي سريع ومضاد للحظر (يمن نت ⚡)",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "فك فوري 0ms لعناوين الـ IP + تشفير DoH/TCP لتسريع البروكسي وفتح الصفحات",
                            fontSize = 11.sp,
                            color = if (uiState.internalDnsEnabled) EmeraldTunnel else TextSecondary
                        )
                    }
                    Switch(
                        checked = uiState.internalDnsEnabled,
                        onCheckedChange = onToggleInternalDns,
                        colors = SwitchDefaults.colors(checkedThumbColor = EmeraldTunnel),
                        modifier = Modifier.testTag("internal_dns_switch")
                    )
                }
            }
        }

        Surface(
            color = CyberCard,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CyberBorder, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "البروكسيات المحفوظة (${uiState.savedProxies.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "عند تشغيل أي بروكسي يتم إيقاف البروكسي الآخر تلقائياً لمنع التعارض",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }

                if (uiState.savedProxies.isEmpty()) {
                    Surface(
                        color = CyberSurface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "لا توجد بروكسيات محفوظة بعد",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "أضف بيانات البروكسي في الحقول بالأسفل واضغط (إضافة وحفظ البروكسي) ليتم حفظه دائماً هنا",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        uiState.savedProxies.forEach { profile ->
                            val isThisConnected = uiState.activeRunningProxyId == profile.id &&
                                telemetry.status == ConnectionStatus.CONNECTED
                            val isThisConnecting = uiState.activeRunningProxyId == profile.id &&
                                (telemetry.status == ConnectionStatus.CONNECTING ||
                                    telemetry.status == ConnectionStatus.RECONNECTING)
                            val isThisBeingEdited = uiState.editingProxyId == profile.id

                            SavedProxyItemCard(
                                profile = profile,
                                isRunning = isThisConnected,
                                isConnecting = isThisConnecting,
                                isBeingEdited = isThisBeingEdited,
                                onToggleStartStop = { onToggleSavedProxy(profile) },
                                onEdit = { onStartEditingProxy(profile) },
                                onDelete = { onDeleteSavedProxy(profile) }
                            )
                        }
                    }
                }
            }
        }

        Surface(
            color = CyberCard,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (isEditing) 1.5.dp else 1.dp,
                    color = if (isEditing) AmberPulse else CyberBorder,
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isEditing) Icons.Default.Edit else Icons.Default.Key,
                            contentDescription = null,
                            tint = if (isEditing) AmberPulse else ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (isEditing) {
                                    "تعديل بيانات البروكسي المحدد"
                                } else {
                                    "إضافة بروكسي جديد (SOCKS5 Proxy)"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isEditing) AmberPulse else Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isEditing) {
                                    "عدّل العنوان أو البورت ثم اضغط (حفظ التعديل)"
                                } else {
                                    "أدخل عنوان البروكسي والبورت لحفظه في قائمتك"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    if (isEditing) {
                        IconButton(
                            onClick = onCancelEditingProxy,
                            modifier = Modifier.testTag("cancel_edit_proxy_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إلغاء التعديل",
                                tint = TextSecondary
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = uiState.formNameInput,
                    onValueChange = onUpdateFormName,
                    label = { Text("اسم البروكسي (اختياري)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .scrollAboveKeyboardOnFocus(scrollState, uiState.formNameInput) { viewportTopY }
                        .testTag("socks_proxy_name_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = cfg.proxyIp,
                        onValueChange = { ip -> onUpdateSocksConfig { it.copy(proxyIp = ip) } },
                        label = { Text("عنوان البروكسي (IP)") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1.8f)
                            .scrollAboveKeyboardOnFocus(scrollState, cfg.proxyIp) { viewportTopY }
                            .testTag("socks_proxy_ip_input")
                    )

                    OutlinedTextField(
                        value = cfg.proxyPortInput,
                        onValueChange = { portStr ->
                            val cleanDigits = portStr.filter { it.isDigit() }.take(5)
                            onUpdateSocksConfig { it.copy(proxyPortInput = cleanDigits) }
                        },
                        label = { Text("البورت (Port)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .scrollAboveKeyboardOnFocus(scrollState, cfg.proxyPortInput) { viewportTopY }
                            .testTag("socks_proxy_port_input")
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CyberSurface, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (cfg.authEnabled) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = if (cfg.authEnabled) ElectricCyan else EmeraldTunnel,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (cfg.authEnabled) {
                                    "مصادقة يوزر وباسورد مفعلة (RFC 1929)"
                                } else {
                                    "يعمل بدون اسم مستخدم أو كلمة سر (No-Auth)"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Switch(
                        checked = cfg.authEnabled,
                        onCheckedChange = { enabled ->
                            onUpdateSocksConfig { it.copy(authEnabled = enabled) }
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = EmeraldTunnel),
                        modifier = Modifier.testTag("socks_auth_switch")
                    )
                }

                AnimatedVisibility(visible = cfg.authEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = cfg.username,
                            onValueChange = { u -> onUpdateSocksConfig { it.copy(username = u) } },
                            label = { Text("اسم المستخدم (Username)") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .scrollAboveKeyboardOnFocus(scrollState, cfg.username) { viewportTopY }
                                .testTag("socks_username_input")
                        )

                        OutlinedTextField(
                            value = cfg.password,
                            onValueChange = { p -> onUpdateSocksConfig { it.copy(password = p) } },
                            label = { Text("كلمة المرور (Password)") },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) {
                                            Icons.Default.VisibilityOff
                                        } else {
                                            Icons.Default.Visibility
                                        },
                                        contentDescription = "إظهار/إخفاء كلمة المرور"
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .scrollAboveKeyboardOnFocus(scrollState, cfg.password) { viewportTopY }
                                .testTag("socks_password_input")
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onSaveProxyToProfileList(false) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isEditing) AmberPulse else EmeraldTunnel
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("save_apply_socks_button")
                    ) {
                        Icon(
                            imageVector = if (isEditing) Icons.Default.Save else Icons.Default.AddCircleOutline,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isEditing) "حفظ التعديل" else "إضافة وحفظ البروكسي",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Button(
                        onClick = { onSaveProxyToProfileList(true) },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("save_and_start_proxy_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isEditing) "حفظ وتشغيل" else "حفظ وتشغيل فوراً",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onTestSocksHandshake,
                        enabled = !uiState.isTestingHandshake,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("test_socks_handshake_button")
                    ) {
                        if (uiState.isTestingHandshake) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = ElectricCyan
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "فحص اتصال البروكسي",
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    if (isEditing) {
                        OutlinedButton(
                            onClick = onCancelEditingProxy,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .height(44.dp)
                        ) {
                            Text(
                                text = "إلغاء التعديل",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = !uiState.handshakeBannerMessage.isNullOrBlank()) {
                    val bannerColor = if (uiState.handshakeBannerSuccess) EmeraldTunnel else CrimsonAlert
                    Surface(
                        color = bannerColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, bannerColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = bannerColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.handshakeBannerMessage.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        Surface(
            color = CyberCard,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Router,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isEngineActive) {
                                "فتح صفحة المايكروتيك السريع (عبر البروكسي النشط)"
                            } else {
                                "فتح صفحة المايكروتيك السريع (اتصال مباشر بدون بروكسي)"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "يحفظ الـ IP وكاش الصور تلقائياً في (الايبيات المحفوظة + الكاش) ويفتح المكرر بالكاش فوراً ⚡",
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 11.sp,
                            color = EmeraldTunnel
                        )
                    }
                }

                val typedNormalizedKey = remember(uiState.quickIpInput) {
                    RouterIpCacheVault.normalizeIpKey(uiState.quickIpInput)
                }
                val matchedSavedIp = remember(typedNormalizedKey, uiState.savedRouterIps) {
                    if (typedNormalizedKey.isEmpty()) null
                    else uiState.savedRouterIps.find { it.normalizedKey.equals(typedNormalizedKey, ignoreCase = true) }
                }

                AnimatedVisibility(visible = matchedSavedIp != null) {
                    matchedSavedIp?.let { saved ->
                        Surface(
                            color = EmeraldTunnel.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, EmeraldTunnel.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Save,
                                    contentDescription = null,
                                    tint = EmeraldTunnel,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "⚡ هذا الـ IP (${saved.displayIp}) محفوظ في الكاش (${saved.cachedImagesCount} صورة • ${saved.cachedFilesCount} ملف) — سيفتح فوراً بدون تكرار!",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = EmeraldTunnel
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = uiState.quickIpInput,
                    onValueChange = onQuickIpChange,
                    label = { Text("عنوان IP المايكروتيك") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .scrollAboveKeyboardOnFocus(scrollState, uiState.quickIpInput) { viewportTopY }
                        .testTag("card_quick_ip_input")
                )

                Button(
                    onClick = { onOpenQuickBrowser(uiState.quickIpInput) },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("card_open_quick_browser_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInBrowser,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (matchedSavedIp != null) {
                            "فتح فوراً من الكاش المحفوظ ⚡"
                        } else if (isEngineActive) {
                            "فتح الصفحة وحفظ الكاش عبر البروكسي المشغّل ⚡"
                        } else {
                            "فتح الصفحة وحفظ الكاش مباشرة (بدون بروكسي) ⚡"
                        },
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(340.dp))
    }
}

@Composable
private fun SavedProxyItemCard(
    profile: SavedProxyProfile,
    isRunning: Boolean,
    isConnecting: Boolean = false,
    isBeingEdited: Boolean,
    onToggleStartStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isActiveOrConnecting = isRunning || isConnecting
    Surface(
        color = when {
            isRunning -> EmeraldTunnel.copy(alpha = 0.10f)
            isConnecting -> AmberPulse.copy(alpha = 0.10f)
            else -> CyberSurface
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isActiveOrConnecting || isBeingEdited) 1.5.dp else 1.dp,
                color = when {
                    isRunning -> EmeraldTunnel
                    isConnecting -> AmberPulse
                    isBeingEdited -> AmberPulse
                    else -> CyberBorder
                },
                shape = RoundedCornerShape(12.dp)
            )
            .testTag("saved_proxy_card_${profile.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (profile.name.isNotBlank()) profile.name else "${profile.proxyIp}:${profile.proxyPort}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = when {
                                isRunning -> EmeraldTunnel.copy(alpha = 0.22f)
                                isConnecting -> AmberPulse.copy(alpha = 0.22f)
                                else -> CyberCard
                            },
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = when {
                                    isRunning -> "متصل فعلياً ●"
                                    isConnecting -> "جاري الفحص..."
                                    else -> "متوقف"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isRunning -> EmeraldTunnel
                                    isConnecting -> AmberPulse
                                    else -> TextMuted
                                },
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "IP: ${profile.proxyIp}  •  Port: ${profile.proxyPort}  •  ${if (profile.authEnabled) "RFC 1929 (${profile.username})" else "No-Auth"}",
                        fontFamily = RobotoMonoFontFamily,
                        fontSize = 11.sp,
                        color = ElectricCyan
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("delete_proxy_${profile.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "حذف البروكسي",
                        tint = CrimsonAlert,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onToggleStartStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isActiveOrConnecting) CrimsonAlert else EmeraldTunnel
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(38.dp)
                        .testTag("toggle_proxy_${profile.id}")
                ) {
                    Icon(
                        imageVector = if (isActiveOrConnecting) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isActiveOrConnecting) Color.White else Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            isRunning -> "إيقاف وقطع السوكيت"
                            isConnecting -> "إلغاء وقطع السوكيت"
                            else -> "تشغيل هذا البروكسي"
                        },
                        color = if (isActiveOrConnecting) Color.White else Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                OutlinedButton(
                    onClick = onEdit,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .testTag("edit_proxy_${profile.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = AmberPulse,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isBeingEdited) "قيد التعديل..." else "تعديل",
                        color = AmberPulse,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

