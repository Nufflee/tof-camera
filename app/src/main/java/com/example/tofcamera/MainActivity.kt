package com.example.tofcamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
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
import kotlin.math.round

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
  private lateinit var statusTextView: TextView

  private lateinit var cameraThread: HandlerThread
  private lateinit var camera: DepthCamera<MainActivity>
  private lateinit var idleMonitor: AccelerometerIdleMonitor<MainActivity>

  // Matrix that scales the recorded frame to the size of the textureView.
  private val bitmapMatrix: Matrix by lazy {
    val matrix = Matrix()
    val bufferWidth = 480
    val bufferHeight = 640
    val bufferRect = RectF(0f, 0f, bufferWidth.toFloat(), bufferHeight.toFloat())
    val viewRect = RectF(0f, 0f, textureView.width.toFloat(), textureView.height.toFloat())

    matrix.setRectToRect(bufferRect, viewRect, Matrix.ScaleToFit.CENTER)

    matrix
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    setSupportActionBar(findViewById(R.id.toolbar))

    textureView = findViewById(R.id.textureView)

    idleMonitor = AccelerometerIdleMonitor(this)

    printCameraInfo()

    val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)

    if (cameraPermission != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
    }

    val dynamicRangeCheckbox = findViewById<CheckBox>(R.id.dynamicRangeCheckbox)
    val rangeTextView = findViewById<TextView>(R.id.rangeTextView)
    statusTextView = findViewById(R.id.cameraStatusTextView)

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

      builder.appendLine()
      builder.appendLine("Camera $cameraId:")

      val facing = chars.get(CameraCharacteristics.LENS_FACING)
      builder.appendLine(
        "\tFacing: ${
          when (facing) {
            CameraMetadata.LENS_FACING_FRONT -> "Front"
            CameraMetadata.LENS_FACING_BACK -> "Back"
            CameraMetadata.LENS_FACING_EXTERNAL -> "External"
            else -> throw IllegalArgumentException("Unknown lens facing: $facing")
          }
        }"
      )

      builder.appendLine("\tPhysical size: ${chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)} mm")

      val activePixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) as Rect;
      builder.appendLine("\tActive pixel array size: ${activePixelArray.width()}x${activePixelArray.height()}")

      builder.appendLine("\tTotal pixel array size: ${chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)}")

      val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
      builder.appendLine(
        "\tCapabilities: ${
          capabilities!!.joinToString(", ") { capability ->
            val prefix = "REQUEST_AVAILABLE_CAPABILITIES_"

            CameraMetadata::class.java.declaredFields.find {
              it.name.startsWith(prefix) && it.get(this) == capability
            }!!.name.removePrefix(prefix)
          }
        }"
      )

      val configurationMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
      builder.appendLine(
        "\tFormats: ${
          configurationMap.outputFormats.joinToString(", ") { getFieldNameByValue(ImageFormat::class.java, it, Modifier.PUBLIC or Modifier.STATIC)!! }
        }"
      )

      val getResolutionsForFormat = { format: Int ->
        val resolutions = configurationMap.getOutputSizes(format)

        "\tResolutions (${getFieldNameByValue(ImageFormat::class.java, format, Modifier.PUBLIC or Modifier.STATIC)}): ${
          resolutions.joinToString(", ") { "$it (${round(1 / (configurationMap.getOutputMinFrameDuration(format, it) / 1e9))} FPS)" }
        }"
      }

      if (capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)) {
        builder.appendLine(getResolutionsForFormat(ImageFormat.DEPTH16))
      } else {
        builder.appendLine(getResolutionsForFormat(ImageFormat.JPEG))
        builder.appendLine(getResolutionsForFormat(ImageFormat.YUV_420_888))
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

  override fun onStop() {
    super.onStop()

    startIdle()
    idleMonitor.stop()
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

    //socket.close()

    statusTextView.text = "Camera paused due to inactivity..."

    val canvas = textureView.lockCanvas()!!
    canvas.drawARGB(255, 0, 0, 0)
    textureView.unlockCanvasAndPost(canvas)

    Log.i(TAG, "Started idling.")
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