package com.example.clickycursor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class CursorAccessibilityService : AccessibilityService() {

    companion object {
        // Contract with TyrionDictionary's IME: it broadcasts this explicitly (setPackage-targeted)
        // whenever it starts/stops actively composing text, so we don't have to guess typing state
        // purely from accessibility window snooping (which was getting stuck / unreliable).
        private const val ACTION_TYPING_STATE = "com.tyrion.dictionary.TYPING_STATE"
        private const val EXTRA_TYPING = "typing"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var cursorView: ImageView
    private lateinit var params: WindowManager.LayoutParams

    private var cursorX = 0f
    private var cursorY = 0f

    private var moveStepPerTick = 7f
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

    // --- Center/OK key: tap / long-press right-click / auto-click on hold ---
    private val longPressThresholdMs = 400L
    private val longPressGestureDurationMs = 700L
    private var longPressFired = false

    private val dragStartDelayMs = 400L
    private var dragActive = false
    private var dragLastX = 0f
    private var dragLastY = 0f
    private var lastDragDispatchTime = 0L
    private val dragThrottleMs = 60L

    private val dragStartRunnable = Runnable {
        dragActive = true
        dragLastX = cursorX
        dragLastY = cursorY
    }

    private val longPressRunnable = Runnable {
        longPressFired = true
        performLongPressAt(cursorX, cursorY)
        showClickFeedback()
        clickHandler.postDelayed(dragStartRunnable, dragStartDelayMs)
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

    // --- Menu key (magnifying glass, keycode 82): tap = delete one char, hold = continuous delete ---
    private var menuHeld = false
    private val menuRepeatDelayMs = 400L
    private val menuRepeatIntervalMs = 140L
    private val menuRepeatRunnable = object : Runnable {
        override fun run() {
            if (!menuHeld) return
            performBackspace()
            clickHandler.postDelayed(this, menuRepeatIntervalMs)
        }
    }
    private val menuStartRepeatRunnable = Runnable {
        menuHeld = true
        clickHandler.post(menuRepeatRunnable)
    }

    private val brightnessHandler = Handler(Looper.getMainLooper())
    private var brightnessAdjusting = false
    private var brightnessDecreasing = true
    private val brightnessStepDelayMs = 90L
    private val brightnessStep = 8
    private val brightnessRunnable = object : Runnable {
        override fun run() {
            if (!brightnessAdjusting) return
            adjustBrightness(if (brightnessDecreasing) -brightnessStep else brightnessStep)
            brightnessHandler.postDelayed(this, brightnessStepDelayMs)
        }
    }

    // Triple-tap the center key quickly = screenshot.
    private val screenshotClickTimestamps = mutableListOf<Long>()
    private val tripleClickWindowMs = 600L

    // Reaching the top edge toggles the status bar (once per arrival, not repeated).
    private var topEdgeActive = false
    private var statusBarExpanded = false

    // --- Back key: tap = normal Back, hold = Recents ---
    private var backLongPressFired = false
    private val backLongPressRunnable = Runnable {
        backLongPressFired = true
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    private var lastImeVisibleTime = 0L
    private val imeGracePeriodMs = 1000L

    // --- Star key long-press: floating emoji picker ---
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
    private var externalTypingActive = false

    private val typingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_TYPING_STATE) {
                externalTypingActive = intent.getBooleanExtra(EXTRA_TYPING, false)
                updatePointerPausedState()
            }
        }
    }

    private lateinit var prefs: SharedPreferences
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (key == MainActivity.KEY_CURSOR_SPEED) {
            moveStepPerTick = sp.getFloat(MainActivity.KEY_CURSOR_SPEED, MainActivity.DEFAULT_SPEED)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        moveStepPerTick = prefs.getFloat(MainActivity.KEY_CURSOR_SPEED, MainActivity.DEFAULT_SPEED)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        setupOverlay()

        val filter = IntentFilter(ACTION_TYPING_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(typingStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(typingStateReceiver, filter)
        }
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

        if (dragActive) {
            val now = System.currentTimeMillis()
            if (now - lastDragDispatchTime >= dragThrottleMs) {
                performDragSegment(dragLastX, dragLastY, cursorX, cursorY)
                dragLastX = cursorX
                dragLastY = cursorY
                lastDragDispatchTime = now
            }
        }
    }

    private fun performDragSegment(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, dragThrottleMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun checkEdgeScroll() {
        // Top edge: toggle the status bar once per arrival, not repeatedly while held.
        if (activeDirY < 0 && cursorY <= 0f) {
            if (!topEdgeActive) {
                toggleStatusBar()
                topEdgeActive = true
            }
        } else if (cursorY > 0f) {
            topEdgeActive = false
        }

        val now = System.currentTimeMillis()
        if (now - lastEdgeScrollTime < edgeScrollIntervalMs) return

        var triggered = false

        if (activeDirY > 0 && cursorY >= screenHeight.toFloat()) {
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

    private fun registerClickForTripleTapScreenshot() {
        val now = System.currentTimeMillis()
        screenshotClickTimestamps.add(now)
        while (screenshotClickTimestamps.isNotEmpty() &&
            now - screenshotClickTimestamps.first() > tripleClickWindowMs
        ) {
            screenshotClickTimestamps.removeAt(0)
        }
        if (screenshotClickTimestamps.size >= 3) {
            screenshotClickTimestamps.clear()
            takeScreenshot()
        }
    }

    private fun adjustBrightness(delta: Int) {
        if (!Settings.System.canWrite(this)) return
        try {
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val current = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val newVal = (current + delta).coerceIn(5, 255)
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, newVal)
        } catch (e: Exception) {
            // Permission not granted or setting unavailable; fail quietly.
        }
    }

    private fun toggleStatusBar() {
        if (!statusBarExpanded) {
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            statusBarExpanded = true
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
            statusBarExpanded = false
        }
    }

    private fun performBackspace() {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        try {
            val isHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
            if (isHint) return
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
            val isHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
            val current = if (isHint) "" else (node.text?.toString() ?: "")
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

    private val emojiWindowSize = 7

    private fun buildEmojiPanel() {
        emojiPanelView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xDD222222.toInt())
            setPadding(20, 14, 20, 14)
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

    private fun refreshEmojiPanelViews() {
        emojiPanelView.removeAllViews()
        emojiTextViews.clear()

        val size = emojiList.size
        val windowSize = emojiWindowSize.coerceAtMost(size)
        var start = emojiSelectedIndex - windowSize / 2
        start = start.coerceIn(0, (size - windowSize).coerceAtLeast(0))
        val end = start + windowSize

        if (start > 0) {
            emojiPanelView.addView(TextView(this).apply {
                text = "\u2039"
                textSize = 16f
                setPadding(6, 6, 6, 6)
            })
        }

        for (i in start until end) {
            val tv = TextView(this).apply {
                text = emojiList[i]
                textSize = 20f
                setPadding(10, 6, 10, 6)
                setBackgroundColor(if (i == emojiSelectedIndex) 0xFFFFFFFF.toInt() else 0x00000000)
            }
            emojiTextViews.add(tv)
            emojiPanelView.addView(tv)
        }

        if (end < size) {
            emojiPanelView.addView(TextView(this).apply {
                text = "\u203A"
                textSize = 16f
                setPadding(6, 6, 6, 6)
            })
        }
    }

    private fun openEmojiPanel() {
        if (!emojiPanelInitialized) buildEmojiPanel()
        stopMoving()
        emojiSelectedIndex = 0
        refreshEmojiPanelViews()
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
        refreshEmojiPanelViews()
    }

    private fun insertSelectedEmoji() {
        insertTextAtFocus(emojiList[emojiSelectedIndex])
        closeEmojiPanel()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (emojiPanelOpen) {
            return handleEmojiPanelKeyEvent(event)
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (pointerPaused) super.onKeyEvent(event) else { handleDirection(event, 0f, -1f); true }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (pointerPaused) super.onKeyEvent(event) else { handleDirection(event, 0f, 1f); true }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (pointerPaused) super.onKeyEvent(event) else { handleDirection(event, -1f, 0f); true }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (pointerPaused) super.onKeyEvent(event) else { handleDirection(event, 1f, 0f); true }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (pointerPaused) super.onKeyEvent(event) else { handleClickKey(event); true }
            }
            KeyEvent.KEYCODE_MENU -> {
                handleMenuKey(event); true
            }
            KeyEvent.KEYCODE_BACK -> {
                handleBackKey(event); true
            }
            KeyEvent.KEYCODE_4 -> {
                if (isTypingContextActive()) {
                    super.onKeyEvent(event)
                } else {
                    handleBrightnessKey(event, decrease = true); true
                }
            }
            KeyEvent.KEYCODE_6 -> {
                if (isTypingContextActive()) {
                    super.onKeyEvent(event)
                } else {
                    handleBrightnessKey(event, decrease = false); true
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
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) moveEmojiSelection(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) moveEmojiSelection(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) insertSelectedEmoji()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) closeEmojiPanel()
                true
            }
            else -> super.onKeyEvent(event)
        }
    }

    private fun getDisplayRotation(): Int {
        return try {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        } catch (e: Exception) {
            Surface.ROTATION_0
        }
    }

    private fun rotateForDisplay(dx: Float, dy: Float): Pair<Float, Float> {
        return when (getDisplayRotation()) {
            Surface.ROTATION_90 -> Pair(dy, -dx)
            Surface.ROTATION_180 -> Pair(-dx, -dy)
            Surface.ROTATION_270 -> Pair(-dy, dx)
            else -> Pair(dx, dy)
        }
    }

    private fun handleDirection(event: KeyEvent, dirX: Float, dirY: Float) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    val (rdx, rdy) = rotateForDisplay(dirX, dirY)
                    startMoving(rdx, rdy)
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
                clickHandler.removeCallbacks(dragStartRunnable)
                dragActive = false
                if (!longPressFired) {
                    performClickAt(cursorX, cursorY, longClick = false)
                    showClickFeedback()
                    registerClickForTripleTapScreenshot()
                }
            }
        }
    }

    private fun handleMenuKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    performBackspace()
                    clickHandler.postDelayed(menuStartRepeatRunnable, menuRepeatDelayMs)
                }
            }
            KeyEvent.ACTION_UP -> {
                clickHandler.removeCallbacks(menuStartRepeatRunnable)
                clickHandler.removeCallbacks(menuRepeatRunnable)
                menuHeld = false
            }
        }
    }

    private fun handleBackKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    backLongPressFired = false
                    clickHandler.postDelayed(backLongPressRunnable, longPressThresholdMs)
                }
            }
            KeyEvent.ACTION_UP -> {
                clickHandler.removeCallbacks(backLongPressRunnable)
                if (!backLongPressFired) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
        }
    }

    private fun handleBrightnessKey(event: KeyEvent, decrease: Boolean) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    brightnessDecreasing = decrease
                    adjustBrightness(if (decrease) -brightnessStep else brightnessStep)
                    brightnessAdjusting = true
                    brightnessHandler.postDelayed(brightnessRunnable, brightnessStepDelayMs)
                }
            }
            KeyEvent.ACTION_UP -> {
                brightnessAdjusting = false
                brightnessHandler.removeCallbacks(brightnessRunnable)
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
            updatePointerPausedState()
        }
    }

    /**
     * Combines two signals: (1) whether any accessibility window of type
     * TYPE_INPUT_METHOD is currently on screen (works for the system keyboard,
     * Gboard, etc.), and (2) the explicit broadcast TyrionDictionary sends on
     * onStartInputView/onFinishInputView. TyrionDictionary intentionally shows
     * no IME window at all now (to avoid the platform's "first Back dismisses
     * the keyboard" behavior), so signal (2) is what actually drives this while
     * using it; signal (1) is kept as a fallback for other keyboards.
     */
    private fun updatePointerPausedState() {
        val windowBasedTyping = windows?.any {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        } == true
        val imeVisible = windowBasedTyping || externalTypingActive
        if (imeVisible) lastImeVisibleTime = System.currentTimeMillis()
        if (imeVisible != pointerPaused) {
            pointerPaused = imeVisible
            cursorView.visibility = if (pointerPaused) android.view.View.INVISIBLE else android.view.View.VISIBLE
        }
    }

    private fun isTypingContextActive(): Boolean {
        return pointerPaused || (System.currentTimeMillis() - lastImeVisibleTime < imeGracePeriodMs)
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::windowManager.isInitialized) return

        val oldWidth = screenWidth
        val oldHeight = screenHeight
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        cursorX = if (oldWidth > 0) (cursorX / oldWidth * screenWidth) else screenWidth / 2f
        cursorY = if (oldHeight > 0) (cursorY / oldHeight * screenHeight) else screenHeight / 2f

        if (::cursorView.isInitialized) updateOverlayPosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMoving()
        clickHandler.removeCallbacksAndMessages(null)
        brightnessHandler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        try {
            unregisterReceiver(typingStateReceiver)
        } catch (e: Exception) {
            // Not registered (e.g. onServiceConnected never ran); safe to ignore.
        }
        if (::windowManager.isInitialized && ::cursorView.isInitialized) {
            windowManager.removeView(cursorView)
        }
        if (emojiPanelOpen && ::emojiPanelView.isInitialized) {
            windowManager.removeView(emojiPanelView)
        }
    }
}
