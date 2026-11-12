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
        const val KEY_SIZE_DP = "size_dp"
        const val KEY_COLOR = "color"
        const val KEY_TARGET = "target"
        const val KEY_POS_X = "pos_x"
        const val KEY_POS_Y = "pos_y"

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
    private var sizePx = 0
    private var busy = false

    // 执行期间的触摸拦截层：
    // 长按阶段 = 4 条带围孔（孔区域无窗口，注入手势才能穿透到快手）
    // 弹窗阶段 = 实心全屏（点击用 performAction 不需要孔，用户触摸全拦截）
    private val shieldBands = mutableListOf<View>()
    private var solidShield: View? = null
    private var shieldTimeout: Runnable? = null

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (prefs().getBoolean(KEY_ENABLED, false)) {
            showFloat()
        }
    }

    override fun onDestroy() {
        removeFloatView()
        hideShield()
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}

    // ---------------- 悬浮按钮 ----------------

    fun showFloat() {
        if (floatView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        this.wm = wm

        val sizeDp = prefs().getInt(KEY_SIZE_DP, DEFAULT_SIZE_DP)
        val density = resources.displayMetrics.density
        sizePx = (sizeDp * density).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            sizePx, sizePx, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 恢复上次拖动保存的位置
            x = prefs().getInt(KEY_POS_X, DEFAULT_X)
            y = prefs().getInt(KEY_POS_Y, DEFAULT_Y)
        }
        layoutParams = params

        val view = FrameLayout(this)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(prefs().getInt(KEY_COLOR, DEFAULT_COLOR))
        }
        view.background = bg
        view.elevation = 8f * density

        val label = TextView(this).apply {
            text = "踩"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
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

    fun applyConfig(sizeDp: Int, color: Int) {
        val density = resources.displayMetrics.density
        sizePx = (sizeDp * density).toInt()
        val view = floatView ?: return
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        view.background = bg
        layoutParams?.let {
            it.width = sizePx
            it.height = sizePx
            wm?.updateViewLayout(view, it)
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

    private val touchListener = View.OnTouchListener { v, e ->
        val p = layoutParams ?: return@OnTouchListener false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchState = floatArrayOf(e.rawX, e.rawY, p.x.toFloat(), p.y.toFloat(), 0f)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val st = touchState ?: return@OnTouchListener true
                val dx = e.rawX - st[0]
                val dy = e.rawY - st[1]
                if (abs(dx) > slop || abs(dy) > slop) {
                    st[4] = 1f
                    p.x = st[2].toInt() + dx.toInt()
                    p.y = st[3].toInt() + dy.toInt()
                    try {
                        wm?.updateViewLayout(v, p)
                    } catch (_: Exception) {
                    }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val st = touchState
                touchState = null
                if (st != null && st[4] == 0f) {
                    execute()
                } else {
                    // 拖动结束，保存位置
                    layoutParams?.let { p ->
                        prefs().edit().putInt(KEY_POS_X, p.x).putInt(KEY_POS_Y, p.y).apply()
                    }
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchState = null
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
            val btnRect = Rect(p.x, p.y, p.x + sizePx, p.y + sizePx)
            val inflated = Rect(btnRect)
            inflated.inset(-80, -80)
            if (inflated.contains(pressX.toInt(), pressY.toInt())) {
                pressY = dm.heightPixels / 4f
            }
        }

        // 第一步：先上开孔遮挡阻止用户触摸（孔对准长按注入点），再执行后续逻辑
        showShield(pressX, pressY)

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
            // 长按完成：快手弹窗已弹出，换成实心全屏遮挡（最后 add 所以 z-order 最高），
            // 后续点击「不感兴趣」走 performAction 节点级操作，不受遮挡影响
            solidShield()
            var tries = 0
            fun poll() {
                if (!busy) return
                val node = findTargetNode()
                if (node != null) {
                    hideShield()
                    clickNode(node)
                    toast("已点击「不感兴趣」")
                    finishRun()
                } else if (tries++ < 40) {
                    mainHandler.postDelayed({ poll() }, 60)
                } else {
                    toast("未找到「不感兴趣」入口，请在目标页面重试")
                    finishRun()
                }
            }
            mainHandler.postDelayed({ poll() }, 100)
        }
    }

    // ---------------- 触摸拦截层（两阶段） ----------------

    /**
     * 阶段 1：长按期间用 4 条带围出中心孔。
     * 孔区域没有任何窗口覆盖，InputDispatcher 会把注入的长按手势直接发给快手；
     * 条带区域消费用户触摸，防止误触。
     */
    private fun showShield(pressX: Float, pressY: Float) {
        if (shieldBands.isNotEmpty()) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels

            val hole = 36
            val holeL = (pressX - hole).toInt().coerceIn(0, screenW)
            val holeR = (pressX + hole).toInt().coerceIn(0, screenW)
            val holeT = (pressY - hole).toInt().coerceIn(0, screenH)
            val holeB = (pressY + hole).toInt().coerceIn(0, screenH)
            addBand(wm, 0, 0, screenW, holeT)                 // 上
            addBand(wm, 0, holeB, screenW, screenH - holeB)   // 下
            addBand(wm, 0, holeT, holeL, holeB - holeT)       // 左
            addBand(wm, holeR, holeT, screenW - holeR, holeB - holeT) // 右

            shieldTimeout = Runnable {
                if (busy) {
                    toast("执行超时，已恢复触屏")
                    finishRun()
                }
            }.also { mainHandler.postDelayed(it, SHIELD_TIMEOUT_MS) }
        } catch (e: Exception) {
            toast("触摸拦截层添加失败：${e.message}")
        }
    }

    private fun addBand(wm: WindowManager, x: Int, y: Int, bw: Int, bh: Int) {
        if (bw <= 0 || bh <= 0) return
        val params = WindowManager.LayoutParams(
            bw, bh, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        val view = View(this).apply {
            background = android.graphics.drawable.ColorDrawable(0x33000000)
            setOnTouchListener { _, _ -> true }
        }
        wm.addView(view, params)
        shieldBands.add(view)
    }

    /** 阶段 2：移除条带，换成实心全屏遮挡（此时不需要孔，点击走 performAction）。 */
    private fun solidShield() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        shieldBands.forEach { v ->
            try { wm.removeViewImmediate(v) } catch (_: Exception) {}
        }
        shieldBands.clear()
        if (solidShield != null) return
        try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            val view = View(this).apply {
                background = android.graphics.drawable.ColorDrawable(0x33000000)
                setOnTouchListener { _, _ -> true }
            }
            wm.addView(view, params)
            solidShield = view
        } catch (_: Exception) {
        }
    }

    private fun overlayType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else
        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun hideShield() {
        shieldTimeout?.let { mainHandler.removeCallbacks(it) }
        shieldTimeout = null
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        shieldBands.forEach { v ->
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        shieldBands.clear()
        solidShield?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        solidShield = null
    }

    private fun finishRun() {
        busy = false
        hideShield()
        setLabel("踩")
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
