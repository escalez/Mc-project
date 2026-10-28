package com.example.network

import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * تطبيق مستقل لنظام قطع اتصال البروكسي وإغلاق السوكيتات (ProxySocketKillSwitch).
 * - يغلق جميع السوكيتات المعلقة عند التحول من وايفاي إلى بيانات الهاتف لمنع بقاء اتصالات ميتة على ZeroTier.
 * - يغلق السوكيتات العادية بـ TCP FIN نظيف حتى لا يحظر سيرفر المايكروتيك عنوان ZeroTier المحلي،
 *   ويستخدم الإغلاق القسري الفوري عند إيقاف البروكسي أو تبديل الشبكة.
 */
class ProxySocketKillSwitch(
    private val logger: ProxyLogContract = VpnSocketProtector
) : ProxyKillSwitchContract {

    private val trackedSockets: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    private val serverSocketRef = AtomicReference<ServerSocket?>(null)

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
        val prev = serverSocketRef.getAndSet(server)
        if (prev != null && prev !== server) {
            try {
                prev.close()
            } catch (_: Exception) {
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

        val srv = serverSocketRef.getAndSet(null)
        if (srv != null && !srv.isClosed) {
            try {
                srv.close()
                killedCount++
            } catch (_: Exception) {
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
                try {
                    sock.shutdownOutput()
                } catch (_: Exception) {
                }
                try {
                    sock.shutdownInput()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        try {
            sock.close()
        } catch (_: Exception) {
        }
    }

    private fun forceCloseSingleSocket(sock: Socket) {
        try {
            if (!sock.isClosed && sock.isConnected) {
                sock.setSoLinger(true, 0)
            }
        } catch (_: Exception) {
        }
        try {
            sock.close()
        } catch (_: Exception) {
        }
    }
}
