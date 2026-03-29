package com.example.ndi_camera

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ndi_camera.databinding.ActivityMainBinding
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var isNDIStarted = false

    // 오디오 관련 변수
    private var audioRecord: AudioRecord? = null
    private var isRecordingAudio = false
    private lateinit var audioExecutor: ExecutorService
    private val sampleRate = 48000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // Native functions
    private external fun startNDISend(name: String): Boolean
    private external fun stopNDISend()
    private external fun sendVideoFrame(
        yBuffer: ByteBuffer, yStride: Int,
        uBuffer: ByteBuffer, uStride: Int,
        vBuffer: ByteBuffer, vStride: Int,
        pixelStride: Int,
        width: Int, height: Int
    )
    private external fun sendAudioFrame(
        audioData: ByteArray, length: Int, sampleRate: Int, channels: Int
    )

    companion object {
        private const val TAG = "NDICamera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        init {
            System.loadLibrary("ndi_camera")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()
        audioExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            setupNDI()
            startCamera()
            startAudioRecord()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onPause() {
        super.onPause()
        stopAudioRecord()
        if (isNDIStarted) {
            stopNDISend()
            isNDIStarted = false
            Log.d(TAG, "NDI Sender stopped onPause")
        }
    }

    private fun setupNDI() {
        if (!isNDIStarted) {
            isNDIStarted = startNDISend("Android Camera A/V")
            if (isNDIStarted) {
                Log.d(TAG, "NDI Sender started successfully")
            } else {
                Log.e(TAG, "Failed to start NDI Sender")
            }
        }
    }

    private fun startAudioRecord() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBufferSize
        )

        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
            audioRecord?.startRecording()
            isRecordingAudio = true

            audioExecutor.execute {
                val audioBuffer = ByteArray(minBufferSize)
                while (isRecordingAudio) {
                    val readResult = audioRecord?.read(audioBuffer, 0, minBufferSize) ?: 0
                    if (readResult > 0 && isNDIStarted) {
                        // 모노 오디오 전송 (채널 수 1)
                        sendAudioFrame(audioBuffer, readResult, sampleRate, 1)
                    }
                }
            }
        } else {
            Log.e(TAG, "AudioRecord initialization failed")
        }
    }

    private fun stopAudioRecord() {
        isRecordingAudio = false
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(1280, 720))
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (isNDIStarted) {
                            processImageForNDI(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageForNDI(imageProxy: ImageProxy) {
        try {
            val planes = imageProxy.planes
            sendVideoFrame(
                planes[0].buffer, planes[0].rowStride,
                planes[1].buffer, planes[1].rowStride,
                planes[2].buffer, planes[2].rowStride,
                planes[1].pixelStride,
                imageProxy.width, imageProxy.height
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        audioExecutor.shutdown()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                setupNDI()
                startCamera()
                startAudioRecord()
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
