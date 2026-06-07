package com.kr.aicamera

import android.Manifest
import android.content.ContentValues
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.CamcorderProfile
import android.media.MediaActionSound
import android.media.MediaRecorder
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
    private val modeNames = arrayOf("FOTO", "VIDEO", "PORTRAIT")

    // Suara shutter
    private val shutterSound = MediaActionSound()

    // Gesture detector untuk swipe
    private lateinit var gestureDetector: GestureDetector

    // Grid
    private var isGridEnabled = false

    // Timer
    private var timerSeconds = 0
    private var timerCountDown: CountDownTimer? = null

    // Rekaman video
    private var mediaRecorder: MediaRecorder? = null
    private var videoFile: File? = null
    private var isRecording = false

    // Geotagging
    private var lastLocation: Location? = null
    private lateinit var locationManager: LocationManager
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) { lastLocation = loc }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("KR_CAMERA_PREFS", MODE_PRIVATE)
        loadSettings()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        // Gesture swipe mode
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
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION), 100)
        } else {
            startCamera()
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
        isGridEnabled = prefs.getBoolean("GRID", false)
        if (isGridEnabled) binding.gridOverlay.visibility = View.VISIBLE else binding.gridOverlay.visibility = View.GONE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
            startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindCamera(cameraProvider, lensFacing)
            setupButtons(cameraProvider)
            updateModeUI()
            startLocationUpdates()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(cameraProvider: ProcessCameraProvider, facing: Int) {
        cameraProvider.unbindAll()
        val resSize = if (facing == CameraSelector.LENS_FACING_BACK) backRes else frontRes
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(resSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
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

        val cameraSelector = CameraSelector.Builder().requireLensFacing(facing).build()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
        } catch (e: Exception) {
            Toast.makeText(this, "Resolusi tidak didukung", Toast.LENGTH_SHORT).show()
            val fallbackPreview = Preview.Builder().build().also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
            val fallbackCapture = ImageCapture.Builder().build()
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, fallbackPreview, fallbackCapture)
        }
    }

    private fun updateModeUI() {
        binding.txtMode.text = modeNames[currentMode]
        binding.btnCapture.setImageResource(when (currentMode) {
            0 -> R.drawable.ic_camera
            1 -> if (isRecording) R.drawable.ic_stop else R.drawable.ic_video
            2 -> R.drawable.ic_portrait
            else -> R.drawable.ic_camera
        })
    }

    private fun setupButtons(cameraProvider: ProcessCameraProvider) {
        binding.btnCapture.setOnClickListener {
            when (currentMode) {
                0 -> takePhoto()
                1 -> toggleRecording()
                2 -> takePhoto() // portrait placeholder
            }
        }
        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCamera(cameraProvider, lensFacing)
        }
        binding.btnGallery.setOnClickListener {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                type = if (currentMode == 1) "video/*" else "image/*"
            })
        }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnFlash.setOnClickListener { toggleFlash() }
        binding.thumbnail.setOnClickListener { binding.btnGallery.performClick() }
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
        binding.btnFlash.setImageResource(when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
            ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
            else -> R.drawable.ic_flash_off
        })
        startCamera()
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this).apply {
            setTitle("Pengaturan Kamera")
            setItems(arrayOf("Kamera Belakang", "Kamera Depan", "Grid", "Timer")) { _, which ->
                when (which) {
                    0 -> showBackSettings()
                    1 -> showFrontSettings()
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

    private fun showBackSettings() {
        AlertDialog.Builder(this).apply {
            setTitle("Resolusi Belakang")
            setItems(arrayOf("4K 30fps", "4K 60fps", "1080p 30fps", "1080p 60fps", "720p 30fps")) { _, i ->
                prefs.edit().putString("BACK_RES", arrayOf("4K_30","4K_60","1080p_30","1080p_60","720p_30")[i]).apply()
                loadSettings()
                startCamera()
            }
            show()
        }
    }

    private fun showFrontSettings() {
        AlertDialog.Builder(this).apply {
            setTitle("Resolusi Depan")
            setItems(arrayOf("1080p 30fps", "1080p 60fps", "720p 30fps", "720p 60fps")) { _, i ->
                prefs.edit().putString("FRONT_RES", arrayOf("1080p_30","1080p_60","720p_30","720p_60")[i]).apply()
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
        val fileName = "KR_AI_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            // Tambah lokasi jika ada
            lastLocation?.let {
                put(MediaStore.Images.Media.LATITUDE, it.latitude)
                put(MediaStore.Images.Media.LONGITUDE, it.longitude)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: run {
            Toast.makeText(this, "Gagal membuat file", Toast.LENGTH_SHORT).show()
            return
        }

        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(contentResolver, uri, values).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                    }
                    // Tampilkan thumbnail
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Foto tersimpan", Toast.LENGTH_SHORT).show()
                        val bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
                        binding.thumbnail.setImageBitmap(bitmap)
                        binding.thumbnail.visibility = View.VISIBLE
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    contentResolver.delete(uri, null, null)
                    runOnUiThread { Toast.makeText(this@MainActivity, "Gagal: ${exception.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        )
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

    private fun toggleRecording() {
        if (isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val camera = camera ?: return
        val file = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "KR_Video_${System.currentTimeMillis()}.mp4")
        videoFile = file
        val profile = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CamcorderProfile.get(CamcorderProfile.QUALITY_1080P) else CamcorderProfile.get(CamcorderProfile.QUALITY_720P)
        mediaRecorder = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight)
            setVideoFrameRate(profile.videoFrameRate)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        isRecording = true
        updateModeUI()
        Toast.makeText(this, "Merekam video...", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
        isRecording = false
        updateModeUI()

        // Simpan ke galeri
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, videoFile?.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { out ->
            videoFile?.inputStream()?.copyTo(out)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
        videoFile?.delete()
        Toast.makeText(this, "Video tersimpan", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        locationManager.removeUpdates(locationListener)
    }
}
