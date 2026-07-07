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

    private val moveStepPerTick = 6f
    private val tickIntervalMs = 16L

    private val moveHandler = Handler(Looper.getMainLooper())
    private var activeDirX = 0f
    private var activeDirY = 0f
    private var isMoving = false

    private val moveRunnable = object : Runnable {
        override fun run() {
            if (!isMoving) return
            moveCursorBy(activeDirX * moveStepPerTick, activeDirY * moveStepPerTick)
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

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        performClickAt(cursorX, cursorY, longClick = true)
    }

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
            setImageResource(R.drawable.cursor_dot)
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
            x = cursorX.toInt()
            y = cursorY.toInt()
        }

        windowManager.addView(cursorView, params)
    }

    private fun moveCursorBy(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, screenWidth.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, screenHeight.toFloat())
        params.x = cursorX.toInt()
        params.y = cursorY.toInt()
        windowManager.updateViewLayout(cursorView, params)
    }

    private fun performClickAt(x: Float, y: Float, longClick: Boolean) {
        val path = Path().apply { moveTo(x, y) }
        val duration = if (longClick) 600L else 40L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
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
                    longPressTriggered = false
                    longPressHandler.postDelayed(longPressRunnable, 500)
                }
            }
            KeyEvent.ACTION_UP -> {
                longPressHandler.removeCallbacks(longPressRunnable)
                if (!longPressTriggered) {
                    performClickAt(cursorX, cursorY, longClick = false)
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
        if (::windowManager.isInitialized && ::cursorView.isInitialized) {
            windowManager.removeView(cursorView)
        }
    }
}
