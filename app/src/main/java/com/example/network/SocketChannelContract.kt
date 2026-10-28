package com.example.network

import java.net.Socket

/**
 * واجهة مستقلة خاصة بإنشاء وإدارة قنوات السوكيت (Socket Channel Contract).
 * تفصل منطق سوكيت الواي فاي وسوكيت بيانات الهاتف + ZeroTier عن بروتوكول SOCKS5 والجسر المحلي.
 */
interface SocketChannelContract {
    /**
     * القناة النشطة حالياً (WIFI_DEDICATED أو CELLULAR_ZEROTIER أو STANDBY).
     */
    fun currentSocketChannel(): ActiveSocketChannel

    /**
     * يكتشف القناة الفيزيائية الحالية للجهاز (واي فاي أو بيانات الهاتف + ZeroTier).
     */
    fun detectActiveSocketChannel(): ActiveSocketChannel

    /**
     * يحدد المهلة المثالية لفتح السوكيت حسب نوع الشبكة:
     * - على بيانات الهاتف + ZeroTier: مهلة كافية (7500ms) لعبور نفق UDP الخاص بـ ZeroTier بدون انقطاع مبكر.
     * - على الواي فاي: مهلة سريعة (4500ms).
     */
    fun recommendedConnectTimeoutMs(requestedTimeoutMs: Int = 5_000): Int

    /**
     * يفتح سوكيت TCP واحد نظيف ومستقر نحو البروكسي (`host:port`) بدون عاصفة فتح سوكيتات متعددة
     * وبدون فرض ربط خاطئ على عنوان ZeroTier المحلي يمنع اكتمال مصافحة TCP عبر بيانات الهاتف.
     */
    fun openDedicatedChannelSocket(
        host: String,
        port: Int,
        timeoutMs: Int,
        isAllowedToConnect: () -> Boolean = { true }
    ): Socket

    /**
     * يبدأ مراقبة التحول الفعلي بين الواي فاي وبيانات الهاتف فقط (مع تجاهل تغير أبراج التغطية اللحظي).
     */
    fun registerNetworkMonitor(onActualChannelChanged: (oldChannel: ActiveSocketChannel, newChannel: ActiveSocketChannel) -> Unit)

    /**
     * يوقف مراقبة الشبكة عند إيقاف البروكسي.
     */
    fun unregisterNetworkMonitor()

    /**
     * يزيل أي تقييد قديم لربط سوكيتات العملية بشبكة سابقة.
     */
    fun clearStaleProcessNetworkBinding()
}
