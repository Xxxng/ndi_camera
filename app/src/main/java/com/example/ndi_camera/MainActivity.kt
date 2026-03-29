package com.example.ndi_camera

import android.Manifest
import android.content.pm.PackageManager
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

    companion object {
        private const val TAG = "NDICamera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        init {
            System.loadLibrary("ndi_camera")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            setupNDI()
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onPause() {
        super.onPause()
        // 백그라운드 전환 시 NDI 중지 (가이드 권장 사항)
        if (isNDIStarted) {
            stopNDISend()
            isNDIStarted = false
            Log.d(TAG, "NDI Sender stopped onPause")
        }
    }

    private fun setupNDI() {
        if (!isNDIStarted) {
            isNDIStarted = startNDISend("Android Camera Optimized")
            if (isNDIStarted) {
                Log.d(TAG, "NDI Sender started successfully")
            } else {
                Log.e(TAG, "Failed to start NDI Sender")
            }
        }
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
            // UYVY는 2픽셀당 하나의 UV를 공유하므로 짝수 해상도가 권장됨
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
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                setupNDI()
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
