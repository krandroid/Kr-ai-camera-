package com.kr.aicamera

import android.Manifest
import android.content.ContentValues
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaActionSound
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
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
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private lateinit var prefs: SharedPreferences
    private var backRes = Size(1920, 1080)
    private var frontRes = Size(1280, 720)
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    private var currentMode = 0 // 0=Foto, 1=Video, 2=Portrait
    private val modeNames = arrayOf("FOTO", "VIDEO", "PORTRAIT")

    private val shutterSound = MediaActionSound()
    private lateinit var gestureDetector: GestureDetector
    private var isGridEnabled = false
    private var timerSeconds = 0
    private var timerCountDown: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("KR_CAMERA_PREFS", MODE_PRIVATE)
        loadSettings()

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                if (Math.abs(diffX) > Math.abs(e2.y - e1.y) && Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                    currentMode = if (diffX > 0) (currentMode - 1 + modeNames.size) % modeNames.size else (currentMode + 1) % modeNames.size
                    runOnUiThread { updateModeUI() }
                    return true
                }
                return false
            }
        })

        binding.viewFinder.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        } else {
            startCamera()
        }
    }

    private fun loadSettings() {
        backRes = when (prefs.getString("BACK_RES", "1080p")) {
            "4K" -> Size(3840, 2160)
            "1080p" -> Size(1920, 1080)
            else -> Size(1280, 720)
        }
        frontRes = when (prefs.getString("FRONT_RES", "720p")) {
            "1080p" -> Size(1920, 1080)
            else -> Size(1280, 720)
        }
        flashMode = when (prefs.getString("FLASH", "off")) {
            "on" -> ImageCapture.FLASH_MODE_ON
            "auto" -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        isGridEnabled = prefs.getBoolean("GRID", false)
        binding.gridOverlay.visibility = if (isGridEnabled) View.VISIBLE else View.GONE
        updateFlashIcon()
    }

    private fun updateFlashIcon() {
        binding.btnFlash.setImageResource(when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
            ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
            else -> R.drawable.ic_flash_off
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            bindCamera(cameraProviderFuture.get(), lensFacing)
            setupButtons()
            updateModeUI()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider, facing: Int) {
        provider.unbindAll()
        val resSize = if (facing == CameraSelector.LENS_FACING_BACK) backRes else frontRes

        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(resSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build())
            .build()
            .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setResolutionSelector(ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(resSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build())
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .build()

        try {
            provider.bindToLifecycle(this, CameraSelector.Builder().requireLensFacing(facing).build(), preview, imageCapture)
        } catch (e: Exception) {
            Toast.makeText(this, "Kamera tidak mendukung resolusi ini", Toast.LENGTH_SHORT).show()
            val fallbackPreview = Preview.Builder().build().also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
            imageCapture = ImageCapture.Builder().build()
            provider.bindToLifecycle(this, CameraSelector.Builder().requireLensFacing(facing).build(), fallbackPreview, imageCapture)
        }
    }

    private fun updateModeUI() {
        binding.txtMode.text = modeNames[currentMode]
        binding.btnCapture.setImageResource(when (currentMode) {
            0 -> R.drawable.ic_camera
            1 -> R.drawable.ic_video
            2 -> R.drawable.ic_portrait
            else -> R.drawable.ic_camera
        })
    }

    private fun setupButtons() {
        binding.btnCapture.setOnClickListener {
            when (currentMode) {
                0 -> takePhoto()
                1 -> Toast.makeText(this, "Mode video segera hadir", Toast.LENGTH_SHORT).show()
                2 -> Toast.makeText(this, "Mode portrait segera hadir", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                bindCamera(cameraProviderFuture.get(), lensFacing)
            }, ContextCompat.getMainExecutor(this))
        }

        binding.btnGallery.setOnClickListener {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                type = "image/*"
            })
        }

        binding.btnSettings.setOnClickListener { showSettings() }

        binding.btnFlash.setOnClickListener {
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
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                bindCamera(cameraProviderFuture.get(), lensFacing)
            }, ContextCompat.getMainExecutor(this))
        }

        binding.thumbnail.setOnClickListener { binding.btnGallery.performClick() }
    }

    private fun takePhoto() {
        if (timerSeconds > 0) {
            startTimer { capturePhoto() }
        } else {
            capturePhoto()
        }
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)

        val photoFile = File(cacheDir, "kr_temp_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "KR_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    }
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out ->
                        photoFile.inputStream().copyTo(out)
                    }
                }
                photoFile.delete()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Foto tersimpan", Toast.LENGTH_SHORT).show()
                    uri?.let {
                        val bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(it))
                        binding.thumbnail.setImageBitmap(bitmap)
                        binding.thumbnail.visibility = View.VISIBLE
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                photoFile.delete()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Gagal: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun startTimer(onFinish: () -> Unit) {
        binding.txtTimer.visibility = View.VISIBLE
        timerCountDown = object : CountDownTimer(timerSeconds * 1000L, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                binding.txtTimer.text = "${millisUntilFinished / 1000}"
            }
            override fun onFinish() {
                binding.txtTimer.visibility = View.GONE
                onFinish()
            }
        }.start()
    }

    private fun showSettings() {
        AlertDialog.Builder(this).apply {
            setTitle("Pengaturan")
            setItems(arrayOf("Kamera Belakang", "Kamera Depan", "Grid", "Timer")) { _, i ->
                when (i) {
                    0 -> showBackResDialog()
                    1 -> showFrontResDialog()
                    2 -> {
                        isGridEnabled = !isGridEnabled
                        prefs.edit().putBoolean("GRID", isGridEnabled).apply()
                        binding.gridOverlay.visibility = if (isGridEnabled) View.VISIBLE else View.GONE
                    }
                    3 -> showTimerDialog()
                }
            }
            show()
        }
    }

    private fun showBackResDialog() {
        AlertDialog.Builder(this).apply {
            setTitle("Resolusi Belakang")
            setItems(arrayOf("4K", "1080p", "720p")) { _, i ->
                prefs.edit().putString("BACK_RES", arrayOf("4K", "1080p", "720p")[i]).apply()
                loadSettings()
                startCamera()
            }
            show()
        }
    }

    private fun showFrontResDialog() {
        AlertDialog.Builder(this).apply {
            setTitle("Resolusi Depan")
            setItems(arrayOf("1080p", "720p")) { _, i ->
                prefs.edit().putString("FRONT_RES", arrayOf("1080p", "720p")[i]).apply()
                loadSettings()
                startCamera()
            }
            show()
        }
    }

    private fun showTimerDialog() {
        AlertDialog.Builder(this).apply {
            setTitle("Timer")
            setItems(arrayOf("Off", "3 detik", "5 detik", "10 detik")) { _, i ->
                timerSeconds = when (i) { 1->3; 2->5; 3->10; else->0 }
            }
            show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
