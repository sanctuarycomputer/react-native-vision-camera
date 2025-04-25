package com.mrousavy.camera.core

import android.os.SystemProperties
import android.util.Log

fun CameraSession.setEISMode(newMode: String) {
  try {
    Log.i(CameraSession.TAG, "EIS: changed from: " + SystemProperties.get("persist.vendor.camera.enableEIS"));
    SystemProperties.set("persist.vendor.camera.enableEIS", newMode);
    Log.i(CameraSession.TAG, "EIS: changed to: " + SystemProperties.get("persist.vendor.camera.enableEIS"));
  }
  catch (e: Exception) {
    Log.e(CameraSession.TAG, "Unable to set EIS mode ${e.message}")
  }
}
