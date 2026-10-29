package com.example.network

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class DynamicLocalIpPoolManager(
    private val killSwitch: ProxyKillSwitchContract,
    private val logger: ProxyLogContract = VpnSocketProtector
) : LocalIpPoolContract {

    private class SlotState(val slotIndex: Int) {
        @Volatile
        var inUse: Boolean = false

        @Volatile
        var serverSocket: ServerSocket? = null

        val activeSockets: MutableSet<Socket> =
            Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    }

    private val lock = Any()
    private val slots = Array(LocalIpPoolContract.MAX_LOCAL_IP_SLOTS + 1) { idx ->
        SlotState(idx)
    }

    companion object {
        fun slotIndexToIpString(slotIndex: Int): String {
            val safeIdx = slotIndex.coerceIn(1, LocalIpPoolContract.MAX_LOCAL_IP_SLOTS)
            return if (safeIdx <= 254) "127.0.0.$safeIdx" else "127.0.1.1"
        }

        fun slotIndexToIpBytes(slotIndex: Int): ByteArray {
            val safeIdx = slotIndex.coerceIn(1, LocalIpPoolContract.MAX_LOCAL_IP_SLOTS)
            return if (safeIdx <= 254) {
                byteArrayOf(127, 0, 0, safeIdx.toByte())
            } else {
                byteArrayOf(127, 0, 1, 1)
            }
        }
    }

    private fun reclaimDisconnectedSlotsLocked() {
        for (idx in 1..LocalIpPoolContract.MAX_LOCAL_IP_SLOTS) {
            val slot = slots[idx]
            val iterator = slot.activeSockets.iterator()
            while (iterator.hasNext()) {
                val sock = iterator.next()
                if (sock.isClosed || !sock.isConnected) {
                    iterator.remove()
                }
            }
            val srv = slot.serverSocket
            if ((srv == null || srv.isClosed) && slot.activeSockets.isEmpty()) {
                slot.serverSocket = null
                slot.inUse = false
            }
        }
    }

    override fun acquireNextAvailableLocalIp(
        basePort: Int,
        excludeSlotIndex: Int
    ): LocalIpLease = synchronized(lock) {
        reclaimDisconnectedSlotsLocked()
        val safeBasePort = basePort.coerceIn(1025, 65000)

        for (slotIdx in 1..LocalIpPoolContract.MAX_LOCAL_IP_SLOTS) {
            if (slotIdx == excludeSlotIndex) continue
            val slot = slots[slotIdx]
            if (!slot.inUse && slot.activeSockets.isEmpty()) {
                val lease = tryBindSlotServer(slotIdx, safeBasePort)
                if (lease != null) {
                    slot.inUse = true
                    slot.serverSocket = lease.serverSocket
                    killSwitch.attachServerSocket(lease.serverSocket)
                    return@synchronized lease
                }
            }
        }

        for (slotIdx in 1..LocalIpPoolContract.MAX_LOCAL_IP_SLOTS) {
            if (slotIdx == excludeSlotIndex) continue
            val slot = slots[slotIdx]
            if (slot.activeSockets.isEmpty()) {
                releaseAndDisconnectSlotLocked(slotIdx)
                val lease = tryBindSlotServer(slotIdx, safeBasePort)
                if (lease != null) {
                    slot.inUse = true
                    slot.serverSocket = lease.serverSocket
                    killSwitch.attachServerSocket(lease.serverSocket)
                    return@synchronized lease
                }
            }
        }

        val fallbackIdx = if (excludeSlotIndex == 1) 2 else 1
        releaseAndDisconnectSlotLocked(fallbackIdx)
        val fallbackLease = tryBindSlotServer(fallbackIdx, safeBasePort)
            ?: throw IllegalStateException("تعذر حجز آيبي محلي من المجمع (1..255)")
        slots[fallbackIdx].inUse = true
        slots[fallbackIdx].serverSocket = fallbackLease.serverSocket
        killSwitch.attachServerSocket(fallbackLease.serverSocket)
        return@synchronized fallbackLease
    }

    private fun tryBindSlotServer(slotIndex: Int, basePort: Int): LocalIpLease? {
        val targetIp = slotIndexToIpString(slotIndex)
        val targetIpBytes = slotIndexToIpBytes(slotIndex)
        val preferredPort = (basePort + slotIndex - 1).coerceIn(1025, 65534)

        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(targetIp, preferredPort), 256)
            return LocalIpLease(
                slotIndex = slotIndex,
                localIp = targetIp,
                localIpBytes = targetIpBytes,
                localPort = server.localPort,
                serverSocket = server
            )
        } catch (_: Exception) {
        }

        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(targetIp, 0), 256)
            return LocalIpLease(
                slotIndex = slotIndex,
                localIp = targetIp,
                localIpBytes = targetIpBytes,
                localPort = server.localPort,
                serverSocket = server
            )
        } catch (_: Exception) {
        }

        return try {
            val server = ServerSocket()
            server.reuseAddress = true
            try {
                server.bind(InetSocketAddress("127.0.0.1", preferredPort), 256)
            } catch (_: Exception) {
                server.bind(InetSocketAddress("127.0.0.1", 0), 256)
            }
            LocalIpLease(
                slotIndex = slotIndex,
                localIp = "127.0.0.1",
                localIpBytes = byteArrayOf(127, 0, 0, 1),
                localPort = server.localPort,
                serverSocket = server
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun registerSocketOnSlot(slotIndex: Int, socket: Socket) {
        if (slotIndex !in 1..LocalIpPoolContract.MAX_LOCAL_IP_SLOTS) return
        slots[slotIndex].activeSockets.add(socket)
        killSwitch.trackSocket(socket)
    }

    override fun unregisterSocketOnSlot(slotIndex: Int, socket: Socket) {
        if (slotIndex !in 1..LocalIpPoolContract.MAX_LOCAL_IP_SLOTS) return
        val slot = slots[slotIndex]
        slot.activeSockets.remove(socket)
        killSwitch.untrackAndCloseSocket(socket)

        synchronized(lock) {
            val srv = slot.serverSocket
            if ((srv == null || srv.isClosed) && slot.activeSockets.isEmpty()) {
                slot.serverSocket = null
                slot.inUse = false
            }
        }
    }

    override fun releaseAndDisconnectSlot(slotIndex: Int): Int = synchronized(lock) {
        releaseAndDisconnectSlotLocked(slotIndex)
    }

    private fun releaseAndDisconnectSlotLocked(slotIndex: Int): Int {
        if (slotIndex !in 1..LocalIpPoolContract.MAX_LOCAL_IP_SLOTS) return 0
        val slot = slots[slotIndex]
        var closedCount = 0

        slot.serverSocket?.let { srv ->
            try {
                if (!srv.isClosed) {
                    srv.close()
                    closedCount++
                }
            } catch (_: Exception) {
            }
        }
        slot.serverSocket = null

        val socketsSnapshot = slot.activeSockets.toList()
        slot.activeSockets.clear()
        for (sock in socketsSnapshot) {
            try {
                if (!sock.isClosed) {
                    try { sock.setSoLinger(true, 0) } catch (_: Exception) {}
                    try { sock.shutdownInput() } catch (_: Exception) {}
                    try { sock.shutdownOutput() } catch (_: Exception) {}
                    sock.close()
                    closedCount++
                }
            } catch (_: Exception) {
            } finally {
                killSwitch.untrackAndCloseSocket(sock)
            }
        }

        slot.inUse = false
        return closedCount
    }

    override fun releaseAllSlots(): Int = synchronized(lock) {
        var totalClosed = 0
        for (idx in 1..LocalIpPoolContract.MAX_LOCAL_IP_SLOTS) {
            totalClosed += releaseAndDisconnectSlotLocked(idx)
        }
        totalClosed
    }

    override fun getActiveSocketCountForSlot(slotIndex: Int): Int {
        if (slotIndex !in 1..LocalIpPoolContract.MAX_LOCAL_IP_SLOTS) return 0
        val slot = slots[slotIndex]
        slot.activeSockets.removeIf { it.isClosed || !it.isConnected }
        return slot.activeSockets.size
    }

    override fun isSlotCompletelyFree(slotIndex: Int): Boolean = synchronized(lock) {
        if (slotIndex !in 1..LocalIpPoolContract.MAX_LOCAL_IP_SLOTS) return false
        reclaimDisconnectedSlotsLocked()
        val slot = slots[slotIndex]
        !slot.inUse && slot.activeSockets.isEmpty()
    }
}
