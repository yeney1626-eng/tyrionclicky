package com.example.clickycursor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class CursorAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var cursorView: ImageView
    private lateinit var params: WindowManager.LayoutParams

    private var cursorX = 0f
    private var cursorY = 0f

    private val moveStepPerTick = 7f
    private val tickIntervalMs = 12L

    private val moveHandler = Handler(Looper.getMainLooper())
    private var activeDirX = 0f
    private var activeDirY = 0f
    private var isMoving = false

    private var lastEdgeScrollTime = 0L
    private val edgeScrollIntervalMs = 650L
    private val edgeScrollDistance = 380f
    private val edgeScrollDurationMs = 300L

    private val clickHandler = Handler(Looper.getMainLooper())

    private val longPressThresholdMs = 400L
    private val longPressGestureDurationMs = 700L
    private var longPressFired = false

    private val autoClickStartDelayMs = 500L
    private var autoClickActive = false
    private var autoClickIntervalMs = 500L
    private val autoClickMinIntervalMs = 90L
    private val autoClickAccelStep = 45L

    private val longPressRunnable = Runnable {
        longPressFired = true
        performLongPressAt(cursorX, cursorY)
        showClickFeedback()
        clickHandler.postDelayed(autoClickStartRunnable, autoClickStartDelayMs)
    }

    private val autoClickRunnable = object : Runnable {
        override fun run() {
            if (!autoClickActive) return
            performClickAt(cursorX, cursorY, longClick = false)
            showClickFeedback()
            autoClickIntervalMs = (autoClickIntervalMs - autoClickAccelStep).coerceAtLeast(autoClickMinIntervalMs)
            clickHandler.postDelayed(this, autoClickIntervalMs)
        }
    }

    private val autoClickStartRunnable = Runnable {
        autoClickActive = true
        autoClickIntervalMs = 500L
        clickHandler.post(autoClickRunnable)
    }

    private val revertImageRunnable = Runnable {
        cursorView.setImageResource(R.drawable.cursor_dog_idle)
        updateOverlayPosition()
    }

    private fun showClickFeedback() {
        cursorView.setImageResource(R.drawable.cursor_dog_click)
        updateOverlayPosition()
        clickHandler.removeCallbacks(revertImageRunnable)
        clickHandler.postDelayed(revertImageRunnable, 180)
    }

    private var menuLongPressFired = false
    private val menuLongPressRunnable = Runnable {
        menuLongPressFired = true
        takeScreenshot()
    }

    private val backspaceHandler = Handler(Looper.getMainLooper())
    private var backspaceHeld = false
    private val backspaceRepeatDelayMs = 400L
    private val backspaceRepeatIntervalMs = 140L

    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            if (!backspaceHeld) return
            performBackspace()
            backspaceHandler.postDelayed(this, backspaceRepeatIntervalMs)
        }
    }

    private val backspaceStartRepeatRunnable = Runnable {
        backspaceHeld = true
        backspaceHandler.post(backspaceRepeatRunnable)
    }

    private val starLongPressRunnable = Runnable { openEmojiPanel() }

    private val emojiList = listOf(
        "\uD83D\uDE00", "\uD83D\uDE02", "\uD83D\uDE0D", "\uD83D\uDC4D", "\uD83D\uDE4F",
        "\u2764\uFE0F", "\uD83D\uDE22", "\uD83D\uDE2E", "\uD83D\uDD25", "\uD83C\uDF89",
        "\uD83D\uDE05", "\uD83E\uDD14", "\uD83D\uDE0E", "\uD83D\uDC4F", "\uD83D\uDCAF", "\uD83D\uDE2D",
        "\uD83D\uDE0F", "\uD83D\uDE44", "\uD83E\uDD79", "\uD83C\uDF7B",
        "\uD83D\uDC4C\uD83C\uDFFC", "\u270C\uD83C\uDFFC", "\uD83D\uDE0A"
    )
    private var emojiPanelOpen = false
    private var emojiSelectedIndex = 0
    private var emojiPanelInitialized = false
    private lateinit var emojiPanelView: LinearLayout
    private lateinit var emojiPanelParams: WindowManager.LayoutParams
    private val emojiTextViews = mutableListOf<TextView>()

    private val moveRunnable = object : Runnable {
        override fun run() {
            if (!isMoving) return
            moveCursorBy(activeDirX * moveStepPerTick, activeDirY * moveStepPerTick)
            checkEdgeScroll()
            moveHandler.postDelayed(this, tickIntervalMs)
        }
    }

    private fun startMoving(dirX: Float, dirY: Float) {
        activeDirX = dirX
        activeDirY = dirY
        if (!isMoving) {
            isMoving = true
            moveHandler.post(moveRunnable)
        }
    }

    private fun stopMoving() {
        isMoving = false
        moveHandler.removeCallbacks(moveRunnable)
    }

    private var screenWidth = 0
    private var screenHeight = 0

    private var pointerPaused = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlay()
    }

    private fun setupOverlay() {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f

        cursorView = ImageView(this).apply {
            setImageResource(R.drawable.cursor_dog_idle)
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(cursorView, params)
        updateOverlayPosition()
    }

    private fun updateOverlayPosition() {
        val halfW = (cursorView.drawable?.intrinsicWidth ?: 0) / 2
        val halfH = (cursorView.drawable?.intrinsicHeight ?: 0) / 2
        params.x = (cursorX - halfW).toInt()
        params.y = (cursorY - halfH).toInt()
        windowManager.updateViewLayout(cursorView, params)
    }

    private fun moveCursorBy(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, screenWidth.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, screenHeight.toFloat())
        updateOverlayPosition()
    }

    private fun checkEdgeScroll() {
        val now = System.currentTimeMillis()
        if (now - lastEdgeScrollTime < edgeScrollIntervalMs) return

        var triggered = false

        if (activeDirY < 0 && cursorY <= 0f) {
            scrollVertical(scrollDown = false)
            triggered = true
        } else if (activeDirY > 0 && cursorY >= screenHeight.toFloat()) {
            scrollVertical(scrollDown = true)
            triggered = true
        }

        if (activeDirX < 0 && cursorX <= 0f) {
            scrollHorizontal(scrollRight = false)
            triggered = true
        } else if (activeDirX > 0 && cursorX >= screenWidth.toFloat()) {
            scrollHorizontal(scrollRight = true)
            triggered = true
        }

        if (triggered) lastEdgeScrollTime = now
    }

    private fun scrollVertical(scrollDown: Boolean) {
        val centerX = screenWidth / 2f
        val startY = if (scrollDown) screenHeight * 0.7f else screenHeight * 0.3f
        val endY = if (scrollDown) {
            (startY - edgeScrollDistance).coerceAtLeast(0f)
        } else {
            (startY + edgeScrollDistance).coerceAtMost(screenHeight.toFloat())
        }
        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, edgeScrollDurationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun scrollHorizontal(scrollRight: Boolean) {
        val centerY = screenHeight / 2f
        val startX = if (scrollRight) screenWidth * 0.7f else screenWidth * 0.3f
        val endX = if (scrollRight) {
            (startX - edgeScrollDistance).coerceAtLeast(0f)
        } else {
            (startX + edgeScrollDistance).coerceAtMost(screenWidth.toFloat())
        }
        val path = Path().apply {
            moveTo(startX, centerY)
            lineTo(endX, centerY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, edgeScrollDurationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun performClickAt(x: Float, y: Float, longClick: Boolean) {
        val path = Path().apply { moveTo(x, y) }
        val duration = if (longClick) 600L else 40L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun performLongPressAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, longPressGestureDurationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun takeScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        }
    }

    private fun performBackspace() {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        try {
            val text = node.text?.toString() ?: return
            if (text.isEmpty()) return
            val newText = text.substring(0, text.length - 1)
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (e: Exception) {
            // Some fields don't support programmatic edits; fail quietly rather than
            // crashing the service and losing the cursor overlay.
        } finally {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
        }
    }

    private fun insertTextAtFocus(extra: String) {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        try {
            val current = node.text?.toString() ?: ""
            val newText = current + extra
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (e: Exception) {
            // Ignore fields that don't support programmatic edits.
        } finally {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
        }
    }

    private fun buildEmojiPanel() {
        emojiPanelView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xDD222222.toInt())
            setPadding(20, 14, 20, 14)
        }
        emojiList.forEach { e ->
            val tv = TextView(this).apply {
                text = e
                textSize = 20f
                setPadding(10, 6, 10, 6)
            }
            emojiTextViews.add(tv)
            emojiPanelView.addView(tv)
        }
        emojiPanelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 140
        }
        emojiPanelInitialized = true
    }

    private fun updateEmojiHighlight() {
        emojiTextViews.forEachIndexed { i, tv ->
            tv.setBackgroundColor(if (i == emojiSelectedIndex) 0xFFFFFFFF.toInt() else 0x00000000)
        }
    }

    private fun openEmojiPanel() {
        if (!emojiPanelInitialized) buildEmojiPanel()
        stopMoving()
        emojiSelectedIndex = 0
        updateEmojiHighlight()
        if (!emojiPanelOpen) {
            windowManager.addView(emojiPanelView, emojiPanelParams)
            emojiPanelOpen = true
        }
    }

    private fun closeEmojiPanel() {
        if (emojiPanelOpen) {
            windowManager.removeView(emojiPanelView)
            emojiPanelOpen = false
        }
    }

    private fun moveEmojiSelection(delta: Int) {
        val size = emojiList.size
        emojiSelectedIndex = ((emojiSelectedIndex + delta) % size + size) % size
        updateEmojiHighlight()
    }

    private fun insertSelectedEmoji() {
        insertTextAtFocus(emojiList[emojiSelectedIndex])
        closeEmojiPanel()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (pointerPaused) return super.onKeyEvent(event)

        if (emojiPanelOpen) {
            return handleEmojiPanelKeyEvent(event)
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { handleDirection(event, 0f, -1f); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { handleDirection(event, 0f, 1f); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { handleDirection(event, -1f, 0f); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { handleDirection(event, 1f, 0f); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                handleClickKey(event); true
            }
            KeyEvent.KEYCODE_MENU -> {
                handleMenuKey(event); true
            }
            KeyEvent.KEYCODE_BACK -> {
                val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (node != null) {
                    handleBackspaceKey(event)
                    true
                } else {
                    super.onKeyEvent(event)
                }
            }
            KeyEvent.KEYCODE_STAR -> {
                handleStarKey(event)
                super.onKeyEvent(event)
            }
            else -> super.onKeyEvent(event)
        }
    }

    private fun handleEmojiPanelKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveEmojiSelection(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveEmojiSelection(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> insertSelectedEmoji()
            KeyEvent.KEYCODE_BACK -> closeEmojiPanel()
            else -> {}
        }
        return true
    }

    private fun handleDirection(event: KeyEvent, dirX: Float, dirY: Float) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    startMoving(dirX, dirY)
                }
            }
            KeyEvent.ACTION_UP -> {
                stopMoving()
            }
        }
    }

    private fun handleClickKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    longPressFired = false
                    clickHandler.postDelayed(longPressRunnable, longPressThresholdMs)
                }
            }
            KeyEvent.ACTION_UP -> {
                clickHandler.removeCallbacks(longPressRunnable)
                clickHandler.removeCallbacks(autoClickStartRunnable)
                clickHandler.removeCallbacks(autoClickRunnable)
                autoClickActive = false
                if (!longPressFired) {
                    performClickAt(cursorX, cursorY, longClick = false)
                    showClickFeedback()
                }
            }
        }
    }

    private fun handleMenuKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    menuLongPressFired = false
                    clickHandler.postDelayed(menuLongPressRunnable, longPressThresholdMs)
                }
            }
            KeyEvent.ACTION_UP -> {
                clickHandler.removeCallbacks(menuLongPressRunnable)
                if (!menuLongPressFired) {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                }
            }
        }
    }

    private fun handleBackspaceKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    performBackspace()
                    backspaceHandler.postDelayed(backspaceStartRepeatRunnable, backspaceRepeatDelayMs)
                }
            }
            KeyEvent.ACTION_UP -> {
                backspaceHandler.removeCallbacks(backspaceStartRepeatRunnable)
                backspaceHandler.removeCallbacks(backspaceRepeatRunnable)
                backspaceHeld = false
            }
        }
    }

    private fun handleStarKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    clickHandler.postDelayed(starLongPressRunnable, longPressThresholdMs)
                }
            }
            KeyEvent.ACTION_UP -> {
                clickHandler.removeCallbacks(starLongPressRunnable)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            val imeVisible = windows?.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
            if (imeVisible != pointerPaused) {
                pointerPaused = imeVisible
                cursorView.visibility = if (pointerPaused) android.view.View.INVISIBLE else android.view.View.VISIBLE
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        stopMoving()
        clickHandler.removeCallbacksAndMessages(null)
        backspaceHandler.removeCallbacksAndMessages(null)
        if (::windowManager.isInitialized && ::cursorView.isInitialized) {
            windowManager.removeView(cursorView)
        }
        if (emojiPanelOpen && ::emojiPanelView.isInitialized) {
            windowManager.removeView(emojiPanelView)
        }
    }
}
