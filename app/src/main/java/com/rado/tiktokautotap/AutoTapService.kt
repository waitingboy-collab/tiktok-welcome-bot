package com.rado.tiktokautotap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * AccessibilityService, която изпълнява автоматични тапове (сърца) за TikTok LIVE.
 * Използва dispatchGesture() с GestureDescription и StrokeDescription.
 */
class AutoTapService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var overlayController: OverlayController

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var currentTapCount = 0

    // Параметри за тапване по подразбиране
    private var intervalMs: Long = 200L
    private var jitterMs: Int = 40
    private var maxTaps: Int = 500
    private var showOverlay: Boolean = true

    private val tapRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            // Извършване на тап в центъра на мерника
            performTap()

            currentTapCount++
            AutoTapState.setTapCount(currentTapCount)
            overlayController.updateUI(true, currentTapCount)

            // Автоматично спиране при достигнат лимит (0 = без лимит)
            if (maxTaps > 0 && currentTapCount >= maxTaps) {
                stopTapping()
                Toast.makeText(this@AutoTapService, "Достигнат е зададеният лимит от тапове!", Toast.LENGTH_SHORT).show()
                return
            }

            // Планиране на следващия тап с интервал + случаен разброс (jitter)
            val randomJitter = if (jitterMs > 0) Random.nextInt(-jitterMs, jitterMs + 1) else 0
            val nextDelay = (intervalMs + randomJitter).coerceAtLeast(30L)

            handler.postDelayed(this, nextDelay)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)

        overlayController = OverlayController(this) {
            toggleTapping()
        }

        AutoTapState.setServiceRunning(true)

        // Четене на настройките в реално време чрез Flow
        serviceScope.launch {
            settingsRepository.intervalFlow.collectLatest { intervalMs = it }
        }
        serviceScope.launch {
            settingsRepository.jitterFlow.collectLatest { jitterMs = it }
        }
        serviceScope.launch {
            settingsRepository.maxTapsFlow.collectLatest { maxTaps = it }
        }
        serviceScope.launch {
            settingsRepository.showOverlayFlow.collectLatest { show ->
                showOverlay = show
                if (show) {
                    overlayController.show(
                        overlayController.currentButtonX,
                        overlayController.currentButtonY,
                        overlayController.currentCrosshairX,
                        overlayController.currentCrosshairY
                    )
                } else {
                    stopTapping()
                    overlayController.hide()
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Не се изисква анализ на съдържанието на екрана (canRetrieveWindowContent="false")
    }

    override fun onInterrupt() {
        stopTapping()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTapping()
        overlayController.hide()
        AutoTapState.setServiceRunning(false)
        serviceScope.cancel()
    }

    private fun toggleTapping() {
        if (isRunning) {
            stopTapping()
        } else {
            startTapping()
        }
    }

    private fun startTapping() {
        if (isRunning) return
        // Проверка дали бутонът и мерникът се застъпват
        if (overlayController.checkOverlapAndWarn()) {
            return
        }

        currentTapCount = 0
        AutoTapState.setTapCount(0)
        isRunning = true
        AutoTapState.setTapping(true)
        overlayController.updateUI(true, 0)

        handler.post(tapRunnable)
    }

    private fun stopTapping() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacks(tapRunnable)
        AutoTapState.setTapping(false)
        overlayController.updateUI(false, currentTapCount)
    }

    private fun performTap() {
        val (x, y) = overlayController.getTapCoordinates()
        val path = Path().apply {
            moveTo(x, y)
        }
        // Продължителност на тапа между 40 и 60 мс
        val duration = Random.nextLong(40L, 61L)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gestureBuilder = GestureDescription.Builder().addStroke(stroke)

        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
            }
        }, null)
    }
}
