package com.example.clickycursor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView

class CursorAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var cursorView: ImageView
    private lateinit var params: WindowManager.LayoutParams

    private var cursorX = 0f
    private var cursorY = 0f

    private val moveStepPerTick = 14f
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

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (pointerPaused) return super.onKeyEvent(event)

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { handleDirection(event, 0f, -1f); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { handleDirection(event, 0f, 1f); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { handleDirection(event, -1f, 0f); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { handleDirection(event, 1f, 0f); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                handleClickKey(event); true
            }
            else -> super.onKeyEvent(event)
        }
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
        if (::windowManager.isInitialized && ::cursorView.isInitialized) {
            windowManager.removeView(cursorView)
        }
    }
}
