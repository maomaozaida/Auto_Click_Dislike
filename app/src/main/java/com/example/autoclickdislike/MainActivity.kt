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
            0xFF212121.toInt()  // 黑
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
    private lateinit var seekSize: SeekBar
    private lateinit var colorRow: LinearLayout
    private lateinit var btnLaunch: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(FloatingAccessibilityService.PREFS, Context.MODE_PRIVATE)

        toggleTarget = findViewById(R.id.toggleTarget)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvA11yStatus = findViewById(R.id.tvA11yStatus)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnA11y = findViewById(R.id.btnA11y)
        switchShow = findViewById(R.id.switchShow)
        seekSize = findViewById(R.id.seekSize)
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
            if (isChecked) prefs.edit().putString(FloatingAccessibilityService.KEY_TARGET, newTarget).apply()
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

        // 悬浮按钮开关
        switchShow.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(FloatingAccessibilityService.KEY_ENABLED, checked).apply()
            if (checked) {
                FloatingAccessibilityService.instance?.showFloat()
            } else {
                FloatingAccessibilityService.instance?.hideFloat()
            }
        }

        // 大小：进度 0-100 对应 40-140dp
        val savedSize = prefs.getInt(FloatingAccessibilityService.KEY_SIZE_DP, DEFAULT_SIZE_DP)
        seekSize.progress = savedSize - 40
        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val sizeDp = 40 + progress
                prefs.edit().putInt(FloatingAccessibilityService.KEY_SIZE_DP, sizeDp).apply()
                FloatingAccessibilityService.instance?.applyConfig(sizeDp, currentColor())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        buildColorRow()

        // 重置悬浮按钮位置（防拖出屏幕外卡住）
        findViewById<Button>(R.id.btnResetPos).setOnClickListener {
            prefs.edit()
                .remove(FloatingAccessibilityService.KEY_POS_X)
                .remove(FloatingAccessibilityService.KEY_POS_Y)
                .apply()
            FloatingAccessibilityService.instance?.resetPosition()
            Toast.makeText(this, "悬浮按钮位置已重置", Toast.LENGTH_SHORT).show()
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
                FloatingAccessibilityService.instance?.applyConfig(currentSizeDp(), color)
                buildColorRow()
            }
            colorRow.addView(view)
        }
    }

    private fun currentSizeDp(): Int = 40 + seekSize.progress

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
    }
}
