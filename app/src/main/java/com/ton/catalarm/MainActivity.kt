package com.ton.catalarm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.WindowManager
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import java.io.FileNotFoundException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var preferences: SharedPreferences
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var cameraGroup: RadioGroup
    private lateinit var backCameraRadio: RadioButton
    private lateinit var frontCameraRadio: RadioButton
    private lateinit var audioPathText: TextView
    private lateinit var selectMp3Button: MaterialButton
    private lateinit var toggleDetectionButton: MaterialButton
    private lateinit var modelInputPreview: ImageView

    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var detectionEnabled = true
    private var audioUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var catDetector: CatDetector? = null
    private val isAnalyzing = AtomicBoolean(false)
    private var lastCatAlertAt = 0L
    private lateinit var cronitorHeartbeat: CronitorHeartbeat
    private val activateNotificationSequenceInProgress = AtomicBoolean(false)
    private var pendingSecondActivateNotificationJob: Job? = null
    private val nextNotificationId = AtomicInteger(INITIAL_NOTIFICATION_ID)
    private var lastNotificationTriggeredAt = 0L

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            setStatus("Status: camera permission denied")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            setStatus("Status: notification permission denied")
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
            audioPathText.text = "MP3: ${resolveDisplayName(uri)}"
            setStatus("Status: MP3 selected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bindViews()
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        cameraExecutor = Executors.newSingleThreadExecutor()
        cronitorHeartbeat = CronitorHeartbeat(
            scope = lifecycleScope,
            apiKey = BuildConfig.CRONITOR_API_KEY,
            monitorKey = BuildConfig.CRONITOR_MONITOR_KEY,
            env = BuildConfig.CRONITOR_ENV
        )

        restoreSavedSettings()
        initializeDetector()
        bindUi()
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        cronitorHeartbeat.start()
    }

    override fun onStop() {
        cronitorHeartbeat.stop()
        super.onStop()
    }

    override fun onDestroy() {
        cronitorHeartbeat.stop()
        pendingSecondActivateNotificationJob?.cancel()
        pendingSecondActivateNotificationJob = null
        activateNotificationSequenceInProgress.set(false)
        mediaPlayer?.release()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        cameraGroup = findViewById(R.id.cameraGroup)
        backCameraRadio = findViewById(R.id.backCameraRadio)
        frontCameraRadio = findViewById(R.id.frontCameraRadio)
        audioPathText = findViewById(R.id.audioPathText)
        selectMp3Button = findViewById(R.id.selectMp3Button)
        toggleDetectionButton = findViewById(R.id.toggleDetectionButton)
        modelInputPreview = findViewById(R.id.modelInputPreview)
    }

    private fun bindUi() {
        selectMp3Button.setOnClickListener {
            pickAudioLauncher.launch(arrayOf("audio/mpeg", "audio/mp3", "audio/*"))
        }

        toggleDetectionButton.text = if (detectionEnabled) {
            getString(R.string.pause_detection)
        } else {
            getString(R.string.resume_detection)
        }
        toggleDetectionButton.setOnClickListener {
            detectionEnabled = !detectionEnabled
            toggleDetectionButton.text = if (detectionEnabled) {
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

        cameraGroup.setOnCheckedChangeListener { _, checkedId ->
            cameraSelector = if (checkedId == frontCameraRadio.id) {
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
            audioPathText.text = "MP3: ${resolveDisplayName(audioUri!!)}"
        }

        val cameraValue = preferences.getString(PREF_CAMERA, CAMERA_BACK)
        cameraSelector = if (cameraValue == CAMERA_FRONT) {
            frontCameraRadio.isChecked = true
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            backCameraRadio.isChecked = true
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            ACTIVATE_NOTIFICATION_CHANNEL_ID,
            "Cat alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications shown when a cat is detected"
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
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
            it.setSurfaceProvider(previewView.surfaceProvider)
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
                        "Status: CAT detected (${String.format(Locale.getDefault(), "%.0f", result.score * 100)}%) | $debugLabels"
                    )
                    handleCatDetected()
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

    private fun handleCatDetected() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCatAlertAt < COOLDOWN_MS) return
        lastCatAlertAt = now

        maybePlayAlarm()
        triggerActivateNotificationSequence()
    }

    private fun triggerActivateNotificationSequence() {
        if (!hasNotificationPermission()) {
            setStatus("Status: cat detected, but notification permission is not granted")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationTriggeredAt < NOTIFICATION_COOLDOWN_MS) {
            Log.i(TAG, "Skipping Activate notification sequence; cooldown is active")
            return
        }

        if (!activateNotificationSequenceInProgress.compareAndSet(false, true)) {
            Log.i(TAG, "Skipping Activate notification sequence; one is already in progress")
            return
        }

        lastNotificationTriggeredAt = now

        pendingSecondActivateNotificationJob?.cancel()
        pendingSecondActivateNotificationJob = null

        if (!sendActivateNotification(1)) {
            completeActivateNotificationSequence()
            return
        }

        pendingSecondActivateNotificationJob = lifecycleScope.launch {
            delay(ACTIVATE_NOTIFICATION_DELAY_MS)
            pendingSecondActivateNotificationJob = null
            sendActivateNotification(2)
            completeActivateNotificationSequence()
        }
    }

    private fun sendActivateNotification(sequenceNumber: Int): Boolean {
        return try {
            val notification = NotificationCompat.Builder(this, ACTIVATE_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Cat alarm")
                .setContentText("Activate")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Activate"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setAutoCancel(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }

            val notificationId = nextNotificationId.incrementAndGet()
            NotificationManagerCompat.from(this).notify(notificationId, notification)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to post Activate notification #$sequenceNumber", t)
            false
        }
    }

    private fun completeActivateNotificationSequence() {
        pendingSecondActivateNotificationJob?.cancel()
        pendingSecondActivateNotificationJob = null
        activateNotificationSequenceInProgress.set(false)
    }

    private fun maybePlayAlarm() {
        val uri = audioUri ?: run {
            setStatus("Status: cat detected, but no MP3 selected")
            return
        }

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
        Log.i(TAG, text)
        statusText.text = text
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
        private const val TAG = "MainActivity"
        private const val COOLDOWN_MS = 4_000L
        private const val NOTIFICATION_COOLDOWN_MS = 6_000L
        private const val ACTIVATE_NOTIFICATION_DELAY_MS = 3_000L
        private const val ACTIVATE_NOTIFICATION_CHANNEL_ID = "cat_alarm_activate"
        private const val INITIAL_NOTIFICATION_ID = 20_000
        private const val PREFS_NAME = "cat_alarm_prefs"
        private const val PREF_AUDIO_URI = "audio_uri"
        private const val PREF_CAMERA = "camera"
        private const val CAMERA_FRONT = "front"
        private const val CAMERA_BACK = "back"
    }
}

