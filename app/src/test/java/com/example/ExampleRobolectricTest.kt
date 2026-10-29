package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.network.ActiveSocketChannel
import com.example.network.DedicatedSocketChannelEngine
import com.example.network.DnsResolverContract
import com.example.network.InternalDnsResolver
import com.example.network.LocalSocksAuthRelay
import com.example.network.ProxyKillSwitchContract
import com.example.network.ProxyLogContract
import com.example.network.ProxyPreferencesContract
import com.example.network.ProxyPreferencesRepository
import com.example.network.ProxyRelayEngineContract
import com.example.network.ProxySocketKillSwitch
import com.example.network.RouterIpCacheContract
import com.example.network.RouterIpCacheVault
import com.example.network.SavedProxyProfile
import com.example.network.SocketChannelContract
import com.example.network.Socks5Config
import com.example.network.Socks5HandshakeContract
import com.example.network.Socks5ProtocolEngine
import com.example.network.VpnSocketProtector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    @Test
    fun `verify app name is MC ProxyTier and default inputs start empty`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("MC ProxyTier", appName)

        val defaultConfig = Socks5Config()
        assertEquals("", defaultConfig.proxyIp)
        assertEquals("", defaultConfig.proxyPortInput)
        assertFalse(defaultConfig.isValid)
    }

    @Test
    fun `verify modular interfaces are properly wired and Cellular ZeroTier timeout is never 1500ms`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val logger: ProxyLogContract = VpnSocketProtector
        val dns: DnsResolverContract = InternalDnsResolver
        val killSwitch: ProxyKillSwitchContract = ProxySocketKillSwitch(logger)
        val socketChannel: SocketChannelContract = DedicatedSocketChannelEngine(
            context = context,
            killSwitch = killSwitch,
            dnsResolver = dns,
            logger = logger
        )
        val handshakeEngine: Socks5HandshakeContract = Socks5ProtocolEngine(
            socketChannel = socketChannel,
            killSwitch = killSwitch,
            logger = logger
        )

        val timeout = socketChannel.recommendedConnectTimeoutMs(1_500)
        assertTrue(timeout >= 4_000)
        if (socketChannel.detectActiveSocketChannel() == ActiveSocketChannel.CELLULAR_ZEROTIER) {
            assertTrue(timeout >= 5_500)
        }

        val dummySocket = Socket()
        killSwitch.trackSocket(dummySocket)
        assertEquals(1, killSwitch.activeTrackedCount())
        val killed = killSwitch.killAllSocketsNow(logIfKilled = false)
        assertEquals(1, killed)
        assertEquals(0, killSwitch.activeTrackedCount())
        assertTrue(dummySocket.isClosed)
        assertTrue(handshakeEngine is Socks5ProtocolEngine)
    }

    @Test
    fun `verify 255-slot DynamicLocalIpPool allocates 127_0_0_1 then 127_0_0_2 then 127_0_0_3 and returns to 127_0_0_1 when disconnected`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val killSwitch = ProxySocketKillSwitch(VpnSocketProtector)
        val pool = com.example.network.DynamicLocalIpPoolManager(killSwitch, VpnSocketProtector)

        val lease1 = pool.acquireNextAvailableLocalIp(basePort = 19100)
        assertEquals(1, lease1.slotIndex)
        assertEquals("127.0.0.1", lease1.localIp)

        val lease2 = pool.acquireNextAvailableLocalIp(basePort = 19100)
        assertEquals(2, lease2.slotIndex)

        val lease3 = pool.acquireNextAvailableLocalIp(basePort = 19100)
        assertEquals(3, lease3.slotIndex)

        pool.releaseAndDisconnectSlot(1)
        assertTrue(pool.isSlotCompletelyFree(1))

        val reclaimedLease1 = pool.acquireNextAvailableLocalIp(basePort = 19100)
        assertEquals(1, reclaimedLease1.slotIndex)
        assertEquals("127.0.0.1", reclaimedLease1.localIp)

        pool.releaseAllSlots()

        val relay = LocalSocksAuthRelay(context)
        val config = Socks5Config(
            proxyIp = "10.147.17.1",
            proxyPortInput = "1080",
            authEnabled = false,
            localRelayPort = 18991
        )
        val startResult = relay.startRelay(config = config)
        assertTrue(startResult.isSuccess)
        assertEquals(1, relay.activeLocalSlotIndex)

        val rotatedTo2 = relay.rotateToNextAvailableLocalIp("TEST-HANDOVER-1")
        assertEquals(2, rotatedTo2?.slotIndex)
        assertTrue(relay.getLocalIpPoolManager().isSlotCompletelyFree(1))

        val rotatedBackTo1 = relay.rotateToNextAvailableLocalIp("TEST-RETURN-TO-1")
        assertEquals(1, rotatedBackTo1?.slotIndex)
        assertEquals("127.0.0.1", rotatedBackTo1?.localIp)

        relay.stopRelay()
        assertFalse(relay.isRelayActive())
    }

    @Test
    fun `verify ProxyPreferencesRepository persists multiple saved proxy profiles`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo: ProxyPreferencesContract = ProxyPreferencesRepository(context)

        val profiles = listOf(
            SavedProxyProfile(
                id = "p1",
                name = "ZeroTier Main",
                proxyIp = "10.147.17.10",
                proxyPort = 1080,
                authEnabled = false
            ),
            SavedProxyProfile(
                id = "p2",
                name = "MikroTik Auth",
                proxyIp = "192.168.88.1",
                proxyPort = 1081,
                authEnabled = true,
                username = "admin",
                password = "secret"
            )
        )
        repo.saveProxiesList(profiles)
        repo.saveSelectedProxyId("p2")

        val reloaded = repo.loadSavedProxies()
        assertEquals(2, reloaded.size)
        assertEquals("10.147.17.10", reloaded[0].proxyIp)
        assertEquals("192.168.88.1", reloaded[1].proxyIp)
        assertTrue(reloaded[1].authEnabled)
        assertEquals("p2", repo.loadSelectedProxyId())
    }

    @Test
    fun `verify Weak Internet Turbo state persists when disabled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo: ProxyPreferencesContract = ProxyPreferencesRepository(context)

        repo.saveWeakNetTurboEnabled(false)
        assertFalse(repo.loadWeakNetTurboEnabled())

        repo.saveWeakNetTurboEnabled(true)
        assertTrue(repo.loadWeakNetTurboEnabled())
    }

    @Test
    fun `verify opening quick browser when proxy is stopped does not auto-start proxy`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repo = ProxyPreferencesRepository(app)
        repo.saveProxiesList(
            listOf(
                SavedProxyProfile(
                    id = "p_saved",
                    name = "Saved Proxy",
                    proxyIp = "10.147.17.5",
                    proxyPort = 1080
                )
            )
        )

        val vm = com.example.ui.MainViewModel(app)
        vm.openQuickBrowser("192.168.88.1")

        assertEquals(null, vm.uiState.value.activeRunningProxyId)
        assertFalse(vm.telemetry.value.localRelayRunning)
    }

    @Test
    fun `verify InternalDnsResolver parses IPv4 literals in 0ms and persists setting`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = ProxyPreferencesRepository(context)
        val dns: DnsResolverContract = InternalDnsResolver

        val ipv4Bytes = dns.parseIpv4LiteralBytes("172.22.22.22")
        assertTrue(ipv4Bytes != null && ipv4Bytes.size == 4)
        assertEquals(172, ipv4Bytes!![0].toInt() and 0xFF)
        assertEquals(22, ipv4Bytes[1].toInt() and 0xFF)

        val resolvedSock = dns.resolveSocketAddressFastSync("172.22.22.22", 8222)
        assertEquals("172.22.22.22", resolvedSock.address.hostAddress)
        assertEquals(8222, resolvedSock.port)

        repo.saveInternalDnsEnabled(false)
        assertFalse(repo.loadInternalDnsEnabled())
        repo.saveInternalDnsEnabled(true)
        assertTrue(repo.loadInternalDnsEnabled())
    }

    @Test
    fun `verify rapid Stop and Start cycle works cleanly without socket hang`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val relay: ProxyRelayEngineContract = LocalSocksAuthRelay(context)
        val config = Socks5Config(
            proxyIp = "172.22.22.22",
            proxyPortInput = "8222",
            authEnabled = false,
            localRelayPort = 18992
        )

        assertTrue(relay.startRelay(config).isSuccess)
        assertTrue(relay.isRelayActive())

        relay.stopRelay()
        assertFalse(relay.isRelayActive())

        assertTrue(relay.startRelay(config).isSuccess)
        assertTrue(relay.isRelayActive())

        relay.stopRelay()
        assertFalse(relay.isRelayActive())
    }

    @Test
    fun `verify RouterIpCacheVault caches images persistently and closing browser does not wipe cache`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vault: RouterIpCacheContract = RouterIpCacheVault
        vault.clearAllIpsCache(app)

        val ipKey = vault.normalizeIpKey("http://10.147.17.1/webfig/")
        assertEquals("10.147.17.1", ipKey)

        val fakePngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        RouterIpCacheVault.putResource(
            context = app,
            normalizedIpKey = ipKey,
            requestUrl = "http://10.147.17.1/logo.png?v=123",
            mimeType = "image/png",
            bytes = fakePngBytes
        )

        val vm = com.example.ui.MainViewModel(app)
        vm.clearAllSavedRouterIpsAndCache()

        RouterIpCacheVault.putResource(
            context = app,
            normalizedIpKey = ipKey,
            requestUrl = "http://10.147.17.1/logo.png",
            mimeType = "image/png",
            bytes = fakePngBytes
        )

        vm.openQuickBrowser("10.147.17.1")
        assertEquals(1, vm.uiState.value.savedRouterIps.size)
        assertEquals("10.147.17.1", vm.uiState.value.savedRouterIps[0].normalizedKey)
        assertEquals(1, vm.uiState.value.savedRouterIps[0].cachedImagesCount)

        vm.closeQuickBrowser()
        val cachedAfterClose = RouterIpCacheVault.getResource(
            app,
            ipKey,
            "http://10.147.17.1/logo.png"
        )
        assertTrue(cachedAfterClose != null && cachedAfterClose.bytes.size == fakePngBytes.size)

        vm.openQuickBrowser("http://10.147.17.1/")
        vm.openQuickBrowser("http://10.147.17.1/webfig/#Wireless:Interfaces")
        vm.onBrowserPageAndCacheUpdated("http://10.147.17.1/webfig/#System", "RouterOS WebFig")

        assertEquals(1, vm.uiState.value.savedRouterIps.size)
        assertEquals("10.147.17.1", vm.uiState.value.savedRouterIps[0].displayIp)
        assertEquals("http://10.147.17.1", vm.uiState.value.savedRouterIps[0].fullUrl)
        assertEquals("10.147.17.1", vm.uiState.value.quickIpInput)
        assertEquals("http://10.147.17.1", vm.uiState.value.activeBrowserUrl)
        assertEquals(3, vm.uiState.value.savedRouterIps[0].openCount)
        assertEquals(1, vm.uiState.value.savedRouterIps[0].cachedImagesCount)
    }
}
