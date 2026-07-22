package com.tvfilebridge.tvcompanion

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView

private const val CURSOR_SIZE_PX = 26
private const val AUTO_HIDE_DELAY_MS = 4000L
private const val ICON_SIZE_PX = 96

class CursorAccessibilityService : AccessibilityService(), CommandHandler {

    private lateinit var windowManager: WindowManager
    private var cursorView: ImageView? = null
    private var cursorX = 0f
    private var cursorY = 0f
    private var screenWidth = 0
    private var screenHeight = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { hideCursor() }

    private val commandServer = CommandServer(this)

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        cursorX = screenWidth / 2f
        cursorY = screenHeight / 2f

        // Cursor starts hidden - only appears once the phone actually enters
        // cursor mode (a MOVE/CLICK/SHOW command arrives), and disappears
        // again after a few seconds of no activity rather than sitting on
        // screen permanently.
        commandServer.start()
    }

    override fun onDestroy() {
        commandServer.stop()
        mainHandler.removeCallbacks(autoHideRunnable)
        hideCursor()
        super.onDestroy()
    }

    private fun showCursor() {
        mainHandler.post {
            if (cursorView == null) {
                val view = ImageView(this).apply {
                    setImageResource(R.drawable.ic_cursor)
                }
                val params = WindowManager.LayoutParams(
                    CURSOR_SIZE_PX,
                    CURSOR_SIZE_PX,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = cursorX.toInt()
                    y = cursorY.toInt()
                }
                windowManager.addView(view, params)
                cursorView = view
            }
            scheduleAutoHide()
        }
    }

    private fun hideCursor() {
        mainHandler.post {
            cursorView?.let { runCatching { windowManager.removeView(it) } }
            cursorView = null
        }
    }

    private fun scheduleAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }

    private fun updateCursorPosition() {
        mainHandler.post {
            val view = cursorView ?: return@post
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@post
            params.x = cursorX.toInt()
            params.y = cursorY.toInt()
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    override fun onMove(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, screenWidth.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, screenHeight.toFloat())
        showCursor()
        updateCursorPosition()
    }

    /**
     * dispatchGesture()'s synthesized single-point tap gets cancelled
     * instantly on this TV's OS build regardless of stroke shape/duration.
     * `adb shell input tap` (same mechanism the D-pad's keyevents already use
     * reliably) works fine, so clicking is done phone-side over ADB - this
     * just reports where the cursor currently is.
     */
    override fun onClick(): Pair<Int, Int> {
        showCursor()
        return cursorX.toInt() to cursorY.toInt()
    }

    override fun onShowCursor() {
        showCursor()
    }

    override fun onHideCursor() {
        mainHandler.removeCallbacks(autoHideRunnable)
        hideCursor()
    }

    override fun listApps(): List<AppEntry> {
        val pm = packageManager
        val leanback = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER"),
            PackageManager.MATCH_ALL,
        )
        // Sideloaded phone/tablet apps (e.g. a browser with no TV build) only
        // declare the regular phone-style LAUNCHER category, not
        // LEANBACK_LAUNCHER, so they'd otherwise never show up here even
        // though they're installed and launchable. Add them too, but only
        // packages not already found via LEANBACK_LAUNCHER, so TV apps that
        // reasonably declare both aren't duplicated.
        val leanbackPackages = leanback.map { it.activityInfo.packageName }.toSet()
        val regular = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.MATCH_ALL,
        ).filter { it.activityInfo.packageName !in leanbackPackages }

        return (leanback + regular)
            .map { info ->
                AppEntry(
                    packageName = info.activityInfo.packageName,
                    label = info.loadLabel(pm).toString(),
                    iconBase64 = encodeIcon(info.loadIcon(pm)),
                )
            }
            // Some apps register more than one launcher activity (e.g. one
            // per profile/mode) - queryIntentActivities then returns the same
            // package twice, which crashes the phone's LazyVerticalGrid
            // (needs a unique key per item).
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun encodeIcon(drawable: android.graphics.drawable.Drawable): String {
        val size = ICON_SIZE_PX
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
    }

    override fun launchApp(packageName: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
        intent?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
