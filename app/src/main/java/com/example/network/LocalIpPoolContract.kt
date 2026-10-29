package com.example.network

import java.net.ServerSocket
import java.net.Socket

data class LocalIpLease(
    val slotIndex: Int,
    val localIp: String,
    val localIpBytes: ByteArray,
    val localPort: Int,
    val serverSocket: ServerSocket
) {
    val bindAddress: String
        get() = "$localIp:$localPort"
}

interface LocalIpPoolContract {
    companion object {
        const val MAX_LOCAL_IP_SLOTS = 255
    }

    fun acquireNextAvailableLocalIp(
        basePort: Int = 10808,
        excludeSlotIndex: Int = -1
    ): LocalIpLease

    fun registerSocketOnSlot(slotIndex: Int, socket: Socket)
    fun unregisterSocketOnSlot(slotIndex: Int, socket: Socket)
    fun releaseAndDisconnectSlot(slotIndex: Int): Int
    fun releaseAllSlots(): Int
    fun getActiveSocketCountForSlot(slotIndex: Int): Int
    fun isSlotCompletelyFree(slotIndex: Int): Boolean
}
