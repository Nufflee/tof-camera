package com.example.tofcamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.util.Range
import androidx.core.app.ActivityCompat
import kotlin.experimental.and

class DepthCamera<T>(
  private val context: T,
  private val shouldUseDynamicRanging: () -> Boolean,
  private val setRangeText: (String) -> Unit,
  private val setStatusText: (String) -> Unit,
) : CameraDevice.StateCallback(), ImageReader.OnImageAvailableListener
  where T : Context, T : DepthCameraImageListener {
  var isOpen: Boolean = false
  var handler: Handler? = null

  private val TAG: String = DepthCamera::class.java.simpleName

  private val cameraManager = context.getSystemService(CameraManager::class.java)
  private val imageReader = ImageReader.newInstance(640, 480, ImageFormat.DEPTH16, 2)
  private lateinit var requestBuilder: CaptureRequest.Builder
  private var captureSession: CameraCaptureSession? = null
  private lateinit var cameraId: String

  init {
    val cameraId = getDepthCameraId()

    if (cameraId == null) {
      Log.e(TAG, "Couldn't find a Depth Camera")
    } else {
      this.cameraId = cameraId
    }
  }

  fun open() {
    if (isOpen) {
      return
    }

    Log.i(TAG, "Opening camera session.")

    isOpen = true

    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      Log.e(TAG, "Camera permission is not granted!")
      return
    }

    setStatusText("Camera is starting...")

    if (handler == null) {
      Log.e(TAG, "Camera thread handler is null!")

      return
    }

    imageReader.setOnImageAvailableListener(this, handler)
    cameraManager.openCamera(cameraId, this, null)

    Log.i(TAG, "Camera session open.")
  }

  private fun getDepthCameraId(): String? {
    for (cameraId in cameraManager.cameraIdList) {
      val chars = cameraManager.getCameraCharacteristics(cameraId)

      if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
        continue
      }

      val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue

      for (capability in capabilities) {
        if (capability == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) {
          return cameraId
        }
      }
    }

    return null
  }

  override fun onOpened(camera: CameraDevice) {
    Log.i(TAG, "Depth Camera has been opened PogOmega")

    requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
    requestBuilder.set(CaptureRequest.JPEG_ORIENTATION, 0)
    requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(5, 5))
    requestBuilder.addTarget(imageReader.surface)

    camera.createCaptureSession(
      SessionConfiguration(
        SessionConfiguration.SESSION_REGULAR,
        listOf(OutputConfiguration(imageReader.surface)),
        context.mainExecutor,
        object : CameraCaptureSession.StateCallback() {
          override fun onConfigured(session: CameraCaptureSession) {
            Log.i(TAG, "Capture Session created")
            captureSession = session

            captureSession!!.setRepeatingRequest(requestBuilder.build(), null, null)
          }

          override fun onConfigureFailed(session: CameraCaptureSession) {
            Log.e(TAG, "Failed to configure the Depth Camera!")
          }
        })
    )
  }

  fun close() {
    if (!isOpen) {
      return
    }

    Log.i(TAG, "Closing capture session.")

    isOpen = false

    captureSession?.abortCaptures()
    captureSession?.stopRepeating()
    captureSession?.close()

    Log.i(TAG, "Capture session closed.")
  }

  override fun onDisconnected(camera: CameraDevice) {
    Log.i(TAG, "Camera disconnected!")
  }

  override fun onError(camera: CameraDevice, error: Int) {
    Log.e(TAG, "Camera error: $error!")
  }

  var lastTime = System.currentTimeMillis()

  private val RANGE_MIN = 0.toShort()
  private val RANGE_MAX = 2500.toShort()

  private fun normalizeRange(range: Short, min: Short, max: Short): Int {
    var normalized = range.toFloat() - min
    // Clamp to min/max
    normalized = Math.max(min.toFloat(), normalized)
    normalized = Math.min(max.toFloat(), normalized)
    // Normalize to 0 to 255
    normalized -= min
    normalized = normalized / (max - min) * 255
    return normalized.toInt()
  }

  override fun onImageAvailable(reader: ImageReader?) {
    context.mainExecutor.execute { setStatusText("") }

    val image = reader!!.acquireNextImage()

    if (image != null && image.format == ImageFormat.DEPTH16) {
      Log.i(TAG, "Pog we got image data: ${image.width}x${image.height} px, dt = ${System.currentTimeMillis() - lastTime} ms")
      lastTime = System.currentTimeMillis()

      val depthBuffer = image.planes[0].buffer.asShortBuffer()

      val width = image.width
      val height = image.height

      val depthRanges = ShortArray(width * height)
      var minRange: Short = 0
      var maxRange: Short = 0

      for (x in 0 until width) {
        for (y in 0 until height) {
          val index = height * x + y;

          val rawSample = depthBuffer.get(width * (height - 1 - y) + x)

          // From: https://developer.android.com/reference/android/graphics/ImageFormat#DEPTH16
          val depthRange = rawSample and 0x1FFF
          val depthConfidence = ((rawSample.toInt() shr 13) and 0x7).toShort()
          val confidencePercentage = if (depthConfidence.toInt() == 0) 1.0f else (depthConfidence - 1) / 7.0f

          @Suppress("ConvertTwoComparisonsToRangeCheck")
          if (depthRange < minRange && depthRange > 0) {
            minRange = depthRange
          } else if (depthRange > maxRange) {
            maxRange = depthRange
          }

          depthRanges[index] = depthRange
        }
      }

      val pixels = IntArray(width * height)

      for ((i, range) in depthRanges.withIndex()) {
        val normalizedRange = if (shouldUseDynamicRanging()) {
          normalizeRange(range, minRange, maxRange)
        } else {
          normalizeRange(range, RANGE_MIN, RANGE_MAX)
        }

        pixels[i] = Color.argb(255, 0, normalizedRange, 0)
      }

      val bitmap = Bitmap.createBitmap(height, width, Bitmap.Config.ARGB_8888)

      bitmap.setPixels(pixels, 0, height, 0, 0, height, width)

      // TODO: This is a hack.
      context.mainExecutor.execute { setRangeText("Range: $minRange mm to $maxRange mm") }

      context.onNewImage(bitmap)

      bitmap.recycle()
    }

    image.close()
  }
}