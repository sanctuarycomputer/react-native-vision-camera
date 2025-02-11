package com.mrousavy.camera.core

import android.annotation.SuppressLint
import android.media.AudioManager
import com.mrousavy.camera.core.types.Flash
import com.mrousavy.camera.core.types.Orientation
import com.mrousavy.camera.core.types.TakePhotoOptions

import androidx.camera.core.ImageCapture.OnImageCapturedCallback
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import android.os.Looper
import android.util.Log
import android.provider.MediaStore
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import java.io.File
import android.os.Environment
import java.io.FileOutputStream
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.core.internal.compat.workaround.ExifRotationAvailability
import android.media.MediaActionSound
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

fun isOnMainThread() = Looper.myLooper() == Looper.getMainLooper()
fun ensureBackgroundThread(callback: () -> Unit) {
  if (isOnMainThread()) {
    Thread {
      callback()
    }.start()
  } else {
    callback()
  }
}

fun removeStubFile(file: File) {
  if (file.exists()) {
    file.delete()
  }
}
fun broadcastImageProcessingCompleteIntent(context: Context, file: File) {
  val intent = Intent("com.mrousavy.camera.PHOTO_PROCESSED")
  intent.putExtra("filename", file.absolutePath)
  context.sendBroadcast(intent)
}

val TAG = "CameraSession+Photo"

suspend fun CameraSession.takePhoto(options: TakePhotoOptions): Photo = suspendCancellableCoroutine { continuation ->

  photosBeingProcessed++

  val camera = camera ?: throw CameraNotReadyError()
  val configuration = configuration ?: throw CameraNotReadyError()
  val photoConfig = configuration.photo as? CameraConfiguration.Output.Enabled<CameraConfiguration.Photo> ?: throw PhotoNotEnabledError()
  val photoOutput = photoOutput ?: throw PhotoNotEnabledError()

  if (options.flash != Flash.OFF && !camera.cameraInfo.hasFlashUnit()) {
    throw FlashUnavailableError()
  }
  photoOutput.flashMode = options.flash.toFlashMode()

  val enableShutterSound = options.enableShutterSound && !audioManager.isSilent
  val shutterSound = if (enableShutterSound) MediaActionSound() else null
  shutterSound?.load(MediaActionSound.SHUTTER_CLICK)

  val isMirrored = photoConfig.config.isMirrored
  val metadata = Metadata().apply {
    isReversedHorizontal = isMirrored
  }

  val startTime = System.currentTimeMillis()
  var profilerOutput = ""
  Log.i(LP3_TAG, "starting take")

  val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Light-test")
  if (!directory.exists()) {
    directory.mkdirs()
  }
  val capturedAt = System.currentTimeMillis();
  val filename = "img_${capturedAt}.jpg"

  val outputFile = File(directory, filename)
  val returnVal = Photo(
    outputFile.absolutePath,
    0,
    0,
    Orientation.fromSurfaceRotation(photoOutput.targetRotation),
    isMirrored
  )
  Log.i(LP3_TAG, "stub file created")

  Log.i(LP3_TAG, "camera.cameraInfo.cameraState ${camera.cameraInfo.cameraState}");
  Log.i(LP3_TAG, "camera.cameraInfo.cameraState.value ${camera.cameraInfo.cameraState.value}");

  Log.i(LP3_TAG, "camera.cameraInfo.exposureState ${camera.cameraInfo.exposureState}");
  Log.i(LP3_TAG, "camera.cameraInfo.exposureState.exposureCompensationRange ${camera.cameraInfo.exposureState.exposureCompensationRange}");
  Log.i(LP3_TAG, "camera.cameraInfo.exposureState.exposureCompensationIndex ${camera.cameraInfo.exposureState.exposureCompensationIndex}");
  Log.i(LP3_TAG, "camera.cameraInfo.exposureState.exposureCompensationStep ${camera.cameraInfo.exposureState.exposureCompensationStep}");
  Log.i(LP3_TAG, "camera.cameraInfo.exposureState.isExposureCompensationSupported ${camera.cameraInfo.exposureState.isExposureCompensationSupported}");

  Log.i(LP3_TAG, "camera.cameraInfo.zoomState ${camera.cameraInfo.zoomState}");
  Log.i(LP3_TAG, "camera.cameraInfo.torchState ${camera.cameraInfo.torchState}");
  Log.i(LP3_TAG, "camera.cameraInfo.cameraSelector ${camera.cameraInfo.cameraSelector}");
  Log.i(LP3_TAG, "camera.cameraInfo.implementationType ${camera.cameraInfo.implementationType}");
  Log.i(LP3_TAG, "camera.cameraInfo.intrinsicZoomRatio ${camera.cameraInfo.intrinsicZoomRatio}");
  Log.i(LP3_TAG, "camera.cameraInfo.isLogicalMultiCameraSupported ${camera.cameraInfo.isLogicalMultiCameraSupported}");
  Log.i(LP3_TAG, "camera.cameraInfo.isPrivateReprocessingSupported ${camera.cameraInfo.isPrivateReprocessingSupported}");
  Log.i(LP3_TAG, "camera.cameraInfo.isZslSupported ${camera.cameraInfo.isZslSupported}");
  Log.i(LP3_TAG, "camera.cameraInfo.lensFacing ${camera.cameraInfo.lensFacing}");
  Log.i(LP3_TAG, "camera.cameraInfo.physicalCameraInfos ${camera.cameraInfo.physicalCameraInfos}");
  Log.i(LP3_TAG, "camera.cameraInfo.supportedFrameRateRanges ${camera.cameraInfo.supportedFrameRateRanges}");

  val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
  val characteristics = configuration.cameraId?.let { cameraManager.getCameraCharacteristics(it) }

  val hardwareLevel = characteristics?.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
  val halVersion = when (hardwareLevel) {
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
    else -> "UNKNOWN"
  }

  Log.i(LP3_TAG, "Camera HAL version: $halVersion")


  photoOutput.takePicture(CameraQueues.cameraExecutor, object : OnImageCapturedCallback() {
    override fun onCaptureStarted() {
      Log.i(LP3_TAG, "onCaptureStarted called")
      profilerOutput += "${(System.currentTimeMillis() - startTime)/1000.0}, "

      // We need to wait for this callback before unlocking the focus lock
      // Otherwise we risk the camera having time to refocus before shooting
      freeFocusAndExposure();

      outputFile.createNewFile()
      // Add the temp image to MediaStore so it appears in the gallery
      val values = ContentValues().apply {
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.DATE_ADDED, capturedAt / 1000)
        put(MediaStore.Images.Media.DATE_TAKEN, capturedAt)
        put(MediaStore.Images.Media.DATA, outputFile.absolutePath)
      }
      //context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

      if (options.resolveOnCaptureStarted && continuation.isActive) {
        // resolve the promise
          continuation.resume(returnVal)
        }
    }

    // doesn't get called on LP3
    override fun onCaptureProcessProgressed(progress: Int) {
      Log.i(LP3_TAG, "onCaptureProcessProgressed called: $progress")
    }
    // doesn't get called on LP3
    override fun onPostviewBitmapAvailable(bitmap: Bitmap) {
      Log.i(LP3_TAG, "onPostviewBitmapAvailable called")
    }
    @SuppressLint("RestrictedApi")
    override fun onCaptureSuccess(image: ImageProxy) {
      Log.i(LP3_TAG, "onCaptureSuccess called")
      profilerOutput += "${(System.currentTimeMillis() - startTime)/1000.0}, "
      ensureBackgroundThread {
        image.use {
          if (enableShutterSound) {
            shutterSound?.play(MediaActionSound.SHUTTER_CLICK)
          }

          try {
            Log.i(LP3_TAG, "Writing image")
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).apply {
              buffer.get(this)
            }
            FileOutputStream(outputFile).use { output ->
              output.write(bytes)
            }
            Log.i(LP3_TAG, "Image saved successfully to: ${outputFile.absolutePath}, height: ${image.height}, width: ${image.width}, format: ${image.format}")

            val exif = ExifInterface(outputFile.absolutePath)
            // Overwrite the original orientation if the quirk exists.
            if (!ExifRotationAvailability().shouldUseExifOrientation(image)) {
              exif.rotate(image.imageInfo.rotationDegrees)
            }
            if (metadata.isReversedHorizontal) {
              exif.flipHorizontally()
            }
            if (metadata.isReversedVertical) {
              exif.flipVertically()
            }
            exif.saveAttributes();
            Log.i(LP3_TAG, "EXIF data saved")
          } catch (e: Exception) {
            Log.e(LP3_TAG, "Error saving image: ${e.message}")
            removeStubFile(outputFile)
            e.printStackTrace()
          }

          broadcastImageProcessingCompleteIntent(context, outputFile)
          photosBeingProcessed--

          profilerOutput += "${(System.currentTimeMillis() - startTime)/1000.0}, "
          // output will be timings of onCaptureStarted, onCaptureSuccess, processing completion
          Log.i("LP3_PROFILER", profilerOutput)

          if (!options.resolveOnCaptureStarted && continuation.isActive) {
            // resolve the promise
            continuation.resume(returnVal)
          }
        }
      }
    }
    override fun onError(exception: ImageCaptureException) {

      broadcastImageProcessingCompleteIntent(context, outputFile)
      removeStubFile(outputFile)
      photosBeingProcessed--

      Log.d(TAG, "onError: ${exception.message}")
      if (continuation.isActive) {
        continuation.resumeWithException(exception)
      }
    }
  })
}

private val AudioManager.isSilent: Boolean
  get() = ringerMode != AudioManager.RINGER_MODE_NORMAL
