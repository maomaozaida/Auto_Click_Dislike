package com.example.autoclickdislike

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    companion object {
        private val PRESET_COLORS = intArrayOf(
            0xFFE53935.toInt(), // 红
            0xFF1E88E5.toInt(), // 蓝
            0xFF43A047.toInt(), // 绿
            0xFFFB8C00.toInt(), // 橙
            0xFF8E24AA.toInt(), // 紫
            0xFF212121.toInt(),  // 黑
            0xFF000000.toInt()  // 纯黑（关闭光源）
        )
        private const val DEFAULT_SIZE_DP = 64
        private const val DEFAULT_COLOR = 0xFFE53935.toInt()
    }

    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var toggleTarget: MaterialButtonToggleGroup
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvA11yStatus: TextView
    private lateinit var btnOverlay: Button
    private lateinit var btnA11y: Button
    private lateinit var switchShow: SwitchMaterial
    private lateinit var seekWidth: SeekBar
    private lateinit var seekHeight: SeekBar
    private lateinit var tvWidthLabel: TextView
    private lateinit var tvHeightLabel: TextView
    private lateinit var toggleShape: MaterialButtonToggleGroup
    private lateinit var switchText: SwitchMaterial
    private lateinit var colorRow: LinearLayout
    private lateinit var btnLaunch: Button

    // 屏幕尺寸（dp），滑条拉满即平齐屏幕
    private var screenWidthDp = 0
    private var screenHeightDp = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式全屏：内容延伸到状态栏/导航栏区域，状态栏透明
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val night = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        setContentView(R.layout.activity_main)

        // 内容避开状态栏/导航栏（背景仍全屏延伸）
        val root = findViewById<android.widget.ScrollView>(R.id.rootScroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        prefs = getSharedPreferences(FloatingAccessibilityService.PREFS, Context.MODE_PRIVATE)

        toggleTarget = findViewById(R.id.toggleTarget)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvA11yStatus = findViewById(R.id.tvA11yStatus)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnA11y = findViewById(R.id.btnA11y)
        switchShow = findViewById(R.id.switchShow)
        seekWidth = findViewById(R.id.seekWidth)
        seekHeight = findViewById(R.id.seekHeight)
        tvWidthLabel = findViewById(R.id.tvWidthLabel)
        tvHeightLabel = findViewById(R.id.tvHeightLabel)
        toggleShape = findViewById(R.id.toggleShape)
        switchText = findViewById(R.id.switchText)
        colorRow = findViewById(R.id.colorRow)
        btnLaunch = findViewById(R.id.btnLaunch)

        // 目标应用选择
        val savedTarget = prefs.getString(FloatingAccessibilityService.KEY_TARGET, FloatingAccessibilityService.TARGET_KS)
        toggleTarget.check(if (savedTarget == FloatingAccessibilityService.TARGET_DY) R.id.btnTargetDy else R.id.btnTargetKs)
        toggleTarget.addOnButtonCheckedListener { _, _, isChecked ->
            val newTarget = if (toggleTarget.checkedButtonId == R.id.btnTargetDy)
                FloatingAccessibilityService.TARGET_DY
            else
                FloatingAccessibilityService.TARGET_KS
            if (isChecked) {
                prefs.edit().putString(FloatingAccessibilityService.KEY_TARGET, newTarget).apply()
                // 目标应用变了，重新判定悬浮按钮显隐
                FloatingAccessibilityService.instance?.updateFloatVisibility()
            }
        }

        // 悬浮窗权限
        btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        // 无障碍权限
        btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 悬浮按钮开关：打开后仅在快手/抖音全屏前台（或本设置页预览）显示
        switchShow.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(FloatingAccessibilityService.KEY_ENABLED, checked).apply()
            FloatingAccessibilityService.instance?.updateFloatVisibility()
            if (checked) {
                Toast.makeText(this, "已开启：仅在快手/抖音全屏显示", Toast.LENGTH_SHORT).show()
            }
        }

        // 宽高：40dp ~ 真实屏幕宽/高（dp），拉满平齐屏幕；旧版统一大小作为默认值迁移
        val dm = resources.displayMetrics
        // 用真实显示区域（含状态栏/导航栏），否则拉满也比屏幕小一圈
        val real = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            android.graphics.Point(b.width(), b.height())
        } else {
            android.graphics.Point().also {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealSize(it)
            }
        }
        // 向上取整并 +1dp，保证拉满时窗口略大于屏幕，完全覆盖不留边
        screenWidthDp = kotlin.math.ceil(real.x / dm.density).toInt() + 1
        screenHeightDp = kotlin.math.ceil(real.y / dm.density).toInt() + 1
        val legacy = prefs.getInt(FloatingAccessibilityService.KEY_SIZE_DP, DEFAULT_SIZE_DP)
        val savedW = prefs.getInt(FloatingAccessibilityService.KEY_WIDTH_DP, legacy)
            .coerceIn(40, screenWidthDp)
        val savedH = prefs.getInt(FloatingAccessibilityService.KEY_HEIGHT_DP, legacy)
            .coerceIn(40, screenHeightDp)
        seekWidth.max = (screenWidthDp - 40).coerceAtLeast(1)
        seekHeight.max = (screenHeightDp - 40).coerceAtLeast(1)
        seekWidth.progress = savedW - 40
        seekHeight.progress = savedH - 40
        tvWidthLabel.text = "宽度：${savedW}dp / 最大${screenWidthDp}dp"
        tvHeightLabel.text = "高度：${savedH}dp / 最大${screenHeightDp}dp"

        toggleShape.check(
            when (prefs.getInt(FloatingAccessibilityService.KEY_SHAPE, 0)) {
                1 -> R.id.btnShapeRound
                2 -> R.id.btnShapeRect
                else -> R.id.btnShapeCircle
            }
        )
        toggleShape.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) pushConfig()
        }
        switchText.isChecked = prefs.getBoolean(FloatingAccessibilityService.KEY_SHOW_TEXT, true)
        switchText.setOnCheckedChangeListener { _, _ -> pushConfig() }

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (sb === seekWidth) tvWidthLabel.text = "宽度：${progress + 40}dp / 最大${screenWidthDp}dp"
                else tvHeightLabel.text = "高度：${progress + 40}dp / 最大${screenHeightDp}dp"
                pushConfig()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        seekWidth.setOnSeekBarChangeListener(seekListener)
        seekHeight.setOnSeekBarChangeListener(seekListener)

        buildColorRow()

        // 重置位置和大小
        findViewById<Button>(R.id.btnResetPos).setOnClickListener {
            prefs.edit()
                .remove(FloatingAccessibilityService.KEY_POS_X)
                .remove(FloatingAccessibilityService.KEY_POS_Y)
                .remove(FloatingAccessibilityService.KEY_WIDTH_DP)
                .remove(FloatingAccessibilityService.KEY_HEIGHT_DP)
                .apply()
            val defW = DEFAULT_SIZE_DP.coerceIn(40, screenWidthDp)
            val defH = DEFAULT_SIZE_DP.coerceIn(40, screenHeightDp)
            seekWidth.progress = defW - 40
            seekHeight.progress = defH - 40
            tvWidthLabel.text = "宽度：${defW}dp / 最大${screenWidthDp}dp"
            tvHeightLabel.text = "高度：${defH}dp / 最大${screenHeightDp}dp"
            pushConfig()
            FloatingAccessibilityService.instance?.resetPosition()
            Toast.makeText(this, "已重置位置和大小", Toast.LENGTH_SHORT).show()
        }

        // 水平居中 / 垂直居中
        findViewById<Button>(R.id.btnCenterH).setOnClickListener {
            val inst = FloatingAccessibilityService.instance
            if (inst == null) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            } else {
                inst.centerHorizontal()
                Toast.makeText(this, "已水平居中", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnCenterV).setOnClickListener {
            val inst = FloatingAccessibilityService.instance
            if (inst == null) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            } else {
                inst.centerVertical()
                Toast.makeText(this, "已垂直居中", Toast.LENGTH_SHORT).show()
            }
        }

        // 打开所选应用
        btnLaunch.setOnClickListener {
            val target = prefs.getString(FloatingAccessibilityService.KEY_TARGET, FloatingAccessibilityService.TARGET_KS)
            val pkg = if (target == FloatingAccessibilityService.TARGET_DY)
                FloatingAccessibilityService.PKG_DY else FloatingAccessibilityService.PKG_KS
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                Toast.makeText(this, "未安装该应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun currentColor(): Int = prefs.getInt(FloatingAccessibilityService.KEY_COLOR, DEFAULT_COLOR)

    /** 统一写配置并实时应用到悬浮窗 */
    private fun pushConfig() {
        val w = seekWidth.progress + 40
        val h = seekHeight.progress + 40
        val shape = when (toggleShape.checkedButtonId) {
            R.id.btnShapeRound -> 1
            R.id.btnShapeRect -> 2
            else -> 0
        }
        val showText = switchText.isChecked
        prefs.edit()
            .putInt(FloatingAccessibilityService.KEY_WIDTH_DP, w)
            .putInt(FloatingAccessibilityService.KEY_HEIGHT_DP, h)
            .putInt(FloatingAccessibilityService.KEY_SHAPE, shape)
            .putBoolean(FloatingAccessibilityService.KEY_SHOW_TEXT, showText)
            .apply()
        FloatingAccessibilityService.instance?.applyConfig(w, h, currentColor(), shape, showText)
    }

    private fun buildColorRow() {
        colorRow.removeAllViews()
        val selected = currentColor()
        val dp = resources.displayMetrics.density
        PRESET_COLORS.forEach { color ->
            val view = android.view.View(this)
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
                if (color == selected) {
                    setStroke((3 * dp).toInt(), Color.WHITE)
                }
            }
            view.background = bg
            view.layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).apply {
                marginEnd = (14 * dp).toInt()
            }
            view.setOnClickListener {
                prefs.edit().putInt(FloatingAccessibilityService.KEY_COLOR, color).apply()
                pushConfig()
                buildColorRow()
            }
            colorRow.addView(view)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return services.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                    it.resolveInfo.serviceInfo.name == FloatingAccessibilityService::class.java.name
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        val overlayOk = Settings.canDrawOverlays(this)
        val a11yOk = isAccessibilityEnabled()

        tvOverlayStatus.text = if (overlayOk) "已授权" else "未授权"
        tvOverlayStatus.setTextColor(if (overlayOk) 0xFF43A047.toInt() else 0xFFE53935.toInt())
        btnOverlay.text = if (overlayOk) "已授权" else "去授权"
        btnOverlay.isEnabled = !overlayOk

        tvA11yStatus.text = if (a11yOk) "已授权" else "未授权"
        tvA11yStatus.setTextColor(if (a11yOk) 0xFF43A047.toInt() else 0xFFE53935.toInt())
        btnA11y.text = if (a11yOk) "已授权" else "去授权"
        btnA11y.isEnabled = !a11yOk

        switchShow.isEnabled = overlayOk && a11yOk
        switchShow.isChecked = a11yOk && overlayOk &&
                prefs.getBoolean(FloatingAccessibilityService.KEY_ENABLED, false)
        // 回到本页时重算一次显隐（本页允许预览悬浮按钮）
        FloatingAccessibilityService.instance?.updateFloatVisibility()
    }
}
