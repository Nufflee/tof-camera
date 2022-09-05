package com.example.tofcamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.TextureView
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.lang.reflect.Modifier

/*
  TODO:
    - Pointer
    - Frame averaging
    - Try using NDK for image processing
    - Edge detection
    - Switch between 480p and 240p
*/

class MainActivity : AppCompatActivity(), DepthCameraImageListener, IdleListener {
  private val TAG = "camera-tof";

  private lateinit var textureView: TextureView
  private lateinit var cameraThread: HandlerThread
  private lateinit var camera: DepthCamera<MainActivity>
  private var idleMonitor: AccelerometerIdleMonitor<MainActivity> = AccelerometerIdleMonitor(this)

  private val bitmapMatrix: Matrix by lazy {
    val matrix = Matrix()
    val centerX = textureView.width / 2
    val centerY = textureView.height / 2
    val bufferWidth = 480
    val bufferHeight = 640
    val bufferRect = RectF(0f, 0f, bufferWidth.toFloat(), bufferHeight.toFloat())
    val viewRect = RectF(0f, 0f, textureView.width.toFloat(), textureView.height.toFloat())
    matrix.setRectToRect(bufferRect, viewRect, Matrix.ScaleToFit.CENTER)
    //matrix.postRotate(90f, centerX.toFloat(), centerY.toFloat())

    matrix
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    setSupportActionBar(findViewById(R.id.toolbar))

    textureView = findViewById(R.id.textureView)

    printCameraInfo()

    val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)

    if (cameraPermission != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
    }

    val dynamicRangeCheckbox = findViewById<CheckBox>(R.id.dynamicRangeCheckbox)
    val rangeTextView = findViewById<TextView>(R.id.rangeTextView)
    val statusTextView = findViewById<TextView>(R.id.cameraStatusTextView)

    camera = DepthCamera(this, dynamicRangeCheckbox::isChecked, rangeTextView::setText, statusTextView::setText)
    startCameraThread()
    camera.open()

    idleMonitor.start()
  }

  private fun startCameraThread() {
    cameraThread = HandlerThread("Camera")
    cameraThread.start()

    camera.handler = Handler(cameraThread.looper)
  }

  private fun <T, TValue> getFieldNameByValue(klass: Class<T>, value: TValue, modifiers: Int = 0): String? {
    for (field in klass.declaredFields) {
      if ((field.modifiers and modifiers) == modifiers) {
        if (field.get(this) == value) {
          return field.name
        }
      }
    }

    return null
  }

  private fun printCameraInfo() {
    val cameraManager = getSystemService(CameraManager::class.java)

    for (cameraId in cameraManager.cameraIdList) {
      val chars = cameraManager.getCameraCharacteristics(cameraId)

      val builder = StringBuilder()

      builder.appendln("Camera ID: $cameraId:")

      builder.appendln("\tFacing: ${getFieldNameByValue(CameraMetadata::class.java, chars.get(CameraCharacteristics.LENS_FACING), Modifier.PUBLIC or Modifier.STATIC)}")
      builder.appendln("\tPhysical size: ${chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)}mm")
      builder.appendln("\tActive pixel array size: ${chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)}")
      builder.appendln("\tTotal pixel array size: ${chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)}")

      val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

      val capabilitiesString = capabilities!!.map { capability ->
        val prefix = "REQUEST_AVAILABLE_CAPABILITIES_"

        CameraMetadata::class.java.declaredFields.find {
          it.name.startsWith(prefix) && it.get(this) == capability
        }!!.name.removePrefix(prefix)
      }

      builder.appendln("\tCapabilities: $capabilitiesString")

      val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

      builder.appendln("\tFPS ranges: ${fpsRanges!!.joinToString(", ")}")

      val configurationMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

      val resolutionForFormat = { format: Int ->
        val resolutions = configurationMap!!.getOutputSizes(format)

        "\tResolutions (${getFieldNameByValue(ImageFormat::class.java, format, Modifier.PUBLIC or Modifier.STATIC)}): ${resolutions.joinToString(", ") {
          "$it (${1 / (configurationMap.getOutputMinFrameDuration(format, it) / 1e9)} FPS)"
        }
        }"
      }

      if (capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)) {
        builder.appendln(resolutionForFormat(ImageFormat.DEPTH16))
      } else {
        builder.appendln(resolutionForFormat(ImageFormat.JPEG))
        builder.appendln(resolutionForFormat(ImageFormat.YUV_420_888))
      }

      Log.i(TAG, builder.toString())
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    // Inflate the menu; this adds items to the action bar if it is present.
    menuInflater.inflate(R.menu.menu_main, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    return when (item.itemId) {
      R.id.action_settings -> true
      else -> super.onOptionsItemSelected(item)
    }
  }

  override fun onResume() {
    super.onResume()

    stopIdle()
    idleMonitor.start()
  }

  private fun stopIdle() {
    if (!cameraThread.isAlive) {
      startCameraThread()
    }

    if (!camera.isOpen) {
      camera.open()
    }

    Log.i(TAG, "Stopped idling.")
  }

  private fun startIdle() {
    if (camera.isOpen) {
      camera.close()
    }

    if (cameraThread.isAlive) {
      cameraThread.quitSafely()
    }

    Log.i(TAG, "Started idling.")
  }

  override fun onStop() {
    super.onStop()

    startIdle()
    idleMonitor.stop()
  }

  override fun onPause() {
    super.onPause()

    Log.i(TAG, "onPause")
  }

  override fun onNewImage(bitmap: Bitmap) {
    val canvas = textureView.lockCanvas()!!
    canvas.drawBitmap(bitmap, bitmapMatrix, null)
    textureView.unlockCanvasAndPost(canvas)
  }

  override fun onIdle() {
    startIdle()
  }

  override fun onIdleStop() {
    stopIdle()
  }
}