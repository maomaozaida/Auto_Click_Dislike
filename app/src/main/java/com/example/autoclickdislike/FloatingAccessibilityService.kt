package com.example.autoclickdislike

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class FloatingAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: FloatingAccessibilityService? = null
            private set

        const val PREFS = "float_cfg"
        const val KEY_ENABLED = "enabled"
        const val KEY_SIZE_DP = "size_dp" // 旧版统一大小，仅用于迁移默认值
        const val KEY_WIDTH_DP = "width_dp"
        const val KEY_HEIGHT_DP = "height_dp"
        const val KEY_COLOR = "color"
        const val KEY_SHAPE = "shape"
        const val KEY_SHOW_TEXT = "show_text"
        const val KEY_TARGET = "target"
        const val KEY_POS_X = "pos_x"
        const val KEY_POS_Y = "pos_y"

        const val SHAPE_CIRCLE = 0
        const val SHAPE_ROUND = 1
        const val SHAPE_RECT = 2

        const val TARGET_KS = "ks"
        const val TARGET_DY = "dy"
        const val PKG_KS = "com.smile.gifmaker"
        const val PKG_DY = "com.ss.android.ugc.aweme"

        private const val LONG_PRESS_MS = 900L
        private const val DEFAULT_SIZE_DP = 64
        private const val DEFAULT_COLOR = 0xFFE53935.toInt()
        private const val SHIELD_TIMEOUT_MS = 4000L
        const val DEFAULT_X = 60
        const val DEFAULT_Y = 360
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var floatView: FrameLayout? = null
    private var labelView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var widthPx = 0
    private var heightPx = 0
    private var busy = false

    // 长按完成、等待弹窗节点出现：此期间由无障碍事件驱动点击
    @Volatile
    private var awaitPanel = false

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 服务刚连上时窗口信息可能还没就绪，延后一次再判定
        updateFloatVisibility()
        mainHandler.postDelayed({ updateFloatVisibility() }, 500)
    }

    override fun onDestroy() {
        removeFloatView()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /**
     * 事件驱动核心：长按完成后（awaitPanel=true），弹窗 Activity 一启动就会回调
     * TYPE_WINDOW_STATE_CHANGED，此时 rootInActiveWindow 已是弹窗窗口，
     * 立刻找「不感兴趣」节点 performAction 点击——不等轮询、不给误触时间窗。
     */
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (event == null) return
        // 前台窗口变化时判定悬浮窗该不该显示
        if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            updateFloatVisibility()
        }
        if (!awaitPanel) return
        when (event.eventType) {
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> tryClick()
        }
    }

    // ---------------- 前台全屏判定 ----------------

    private fun targetPkgName(): String =
        if (prefs().getString(KEY_TARGET, TARGET_KS) == TARGET_DY) PKG_DY else PKG_KS

    /** 当前获得焦点的应用窗口 */
    private fun activeAppWindow(): AccessibilityWindowInfo? {
        return try {
            windows.firstOrNull {
                it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 目标应用是否在前台全屏独占：
     * 活跃应用窗口属于目标应用，且窗口尺寸基本覆盖整个屏幕（排除分屏/小窗/弹层）。
     */
    private fun isTargetFullscreen(): Boolean {
        val win = activeAppWindow() ?: return false
        val pkg = win.root?.packageName?.toString() ?: return false
        if (pkg != targetPkgName()) return false
        val dm = resources.displayMetrics
        val b = Rect()
        win.getBoundsInScreen(b)
        return b.width() >= dm.widthPixels * 0.98f && b.height() >= dm.heightPixels * 0.9f
    }

    /** 是否应该显示悬浮按钮：开关打开 + 有悬浮窗权限 + 目标应用全屏前台（设置页内显示用于预览） */
    private fun shouldShowFloat(): Boolean {
        if (!prefs().getBoolean(KEY_ENABLED, false)) return false
        if (!android.provider.Settings.canDrawOverlays(this)) return false
        val win = activeAppWindow() ?: return false
        val pkg = win.root?.packageName?.toString() ?: return false
        if (pkg == packageName) return true          // 本设置页：允许预览
        return isTargetFullscreen()
    }

    /** 按当前前台状态显示或隐藏悬浮按钮 */
    fun updateFloatVisibility() {
        // 执行过程中不改显隐，避免面板弹出导致按钮闪断
        if (busy) return
        if (shouldShowFloat()) {
            if (floatView == null) {
                try {
                    showFloat()
                } catch (_: Exception) {
                }
            }
        } else if (floatView != null) {
            removeFloatView()
        }
    }

    /** 找到目标节点就立即点击并收尾，返回是否成功。 */
    private fun tryClick(): Boolean {
        if (!busy) return false
        val node = findTargetNode() ?: return false
        awaitPanel = false
        clickNode(node)
        toast("已点击「不感兴趣」")
        finishRun()
        return true
    }

    // ---------------- 悬浮按钮 ----------------

    /** 读取旧版统一大小作为宽高默认值（迁移兼容） */
    private fun legacySize(): Int = prefs().getInt(KEY_SIZE_DP, DEFAULT_SIZE_DP)

    private fun buildBg(color: Int, shape: Int, density: Float): GradientDrawable =
        GradientDrawable().apply {
            when (shape) {
                SHAPE_CIRCLE -> this.shape = GradientDrawable.OVAL
                SHAPE_ROUND -> {
                    this.shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16f * density
                }
                else -> this.shape = GradientDrawable.RECTANGLE
            }
            setColor(color)
        }

    fun showFloat() {
        if (floatView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        this.wm = wm

        val density = resources.displayMetrics.density
        widthPx = (prefs().getInt(KEY_WIDTH_DP, legacySize()) * density).toInt()
        heightPx = (prefs().getInt(KEY_HEIGHT_DP, legacySize()) * density).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            widthPx, heightPx, type,
            // LAYOUT_NO_LIMITS：允许拖到状态栏/导航栏等全部屏幕区域
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 恢复上次拖动保存的位置
            x = prefs().getInt(KEY_POS_X, DEFAULT_X)
            y = prefs().getInt(KEY_POS_Y, DEFAULT_Y)
        }
        layoutParams = params

        val view = FrameLayout(this)
        view.background = buildBg(
            prefs().getInt(KEY_COLOR, DEFAULT_COLOR),
            prefs().getInt(KEY_SHAPE, SHAPE_CIRCLE),
            density
        )
        view.elevation = 8f * density

        val label = TextView(this).apply {
            text = "踩"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            visibility = if (prefs().getBoolean(KEY_SHOW_TEXT, true)) View.VISIBLE else View.GONE
        }
        view.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        view.setOnTouchListener(touchListener)
        labelView = label
        floatView = view

        wm.addView(view, params)
    }

    fun hideFloat() {
        removeFloatView()
    }

    /** 重置悬浮按钮到默认位置，用于拖出屏幕外卡住时恢复 */
    fun resetPosition() {
        prefs().edit().putInt(KEY_POS_X, DEFAULT_X).putInt(KEY_POS_Y, DEFAULT_Y).apply()
        val p = layoutParams ?: return
        val view = floatView ?: return
        p.x = DEFAULT_X
        p.y = DEFAULT_Y
        try {
            wm?.updateViewLayout(view, p)
        } catch (_: Exception) {
        }
    }

    fun applyConfig(widthDp: Int, heightDp: Int, color: Int, shape: Int, showText: Boolean) {
        val density = resources.displayMetrics.density
        widthPx = (widthDp * density).toInt()
        heightPx = (heightDp * density).toInt()
        val view = floatView ?: return
        view.background = buildBg(color, shape, density)
        labelView?.visibility = if (showText) View.VISIBLE else View.GONE
        layoutParams?.let {
            it.width = widthPx
            it.height = heightPx
            try {
                wm?.updateViewLayout(view, it)
            } catch (_: Exception) {
            }
        }
    }

    private fun removeFloatView() {
        floatView?.let {
            try {
                wm?.removeView(it)
            } catch (_: Exception) {
            }
        }
        floatView = null
        labelView = null
        layoutParams = null
        wm = null
    }

    // downX, downY, startX, startY, moved(0/1)
    private var touchState: FloatArray? = null

    // 拖动节流：每帧（16ms）最多一次 updateViewLayout，pending 位置合并到下一帧
    private var lastLayoutTime = 0L
    private val pendingPos = IntArray(2)
    private var pendingLayout: Runnable? = null

    private fun scheduleLayout(v: View, p: WindowManager.LayoutParams) {
        val now = android.os.SystemClock.uptimeMillis()
        val elapsed = now - lastLayoutTime
        if (elapsed >= 16) {
            lastLayoutTime = now
            try {
                wm?.updateViewLayout(v, p)
            } catch (_: Exception) {
            }
        } else if (pendingLayout == null) {
            pendingLayout = Runnable {
                pendingLayout = null
                lastLayoutTime = android.os.SystemClock.uptimeMillis()
                layoutParams?.let { lp ->
                    lp.x = pendingPos[0]
                    lp.y = pendingPos[1]
                    try {
                        wm?.updateViewLayout(v, lp)
                    } catch (_: Exception) {
                    }
                }
            }.also { mainHandler.postDelayed(it, 16 - elapsed) }
        }
    }

    private val touchListener = View.OnTouchListener { v, e ->
        val p = layoutParams ?: return@OnTouchListener false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pendingLayout?.let { mainHandler.removeCallbacks(it) }
                pendingLayout = null
                touchState = floatArrayOf(e.rawX, e.rawY, p.x.toFloat(), p.y.toFloat(), 0f)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val st = touchState ?: return@OnTouchListener true
                val dx = e.rawX - st[0]
                val dy = e.rawY - st[1]
                if (abs(dx) > slop || abs(dy) > slop) {
                    st[4] = 1f
                    val newX = st[2].toInt() + dx.toInt()
                    val newY = st[3].toInt() + dy.toInt()
                    // 位置没变化就跳过，避免无效窗口更新
                    if (newX != p.x || newY != p.y) {
                        p.x = newX
                        p.y = newY
                        pendingPos[0] = newX
                        pendingPos[1] = newY
                        scheduleLayout(v, p)
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val st = touchState
                touchState = null
                pendingLayout?.let { mainHandler.removeCallbacks(it) }
                pendingLayout = null
                if (st != null && st[4] == 0f) {
                    execute()
                } else {
                    // 拖动结束：最终位置落地 + 保存
                    try {
                        wm?.updateViewLayout(v, p)
                    } catch (_: Exception) {
                    }
                    prefs().edit().putInt(KEY_POS_X, p.x).putInt(KEY_POS_Y, p.y).apply()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchState = null
                pendingLayout?.let { mainHandler.removeCallbacks(it) }
                pendingLayout = null
                true
            }
            else -> false
        }
    }

    // ---------------- 自动化流程 ----------------

    private fun setLabel(text: String) {
        labelView?.text = text
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun execute() {
        if (busy) return
        busy = true
        setLabel("···")

        val target = prefs().getString(KEY_TARGET, TARGET_KS) ?: TARGET_KS
        val expectedPkg = if (target == TARGET_KS) PKG_KS else PKG_DY
        val appName = if (target == TARGET_KS) "快手" else "抖音"

        val dm = resources.displayMetrics
        val pressX = dm.widthPixels / 2f
        // 孔位上移到约 42% 屏高：足够触发视频长按，同时避开长按弹窗顶部
        // （「分享给朋友」区域），即使小孔被误触也只落在视频上
        var pressY = dm.heightPixels * 0.42f

        // 避免长按点落在悬浮按钮自身附近
        val p = layoutParams
        if (p != null) {
            val btnRect = Rect(p.x, p.y, p.x + widthPx, p.y + heightPx)
            val inflated = Rect(btnRect)
            inflated.inset(-80, -80)
            if (inflated.contains(pressX.toInt(), pressY.toInt())) {
                pressY = dm.heightPixels / 4f
            }
        }

        // 超时兜底：4 秒内没完成就恢复
        mainHandler.postDelayed({
            if (busy) {
                toast("未找到「不感兴趣」入口")
                finishRun()
            }
        }, SHIELD_TIMEOUT_MS)

        val current = rootInActiveWindow?.packageName?.toString()
        if (current != expectedPkg) {
            toast("请先打开$appName 再点击悬浮按钮")
            finishRun()
            return
        }

        longPress(pressX, pressY, LONG_PRESS_MS) { ok ->
            if (!ok) {
                toast("手势被系统中断，请重试")
                finishRun()
                return@longPress
            }
            // 长按完成后交给事件驱动：弹窗 Activity 启动时 onAccessibilityEvent 立即触发 tryClick
            awaitPanel = true
            // 先立即试一次：弹窗可能在长按完成前就已就绪
            if (tryClick()) return@longPress
            // 低频兜底轮询（每 250ms）：防止个别 ROM 事件延迟或漏发，
            // 正常路径由事件驱动在弹窗出现瞬间完成，轮询只是保险
            fun fallback() {
                if (!busy || !awaitPanel) return
                if (tryClick()) return
                mainHandler.postDelayed({ fallback() }, 250)
            }
            mainHandler.postDelayed({ fallback() }, 250)
        }
    }

    private fun finishRun() {
        busy = false
        awaitPanel = false
        setLabel("踩")
        // 执行期间跳过显隐更新，结束后补一次（可能已离开目标应用）
        updateFloatVisibility()
    }

    private fun longPress(x: Float, y: Float, durationMs: Long, callback: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback(false)
            }
        }, null)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun findTargetNode(): AccessibilityNodeInfo? {
        // 1) 系统内置文本查找（C 层子串匹配，同时覆盖 text 与 contentDescription），最快
        rootInActiveWindow?.let { root ->
            root.findAccessibilityNodeInfosByText("不感兴趣")?.firstOrNull()?.let { return it }
            root.findAccessibilityNodeInfosByText("不推荐")?.firstOrNull()?.let { return it }
        }
        // 2) 兜底：遍历所有窗口（弹窗可能是独立窗口），递归匹配
        return try {
            windows.forEach { w ->
                w.root?.let { root ->
                    findNodeRecursive(root)?.let { return it }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun findNodeRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.contains("不感兴趣") || text.contains("不推荐") ||
            desc.contains("不感兴趣") || desc.contains("不推荐")
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeRecursive(child)
            if (found != null) return found
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return
            }
            n = n.parent
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        tap(rect.exactCenterX(), rect.exactCenterY())
    }
}
