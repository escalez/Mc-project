package com.example.network

import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class ProxySocketKillSwitch(
    private val logger: ProxyLogContract = VpnSocketProtector
) : ProxyKillSwitchContract {

    private val trackedSockets: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    private val trackedServerSockets: MutableSet<ServerSocket> =
        Collections.newSetFromMap(ConcurrentHashMap<ServerSocket, Boolean>())

    override fun trackSocket(socket: Socket?): Socket? {
        if (socket != null) {
            trackedSockets.add(socket)
        }
        return socket
    }

    override fun untrackAndCloseSocket(socket: Socket?) {
        if (socket == null) return
        trackedSockets.remove(socket)
        gracefulCloseSingleSocket(socket)
    }

    override fun attachServerSocket(server: ServerSocket?) {
        if (server != null) {
            trackedServerSockets.add(server)
        } else {
            val servers = trackedServerSockets.toList()
            trackedServerSockets.clear()
            for (srv in servers) {
                try { srv.close() } catch (_: Exception) {}
            }
        }
    }

    override fun activeTrackedCount(): Int = trackedSockets.size

    override fun flushActiveSocketsPreserveServer(reasonTag: String): Int {
        var flushedCount = 0
        val snapshot = trackedSockets.toList()
        trackedSockets.clear()

        for (sock in snapshot) {
            if (!sock.isClosed) {
                flushedCount++
            }
            forceCloseSingleSocket(sock)
        }

        if (flushedCount > 0) {
            logger.log(
                reasonTag,
                "تم تنظيف وإغلاق $flushedCount سوكيت معلق من الشبكة السابقة لتحرير مسار ZeroTier",
                LogLevel.INFO
            )
        }
        return flushedCount
    }

    override fun killAllSocketsNow(reasonTag: String, logIfKilled: Boolean): Int {
        var killedCount = 0

        val servers = trackedServerSockets.toList()
        trackedServerSockets.clear()
        for (srv in servers) {
            if (!srv.isClosed) {
                try {
                    srv.close()
                    killedCount++
                } catch (_: Exception) {
                }
            }
        }

        val snapshot = trackedSockets.toList()
        trackedSockets.clear()

        for (sock in snapshot) {
            if (!sock.isClosed) {
                killedCount++
            }
            forceCloseSingleSocket(sock)
        }

        if (logIfKilled && killedCount > 0) {
            logger.log(
                reasonTag,
                "تم قطع اتصال البروكسي وإغلاق السوكيت بالكامل ($killedCount سوكيت)",
                LogLevel.WARN
            )
        }

        return killedCount
    }

    private fun gracefulCloseSingleSocket(sock: Socket) {
        try {
            if (!sock.isClosed && sock.isConnected) {
                try { sock.shutdownOutput() } catch (_: Exception) {}
                try { sock.shutdownInput() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
        try { sock.close() } catch (_: Exception) {}
    }

    private fun forceCloseSingleSocket(sock: Socket) {
        try {
            if (!sock.isClosed && sock.isConnected) {
                sock.setSoLinger(true, 0)
            }
        } catch (_: Exception) {
        }
        try { sock.close() } catch (_: Exception) {}
    }
}
