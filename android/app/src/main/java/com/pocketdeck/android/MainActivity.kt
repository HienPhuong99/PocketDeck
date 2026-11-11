package com.pocketdeck.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Phonelink
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pocketdeck.android.services.ScreenShareService
import com.pocketdeck.android.state.PocketDeckEngine
import com.pocketdeck.android.ui.AirPlayTab
import com.pocketdeck.android.ui.ConnectTab
import com.pocketdeck.android.ui.EmeraldOk
import com.pocketdeck.android.ui.FileTransferTab
import com.pocketdeck.android.ui.PocketDeckTheme
import com.pocketdeck.android.ui.SettingsTab
import com.pocketdeck.android.ui.SlateBg
import com.pocketdeck.android.ui.SlateCardElevated
import com.pocketdeck.android.ui.TextSecondary
import com.pocketdeck.android.ui.ViewerActivity

class MainActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) {
            PocketDeckEngine.startScreenShareService(res.resultCode, data)
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        launchScreenCapturePrompt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PocketDeckEngine.ensureInitialized(this)

        setContent {
            PocketDeckTheme {
                var currentTab by rememberSaveable { mutableIntStateOf(0) }
                val snackbarHostState = remember { SnackbarHostState() }
                val conn by PocketDeckEngine.connection.collectAsState()
                val isSharing by ScreenShareService.isSharing.collectAsState()
                val unreadChat by PocketDeckEngine.unreadChat.collectAsState()
                val airPlay by PocketDeckEngine.airPlay.collectAsState()

                LaunchedEffect(Unit) {
                    PocketDeckEngine.toasts.collect { msg ->
                        snackbarHostState.showSnackbar(msg)
                    }
                }

                Scaffold(
                    containerColor = SlateBg,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SlateCardElevated)
                                .statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "PocketDeck",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.weight(1f))
                            val online = conn.connected || isSharing || airPlay.receiverEnabled
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (online) EmeraldOk else TextSecondary)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when {
                                    conn.connected -> "Đã kết nối"
                                    isSharing -> "Đang phát màn hình"
                                    airPlay.receiverEnabled -> "AirPlay sẵn sàng"
                                    else -> "Sẵn sàng"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (online) EmeraldOk else TextSecondary,
                            )
                        }
                    },
                    bottomBar = {
                        NavigationBar(containerColor = SlateCardElevated) {
                            NavigationBarItem(
                                selected = currentTab == 0,
                                onClick = { currentTab = 0 },
                                icon = { Icon(Icons.Rounded.Phonelink, contentDescription = null) },
                                label = { Text("Kết nối") },
                            )
                            NavigationBarItem(
                                selected = currentTab == 1,
                                onClick = { currentTab = 1 },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (unreadChat > 0) Badge { Text("$unreadChat") }
                                        }
                                    ) {
                                        Icon(Icons.Rounded.FolderCopy, contentDescription = null)
                                    }
                                },
                                label = { Text("Truyền File") },
                            )
                            NavigationBarItem(
                                selected = currentTab == 2,
                                onClick = { currentTab = 2 },
                                icon = { Icon(Icons.Rounded.CastConnected, contentDescription = null) },
                                label = { Text("AirPlay") },
                            )
                            NavigationBarItem(
                                selected = currentTab == 3,
                                onClick = { currentTab = 3 },
                                icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                label = { Text("Cài đặt") },
                            )
                        }
                    },
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        when (currentTab) {
                            0 -> ConnectTab(
                                onRequestScreenCapture = { requestCaptureWithPermissions() },
                                onOpenRemoteViewer = { openViewer(ViewerActivity.MODE_DECKLINK) },
                                onOpenFileTab = { currentTab = 1 },
                            )
                            1 -> FileTransferTab()
                            2 -> AirPlayTab(
                                onOpenFullscreenAirPlay = { openViewer(ViewerActivity.MODE_AIRPLAY) },
                            )
                            else -> SettingsTab()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PocketDeckEngine.refreshNetworkAddresses()
    }

    private fun openViewer(mode: String) {
        startActivity(
            Intent(this, ViewerActivity::class.java).apply {
                putExtra(ViewerActivity.EXTRA_MODE, mode)
            }
        )
    }

    private fun requestCaptureWithPermissions() {
        val needed = ArrayList<String>()
        if (PocketDeckEngine.settings.value.shareInternalAudio &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            permissionsLauncher.launch(needed.toTypedArray())
        } else {
            launchScreenCapturePrompt()
        }
    }

    private fun launchScreenCapturePrompt() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
