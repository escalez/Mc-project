package com.example.network

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object VpnSocketProtector : ProxyLogContract {

    private val totalSentBytes = AtomicLong(0L)
    private val totalReceivedBytes = AtomicLong(0L)
    private val lastTelemetryEmitUptime = AtomicLong(0L)

    private val _telemetry = MutableStateFlow(TunnelTelemetry())
    override val telemetry: StateFlow<TunnelTelemetry> = _telemetry.asStateFlow()

    private val _logs = MutableStateFlow<List<PacketLogEntry>>(emptyList())
    override val logs: StateFlow<List<PacketLogEntry>> = _logs.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun updateTelemetry(transform: (TunnelTelemetry) -> TunnelTelemetry) {
        _telemetry.update { current ->
            transform(
                current.copy(
                    bytesSent = totalSentBytes.get(),
                    bytesReceived = totalReceivedBytes.get()
                )
            )
        }
    }

    override fun recordBytes(sentDelta: Long, receivedDelta: Long) {
        val newSent = if (sentDelta > 0) totalSentBytes.addAndGet(sentDelta) else totalSentBytes.get()
        val newRecv = if (receivedDelta > 0) totalReceivedBytes.addAndGet(receivedDelta) else totalReceivedBytes.get()

        val now = SystemClock.elapsedRealtime()
        val prev = lastTelemetryEmitUptime.get()
        if (now - prev >= 600L && lastTelemetryEmitUptime.compareAndSet(prev, now)) {
            _telemetry.update { current ->
                current.copy(
                    bytesSent = newSent,
                    bytesReceived = newRecv
                )
            }
        }
    }

    override fun incrementHandshakes() {
        _telemetry.update { current ->
            current.copy(
                totalHandshakes = current.totalHandshakes + 1,
                bytesSent = totalSentBytes.get(),
                bytesReceived = totalReceivedBytes.get()
            )
        }
    }

    override fun adjustActiveConnections(delta: Int) {
        _telemetry.update { current ->
            current.copy(
                activeConnections = (current.activeConnections + delta).coerceAtLeast(0),
                bytesSent = totalSentBytes.get(),
                bytesReceived = totalReceivedBytes.get()
            )
        }
    }

    override fun log(tag: String, message: String, level: LogLevel) {
        val entry = PacketLogEntry(
            timestamp = synchronized(timeFormatter) { timeFormatter.format(Date()) },
            tag = tag,
            message = message,
            level = level
        )
        _logs.update { current ->
            (listOf(entry) + current).take(80)
        }
    }

    override fun clearLogs() {
        _logs.value = emptyList()
    }
}
