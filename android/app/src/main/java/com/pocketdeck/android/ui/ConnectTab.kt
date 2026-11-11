package com.pocketdeck.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.automirrored.rounded.StopScreenShare
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketdeck.android.services.DiscoveredPeer
import com.pocketdeck.android.services.RemoteControlService
import com.pocketdeck.android.services.ScreenShareService
import com.pocketdeck.android.state.PocketDeckEngine

@Composable
fun ConnectTab(
    onRequestScreenCapture: () -> Unit,
    onOpenRemoteViewer: () -> Unit,
    onOpenFileTab: () -> Unit,
) {
    val context = LocalContext.current
    val settings by PocketDeckEngine.settings.collectAsState()
    val conn by PocketDeckEngine.connection.collectAsState()
    val isSharing by ScreenShareService.isSharing.collectAsState()
    val a11yInstance by RemoteControlService.instance.collectAsState()
    val peersFlow = PocketDeckEngine.discovery?.peersFlow
    val peers by (peersFlow?.collectAsState() ?: remember { mutableStateOf(emptyList()) })

    var partnerTarget by rememberSaveable { mutableStateOf("") }
    var partnerPassword by rememberSaveable { mutableStateOf("") }
    var showQrDialog by remember { mutableStateOf(false) }

    val primaryIp = settings.lanIps.firstOrNull() ?: "Chưa kết nối Wi-Fi"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Active Connection Banner
        if (conn.connected) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)),
                border = BorderStroke(1.dp, EmeraldOk),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = EmeraldOk)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (conn.isInbound) "Đối tác đang kết nối: ${conn.peerName}"
                                else "Đang kết nối tới: ${conn.peerName}",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                            )
                            Text(
                                text = "IP: ${conn.remoteIp}  •  Mã hoá AES-256-GCM",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFA7F3D0),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!conn.isInbound) {
                            Button(
                                onClick = onOpenRemoteViewer,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldOk, contentColor = SlateBg),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Xem màn hình", fontWeight = FontWeight.Bold)
                            }
                        }
                        FilledTonalButton(onClick = onOpenFileTab, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Truyền File")
                        }
                        OutlinedButton(
                            onClick = { PocketDeckEngine.disconnectActiveSession() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFCA5A5)),
                            border = BorderStroke(1.dp, Color(0xFFF87171)),
                        ) {
                            Icon(Icons.Rounded.LinkOff, contentDescription = "Ngắt kết nối", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // UltraViewer ID & Password Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCardElevated),
            border = BorderStroke(1.dp, SlateBorder),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "CHO PHÉP ĐIỀU KHIỂN & TRUYỀN FILE",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentSky,
                    )
                    Row {
                        IconButton(
                            onClick = { showQrDialog = true },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(Icons.Rounded.QrCode2, contentDescription = "Mã QR kết nối", tint = TextPrimary)
                        }
                        IconButton(
                            onClick = {
                                val text = "ID: ${settings.formattedId} | Mật khẩu: ${settings.activePassword} | IP: $primaryIp:${settings.hostPort}"
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("PocketDeck ID", text))
                                PocketDeckEngine.emitToast("Đã sao chép ID & Mật khẩu")
                            },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Sao chép ID", tint = TextPrimary)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column {
                        Text("ID của bạn", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Text(
                            text = settings.formattedId,
                            style = TabularTextStyle.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold),
                            color = TextPrimary,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Mật khẩu", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = settings.activePassword,
                                style = TabularTextStyle.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                                color = Color(0xFFFDE047),
                            )
                            if (settings.fixedPassword.isBlank()) {
                                IconButton(
                                    onClick = { PocketDeckEngine.regenerateSessionPassword() },
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Refresh,
                                        contentDescription = "Đổi mật khẩu phiên",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = SlateBorder)

                Text(
                    text = "Wi-Fi: $primaryIp  •  Cổng ${settings.hostPort}  •  Tên: ${settings.deviceName}",
                    style = TabularTextStyle.copy(fontSize = 12.sp),
                    color = TextSecondary,
                )

                // Screen Share + Accessibility Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!isSharing) {
                        Button(
                            onClick = onRequestScreenCapture,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ScreenShare, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Chia sẻ màn hình", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = { PocketDeckEngine.stopScreenShareService() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Color.White),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.StopScreenShare, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Dừng chia sẻ", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                val a11yEnabled = a11yInstance != null
                Surface(
                    color = if (a11yEnabled) Color(0xFF064E3B).copy(alpha = 0.45f) else Color(0xFF78350F).copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (a11yEnabled) EmeraldOk.copy(alpha = 0.5f) else AmberWarn.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            }
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.TouchApp,
                            contentDescription = null,
                            tint = if (a11yEnabled) EmeraldOk else AmberWarn,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (a11yEnabled) "Điều khiển cảm ứng từ xa: Đã bật"
                            else "Bấm để bật quyền Trợ năng (cho phép PC chạm/vuốt)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // Connect to Partner Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, SlateBorder),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "ĐIỀU KHIỂN HOẶC TRUYỀN FILE TỚI ĐỐI TÁC",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentSky,
                )

                OutlinedTextField(
                    value = partnerTarget,
                    onValueChange = { partnerTarget = it },
                    label = { Text("ID Đối tác (9 số) hoặc IP:Cổng") },
                    placeholder = { Text("VD: 482 910 374 hoặc 192.168.1.15") },
                    singleLine = true,
                    textStyle = TabularTextStyle.copy(fontSize = 16.sp, color = TextPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = partnerPassword,
                    onValueChange = { partnerPassword = it },
                    label = { Text("Mật khẩu phiên (6 chữ số)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = TabularTextStyle.copy(fontSize = 16.sp, color = TextPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            PocketDeckEngine.connectToPartner(partnerTarget, partnerPassword) {
                                onOpenRemoteViewer()
                            }
                        },
                        enabled = !conn.connecting,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        if (conn.connecting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("Điều khiển", fontWeight = FontWeight.SemiBold)
                    }

                    FilledTonalButton(
                        onClick = {
                            PocketDeckEngine.connectToPartner(partnerTarget, partnerPassword) {
                                onOpenFileTab()
                            }
                        },
                        enabled = !conn.connecting,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Truyền File", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Discovered LAN Peers Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, SlateBorder),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "THIẾT BỊ TRONG MẠNG WI-FI (${peers.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                    )
                    TextButton(onClick = { PocketDeckEngine.discovery?.refreshPeers() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Quét lại")
                    }
                }

                if (peers.isEmpty()) {
                    Text(
                        text = "Mở PocketDeck trên máy tính Windows hoặc điện thoại Android khác cùng mạng Wi-Fi để tự động xuất hiện tại đây.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                } else {
                    peers.forEach { peer ->
                        PeerRow(peer = peer) {
                            partnerTarget = peer.formattedId
                            PocketDeckEngine.emitToast("Đã chọn ${peer.name} (${peer.formattedId}) — Nhập mật khẩu để kết nối")
                        }
                    }
                }
            }
        }
    }

    if (showQrDialog) {
        val qrPayload = "pocketdeck://connect?ip=$primaryIp&port=${settings.hostPort}&id=${settings.deviceId}&pwd=${settings.activePassword}"
        val qrBmp = rememberQrBitmap(qrPayload, 512)
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("Mã QR Kết nối Nhanh") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (qrBmp != null) {
                        Box(
                            modifier = Modifier
                                .size(210.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(8.dp),
                        ) {
                            Image(bitmap = qrBmp, contentDescription = "Mã QR PocketDeck", modifier = Modifier.fillMaxSize())
                        }
                    }
                    Text(
                        text = "ID: ${settings.formattedId}\nMật khẩu: ${settings.activePassword}\nĐịa chỉ: $primaryIp:${settings.hostPort}",
                        style = TabularTextStyle.copy(fontSize = 13.sp),
                        color = TextPrimary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) { Text("Đóng") }
            },
        )
    }
}

@Composable
private fun PeerRow(peer: DiscoveredPeer, onSelect: () -> Unit) {
    Surface(
        color = SlateCardElevated,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, SlateBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(PrimaryBlue.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (peer.platform.contains("win", ignoreCase = true)) Icons.Rounded.Computer else Icons.Rounded.Smartphone,
                    contentDescription = null,
                    tint = AccentSky,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "ID: ${peer.formattedId}  •  ${peer.host}:${peer.port}",
                    style = TabularTextStyle.copy(fontSize = 12.sp),
                    color = TextSecondary,
                )
            }
        }
    }
}
