package com.example.catalarm

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.catalarm.databinding.ActivityMainBinding
import java.io.FileNotFoundException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var preferences: SharedPreferences

    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var detectionEnabled = true
    private var audioUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var catDetector: CatDetector? = null
    private val isAnalyzing = AtomicBoolean(false)
    private var lastPlayedAt = 0L

    private lateinit var modelInputPreview: ImageView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            setStatus("Status: camera permission denied")
        }
    }

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            audioUri = uri
            preferences.edit().putString(PREF_AUDIO_URI, uri.toString()).apply()
            binding.audioPathText.text = "MP3: ${resolveDisplayName(uri)}"
            setStatus("Status: MP3 selected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        cameraExecutor = Executors.newSingleThreadExecutor()

        restoreSavedSettings()
        initializeDetector()
        bindUi()
        modelInputPreview = findViewById(R.id.modelInputPreview)

        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun bindUi() {
        binding.selectMp3Button.setOnClickListener {
            pickAudioLauncher.launch(arrayOf("audio/mpeg", "audio/mp3", "audio/*"))
        }

        binding.toggleDetectionButton.setOnClickListener {
            detectionEnabled = !detectionEnabled
            binding.toggleDetectionButton.text = if (detectionEnabled) {
                getString(R.string.pause_detection)
            } else {
                getString(R.string.resume_detection)
            }
            setStatus(
                if (detectionEnabled) {
                    "Status: detection running"
                } else {
                    "Status: detection paused"
                }
            )
        }

        binding.cameraGroup.setOnCheckedChangeListener { _, checkedId ->
            cameraSelector = if (checkedId == binding.frontCameraRadio.id) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            preferences.edit().putString(
                PREF_CAMERA,
                if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) CAMERA_FRONT else CAMERA_BACK
            ).apply()

            if (hasCameraPermission()) {
                startCamera()
            }
        }
    }

    private fun restoreSavedSettings() {
        val savedAudio = preferences.getString(PREF_AUDIO_URI, null)
        if (!savedAudio.isNullOrBlank()) {
            audioUri = Uri.parse(savedAudio)
            binding.audioPathText.text = "MP3: ${resolveDisplayName(audioUri!!)}"
        }

        val cameraValue = preferences.getString(PREF_CAMERA, CAMERA_BACK)
        cameraSelector = if (cameraValue == CAMERA_FRONT) {
            binding.frontCameraRadio.isChecked = true
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            binding.backCameraRadio.isChecked = true
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    private fun initializeDetector() {
        catDetector = try {
            CatDetector(this)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "TensorFlow Lite model missing or invalid: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            setStatus("Status: add app/src/main/assets/cat_detector.tflite and rebuild")
            null
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { useCase ->
                useCase.setAnalyzer(cameraExecutor) { imageProxy ->
                    analyzeFrame(imageProxy)
                }
            }

        provider.unbindAll()
        provider.bindToLifecycle(this, cameraSelector, preview, analysis)
        setStatus(
            if (catDetector != null) {
                "Status: camera ready, scanning for cats"
            } else {
                "Status: model missing, camera preview only"
            }
        )
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val detector = catDetector
        if (!detectionEnabled || detector == null || !isAnalyzing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = YuvFrameConverter.toBitmap(imageProxy)

            runOnUiThread {
                modelInputPreview.setImageBitmap(bitmap)
            }

            val result = detector.detect(bitmap)
            runOnUiThread {
                val debugLabels = if (result.labels.isEmpty()) {
                    "nothing detected"
                } else {
                    result.labels.joinToString(", ")
                }

                if (result.foundCat) {
                    setStatus(
                        "Status: CAT detected (${String.format("%.0f", result.score * 100)}%) | $debugLabels"
                    )
                    maybePlayAlarm()
                } else {
                    setStatus("Status: scanning... detected: $debugLabels")
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                setStatus("Status: frame analysis error: ${e.message}")
            }
        } finally {
            isAnalyzing.set(false)
            imageProxy.close()
        }
    }

    private fun maybePlayAlarm() {
        val uri = audioUri ?: run {
            setStatus("Status: cat detected, but no MP3 selected")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayedAt < COOLDOWN_MS) return
        lastPlayedAt = now

        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@MainActivity, uri)
                prepare()
                start()
            }
        } catch (e: Exception) {
            setStatus("Status: failed to play MP3: ${e.message}")
        }
    }

    private fun setStatus(text: String) {
        binding.statusText.text = text
    }

    private fun resolveDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(nameColumn)
                }
            }
            uri.toString()
        } catch (_: SecurityException) {
            uri.toString()
        } catch (_: FileNotFoundException) {
            uri.toString()
        }
    }

    companion object {
        private const val COOLDOWN_MS = 4_000L
        private const val PREFS_NAME = "cat_alarm_prefs"
        private const val PREF_AUDIO_URI = "audio_uri"
        private const val PREF_CAMERA = "camera"
        private const val CAMERA_FRONT = "front"
        private const val CAMERA_BACK = "back"
    }
}
