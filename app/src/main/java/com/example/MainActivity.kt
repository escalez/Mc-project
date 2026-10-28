package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.network.ConnectionStatus
import com.example.ui.MainViewModel
import com.example.ui.NetTunnelDashboardScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
                val packetLogs by viewModel.packetLogs.collectAsStateWithLifecycle()

                NetTunnelDashboardScreen(
                    uiState = uiState,
                    telemetry = telemetry,
                    packetLogs = packetLogs,
                    onToggleEngine = {
                        val isConnected = uiState.activeRunningProxyId != null &&
                            (telemetry.status == ConnectionStatus.CONNECTED ||
                                telemetry.status == ConnectionStatus.CONNECTING ||
                                telemetry.localRelayRunning)
                        if (isConnected) {
                            viewModel.stopTunnelEngine()
                        } else {
                            viewModel.startTunnelEngine()
                        }
                    },
                    onUpdateFormName = viewModel::updateFormName,
                    onUpdateSocksConfig = viewModel::updateSocksConfig,
                    onSaveProxyToProfileList = viewModel::saveProxyToProfileList,
                    onStartEditingProxy = viewModel::startEditingProxy,
                    onCancelEditingProxy = viewModel::cancelEditingOrClearForm,
                    onToggleSavedProxy = viewModel::toggleSavedProxy,
                    onDeleteSavedProxy = viewModel::deleteSavedProxy,
                    onTestSocksHandshake = viewModel::testSocks5Handshake,
                    onQuickIpChange = viewModel::updateQuickIpInput,
                    onOpenQuickBrowser = viewModel::openQuickBrowser,
                    onCloseQuickBrowser = viewModel::closeQuickBrowser,
                    onBrowserPageAndCacheUpdated = viewModel::onBrowserPageAndCacheUpdated,
                    onDeleteSavedRouterIp = viewModel::deleteSavedRouterIp,
                    onClearSingleIpCache = viewModel::clearSingleIpCacheOnly,
                    onClearAllSavedIpsAndCache = viewModel::clearAllSavedRouterIpsAndCache,
                    onToggleWeakNetTurbo = viewModel::setWeakNetTurboEnabled,
                    onToggleInternalDns = viewModel::setInternalDnsEnabled,
                    onClearLogs = viewModel::clearLogs,
                    onClearSnackbar = viewModel::clearSnackbar
                )
            }
        }
    }
}
