package com.rado.tiktokautotap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

/**
 * Контролер за плаващия overlay (старт/стоп бутон и мерник) през WindowManager (TYPE_ACCESSIBILITY_OVERLAY).
 */
class OverlayController(
    private val context: Context,
    private val onToggleTapping: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var buttonView: View? = null
    private var crosshairView: View? = null

    private var buttonParams: WindowManager.LayoutParams? = null
    private var crosshairParams: WindowManager.LayoutParams? = null

    private var isShown = false

    var currentButtonX = 100
    var currentButtonY = 300
    var currentCrosshairX = 500
    var currentCrosshairY = 1000

    fun show(savedBtnX: Int, savedBtnY: Int, savedCrossX: Int, savedCrossY: Int) {
        if (isShown) return
        currentButtonX = savedBtnX
        currentButtonY = savedBtnY
        currentCrosshairX = savedCrossX
        currentCrosshairY = savedCrossY

        createButtonView()
        createCrosshairView()
        isShown = true
    }

    fun hide() {
        if (!isShown) return
        try {
            buttonView?.let { windowManager.removeView(it) }
            crosshairView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        buttonView = null
        crosshairView = null
        isShown = false
    }

    private fun createButtonView() {
        val layout = FrameLayout(context).apply {
            setBackgroundColor(0xCCFE2C55.toInt()) // TikTok Red с полупрозрачност
            setPadding(24, 16, 24, 16)
            elevation = 12f
        }

        val textView = TextView(context).apply {
            text = "▶ 0"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
        }
        layout.addView(textView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = currentButtonX
            y = currentButtonY
        }
        buttonParams = params

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoved = false

        layout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    if (hypot(deltaX.toDouble(), deltaY.toDouble()) > 10) {
                        isMoved = true
                    }
                    params.x = initialX + deltaX
                    params.y = initialY + deltaY
                    try {
                        windowManager.updateViewLayout(layout, params)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    currentButtonX = params.x
                    currentButtonY = params.y
                    if (!isMoved) {
                        onToggleTapping()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(layout, params)
            buttonView = layout
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createCrosshairView() {
        val size = 120
        val layout = CrosshairLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            alpha = 0.9f
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = currentCrosshairX
            y = currentCrosshairY
        }
        crosshairParams = params

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        layout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(layout, params)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    currentCrosshairX = params.x
                    currentCrosshairY = params.y
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(layout, params)
            crosshairView = layout
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateUI(isTapping: Boolean, count: Int) {
        buttonView?.let { v ->
            val tv = (v as? ViewGroup)?.getChildAt(0) as? TextView
            tv?.text = if (isTapping) "■ $count" else "▶ $count"
            v.setBackgroundColor(if (isTapping) 0xCCFF3333.toInt() else 0xCCFE2C55.toInt())
        }

        crosshairView?.let { v ->
            // ВАЖНО: Докато тапването работи, мерникът става FLAG_NOT_TOUCHABLE и полупрозрачен
            crosshairParams?.let { p ->
                if (isTapping) {
                    p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    v.alpha = 0.4f
                } else {
                    p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    v.alpha = 0.9f
                }
                try {
                    windowManager.updateViewLayout(v, p)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun checkOverlapAndWarn(): Boolean {
        val btnCenterX = currentButtonX + 60
        val btnCenterY = currentButtonY + 30
        val crossCenterX = currentCrosshairX + 60
        val crossCenterY = currentCrosshairY + 60

        val distance = hypot((btnCenterX - crossCenterX).toDouble(), (btnCenterY - crossCenterY).toDouble())
        if (distance < 120) {
            Toast.makeText(context, "Внимание: Плаващият бутон и мерникът се застъпват!", Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    fun getTapCoordinates(): Pair<Float, Float> {
        val centerX = (currentCrosshairX + 60).toFloat()
        val centerY = (currentCrosshairY + 60).toFloat()
        return Pair(centerX, centerY)
    }
}

class CrosshairLayout(context: Context) : View(context) {
    private val paint = Paint().apply {
        color = 0xFFFE2C55.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = 0x33FE2C55.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = Math.min(cx, cy) - 4f

        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, paint)

        canvas.drawLine(cx - 15f, cy, cx + 15f, cy, paint)
        canvas.drawLine(cx, cy - 15f, cx, cy + 15f, paint)
        canvas.drawCircle(cx, cy, 3f, paint)
    }
}
