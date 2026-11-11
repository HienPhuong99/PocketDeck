package com.pocketdeck.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketdeck.android.protocol.DirListing
import com.pocketdeck.android.protocol.FileSystemProvider
import com.pocketdeck.android.protocol.FsEntry
import com.pocketdeck.android.protocol.FsRoot
import com.pocketdeck.android.protocol.TransferProgress
import com.pocketdeck.android.state.PocketDeckEngine
import kotlinx.coroutines.launch

@Composable
fun FileTransferTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conn by PocketDeckEngine.connection.collectAsState()
    val transfers by PocketDeckEngine.transfers.collectAsState()
    val unreadChat by PocketDeckEngine.unreadChat.collectAsState()
    val webPortal by PocketDeckEngine.webPortal.collectAsState()

    // 0 = Máy này, 1 = Đối tác, 2 = Chat, 3 = Web QR
    var subTab by rememberSaveable { mutableIntStateOf(0) }
    var localCurrentPath by rememberSaveable { mutableStateOf("") }
    var remoteCurrentPath by rememberSaveable { mutableStateOf("") }
    var localRefreshTick by remember { mutableIntStateOf(0) }
    var remoteRefreshTick by remember { mutableIntStateOf(0) }

    // Auto-refresh browsers when a transfer completes
    val completedCount = transfers.count { it.status == "completed" }
    LaunchedEffect(completedCount) {
        if (completedCount > 0) {
            localRefreshTick++
            if (conn.connected) remoteRefreshTick++
        }
    }

    LaunchedEffect(subTab) {
        if (subTab == 2) PocketDeckEngine.clearUnreadChat()
    }

    val needsAllFilesPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideDualPane = maxWidth >= 680.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top Mode Switcher (UltraViewer style)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isWideDualPane) {
                    FilterChip(
                        selected = subTab in 0..1,
                        onClick = { subTab = 0 },
                        label = { Text("📁 Cửa sổ Đôi (Máy này ↔ Đối tác)") },
                    )
                } else {
                    FilterChip(
                        selected = subTab == 0,
                        onClick = { subTab = 0 },
                        label = { Text("📁 Máy này") },
                    )
                    FilterChip(
                        selected = subTab == 1,
                        onClick = { subTab = 1 },
                        label = {
                            Text(
                                if (conn.connected) "💻 ${conn.peerName.take(14)}" else "💻 Đối tác"
                            )
                        },
                    )
                }

                FilterChip(
                    selected = subTab == 2,
                    onClick = { subTab = 2 },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("💬 Chat")
                            if (unreadChat > 0) {
                                Spacer(Modifier.width(4.dp))
                                Badge { Text("$unreadChat") }
                            }
                        }
                    },
                )

                FilterChip(
                    selected = subTab == 3,
                    onClick = { subTab = 3 },
                    label = {
                        Text(if (webPortal.running) "🌐 Web QR (Đang bật)" else "🌐 Web QR")
                    },
                )
            }

            if (needsAllFilesPerm && subTab in 0..1) {
                Surface(
                    color = Color(0xFF78350F).copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, AmberWarn.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                val uri = Uri.parse("package:${context.packageName}")
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
                                )
                            }.onFailure {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                }
                            }
                        },
                ) {
                    Text(
                        text = "Cấp quyền Quản lý tất cả tệp để duyệt toàn bộ Bộ nhớ trong (bấm vào đây).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFDE68A),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            // Main Workspace
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    subTab == 2 -> ChatPanel()
                    subTab == 3 -> WebQrPortalPanel()
                    isWideDualPane -> {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            FileBrowserPane(
                                title = "MÁY NÀY (ANDROID)",
                                provider = PocketDeckEngine.localFs,
                                isRemote = false,
                                partnerConnected = conn.connected,
                                currentPath = localCurrentPath,
                                onPathChanged = { localCurrentPath = it },
                                refreshKey = localRefreshTick,
                                onTriggerSendOrPull = { selectedPaths ->
                                    PocketDeckEngine.sendFilesToPartner(selectedPaths, remoteCurrentPath) {
                                        remoteRefreshTick++
                                    }
                                },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )

                            val remoteFs = PocketDeckEngine.activeSession?.files?.remoteFs()
                            FileBrowserPane(
                                title = if (conn.connected) "ĐỐI TÁC (${conn.peerName})" else "ĐỐI TÁC (CHƯA KẾT NỐI)",
                                provider = remoteFs,
                                isRemote = true,
                                partnerConnected = conn.connected,
                                currentPath = remoteCurrentPath,
                                onPathChanged = { remoteCurrentPath = it },
                                refreshKey = remoteRefreshTick,
                                onTriggerSendOrPull = { selectedRemotePaths ->
                                    PocketDeckEngine.pullFilesFromPartner(selectedRemotePaths, localCurrentPath)
                                },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                    subTab == 0 -> {
                        FileBrowserPane(
                            title = "MÁY NÀY (ANDROID)",
                            provider = PocketDeckEngine.localFs,
                            isRemote = false,
                            partnerConnected = conn.connected,
                            currentPath = localCurrentPath,
                            onPathChanged = { localCurrentPath = it },
                            refreshKey = localRefreshTick,
                            onTriggerSendOrPull = { selectedPaths ->
                                PocketDeckEngine.sendFilesToPartner(selectedPaths, remoteCurrentPath) {
                                    remoteRefreshTick++
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> {
                        val remoteFs = PocketDeckEngine.activeSession?.files?.remoteFs()
                        FileBrowserPane(
                            title = if (conn.connected) "ĐỐI TÁC (${conn.peerName})" else "ĐỐI TÁC (CHƯA KẾT NỐI)",
                            provider = remoteFs,
                            isRemote = true,
                            partnerConnected = conn.connected,
                            currentPath = remoteCurrentPath,
                            onPathChanged = { remoteCurrentPath = it },
                            refreshKey = remoteRefreshTick,
                            onTriggerSendOrPull = { selectedRemotePaths ->
                                PocketDeckEngine.pullFilesFromPartner(selectedRemotePaths, localCurrentPath)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // Active / Recent Transfer Queue (UltraViewer bottom transfer table)
            if (transfers.isNotEmpty() && subTab in 0..1) {
                TransferQueuePanel(transfers = transfers.take(8))
            }
        }
    }
}

@Composable
private fun FileBrowserPane(
    title: String,
    provider: FileSystemProvider?,
    isRemote: Boolean,
    partnerConnected: Boolean,
    currentPath: String,
    onPathChanged: (String) -> Unit,
    refreshKey: Int,
    onTriggerSendOrPull: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf<List<FsRoot>>(emptyList()) }
    var listing by remember { mutableStateOf<DirListing?>(null) }
    var errorMsg by remember { mutableStateOf("") }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    var showMkdirDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FsEntry?>(null) }

    fun reload(targetPath: String = currentPath) {
        if (provider == null || (isRemote && !partnerConnected)) {
            listing = null
            roots = emptyList()
            return
        }
        scope.launch {
            errorMsg = ""
            runCatching {
                val r = provider.listRoots()
                roots = r
                val p = targetPath.ifBlank { r.firstOrNull()?.path ?: "" }
                val dir = provider.listDir(p)
                listing = dir
                onPathChanged(dir.path)
                selectedPaths = emptySet()
            }.onFailure { e ->
                errorMsg = e.message ?: "Không đọc được thư mục"
            }
        }
    }

    LaunchedEffect(provider, partnerConnected, refreshKey) {
        reload(currentPath)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, SlateBorder),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header & Toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlateCardElevated)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentSky,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { listing?.parent?.let { reload(it) } },
                    enabled = listing?.parent != null,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = "Thư mục cha", modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = { showMkdirDialog = true },
                    enabled = listing != null,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = "Tạo thư mục mới", modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = { reload(currentPath) },
                    enabled = provider != null,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Làm mới", modifier = Modifier.size(18.dp))
                }
            }

            if (isRemote && !partnerConnected) {
                Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Hãy kết nối tới máy tính Windows hoặc Android đối tác ở tab [Kết nối] để duyệt và truyền file 2 chiều.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
                return@Column
            }

            // Root shortcuts
            if (roots.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    roots.forEach { r ->
                        FilterChip(
                            selected = listing?.path == r.path,
                            onClick = { reload(r.path) },
                            label = { Text(r.name, fontSize = 11.sp) },
                        )
                    }
                }
            }

            // Current path indicator
            listing?.let { dir ->
                Text(
                    text = "${dir.path}  •  Trống ${formatBytes(dir.freeBytes)}",
                    style = TabularTextStyle.copy(fontSize = 11.sp),
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }

            if (errorMsg.isNotEmpty()) {
                Text(
                    text = errorMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFCA5A5),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider(color = SlateBorder)

            // File Entries List
            val entries = listing?.entries.orEmpty()
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(entries, key = { it.path }) { entry ->
                    val checked = entry.path in selectedPaths
                    var menuOpen by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.isDir) {
                                    reload(entry.path)
                                } else {
                                    selectedPaths = if (checked) selectedPaths - entry.path else selectedPaths + entry.path
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { isChecked ->
                                selectedPaths = if (isChecked) selectedPaths + entry.path else selectedPaths - entry.path
                            },
                        )
                        Icon(
                            imageVector = if (entry.isDir) Icons.Rounded.Folder else Icons.Rounded.Description,
                            contentDescription = null,
                            tint = if (entry.isDir) Color(0xFFFBBF24) else AccentSky,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (entry.isDir) "Thư mục" else formatBytes(entry.size),
                                style = TabularTextStyle.copy(fontSize = 11.sp),
                                color = TextSecondary,
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Tác vụ", modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Đổi tên") },
                                    leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
                                    onClick = {
                                        menuOpen = false
                                        renameTarget = entry
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Xoá", color = Color(0xFFFCA5A5)) },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = Color(0xFFFCA5A5)) },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            runCatching {
                                                provider?.delete(entry.path)
                                                reload(currentPath)
                                            }.onFailure { PocketDeckEngine.emitToast("Không xoá được: ${it.message}") }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Action Bar when items selected
            if (selectedPaths.isNotEmpty()) {
                HorizontalDivider(color = SlateBorder)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SlateCardElevated)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Đã chọn ${selectedPaths.size}",
                        style = TabularTextStyle.copy(fontSize = 12.sp),
                        color = TextPrimary,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { selectedPaths = emptySet() }) {
                        Text("Bỏ chọn")
                    }
                    Button(
                        onClick = {
                            val list = selectedPaths.toList()
                            selectedPaths = emptySet()
                            onTriggerSendOrPull(list)
                        },
                        enabled = partnerConnected,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        if (!isRemote) {
                            Text("Gửi sang Đối tác", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, modifier = Modifier.size(16.dp))
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Tải về Máy này", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showMkdirDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showMkdirDialog = false },
            title = { Text("Tạo thư mục mới") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Tên thư mục") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    val base = listing?.path ?: return@Button
                    showMkdirDialog = false
                    scope.launch {
                        runCatching {
                            provider?.mkdir(base, folderName)
                            reload(base)
                        }.onFailure { PocketDeckEngine.emitToast("Lỗi: ${it.message}") }
                    }
                }) { Text("Tạo") }
            },
            dismissButton = {
                TextButton(onClick = { showMkdirDialog = false }) { Text("Huỷ") }
            },
        )
    }

    renameTarget?.let { target ->
        var newName by remember(target) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Đổi tên") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Tên mới") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(onClick = {
                    renameTarget = null
                    scope.launch {
                        runCatching {
                            provider?.rename(target.path, newName)
                            reload(currentPath)
                        }.onFailure { PocketDeckEngine.emitToast("Lỗi: ${it.message}") }
                    }
                }) { Text("Lưu") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Huỷ") }
            },
        )
    }
}

@Composable
private fun TransferQueuePanel(transfers: List<TransferProgress>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCardElevated),
        border = BorderStroke(1.dp, SlateBorder),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 155.dp),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(transfers, key = { it.xferId }) { t ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val arrow = if (t.direction == "send") "↑" else "↓"
                        Text(
                            text = "$arrow ${t.filename}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        val statusLabel = when (t.status) {
                            "completed" -> "Hoàn tất (${formatBytes(t.totalBytes)})"
                            "paused" -> "Tạm dừng • ${t.percent.toInt()}%"
                            "cancelled" -> "Đã huỷ"
                            "error" -> "Lỗi: ${t.error}"
                            else -> "${formatSpeed(t.speedBps)} • ETA ${formatEta(t.etaSeconds)}"
                        }
                        Text(
                            text = statusLabel,
                            style = TabularTextStyle.copy(fontSize = 11.sp),
                            color = if (t.status == "completed") EmeraldOk else TextSecondary,
                        )
                        if (t.status == "active" || t.status == "paused") {
                            if (t.direction == "send") {
                                IconButton(
                                    onClick = { PocketDeckEngine.pauseOrResumeTransfer(t.xferId, t.status) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        if (t.status == "paused") Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                            IconButton(
                                onClick = { PocketDeckEngine.cancelTransfer(t.xferId) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Huỷ", modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { (t.percent / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = if (t.status == "completed") EmeraldOk else PrimaryBlue,
                        trackColor = SlateBorder,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatPanel() {
    val messages by PocketDeckEngine.chatMessages.collectAsState()
    val conn by PocketDeckEngine.connection.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }

    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, SlateBorder),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (conn.connected) "CHAT TRỰC TIẾP VỚI ${conn.peerName.uppercase()}"
                else "CHAT TRỰC TIẾP (CHƯA KẾT NỐI)",
                style = MaterialTheme.typography.labelMedium,
                color = AccentSky,
            )
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { m ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (m.isLocal) Alignment.End else Alignment.Start,
                    ) {
                        Text(m.sender, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Surface(
                            color = if (m.isLocal) PrimaryBlue else SlateCardElevated,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                text = m.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("Nhập tin nhắn Tiếng Việt...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        if (draft.isNotBlank()) {
                            PocketDeckEngine.sendChatMessage(draft)
                            draft = ""
                        }
                    },
                    enabled = conn.connected,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Gửi")
                }
            }
        }
    }
}

@Composable
private fun WebQrPortalPanel() {
    val context = LocalContext.current
    val portal by PocketDeckEngine.webPortal.collectAsState()
    val qrBmp = rememberQrBitmap(portal.url, 512)

    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, SlateBorder),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cổng Truyền File Web QR (iPhone / Trình duyệt)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Quét mã QR từ Camera iPhone hoặc mở trình duyệt bất kỳ cùng mạng Wi-Fi để gửi/tải file tốc độ cao.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = portal.running,
                    onCheckedChange = { PocketDeckEngine.setWebPortalEnabled(it) },
                )
            }

            if (portal.running) {
                HorizontalDivider(color = SlateBorder)
                if (qrBmp != null) {
                    Box(
                        modifier = Modifier
                            .size(210.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White)
                            .padding(10.dp),
                    ) {
                        Image(bitmap = qrBmp, contentDescription = "QR Web Portal", modifier = Modifier.fillMaxSize())
                    }
                }

                Text(
                    text = "Mã PIN bảo vệ: ${portal.pin}",
                    style = TabularTextStyle.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    color = Color(0xFFFDE047),
                )

                Surface(
                    color = SlateCardElevated,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, SlateBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = portal.url,
                            style = TabularTextStyle.copy(fontSize = 13.sp),
                            color = AccentSky,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Web QR URL", portal.url))
                                PocketDeckEngine.emitToast("Đã sao chép đường dẫn Web QR")
                            },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Sao chép URL", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Text(
                    text = "Thư mục lưu: ${portal.sharedDirPath}  •  Đã nhận ${portal.uploadedCount} file",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            } else {
                Spacer(Modifier.height(24.dp))
                Icon(Icons.Rounded.QrCode, contentDescription = null, tint = SlateBorder, modifier = Modifier.size(72.dp))
                Text(
                    text = "Gạt công tắc phía trên để mở cổng truyền file không cần cài app cho iPhone, iPad & MacBook.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
        }
    }
}
