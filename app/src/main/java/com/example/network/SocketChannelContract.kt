package com.example.network

import java.net.Socket

interface SocketChannelContract {
    fun currentSocketChannel(): ActiveSocketChannel
    fun detectActiveSocketChannel(): ActiveSocketChannel
    fun recommendedConnectTimeoutMs(requestedTimeoutMs: Int = 5_000): Int
    fun openDedicatedChannelSocket(
        host: String,
        port: Int,
        timeoutMs: Int,
        isAllowedToConnect: () -> Boolean = { true }
    ): Socket
    fun registerNetworkMonitor(onActualChannelChanged: (oldChannel: ActiveSocketChannel, newChannel: ActiveSocketChannel) -> Unit)
    fun unregisterNetworkMonitor()
    fun clearStaleProcessNetworkBinding()
}
