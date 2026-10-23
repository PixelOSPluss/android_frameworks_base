/*
 * Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.biometrics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.android.systemui.res.R
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.ceil

/**
 * Faithful port of the Xiaomi Madrid style-E UDFPS halo and ripple.
 *
 * The original implementation is:
 *  - `MiuiGxzwAnimItemE` plays 24 `gxzw_e_recognizing_anim_*` frames.
 *  - `MiuiGxzwAnimRes(0x10, true, ...)` means 16 ms per frame and repeat while recognizing.
 *  - `MiuiGxzwHighlightView` draws the 452x452 `gxzw_white_halo_light` image behind the frames.
 *  - The animation is 720x720 at the 1080x2400 reference resolution.
 *
 * The frame resources are the real 720x720 WebP images from
 * `DevicesOverlay.apk!/res/drawable-nxhdpi-v4/gxzw_e_recognizing_anim_*.webp`. They are kept as
 * separate frames, in the original numeric order, instead of being approximated with drawn shapes.
 */
class UdfpsMadridRippleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val enabled = context.resources.getBoolean(R.bool.config_udfpsMadridHaloRipple)

    private val frames = AtomicReferenceArray<Bitmap?>(FRAME_COUNT)
    private val frameResources = intArrayOf(
        R.drawable.gxzw_e_recognizing_anim_1,
        R.drawable.gxzw_e_recognizing_anim_2,
        R.drawable.gxzw_e_recognizing_anim_3,
        R.drawable.gxzw_e_recognizing_anim_4,
        R.drawable.gxzw_e_recognizing_anim_5,
        R.drawable.gxzw_e_recognizing_anim_6,
        R.drawable.gxzw_e_recognizing_anim_7,
        R.drawable.gxzw_e_recognizing_anim_8,
        R.drawable.gxzw_e_recognizing_anim_9,
        R.drawable.gxzw_e_recognizing_anim_10,
        R.drawable.gxzw_e_recognizing_anim_11,
        R.drawable.gxzw_e_recognizing_anim_12,
        R.drawable.gxzw_e_recognizing_anim_13,
        R.drawable.gxzw_e_recognizing_anim_14,
        R.drawable.gxzw_e_recognizing_anim_15,
        R.drawable.gxzw_e_recognizing_anim_16,
        R.drawable.gxzw_e_recognizing_anim_17,
        R.drawable.gxzw_e_recognizing_anim_18,
        R.drawable.gxzw_e_recognizing_anim_19,
        R.drawable.gxzw_e_recognizing_anim_20,
        R.drawable.gxzw_e_recognizing_anim_21,
        R.drawable.gxzw_e_recognizing_anim_22,
        R.drawable.gxzw_e_recognizing_anim_23,
        R.drawable.gxzw_e_recognizing_anim_24,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val haloBitmap: Bitmap? by lazy(LazyThreadSafetyMode.NONE) {
        decodeBitmap(R.drawable.udfps_white_halo_light)
    }
    private val haloRadiusPx = context.resources.getDimensionPixelOffset(
        R.dimen.udfps_madrid_halo_res_circle_radius
    ).toFloat()

    private val decodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "UdfpsMadridDecode").apply { isDaemon = true }
    }
    private val decodeStarted = AtomicBoolean(false)

    private val sensorBounds = Rect()
    private val haloDest = RectF()
    private val frameDest = RectF()
    private val clearDest = RectF()
    private var centerX = 0f
    private var centerY = 0f
    private var animationSize = 0f
    private var haloSize = 0f
    private var lastFrame: Bitmap? = null

    private var running = false
    private var animationStarted = false
    private var clearFullCanvas = false
    private var frameIndex = 0
    private var startTime = 0L

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val elapsed = SystemClock.uptimeMillis() - startTime
            val nextFrame = ((elapsed / FRAME_INTERVAL_MS) % FRAME_COUNT).toInt()
            if (nextFrame != frameIndex) {
                frameIndex = nextFrame
                invalidate()
            }
            postOnAnimation(this)
        }
    }

    init {
        visibility = INVISIBLE
        setWillNotDraw(false)
    }

    /**
     * Called with the true sensor bounds from `UdfpsOverlayParams.sensorBounds`.
     */
    fun setSensorBounds(bounds: Rect) {
        sensorBounds.set(bounds)
        centerX = sensorBounds.exactCenterX()
        centerY = sensorBounds.exactCenterY()

        val displayWidth = resources.displayMetrics.widthPixels
            .takeIf { it > 0 }
            ?: REFERENCE_WIDTH.toInt()
        animationSize = ANIM_SIZE_AT_REFERENCE * displayWidth / REFERENCE_WIDTH

        val sensorWidth = if (sensorBounds.width() > 0) {
            sensorBounds.width().toFloat()
        } else {
            DEFAULT_SENSOR_WIDTH
        }
        haloSize = if (haloRadiusPx > 0f) {
            ceil(HALO_SIZE_AT_REFERENCE * sensorWidth / haloRadiusPx)
        } else {
            HALO_SIZE_AT_REFERENCE
        }

        val halfHalo = haloSize / 2f
        haloDest.set(
            centerX - halfHalo,
            centerY - halfHalo,
            centerX + halfHalo,
            centerY + halfHalo,
        )
        val halfAnimation = animationSize / 2f
        frameDest.set(
            centerX - halfAnimation,
            centerY - halfAnimation,
            centerX + halfAnimation,
            centerY + halfAnimation,
        )
        clearDest.set(frameDest)
        clearDest.union(haloDest)
        invalidate()
    }

    fun startRipple() {
        if (!enabled || running) return
        running = true
        animationStarted = false
        clearFullCanvas = true
        visibility = VISIBLE
        frameIndex = 0
        lastFrame = null
        ensureFramesDecoded()
        maybeStartAnimation()
        invalidate()
    }

    fun stopRipple() {
        if (!running) {
            visibility = INVISIBLE
            return
        }
        running = false
        animationStarted = false
        removeCallbacks(frameRunnable)
        visibility = INVISIBLE
        invalidate()
    }

    private fun maybeStartAnimation() {
        if (!running || animationStarted) return
        val firstFrame = frames.get(0) ?: return
        lastFrame = firstFrame
        frameIndex = 0
        invalidate()
    }

    private fun ensureFramesDecoded() {
        if (decodeExecutor.isShutdown) return
        if (!decodeStarted.compareAndSet(false, true)) return
        try {
            decodeExecutor.execute {
                for (index in frameResources.indices) {
                    val bitmap = decodeBitmap(frameResources[index])
                    if (bitmap != null) {
                        frames.set(index, bitmap)
                        post {
                            maybeStartAnimation()
                            invalidate()
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // The overlay is going away; there is no frame to show.
        }
    }

    private fun decodeBitmap(resId: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeResource(resources, resId, options)
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to decode Madrid UDFPS asset", e)
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return

        // The original MiuiGxzwAnimationView clears its canvas before every frame. Without
        // this, skipped frame indices would leave stale rings behind.
        if (clearFullCanvas) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            clearFullCanvas = false
        } else {
            canvas.save()
            canvas.clipRect(clearDest)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.restore()
        }

        haloBitmap?.let { bitmap ->
            canvas.drawBitmap(bitmap, null, haloDest, paint)
        }

        val bitmap = frames.get(frameIndex) ?: lastFrame
        if (bitmap != null) {
            lastFrame = bitmap
            canvas.drawBitmap(bitmap, null, frameDest, paint)
            if (!animationStarted) {
                // Start the clock only after the first frame has actually been drawn. This
                // guarantees frame 1 (index 0) is visible for one full interval even when
                // decoding or the first vsync lands just after the start time.
                animationStarted = true
                startTime = SystemClock.uptimeMillis()
                postOnAnimation(frameRunnable)
            }
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(frameRunnable)
        decodeExecutor.shutdown()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TAG = "UdfpsMadridRippleView"

        // MiuiGxzwAnimItemE: 24 frames, MiuiGxzwAnimRes(0x10, true, ...).
        private const val FRAME_COUNT = 24
        private const val FRAME_INTERVAL_MS = 16L

        // MiuiGxzwUtils: GXZW_ANIM_WIDTH/HEIGHT = 720 at the 1080x2400 reference.
        private const val REFERENCE_WIDTH = 1080f
        private const val ANIM_SIZE_AT_REFERENCE = 720f

        // MiuiGxzwUtils: GXZW_ICON_WIDTH defaults to 173 and the halo image is 452x452.
        // MiuiGxzwHightlightContainer scales the halo by
        // GXZW_ICON_WIDTH / gxzw_halo_res_circle_radius.
        private const val HALO_SIZE_AT_REFERENCE = 452f
        private const val DEFAULT_SENSOR_WIDTH = 173f
    }
}
