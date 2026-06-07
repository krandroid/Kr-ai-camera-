package com.kr.aicamera

import android.Manifest
import android.content.ContentValues
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.os.*
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
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
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // SharedPreferences
    private lateinit var prefs: SharedPreferences
    private var backRes = Size(1920, 1080)
    private var backFps = 30
    private var frontRes = Size(1280, 720)
    private var frontFps = 30
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    // Mode: 0 = Foto, 1 = Video, 2 = Portrait
    private var currentMode = 0

    // Suara shutter
    private val shutterSound = MediaActionSound()

    // Gesture detector untuk swipe
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("KR_CAMERA_PREFS", MODE_PRIVATE)
        loadSettings()

        // Setup gesture untuk swipe mode
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                    if (diffX > 0) {
                        // Swipe kanan -> mode sebelumnya
                        currentMode = if (currentMode == 0) 2 else currentMode - 1
                    } else {
                        // Swipe kiri -> mode berikutnya
                        currentMode = if (currentMode == 2) 0 else currentMode + 1
                    }
                    runOnUiThread { updateModeUI() }
                    return true
                }
                return false
            }
        })

        // Pasang touch listener untuk gesture
        binding.viewFinder.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    private fun loadSettings() {
        val backResStr = prefs.getString("BACK_RES", "1080p_30") ?: "1080p_30"
        when (backResStr) {
            "4K_30" -> { backRes = Size(3840, 2160); backFps = 30 }
            "4K_60" -> { backRes = Size(3840, 2160); backFps = 60 }
            "1080p_60" -> { backRes = Size(1920, 1080); backFps = 60 }
            "720p_30" -> { backRes = Size(1280, 720); backFps = 30 }
            else -> { backRes = Size(1920, 1080); backFps = 30 }
        }

        val frontResStr = prefs.getString("FRONT_RES", "720p_30") ?: "720p_30"
        when (frontResStr) {
            "1080p_60" -> { frontRes = Size(1920, 1080); frontFps = 60 }
            "1080p_30" -> { frontRes = Size(1920, 1080); frontFps = 30 }
            "720p_60" -> { frontRes = Size(1280, 720); frontFps = 60 }
            else -> { frontRes = Size(1280, 720); frontFps = 30 }
        }

        flashMode = when (prefs.getString("FLASH", "off")) {
            "on" -> ImageCapture.FLASH_MODE_ON
            "auto" -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        updateFlashIcon()
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
            updateModeUI()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(cameraProvider: ProcessCameraProvider, facing: Int) {
        cameraProvider.unbindAll()

        val resSize = if (facing == CameraSelector.LENS_FACING_BACK) backRes else frontRes

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
            .setFlashMode(flashMode)
            .build()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(facing)
            .build()

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
        } catch (e: Exception) {
            Toast.makeText(this, "Resolusi tidak didukung, gunakan fallback", Toast.LENGTH_SHORT).show()
            val fallbackPreview = Preview.Builder().build()
                .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
            val fallbackCapture = ImageCapture.Builder()
                .setFlashMode(flashMode)
                .build()
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, fallbackPreview, fallbackCapture)
        }
    }

    private fun updateModeUI() {
        binding.tvModeVideo.setTextColor(android.graphics.Color.parseColor("#66FFFFFF"))
        binding.tvModePhoto.setTextColor(android.graphics.Color.parseColor("#66FFFFFF"))
        binding.tvModePortrait.setTextColor(android.graphics.Color.parseColor("#66FFFFFF"))
        binding.dotModePhoto.visibility = android.view.View.INVISIBLE

        when (currentMode) {
            0 -> {
                binding.tvModePhoto.setTextColor(android.graphics.Color.parseColor("#22D3EE"))
                binding.dotModePhoto.visibility = android.view.View.VISIBLE
                binding.btnCapture.setImageResource(R.drawable.ic_camera)
            }
            1 -> {
                binding.tvModeVideo.setTextColor(android.graphics.Color.parseColor("#22D3EE"))
                binding.btnCapture.setImageResource(R.drawable.ic_video)
            }
            2 -> {
                binding.tvModePortrait.setTextColor(android.graphics.Color.parseColor("#22D3EE"))
                binding.btnCapture.setImageResource(R.drawable.ic_portrait)
            }
        }
    }

    private fun setupButtons(cameraProvider: ProcessCameraProvider) {
        binding.btnCapture.setOnClickListener {
            when (currentMode) {
                0 -> takePhoto()
                1 -> Toast.makeText(this, "Mode video coming soon", Toast.LENGTH_SHORT).show()
                2 -> takePhoto() // Let Portrait mode also take photos for now
            }
        }

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

        binding.btnFlash.setOnClickListener { toggleFlash() }
    }

    private fun updateFlashIcon() {
        binding.btnFlash.setImageResource(when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
            ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
            else -> R.drawable.ic_flash_off
        })
    }

    private fun toggleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        prefs.edit().putString("FLASH", when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> "on"
            ImageCapture.FLASH_MODE_AUTO -> "auto"
            else -> "off"
        }).apply()

        updateFlashIcon()
        
        // Restart kamera untuk menerapkan flash mode
        startCamera()
    }

    private fun showSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Pengaturan Kamera")
        val items = arrayOf("Kamera Belakang", "Kamera Depan")
        builder.setItems(items) { _, which ->
            if (which == 0) showBackSettings() else showFrontSettings()
        }
        builder.show()
    }

    private fun showBackSettings() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Resolusi Belakang")
        val options = arrayOf("4K 30fps", "4K 60fps", "1080p 30fps", "1080p 60fps", "720p 30fps")
        builder.setItems(options) { _, which ->
            prefs.edit().putString("BACK_RES", options[which].replace(" ", "_")).apply()
            loadSettings()
            startCamera()
        }
        builder.show()
    }

    private fun showFrontSettings() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Resolusi Depan")
        val options = arrayOf("1080p 30fps", "1080p 60fps", "720p 30fps", "720p 60fps")
        builder.setItems(options) { _, which ->
            prefs.edit().putString("FRONT_RES", options[which].replace(" ", "_")).apply()
            loadSettings()
            startCamera()
        }
        builder.show()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        // Putar suara shutter
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)

        val fileName = "KR_AI_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1) // Penting untuk Android 10+
            }
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: run {
            Toast.makeText(this, "Gagal membuat file", Toast.LENGTH_SHORT).show()
            return
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            uri,
            values
        ).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // Tandai file sudah selesai ditulis
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                    }
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Foto tersimpan ✅", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    // Hapus file pending jika gagal
                    contentResolver.delete(uri, null, null)
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
        shutterSound.release() // Properly release sound pool
    }
}
