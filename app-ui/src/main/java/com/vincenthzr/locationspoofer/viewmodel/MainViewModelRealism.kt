package com.vincenthzr.locationspoofer.viewmodel

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.viewModelScope
import com.vincenthzr.locationspoofer.data.model.GaitRecordingState
import com.vincenthzr.locationspoofer.utils.AccelSample
import com.vincenthzr.locationspoofer.utils.GaitTemplate
import com.vincenthzr.locationspoofer.utils.GyroSample
import com.vincenthzr.locationspoofer.utils.MotionRealism
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 运动真实度设置（issue #67 / #68）：随机强度、速度浮动范围、个人步态录制

internal fun MainViewModel.setRealismLevel(levelId: Int) {
    settingsRepository.realismLevel = levelId
    _uiState.update { it.copy(realismLevel = levelId) }
}

internal fun MainViewModel.setSpeedFluctuationPct(pct: Int) {
    val value = pct.coerceIn(0, MotionRealism.MAX_SPEED_FLUCTUATION_PCT)
    settingsRepository.speedFluctuationPct = value
    _uiState.update { it.copy(speedFluctuationPct = value) }
}

internal fun MainViewModel.setUseGaitTemplate(enabled: Boolean) {
    settingsRepository.useGaitTemplate = enabled
    _uiState.update { it.copy(useGaitTemplate = enabled) }
}

internal fun MainViewModel.deleteGaitTemplate() {
    settingsRepository.gaitTemplate = ""
    settingsRepository.useGaitTemplate = false
    _uiState.update { it.copy(gaitTemplateCadence = null, gaitTemplateStrides = 0, gaitTemplateHasGyroscope = false, useGaitTemplate = false) }
}

internal fun MainViewModel.setKeepLastMapPosition(enabled: Boolean) {
    settingsRepository.keepLastMapPosition = enabled
    _uiState.update { it.copy(keepLastMapPosition = enabled) }
}

private const val GAIT_COUNTDOWN_SEC = 5
private const val GAIT_RECORD_SEC = 20

/** 同时录制加速度和角速度，按共同时间轴提取个人步态；开始和结束震动提示。 */
internal fun MainViewModel.startGaitRecording() {
    if (gaitRecordingJob?.isActive == true) return
    gaitRecordingJob = viewModelScope.launch {
        for (s in GAIT_COUNTDOWN_SEC downTo 1) {
            _uiState.update { it.copy(gaitRecording = GaitRecordingState.Countdown(s)) }
            delay(1000)
        }
        vibrate(context, 300)
        val samples = recordGaitSensors(context, GAIT_RECORD_SEC) { progress ->
            _uiState.update { it.copy(gaitRecording = GaitRecordingState.Recording(progress)) }
        }
        vibrate(context, 600)

        _uiState.update { it.copy(gaitRecording = GaitRecordingState.Processing) }
        when (val result = withContext(Dispatchers.Default) { GaitTemplate.extract(samples.acceleration, samples.gyroscope) }) {
            is GaitTemplate.Extraction.Success -> {
                settingsRepository.gaitTemplate = result.template.encode()
                settingsRepository.useGaitTemplate = true
                _uiState.update {
                    it.copy(
                        gaitRecording = GaitRecordingState.Idle,
                        gaitTemplateCadence = result.template.cadenceSpm,
                        gaitTemplateStrides = result.template.strideCount,
                        gaitTemplateHasGyroscope = result.template.hasGyroscope,
                        useGaitTemplate = true
                    )
                }
            }
            is GaitTemplate.Extraction.Failure -> _uiState.update {
                it.copy(gaitRecording = GaitRecordingState.Failed(result.reason))
            }
        }
    }
}

internal fun MainViewModel.cancelGaitRecording() {
    gaitRecordingJob?.cancel()
    gaitRecordingJob = null
    _uiState.update { it.copy(gaitRecording = GaitRecordingState.Idle) }
}

internal fun MainViewModel.dismissGaitRecordingResult() {
    _uiState.update { it.copy(gaitRecording = GaitRecordingState.Idle) }
}

private data class GaitRecordingSamples(
    val acceleration: List<AccelSample> = emptyList(),
    val gyroscope: List<GyroSample> = emptyList()
)

private suspend fun recordGaitSensors(
    context: Context,
    seconds: Int,
    onProgress: (Float) -> Unit
): GaitRecordingSamples {
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return GaitRecordingSamples()
    val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    val samples = ArrayList<AccelSample>()
    val gyroSamples = ArrayList<GyroSample>()
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            synchronized(samples) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> samples += AccelSample(event.timestamp, event.values[0], event.values[1], event.values[2])
                    Sensor.TYPE_GYROSCOPE -> gyroSamples += GyroSample(event.timestamp, event.values[0], event.values[1], event.values[2])
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    try {
        if (!sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)) return GaitRecordingSamples()
        // Gyroscope is optional: unsupported/failed registration retains acceleration recording.
        if (gyroscope != null) runCatching {
            sensorManager.registerListener(listener, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        }
        val totalMs = seconds * 1000L
        var elapsed = 0L
        while (elapsed < totalMs) {
            onProgress(elapsed.toFloat() / totalMs)
            delay(200)
            elapsed += 200
        }
        onProgress(1f)
    } finally {
        sensorManager.unregisterListener(listener)
    }
    return synchronized(samples) { GaitRecordingSamples(samples.toList(), gyroSamples.toList()) }
}

private fun vibrate(context: Context, millis: Long) {
    try {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: Exception) {
    }
}
