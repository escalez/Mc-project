package com.example.network

import java.net.ServerSocket
import java.net.Socket

interface ProxyKillSwitchContract {
    fun trackSocket(socket: Socket?): Socket?
    fun untrackAndCloseSocket(socket: Socket?)
    fun attachServerSocket(server: ServerSocket?)
    fun activeTrackedCount(): Int
    fun flushActiveSocketsPreserveServer(reasonTag: String = "HANDOVER-FLUSH"): Int
    fun killAllSocketsNow(reasonTag: String = "KILL-SWITCH", logIfKilled: Boolean = true): Int
}
