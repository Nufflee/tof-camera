package com.example.tofcamera;

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.CountDownTimer
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

class AccelerometerIdleMonitor<T>(private val context: T) : SensorEventListener
  where T : IdleListener, T : Context {
  private val TAG: String = AccelerometerIdleMonitor::class.java.simpleName

  private val sensorManager = context.getSystemService(SensorManager::class.java)
  private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

  private var lastTime: Long = 0
  private var isIdling: Boolean = false

  private val IDLE_THRESHOLD = 0.5 // m/s^2
  private val IDLE_TIMEOUT: Long = 30 * 1000 // ms

  private val timer = object : CountDownTimer(IDLE_TIMEOUT, 1000) {
    override fun onFinish() {
      isIdling = true
      context.onIdle()
    }

    override fun onTick(millisUntilFinished: Long) {
    }
  }

  fun start() {
    sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    timer.start()
  }

  fun stop() {
    sensorManager.unregisterListener(this)
    timer.cancel()
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    Log.i(TAG, "Accelerometer accuracy changed to $accuracy")
  }

  private fun handleAccelerometerEvent(event: SensorEvent) {
    val (x, y, z) = event.values
    val magnitude = sqrt(x * x + y * y + z * z)

    val difference = abs(magnitude - 9.81f)

    // TODO: This will detect spikes of any length, need a better algorithm for detecting actual motion and not just vibrations
    if (difference > IDLE_THRESHOLD) {
      if (isIdling) {
        isIdling = false
        context.onIdleStop()

        Log.i(TAG, "Stopped idling because acceleration is $difference m/s2.")
      }

      timer.cancel()
      timer.start()
    }

    lastTime = event.timestamp
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
      handleAccelerometerEvent(event)
    }
  }
}
