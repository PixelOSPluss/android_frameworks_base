/*
 * Copyright (C) 2026 Lunaris-AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.quicklaunch

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@SysUISingleton
class QuickLaunchController
@Inject
constructor(
    @Main private val context: Context,
    private val keyguardStateController: KeyguardStateController,
    private val biometricUnlockControllerProvider: Provider<BiometricUnlockController>,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val handler = Handler(Looper.getMainLooper())

    private var overlayView: QuickLaunchRadialView? = null
    private var isShowing = false
    private var isLongPressActive = false

    private var centerX = 0f
    private var centerY = 0f
    private var longPressRunnable: Runnable? = null

    val isEnabled: Boolean
        get() = Settings.Secure.getInt(context.contentResolver, "quick_launch_enabled", 0) == 1

    fun onFingerDown(x: Float, y: Float) {
        if (!isEnabled || !keyguardStateController.isShowing) return

        centerX = x
        centerY = y
        isLongPressActive = false

        longPressRunnable = Runnable {
            isLongPressActive = true
            vibrateHaptic()
            showOverlay(x, y)
        }
        handler.postDelayed(longPressRunnable!!, LONG_PRESS_TIMEOUT_MS)
    }

    fun onTouchMove(x: Float, y: Float) {
        if (isShowing) {
            overlayView?.updateTouchPosition(x, y)
        }
    }

    fun onFingerUp(x: Float, y: Float): Boolean {
        handler.removeCallbacks(longPressRunnable ?: Runnable {})

        if (isShowing) {
            val selectedPkg = overlayView?.getSelectedPackage(x, y)
            dismissOverlay()

            if (!selectedPkg.isNullOrEmpty()) {
                launchApp(selectedPkg)
                return true
            }
        }
        isLongPressActive = false
        return false
    }

    private fun showOverlay(x: Float, y: Float) {
        if (isShowing) return

        val view = QuickLaunchRadialView(context, x, y)
        overlayView = view

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            format = android.graphics.PixelFormat.TRANSLUCENT
        }

        windowManager.addView(view, params)
        view.animateIn()
        isShowing = true
    }

    private fun dismissOverlay() {
        if (!isShowing || overlayView == null) return

        overlayView?.animateOut {
            try {
                windowManager.removeView(overlayView)
            } catch (ignored: Exception) {
            }
            overlayView = null
            isShowing = false
            isLongPressActive = false
        }
    }

    private fun launchApp(pkgName: String) {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(pkgName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        biometricUnlockControllerProvider.get().startKeyguardFadingAway()
        context.startActivityAsUser(intent, UserHandle.CURRENT)
    }

    private fun vibrateHaptic() {
        vibrator?.vibrate(
            VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
        )
    }

    companion object {
        private const val LONG_PRESS_TIMEOUT_MS = 300L
    }
}

class QuickLaunchRadialView(
    context: Context,
    private val centerX: Float,
    private val centerY: Float
) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
    }

    private val slotBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF")
        style = Paint.Style.FILL
    }

    private val activeSlotBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.FILL
    }

    private val packages = mutableListOf<String>()
    private val icons = mutableListOf<Drawable?>()
    private val slotPositions = mutableListOf<PointF>()

    private var selectedIndex = -1
    private val radius = 220f
    private val slotSize = 100f

    init {
        loadPackages()
        calculateSlotPositions()
    }

    private fun loadPackages() {
        val saved = Settings.Secure.getString(context.contentResolver, "quick_launch_slots")
        if (!saved.isNullOrEmpty()) {
            packages.addAll(saved.split(","))
        }
        val pm = context.packageManager
        for (pkg in packages) {
            try {
                icons.add(pm.getApplicationIcon(pkg))
            } catch (e: Exception) {
                icons.add(null)
            }
        }
    }

    private fun calculateSlotPositions() {
        slotPositions.clear()
        val count = packages.size.coerceAtMost(5)
        if (count == 0) return

        val startAngle = -Math.PI / 2
        val angleStep = Math.PI * 2 / count

        for (i in 0 until count) {
            val angle = startAngle + i * angleStep
            val x = centerX + radius * cos(angle).toFloat()
            val y = centerY + radius * sin(angle).toFloat()
            slotPositions.add(PointF(x, y))
        }
    }

    fun updateTouchPosition(touchX: Float, touchY: Float) {
        var closest = -1
        var minDistance = Float.MAX_VALUE

        for (i in slotPositions.indices) {
            val pos = slotPositions[i]
            val dist = hypot(touchX - pos.x, touchY - pos.y)
            if (dist < slotSize && dist < minDistance) {
                minDistance = dist
                closest = i
            }
        }

        if (selectedIndex != closest) {
            selectedIndex = closest
            invalidate()
        }
    }

    fun getSelectedPackage(touchX: Float, touchY: Float): String? {
        updateTouchPosition(touchX, touchY)
        return if (selectedIndex in packages.indices) packages[selectedIndex] else null
    }

    fun animateIn() {
        alpha = 0f
        animate().alpha(1f).setDuration(180).start()
    }

    fun animateOut(onEnd: () -> Unit) {
        animate().alpha(0f).setDuration(150).withEndAction(onEnd).start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        for (i in slotPositions.indices) {
            val pos = slotPositions[i]
            val paint = if (i == selectedIndex) activeSlotBgPaint else slotBgPaint
            canvas.drawCircle(pos.x, pos.y, slotSize / 2, paint)

            val icon = icons.getOrNull(i)
            if (icon != null) {
                val left = (pos.x - slotSize / 3).toInt()
                val top = (pos.y - slotSize / 3).toInt()
                val right = (pos.x + slotSize / 3).toInt()
                val bottom = (pos.y + slotSize / 3).toInt()
                icon.setBounds(left, top, right, bottom)
                icon.draw(canvas)
            }
        }
    }
}
