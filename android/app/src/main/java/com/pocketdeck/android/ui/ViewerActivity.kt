package com.pocketdeck.android.ui

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketdeck.android.state.PocketDeckEngine

class ViewerActivity : ComponentActivity() {

    private var inPipMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PocketDeckEngine.ensureInitialized(this)

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_DECKLINK

        setContent {
            PocketDeckTheme {
                ViewerScreen(
                    mode = mode,
                    isPip = inPipMode,
                    onEnterPip = { enterPip(mode) },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun enterPip(mode: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val (w, h) = if (mode == MODE_AIRPLAY) {
                val a = PocketDeckEngine.airPlay.value
                a.videoWidth to a.videoHeight
            } else {
                val c = PocketDeckEngine.connection.value
                c.remoteWidth to c.remoteHeight
            }
            val safeW = w.coerceIn(100, 2390)
            val safeH = h.coerceIn(100, 2390)
            // Android PiP requires aspect ratio between 1:2.39 and 2.39:1
            val clampedW = safeW.coerceAtLeast((safeH / 2.35).toInt())
            val clampedH = safeH.coerceAtLeast((clampedW / 2.35).toInt())
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(clampedW, clampedH))
                .build()
            runCatching { enterPictureInPictureMode(params) }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipMode = isInPictureInPictureMode
    }

    companion object {
        const val EXTRA_MODE = "viewer_mode"
        const val MODE_DECKLINK = "decklink"
        const val MODE_AIRPLAY = "airplay"
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ViewerScreen(
    mode: String,
    isPip: Boolean,
    onEnterPip: () -> Unit,
    onClose: () -> Unit,
) {
    val conn by PocketDeckEngine.connection.collectAsState()
    val airPlay by PocketDeckEngine.airPlay.collectAsState()

    val videoW = if (mode == ViewerActivity.MODE_AIRPLAY) airPlay.videoWidth else conn.remoteWidth
    val videoH = if (mode == ViewerActivity.MODE_AIRPLAY) airPlay.videoHeight else conn.remoteHeight
    val aspect = (videoW.toFloat() / videoH.coerceAtLeast(1).toFloat()).coerceIn(0.35f, 2.8f)

    var viewSize by remember { mutableStateOf(IntSize(1, 1)) }
    var showTextDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            if (mode == ViewerActivity.MODE_AIRPLAY) {
                                PocketDeckEngine.airPlayVideoDecoder.attachSurface(h.surface, videoW, videoH)
                            } else {
                                PocketDeckEngine.remoteVideoDecoder.attachSurface(h.surface, videoW, videoH)
                            }
                        }

                        override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}

                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            if (mode == ViewerActivity.MODE_AIRPLAY) {
                                PocketDeckEngine.airPlayVideoDecoder.detachSurface()
                            } else {
                                PocketDeckEngine.remoteVideoDecoder.detachSurface()
                            }
                        }
                    })
                }
            },
            modifier = Modifier
                .aspectRatio(aspect)
                .onSizeChanged { viewSize = it }
                .pointerInteropFilter { ev ->
                    if (mode != ViewerActivity.MODE_DECKLINK) return@pointerInteropFilter false
                    val w = viewSize.width.coerceAtLeast(1).toFloat()
                    val h = viewSize.height.coerceAtLeast(1).toFloat()
                    val nx = (ev.x / w).coerceIn(0f, 1f)
                    val ny = (ev.y / h).coerceIn(0f, 1f)
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> PocketDeckEngine.sendRemoteTouch("touch_down", nx, ny)
                        MotionEvent.ACTION_MOVE -> PocketDeckEngine.sendRemoteTouch("touch_move", nx, ny)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            PocketDeckEngine.sendRemoteTouch("touch_up", nx, ny)
                    }
                    true
                },
        )

        // Floating control bar (hidden in PiP)
        if (!isPip) {
            Surface(
                color = SlateBg.copy(alpha = 0.84f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (mode == ViewerActivity.MODE_DECKLINK) {
                        IconButton(onClick = { PocketDeckEngine.sendRemoteNav("back") }, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        IconButton(onClick = { PocketDeckEngine.sendRemoteNav("home") }, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Rounded.Home, contentDescription = "Home", tint = Color.White)
                        }
                        IconButton(onClick = { PocketDeckEngine.sendRemoteNav("recents") }, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Rounded.Apps, contentDescription = "Đa nhiệm", tint = Color.White)
                        }
                        IconButton(onClick = { showTextDialog = true }, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Rounded.Keyboard, contentDescription = "Nhập chữ Tiếng Việt", tint = AccentSky)
                        }
                    }

                    IconButton(onClick = onEnterPip, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = "Hình trong hình (PiP)", tint = Color.White)
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Đóng", tint = DangerRed)
                    }
                }
            }
        }
    }

    if (showTextDialog) {
        var inputText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Gửi văn bản Tiếng Việt sang đối tác") },
            text = {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Nhập nội dung cần gõ...") },
                )
            },
            confirmButton = {
                Button(onClick = {
                    showTextDialog = false
                    PocketDeckEngine.sendRemoteText(inputText)
                }) { Text("Gửi chữ") }
            },
            dismissButton = {
                TextButton(onClick = { showTextDialog = false }) { Text("Huỷ") }
            },
        )
    }
}
