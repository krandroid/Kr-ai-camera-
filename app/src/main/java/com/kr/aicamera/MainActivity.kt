package com.kr.aicamera

import android.Manifest
import android.content.ContentValues
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.*
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kr.aicamera.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // SharedPreferences
    private lateinit var prefs: SharedPreferences
    private var backRes = Size(1920, 1080)  // default 1080p
    private var backFps = 30                // default 30fps
    private var frontRes = Size(1280, 720)  // default 720p
    private var frontFps = 30               // default 30fps

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("KR_CAMERA_PREFS", MODE_PRIVATE)
        loadSettings()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    private fun loadSettings() {
        // Belakang
        val backResStr = prefs.getString("BACK_RES", "1080p_30") ?: "1080p_30"
        when (backResStr) {
            "4K_30" -> { backRes = Size(3840, 2160); backFps = 30 }
            "4K_60" -> { backRes = Size(3840, 2160); backFps = 60 }
            "1080p_60" -> { backRes = Size(1920, 1080); backFps = 60 }
            "720p_30" -> { backRes = Size(1280, 720); backFps = 30 }
            else -> { backRes = Size(1920, 1080); backFps = 30 } // 1080p_30 default
        }

        // Depan
        val frontResStr = prefs.getString("FRONT_RES", "720p_30") ?: "720p_30"
        when (frontResStr) {
            "1080p_60" -> { frontRes = Size(1920, 1080); frontFps = 60 }
            "1080p_30" -> { frontRes = Size(1920, 1080); frontFps = 30 }
            "720p_60" -> { frontRes = Size(1280, 720); frontFps = 60 }
            else -> { frontRes = Size(1280, 720); frontFps = 30 } // 720p_30 default
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindCamera(cameraProvider, lensFacing)
            setupButtons(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(cameraProvider: ProcessCameraProvider, facing: Int) {
        cameraProvider.unbindAll()

        // Tentukan resolusi & fps berdasarkan lensa yang aktif
        val resSize = if (facing == CameraSelector.LENS_FACING_BACK) backRes else frontRes
        val fps = if (facing == CameraSelector.LENS_FACING_BACK) backFps else frontFps

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(resSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
            .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setResolutionSelector(resolutionSelector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(facing)
            .build()

        try {
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
        } catch (e: Exception) {
            Toast.makeText(this, "Resolusi tidak didukung, gunakan fallback", Toast.LENGTH_SHORT).show()
            // Bind ulang tanpa resolution selector (biarkan CameraX pilih sendiri)
            val fallbackPreview = Preview.Builder().build()
                .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
            val fallbackCapture = ImageCapture.Builder().build()
            cameraProvider.bindToLifecycle(this, cameraSelector, fallbackPreview, fallbackCapture)
        }
    }

    private fun setupButtons(cameraProvider: ProcessCameraProvider) {
        binding.btnCapture.setOnClickListener { takePhoto() }

        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT
            else
                CameraSelector.LENS_FACING_BACK
            bindCamera(cameraProvider, lensFacing)
        }

        binding.btnGallery.setOnClickListener {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                type = "image/*"
            })
        }

        binding.btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun showSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Pengaturan Kamera")

        val items = arrayOf(
            "Kamera Belakang",
            "Kamera Depan"
        )
        builder.setItems(items) { _, which ->
            if (which == 0) showBackSettings() else showFrontSettings()
        }
        builder.show()
    }

    private fun showBackSettings() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Resolusi Belakang")

        val options = arrayOf(
            "4K 30fps",
            "4K 60fps",
            "1080p 30fps",
            "1080p 60fps",
            "720p 30fps"
        )
        builder.setItems(options) { _, which ->
            val selected = options[which]
            prefs.edit().putString("BACK_RES", selected.replace(" ", "_")).apply()
            loadSettings()
            // Restart kamera dengan pengaturan baru
            startCamera()
            Toast.makeText(this, "Belakang: $selected", Toast.LENGTH_SHORT).show()
        }
        builder.show()
    }

    private fun showFrontSettings() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Resolusi Depan")

        val options = arrayOf(
            "1080p 30fps",
            "1080p 60fps",
            "720p 30fps",
            "720p 60fps"
        )
        builder.setItems(options) { _, which ->
            val selected = options[which]
            prefs.edit().putString("FRONT_RES", selected.replace(" ", "_")).apply()
            loadSettings()
            startCamera()
            Toast.makeText(this, "Depan: $selected", Toast.LENGTH_SHORT).show()
        }
        builder.show()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val fileName = "KR_AI_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Foto tersimpan", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Gagal: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
