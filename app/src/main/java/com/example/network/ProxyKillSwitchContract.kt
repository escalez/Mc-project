package com.example.network

import java.net.ServerSocket
import java.net.Socket

/**
 * واجهة مستقلة خاصة بنظام قطع اتصال البروكسي وإغلاق السوكيتات (Proxy Kill-Switch Contract).
 * تفصل إدارة السوكيتات النشطة وإغلاقها الفوري عند إيقاف البروكسي أو عند التحويل بين الشبكات.
 */
interface ProxyKillSwitchContract {
    /**
     * تسجيل سوكيت جديد ضمن قائمة السوكيتات المراقبة.
     */
    fun trackSocket(socket: Socket?): Socket?

    /**
     * إلغاء مراقبة سوكيت وإغلاقه بشكل نظيف (FIN).
     */
    fun untrackAndCloseSocket(socket: Socket?)

    /**
     * ربط ServerSocket المحلي ليتم إغلاقه فوراً عند تفعيل قاطع السوكيت.
     */
    fun attachServerSocket(server: ServerSocket?)

    /**
     * عدد السوكيتات النشطة المسجلة حالياً.
     */
    fun activeTrackedCount(): Int

    /**
     * تنظيف وإغلاق جميع السوكيتات المعلقة من الشبكة السابقة (عند التحول من وايفاي إلى بيانات الهاتف)
     * مع الإبقاء على ServerSocket المحلي مفتوحاً.
     */
    fun flushActiveSocketsPreserveServer(reasonTag: String = "HANDOVER-FLUSH"): Int

    /**
     * إغلاق جميع السوكيتات النشطة والمعلقة فوراً وبضربة واحدة نظيفة (مع إغلاق ServerSocket).
     */
    fun killAllSocketsNow(reasonTag: String = "KILL-SWITCH", logIfKilled: Boolean = true): Int
}
