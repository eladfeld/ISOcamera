package com.example.isocamera

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Chronometer
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureRequestBuilder: CaptureRequest.Builder
    private var captureSession: CameraCaptureSession? = null
    private lateinit var logFile: File
    private lateinit var logWriter: FileWriter
    private lateinit var surfaceView: SurfaceView
    private lateinit var recordButton: ImageButton
    private lateinit var mediaRecorder: MediaRecorder
    private lateinit var recorderSurface: Surface
    private lateinit var previewSurface: Surface
    private lateinit var surfaceHolder: SurfaceHolder
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private lateinit var chronometer: Chronometer

    private var isRecording = false
    private var surfaceReady = false
    private val TAG = "ISOLogger"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        surfaceHolder = surfaceView.holder
        recordButton = findViewById(R.id.recordButton)
        chronometer = findViewById(R.id.chronometer)

        surfaceHolder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                Log.d(TAG, "Surface is ready")
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    openCamera()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
            }
        })

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        logFile = File(getExternalFilesDir(null), "ISO_Log_$timeStamp.csv")

        try {
            logWriter = FileWriter(logFile, true)
            logWriter.write("Timestamp,ISO\n")
        } catch (e: IOException) {
            e.printStackTrace()
        }

        startBackgroundThread()

        recordButton.setOnClickListener {
            if (surfaceReady) {
                if (!isRecording) {
                    Log.d(TAG, "Start button clicked: Surface is ready")
                    recordButton.setImageResource(R.drawable.pause)
                    startRecording()
                    chronometer.base = SystemClock.elapsedRealtime()
                    chronometer.start()
                } else {
                    Log.d(TAG, "Stop button clicked: Stopping recording")
                    recordButton.setImageResource(R.drawable.play_arrow)
                    stopRecording()
                    chronometer.stop()
                }
            } else {
                Log.e(TAG, "Surface is not ready yet")
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 200)
        }
    }

    private fun startPreview() {
        try {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewSurface = surfaceHolder.surface
            captureRequestBuilder.addTarget(previewSurface)

            cameraDevice.createCaptureSession(listOf(previewSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        captureSession?.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, backgroundHandler)
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Preview session configuration failed")
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun startRecording() {
        mediaRecorder = MediaRecorder()

        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        val outputFile = File(getExternalFilesDir(null), "recorded_video.mp4")
        mediaRecorder.setOutputFile(outputFile.absolutePath)

        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mediaRecorder.setVideoEncodingBitRate(10000000)
        mediaRecorder.setVideoFrameRate(30)
        mediaRecorder.setVideoSize(1920, 1080)

        try {
            mediaRecorder.prepare()
        } catch (e: IOException) {
            e.printStackTrace()
            return
        }

        startRecordingSession()
    }

    private fun startRecordingSession() {
        recorderSurface = mediaRecorder.surface
        previewSurface = surfaceHolder.surface

        try {
            cameraDevice.createCaptureSession(
                listOf(previewSurface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session

                        captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        captureRequestBuilder.addTarget(previewSurface)
                        captureRequestBuilder.addTarget(recorderSurface)

                        try {
                            captureSession?.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, backgroundHandler)
                            mediaRecorder.start()
                            isRecording = true
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Recording session configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val timestamp = System.currentTimeMillis()
            if (iso != null && isRecording) {
                logISO(timestamp, iso)
            }
        }
    }

    private fun stopRecording() {
        if (isRecording) {
            try {
                captureSession?.stopRepeating()
                mediaRecorder.stop()
                mediaRecorder.reset()
                isRecording = false
                startPreview()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun openCamera() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = manager.cameraIdList[0]
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "Camera opened successfully")
            cameraDevice = camera
            startPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread.quitSafely()
        backgroundThread.join()
    }

    private fun logISO(timestamp: Long, iso: Int) {
        Log.d(TAG, "Logging ISO: $iso at $timestamp")
        try {
            logWriter.write("$timestamp,$iso\n")
            logWriter.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 200) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            } else {
                Log.e(TAG, "Camera permission denied")
            }
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
            cameraDevice.close()
            captureSession = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            logWriter.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        stopBackgroundThread()
    }
}
