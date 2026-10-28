package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.network.LogLevel
import com.example.network.PacketLogEntry
import com.example.network.SavedRouterIpEntry
import com.example.ui.theme.AmberPulse
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.CyberBorder
import com.example.ui.theme.CyberCard
import com.example.ui.theme.CyberObsidian
import com.example.ui.theme.CyberSurface
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldTunnel
import com.example.ui.theme.RobotoMonoFontFamily
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

/**
 * Middle Tab (Index 1): "الايبيات المحفوظة مع الكاش الخاص بها"
 * - Automatically lists every IP opened in the WebView along with its cached images & files.
 * - Strictly deduplicated by IP/Host: opening an already-saved IP never duplicates it here;
 *   instead it pulls its saved cache immediately so images load in 0ms over Mobile Data.
 */
@Composable
fun SavedIpsWithCacheTab(
    savedRouterIps: List<SavedRouterIpEntry>,
    isProxyActive: Boolean,
    onOpenSavedIpWithCache: (String) -> Unit,
    onClearSingleIpCache: (String) -> Unit,
    onDeleteSavedIp: (SavedRouterIpEntry) -> Unit,
    onClearAllSavedIps: () -> Unit
) {
    val totalCachedImages = savedRouterIps.sumOf { it.cachedImagesCount }
    val totalCachedFiles = savedRouterIps.sumOf { it.cachedFilesCount }
    val totalCachedBytes = savedRouterIps.sumOf { it.cachedBytesSize }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("saved_ips_cache_tab_container"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Summary Card
        Surface(
            color = CyberCard,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, EmeraldTunnel.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(EmeraldTunnel.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = null,
                                tint = EmeraldTunnel,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "الايبيات المحفوظة مع الكاش (${savedRouterIps.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "حفظ تلقائي لكل IP تفتحه مع كاش الصور والملفات لفتحها فوراً عبر البيانات بدون تكرار ⚡",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 11.sp,
                                color = EmeraldTunnel
                            )
                        }
                    }

                    if (savedRouterIps.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onClearAllSavedIps,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("clear_all_saved_ips_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "حذف الكل",
                                tint = CrimsonAlert,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "حذف الكل",
                                color = CrimsonAlert,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Cache Vault Telemetry Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CyberSurface, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CacheSummaryMetric(
                        label = "الايبيات المحفوظة",
                        value = "${savedRouterIps.size} IP",
                        color = ElectricCyan
                    )
                    CacheSummaryMetric(
                        label = "الصور المحفوظة",
                        value = "$totalCachedImages صورة",
                        color = EmeraldTunnel
                    )
                    CacheSummaryMetric(
                        label = "ملفات الويب",
                        value = "$totalCachedFiles ملف",
                        color = AmberPulse
                    )
                    CacheSummaryMetric(
                        label = "حجم الكاش الكلي",
                        value = formatBytesShort(totalCachedBytes),
                        color = Color.White
                    )
                }
            }
        }

        if (savedRouterIps.isEmpty()) {
            Surface(
                color = CyberCard,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(1.dp, CyberBorder, RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "لا توجد ايبيات محفوظة مع الكاش بعد",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "بمجرد فتح أي IP في صفحة الويب سيُحفظ هنا تلقائياً مع كاش الصور والملفات الخاصة به، وعند فتح نفس الـ IP مرة أخرى سيسحب الكاش ويفتح فوراً بدون تكرار.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("saved_router_ips_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(savedRouterIps, key = { it.normalizedKey }) { entry ->
                    SavedRouterIpCard(
                        entry = entry,
                        isProxyActive = isProxyActive,
                        onOpenWithCache = { onOpenSavedIpWithCache(entry.displayIp) },
                        onResetCacheOnly = { onClearSingleIpCache(entry.normalizedKey) },
                        onDelete = { onDeleteSavedIp(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CacheSummaryMetric(
    label: String,
    value: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = TextMuted
        )
        Text(
            text = value,
            fontFamily = RobotoMonoFontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun SavedRouterIpCard(
    entry: SavedRouterIpEntry,
    isProxyActive: Boolean,
    onOpenWithCache: () -> Unit,
    onResetCacheOnly: () -> Unit,
    onDelete: () -> Unit
) {
    val hasCachedAssets = entry.cachedImagesCount > 0 || entry.cachedFilesCount > 0
    Surface(
        color = CyberCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.2.dp,
                color = if (hasCachedAssets) EmeraldTunnel.copy(alpha = 0.7f) else ElectricCyan.copy(alpha = 0.5f),
                shape = RoundedCornerShape(14.dp)
            )
            .testTag("saved_router_ip_card_${entry.normalizedKey}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Router,
                        contentDescription = null,
                        tint = if (hasCachedAssets) EmeraldTunnel else ElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = entry.displayIp,
                                fontFamily = RobotoMonoFontFamily,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = if (hasCachedAssets) {
                                    EmeraldTunnel.copy(alpha = 0.18f)
                                } else {
                                    ElectricCyan.copy(alpha = 0.18f)
                                },
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = if (hasCachedAssets) {
                                        "كاش الصور جاهز ⚡"
                                    } else {
                                        "محفوظ بدون تكرار"
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasCachedAssets) EmeraldTunnel else ElectricCyan,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (entry.pageTitle.isNotBlank()) {
                            Text(
                                text = entry.pageTitle,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("delete_saved_ip_${entry.normalizedKey}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "حذف الـ IP والكاش",
                        tint = CrimsonAlert,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Detailed Image & Asset Cache Pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberSurface, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "الصور: ${entry.cachedImagesCount} صورة",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = EmeraldTunnel
                )
                Text(
                    text = "الملفات: ${entry.cachedFilesCount} ملف",
                    fontSize = 11.sp,
                    color = ElectricCyan
                )
                Text(
                    text = "الحجم: ${formatBytesShort(entry.cachedBytesSize)}",
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 11.sp,
                    color = AmberPulse
                )
                Text(
                    text = "الفتح: ${entry.openCount}×",
                    fontFamily = RobotoMonoFontFamily,
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }

            // Action Buttons: Instant Open from Cache + Refresh Cache
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onOpenWithCache,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldTunnel),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1.5f)
                        .height(40.dp)
                        .testTag("open_saved_ip_${entry.normalizedKey}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isProxyActive) {
                            "فتح بالكاش عبر البروكسي ⚡"
                        } else {
                            "فتح بالكاش الفوري الآن ⚡"
                        },
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                OutlinedButton(
                    onClick = onResetCacheOnly,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(0.9f)
                        .height(40.dp)
                        .testTag("reset_ip_cache_${entry.normalizedKey}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = AmberPulse,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "تحديث الكاش",
                        color = AmberPulse,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PacketLogsAndAuditTab(
    logs: List<PacketLogEntry>,
    onClearLogs: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            color = CyberCard,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CyberBorder, RoundedCornerShape(14.dp))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "بنية وضع البروكسي المستقل (ZeroTier Compatible)",
                    style = MaterialTheme.typography.titleMedium,
                    color = ElectricCyan
                )
                Text(
                    text = "• يعمل التطبيق كبروكسي مستقل بدون تفعيل VpnService الخاص بالنظام، مما يسمح بتشغيل تطبيق ZeroTier الخارجي في الخلفية بدون أي تعارض.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Text(
                    text = "• يحفظ تلقائياً كل IP تفتحه مع كاش الصور والملفات في خانة (الايبيات المحفوظة + الكاش) لفتح الصفحات فوراً عبر بيانات الهاتف والزيرو تاير.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = EmeraldTunnel,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "سجل حركة البروكسي الحي (${logs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }

            IconButton(
                onClick = onClearLogs,
                modifier = Modifier.testTag("clear_logs_button")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "مسح السجل",
                    tint = TextSecondary
                )
            }
        }

        Surface(
            color = CyberObsidian,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, CyberBorder, RoundedCornerShape(14.dp))
        ) {
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "السجل نظيف — قم بتشغيل البروكسي أو فتح صفحة لعرض حركة الاتصال",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp)
                        .testTag("packet_logs_list"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(logs, key = { it.id }) { entry ->
                        val badgeColor = when (entry.level) {
                            LogLevel.SUCCESS -> EmeraldTunnel
                            LogLevel.WARN -> AmberPulse
                            LogLevel.ERROR -> CrimsonAlert
                            LogLevel.INFO -> ElectricCyan
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CyberSurface, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "[${entry.timestamp}]",
                                fontFamily = RobotoMonoFontFamily,
                                fontSize = 10.sp,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = entry.tag,
                                fontFamily = RobotoMonoFontFamily,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = entry.message,
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 12.sp,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
