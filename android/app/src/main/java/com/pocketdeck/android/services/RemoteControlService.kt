package com.pocketdeck.android.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot

/**
 * Android `AccessibilityService` executing remote touch, long-press, swipe, mouse-wheel scroll,
 * hardware Back/Home/Recents/Notifications, and full Vietnamese Unicode text input received over DeckLink.
 */
class RemoteControlService : AccessibilityService() {

    private val strokePoints = ArrayList<Pair<Float, Float>>(32)
    private var downTimestampMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event listening kept minimal to avoid any battery or UI overhead
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (_instance.value === this) {
            _instance.value = null
        }
        super.onDestroy()
    }

    @Synchronized
    fun handleControlMessage(msg: Map<String, Any?>) {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.coerceAtLeast(1)
        val sh = dm.heightPixels.coerceAtLeast(1)

        val action = msg["action"]?.toString() ?: return
        when (action) {
            "touch_down" -> {
                val (px, py) = resolveScreenCoords(msg, sw, sh)
                strokePoints.clear()
                strokePoints.add(px to py)
                downTimestampMs = System.currentTimeMillis()
            }
            "touch_move" -> {
                if (strokePoints.isNotEmpty() && strokePoints.size < 32) {
                    val (px, py) = resolveScreenCoords(msg, sw, sh)
                    val (lx, ly) = strokePoints.last()
                    if (hypot(px - lx, py - ly) >= 6f) {
                        strokePoints.add(px to py)
                    }
                }
            }
            "touch_up" -> {
                val (px, py) = resolveScreenCoords(msg, sw, sh)
                if (strokePoints.isEmpty()) {
                    dispatchTapOrSwipe(listOf(px to py), 45L)
                } else {
                    val (lx, ly) = strokePoints.last()
                    if (hypot(px - lx, py - ly) >= 2f) {
                        strokePoints.add(px to py)
                    }
                    val heldMs = (System.currentTimeMillis() - downTimestampMs).coerceIn(40L, 2500L)
                    val pts = strokePoints.toList()
                    strokePoints.clear()
                    dispatchTapOrSwipe(pts, heldMs)
                }
            }
            "tap" -> {
                val (px, py) = resolveScreenCoords(msg, sw, sh)
                dispatchTapOrSwipe(listOf(px to py), 45L)
            }
            "scroll" -> {
                val (px, py) = resolveScreenCoords(msg, sw, sh)
                val vscroll = (msg["vscroll"] as? Number)?.toFloat() ?: 0f
                val hscroll = (msg["hscroll"] as? Number)?.toFloat() ?: 0f
                val dy = (vscroll * sh * 0.16f).coerceIn(-sh * 0.35f, sh * 0.35f)
                val dx = (hscroll * sw * 0.16f).coerceIn(-sw * 0.35f, sw * 0.35f)
                val endX = (px + dx).coerceIn(1f, sw - 2f)
                val endY = (py + dy).coerceIn(1f, sh - 2f)
                if (hypot(endX - px, endY - py) >= 4f) {
                    dispatchTapOrSwipe(listOf(px to py, endX to endY), 110L)
                }
            }
            "key", "nav" -> {
                val key = (msg["key"] ?: msg["name"])?.toString()?.lowercase() ?: return
                when (key) {
                    "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                    "power", "lock" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                        }
                    }
                    "enter" -> handleEnterKey()
                    "backspace" -> handleBackspaceKey()
                }
            }
            "text" -> {
                val text = msg["text"]?.toString() ?: return
                if (text.isNotEmpty()) {
                    injectUnicodeText(text)
                }
            }
        }
    }

    private fun resolveScreenCoords(msg: Map<String, Any?>, sw: Int, sh: Int): Pair<Float, Float> {
        val nx = (msg["nx"] as? Number)?.toFloat()
        val ny = (msg["ny"] as? Number)?.toFloat()
        if (nx != null && ny != null) {
            return (nx * sw).coerceIn(1f, sw - 2f) to (ny * sh).coerceIn(1f, sh - 2f)
        }
        val rx = (msg["x"] as? Number)?.toFloat() ?: (sw / 2f)
        val ry = (msg["y"] as? Number)?.toFloat() ?: (sh / 2f)
        val refW = (msg["screen_w"] as? Number)?.toFloat()?.takeIf { it > 0f } ?: sw.toFloat()
        val refH = (msg["screen_h"] as? Number)?.toFloat()?.takeIf { it > 0f } ?: sh.toFloat()
        return ((rx / refW) * sw).coerceIn(1f, sw - 2f) to ((ry / refH) * sh).coerceIn(1f, sh - 2f)
    }

    private fun dispatchTapOrSwipe(points: List<Pair<Float, Float>>, durationMs: Long) {
        if (points.isEmpty()) return
        val path = Path()
        path.moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size) {
            path.lineTo(points[i].first, points[i].second)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        runCatching { dispatchGesture(gesture, null, null) }
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        return null
    }

    private fun injectUnicodeText(text: String) {
        val node = findFocusedEditable()
        if (node != null) {
            val current = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
            val selStart = node.textSelectionStart.takeIf { it in 0..current.length } ?: current.length
            val selEnd = node.textSelectionEnd.takeIf { it in 0..current.length } ?: current.length
            val lo = minOf(selStart, selEnd)
            val hi = maxOf(selStart, selEnd)
            val updated = current.substring(0, lo) + text + current.substring(hi)

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                val cursor = lo + text.length
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                return
            }
        }

        // Clipboard + ACTION_PASTE fallback for custom editors (e.g. WebViews / canvas inputs)
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PocketDeck", text))
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
    }

    private fun handleBackspaceKey() {
        val node = findFocusedEditable() ?: return
        if (node.isShowingHintText) return
        val current = node.text?.toString() ?: return
        if (current.isEmpty()) return

        val selStart = node.textSelectionStart.takeIf { it in 0..current.length } ?: current.length
        val selEnd = node.textSelectionEnd.takeIf { it in 0..current.length } ?: current.length
        val lo = minOf(selStart, selEnd)
        val hi = maxOf(selStart, selEnd)
        val (updated, nextCursor) = if (lo != hi) {
            (current.substring(0, lo) + current.substring(hi)) to lo
        } else if (lo > 0) {
            val prev = Character.offsetByCodePoints(current, lo, -1)
            (current.substring(0, prev) + current.substring(lo)) to prev
        } else {
            return
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            val selArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, nextCursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, nextCursor)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        }
    }

    private fun handleEnterKey() {
        val node = findFocusedEditable() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                return
            }
        }
        injectUnicodeText("\n")
    }

    companion object {
        private val _instance = MutableStateFlow<RemoteControlService?>(null)
        val instance: StateFlow<RemoteControlService?> = _instance.asStateFlow()
    }
}
