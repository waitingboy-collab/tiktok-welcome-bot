package com.rado.tiktokautotap

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Сингълтън обект за комуникация между MainActivity и AutoTapService в реално време (StateFlow).
 */
object AutoTapState {
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _isTapping = MutableStateFlow(false)
    val isTapping: StateFlow<Boolean> = _isTapping.asStateFlow()

    private val _tapCount = MutableStateFlow(0)
    val tapCount: StateFlow<Int> = _tapCount.asStateFlow()

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
        if (!running) {
            _isTapping.value = false
        }
    }

    fun setTapping(tapping: Boolean) {
        _isTapping.value = tapping
    }

    fun setTapCount(count: Int) {
        _tapCount.value = count
    }
}
