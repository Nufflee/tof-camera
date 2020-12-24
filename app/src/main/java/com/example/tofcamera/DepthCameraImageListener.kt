package com.example.tofcamera

import android.graphics.Bitmap

interface DepthCameraImageListener {
  fun onNewImage(bitmap: Bitmap)
}