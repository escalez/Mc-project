package com.example.network

import kotlinx.coroutines.flow.StateFlow

/**
 * واجهة مستقلة خاصة بنظام السجل الحي والإحصائيات (Logger & Telemetry Contract).
 * تفصل منطق تسجيل الأحداث وحركة البيانات عن بقية ملفات السوكيت والشبكة.
 */
interface ProxyLogContract {
    val telemetry: StateFlow<TunnelTelemetry>
    val logs: StateFlow<List<PacketLogEntry>>

    fun log(tag: String, message: String, level: LogLevel = LogLevel.INFO)
    fun clearLogs()
    fun updateTelemetry(transform: (TunnelTelemetry) -> TunnelTelemetry)
    fun recordBytes(sentDelta: Long = 0L, receivedDelta: Long = 0L)
    fun incrementHandshakes()
    fun adjustActiveConnections(delta: Int)
}
