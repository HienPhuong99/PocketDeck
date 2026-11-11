package com.pocketdeck.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketdeck.android.state.PocketDeckEngine

@Composable
fun AirPlayTab(onOpenFullscreenAirPlay: () -> Unit) {
    val settings by PocketDeckEngine.settings.collectAsState()
    val airPlay by PocketDeckEngine.airPlay.collectAsState()
    val primaryIp = settings.lanIps.firstOrNull() ?: "Chưa có Wi-Fi"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Đầu nhận AirPlay (PocketPane)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Biến máy Android này thành màn hình nhận phản chiếu không dây cho iPhone / iPad / Mac.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = airPlay.receiverEnabled,
                        onCheckedChange = { PocketDeckEngine.setAirPlayEnabled(it) },
                    )
                }

                HorizontalDivider(color = SlateBorder)

                Text(
                    text = if (airPlay.receiverEnabled) {
                        "Tên hiển thị trên iPhone: \"${settings.deviceName}\"  •  IP: $primaryIp:${airPlay.rtspPort}"
                    } else {
                        "Trạng thái: Đang tắt"
                    },
                    style = TabularTextStyle.copy(fontSize = 12.sp),
                    color = if (airPlay.receiverEnabled) EmeraldOk else TextSecondary,
                )

                if (airPlay.streamingClientName.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = onOpenFullscreenAirPlay, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Fullscreen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Toàn màn hình / PiP", fontWeight = FontWeight.SemiBold)
                        }
                        FilledTonalButton(onClick = { PocketDeckEngine.toggleAirPlayMute() }) {
                            Icon(
                                if (airPlay.audioMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                                contentDescription = "Âm thanh",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        if (airPlay.receiverEnabled) {
            val ratio = (airPlay.videoWidth.toFloat() / airPlay.videoHeight.coerceAtLeast(1).toFloat())
                .coerceIn(0.42f, 2.2f)

            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                border = BorderStroke(1.dp, SlateBorder),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio.coerceAtLeast(0.56f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(h: SurfaceHolder) {
                                        PocketDeckEngine.airPlayVideoDecoder.attachSurface(
                                            h.surface,
                                            airPlay.videoWidth,
                                            airPlay.videoHeight,
                                        )
                                    }

                                    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}

                                    override fun surfaceDestroyed(h: SurfaceHolder) {
                                        PocketDeckEngine.airPlayVideoDecoder.detachSurface()
                                    }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                    )

                    if (airPlay.streamingClientName.isEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .background(SlateBg.copy(alpha = 0.88f), RoundedCornerShape(12.dp))
                                .padding(20.dp),
                        ) {
                            Icon(Icons.Rounded.CastConnected, null, tint = AccentSky, modifier = Modifier.size(40.dp))
                            Text(
                                text = "Đang chờ kết nối từ iPhone / iPad...",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "1. Kết nối iPhone cùng mạng Wi-Fi ($primaryIp)\n" +
                                    "2. Mở Trung tâm điều khiển → Phản chiếu màn hình\n" +
                                    "3. Chọn \"${settings.deviceName}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTab() {
    val context = LocalContext.current
    val current by PocketDeckEngine.settings.collectAsState()

    var name by rememberSaveable(current.deviceName) { mutableStateOf(current.deviceName) }
    var fixedPass by rememberSaveable(current.fixedPassword) { mutableStateOf(current.fixedPassword) }
    var maxDim by rememberSaveable(current.maxVideoDim) { mutableIntStateOf(current.maxVideoDim) }
    var bitrate by rememberSaveable(current.bitrateMbps) { mutableIntStateOf(current.bitrateMbps) }
    var fps by rememberSaveable(current.fps) { mutableIntStateOf(current.fps) }
    var shareAudio by rememberSaveable(current.shareInternalAudio) { mutableStateOf(current.shareInternalAudio) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, SlateBorder),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("NHẬN DIỆN & BẢO MẬT", style = MaterialTheme.typography.labelMedium, color = AccentSky)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tên thiết bị hiển thị") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = fixedPass,
                    onValueChange = { fixedPass = it },
                    label = { Text("Mật khẩu cố định (tuỳ chọn, để trống nếu dùng mật khẩu phiên)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider(color = SlateBorder)

                Text("CHẤT LƯỢNG PHẢN CHIẾU MÀN HÌNH", style = MaterialTheme.typography.labelMedium, color = AccentSky)

                Text("Độ phân giải tối đa", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(960 to "HD 960p", 1280 to "HD+ 1280p", 1920 to "Full HD 1080p").forEach { (v, label) ->
                        FilterChip(selected = maxDim == v, onClick = { maxDim = v }, label = { Text(label) })
                    }
                }

                Text("Băng thông Video (Bitrate)", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 4, 8, 12).forEach { mb ->
                        FilterChip(selected = bitrate == mb, onClick = { bitrate = mb }, label = { Text("$mb Mbps") })
                    }
                }

                Text("Tốc độ khung hình", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60).forEach { f ->
                        FilterChip(selected = fps == f, onClick = { fps = f }, label = { Text("$f FPS") })
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Truyền kèm âm thanh hệ thống", style = MaterialTheme.typography.bodyMedium)
                        Text("Yêu cầu Android 10+ khi chia sẻ màn hình", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    Switch(checked = shareAudio, onCheckedChange = { shareAudio = it })
                }

                Button(
                    onClick = {
                        PocketDeckEngine.updateSettings(
                            deviceName = name,
                            fixedPassword = fixedPass,
                            maxVideoDim = maxDim,
                            bitrateMbps = bitrate,
                            fps = fps,
                            shareInternalAudio = shareAudio,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lưu cài đặt", fontWeight = FontWeight.Bold)
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, SlateBorder),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("QUYỀN HỆ THỐNG ANDROID", style = MaterialTheme.typography.labelMedium, color = AccentSky)

                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Mở Cài đặt Trợ năng (Điều khiển cảm ứng)")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val uri = Uri.parse("package:${context.packageName}")
                                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Mở Quyền Truy cập Tất cả Tệp (Truyền File)")
                    }
                }

                HorizontalDivider(color = SlateBorder)
                Text(
                    text = "PocketDeck v1.0.0 (Android & Windows) • Giao thức DeckLink v1 AES-256-GCM + AirPlay RTSP • Giấy phép nguồn mở GPL-3.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
    }
}
