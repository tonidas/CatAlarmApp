package com.ton.catalarm

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import java.io.FileNotFoundException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val bleLogTag = "MainActivity-BLE"

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var preferences: SharedPreferences
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var fingerbotSequenceIndicator: TextView
    private lateinit var cameraGroup: RadioGroup
    private lateinit var backCameraRadio: RadioButton
    private lateinit var frontCameraRadio: RadioButton
    private lateinit var audioPathText: TextView
    private lateinit var selectMp3Button: MaterialButton
    private lateinit var toggleDetectionButton: MaterialButton
    private lateinit var pairFingerbotButton: MaterialButton
    private lateinit var activateFingerbotButton: MaterialButton
    private lateinit var unpairFingerbotButton: MaterialButton
    private lateinit var refreshBluetoothDevicesButton: MaterialButton
    private lateinit var pairedBluetoothDevicesText: TextView

    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var detectionEnabled = true
    private var audioUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var catDetector: CatDetector? = null
    private val isAnalyzing = AtomicBoolean(false)
    private var lastPlayedAt = 0L
    private lateinit var cronitorHeartbeat: CronitorHeartbeat
    private var tuyaHomeId: Long = -1L
    private var fingerbotDevId: String? = null
    private val fingerbotCatActivationInProgress = AtomicBoolean(false)
    private val fingerbotManualActivationInProgress = AtomicBoolean(false)
    private var pendingSecondFingerbotActivationJob: Job? = null
    private var pendingFingerbotSequenceCompletionJob: Job? = null
    private var pendingManualSecondFingerbotActivationJob: Job? = null

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
            audioPathText.text = "MP3: ${resolveDisplayName(uri)}"
            setStatus("Status: MP3 selected")
        }
    }

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        Log.i(bleLogTag, "BLE permission result received: $grants")
        val denied = grants.entries.firstOrNull { !it.value }
        if (denied != null) {
            val deniedAction = pendingBleDeniedAction
            pendingBleAction = null
            pendingBleDeniedAction = null
            Log.w(bleLogTag, "BLE permission denied: ${denied.key}")
            setStatus("Status: missing permission ${denied.key}")
            deniedAction?.invoke()
            return@registerForActivityResult
        }
        Log.i(bleLogTag, "All requested BLE permissions granted; continuing pending BLE action")
        pendingBleAction?.invoke()
        pendingBleAction = null
        pendingBleDeniedAction = null
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val grantedAction = pendingBluetoothAction
        val deniedAction = pendingBluetoothDeniedAction
        pendingBluetoothAction = null
        pendingBluetoothDeniedAction = null

        if (isBluetoothEnabled()) {
            Log.i(bleLogTag, "Bluetooth enabled; continuing pending BLE action")
            grantedAction?.invoke()
        } else {
            Log.w(bleLogTag, "Bluetooth enable request was declined or failed")
            setStatus("Status: Bluetooth must be enabled for Fingerbot pairing")
            deniedAction?.invoke()
        }
    }

    private var pendingBleAction: (() -> Unit)? = null
    private var pendingBleDeniedAction: (() -> Unit)? = null
    private var pendingBluetoothAction: (() -> Unit)? = null
    private var pendingBluetoothDeniedAction: (() -> Unit)? = null

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
        updateFingerbotSequenceIndicator()
        refreshThingSmartDevicesUi(forceInit = false)

        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        cronitorHeartbeat.start()
        refreshThingSmartDevicesUi(forceInit = false)
    }

    override fun onStop() {
        cronitorHeartbeat.stop()
        super.onStop()
    }

    override fun onDestroy() {
        cronitorHeartbeat.stop()
        TuyaBleBridge.stopScan()
        pendingSecondFingerbotActivationJob?.cancel()
        pendingSecondFingerbotActivationJob = null
        pendingFingerbotSequenceCompletionJob?.cancel()
        pendingFingerbotSequenceCompletionJob = null
        pendingManualSecondFingerbotActivationJob?.cancel()
        pendingManualSecondFingerbotActivationJob = null
        fingerbotCatActivationInProgress.set(false)
        fingerbotManualActivationInProgress.set(false)
        mediaPlayer?.release()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        fingerbotSequenceIndicator = findViewById(R.id.fingerbotSequenceIndicator)
        cameraGroup = findViewById(R.id.cameraGroup)
        backCameraRadio = findViewById(R.id.backCameraRadio)
        frontCameraRadio = findViewById(R.id.frontCameraRadio)
        audioPathText = findViewById(R.id.audioPathText)
        selectMp3Button = findViewById(R.id.selectMp3Button)
        toggleDetectionButton = findViewById(R.id.toggleDetectionButton)
        pairFingerbotButton = findViewById(R.id.pairFingerbotButton)
        activateFingerbotButton = findViewById(R.id.activateFingerbotButton)
        unpairFingerbotButton = findViewById(R.id.unpairFingerbotButton)
        refreshBluetoothDevicesButton = findViewById(R.id.refreshBluetoothDevicesButton)
        pairedBluetoothDevicesText = findViewById(R.id.pairedBluetoothDevicesText)
        modelInputPreview = findViewById(R.id.modelInputPreview)
    }

    private fun bindUi() {
        selectMp3Button.setOnClickListener {
            pickAudioLauncher.launch(arrayOf("audio/mpeg", "audio/mp3", "audio/*"))
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

        pairFingerbotButton.setOnClickListener {
            ensureBleReady {
                startFingerbotPairing()
            }
        }

        activateFingerbotButton.setOnClickListener {
            activateFingerbot()
        }

        unpairFingerbotButton.setOnClickListener {
            unpairFingerbot()
        }

        refreshBluetoothDevicesButton.setOnClickListener {
            refreshThingSmartDevicesUi(forceInit = true)
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

        tuyaHomeId = preferences.getLong(PREF_TUYA_HOME_ID, -1L)
        fingerbotDevId = preferences.getString(PREF_FINGERBOT_DEV_ID, null)
    }

    private fun initTuyaAndListHomes(
        onReady: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        Log.i(
            bleLogTag,
            "Initializing Tuya flow for BLE operations (homeId=$tuyaHomeId, hasCredentials=${hasThingSmartCredentials()}, sdkInitialized=${TuyaBleBridge.isSdkInitialized()})"
        )
        val hasKeys = BuildConfig.THING_SMART_KEY.isNotBlank() && BuildConfig.THING_SMART_SECRET.isNotBlank()
        Log.i(
            bleLogTag,
            "hasKeys=$hasKeys (key=${BuildConfig.THING_SMART_KEY.isNotBlank()}, secret=${BuildConfig.THING_SMART_SECRET.isNotBlank()})"
        )
        if (!hasKeys) {
            val message = "add THING_SMART_KEY and THING_SMART_SECRET in app.properties"
            Log.e(bleLogTag, "Cannot initialize Tuya BLE flow because app key/secret are missing")
            setStatus("Status: $message")
            onError?.invoke(message)
            return
        }

        val initialized = TuyaBleBridge.initSdk(application, BuildConfig.THING_SMART_KEY, BuildConfig.THING_SMART_SECRET)
        if (!initialized) {
            val detail = TuyaBleBridge.getLastInitError().orEmpty()
            val message = "Tuya SDK init failed${if (detail.isNotBlank()) ": ${withTuyaSigningDiagnostics(detail)}" else ""}"
            Log.e(bleLogTag, "Tuya SDK init failed for BLE flow: $message")
            setStatus("Status: $message")
            onError?.invoke(message)
            return
        }

        val continueWithHomeListing = {
            Log.i(bleLogTag, "Tuya SDK ready; querying home list for BLE flow")
            TuyaBleBridge.queryHomeList(
                onSuccess = { homes ->
                    Log.i(bleLogTag, "Home list loaded for BLE flow: count=${homes.size}")
                    if (homes.isEmpty()) {
                        Log.i(bleLogTag, "No Tuya homes found; creating default home for BLE flow")
                        runOnUiThread {
                            setStatus("Status: no home found, creating one...")
                        }
                        TuyaBleBridge.createHomeIfNeeded(
                            homeName = "Cat Alarm Home",
                            onSuccess = { createdHome ->
                                tuyaHomeId = createdHome.id
                                preferences.edit().putLong(PREF_TUYA_HOME_ID, createdHome.id).apply()
                                Log.i(bleLogTag, "Created Tuya home for BLE flow: id=${createdHome.id}, name=${createdHome.name}")
                                runOnUiThread {
                                    setStatus("Status: Tuya home ${createdHome.name} (${createdHome.id}) created")
                                    refreshThingSmartDevicesUi(forceInit = false)
                                    onReady?.invoke()
                                }
                            },
                            onError = { createError ->
                                Log.e(bleLogTag, "Failed creating Tuya home for BLE flow: $createError")
                                runOnUiThread {
                                    setStatus("Status: Tuya home creation failed: $createError")
                                }
                                onError?.invoke("Tuya home creation failed: $createError")
                            }
                        )
                        return@queryHomeList
                    }

                    val first = homes.first()
                    tuyaHomeId = first.id
                    preferences.edit().putLong(PREF_TUYA_HOME_ID, first.id).apply()
                    Log.i(bleLogTag, "Selected Tuya home for BLE flow: id=${first.id}, name=${first.name}")
                    runOnUiThread {
                        setStatus("Status: Tuya home ${first.name} (${first.id}) ready")
                        refreshThingSmartDevicesUi(forceInit = false)
                        onReady?.invoke()
                    }
                },
                onError = { error ->
                    Log.e(bleLogTag, "Failed querying Tuya home list for BLE flow: $error")
                    runOnUiThread {
                        setStatus("Status: Tuya home listing failed: $error")
                    }
                    onError?.invoke("Tuya home listing failed: $error")
                }
            )
        }

        if (TuyaBleBridge.isUserLoggedIn()) {
            Log.i(bleLogTag, "Tuya user already logged in; skipping login for BLE flow")
            continueWithHomeListing()
            return
        }

        Log.i(bleLogTag, "Logging into Tuya account for BLE flow")
        setStatus("Status: logging into Tuya account...")
        TuyaBleBridge.loginIfNeeded(
            countryCode = BuildConfig.THING_SMART_COUNTRY_CODE,
            username = BuildConfig.THING_SMART_USERNAME,
            password = BuildConfig.THING_SMART_PASSWORD,
            onSuccess = {
                Log.i(bleLogTag, "Tuya login succeeded for BLE flow")
                runOnUiThread {
                    setStatus("Status: Tuya login succeeded, listing homes...")
                }
                continueWithHomeListing()
            },
            onError = { error ->
                Log.e(bleLogTag, "Tuya login failed for BLE flow: $error")
                runOnUiThread {
                    setStatus("Status: Tuya login failed: ${withTuyaSigningDiagnostics(error)}")
                }
                onError?.invoke("Tuya login failed: ${withTuyaSigningDiagnostics(error)}")
            }
        )
    }

    private fun withTuyaSigningDiagnostics(message: String): String {
        if (!looksLikeTuyaSigningFailure(message)) return message
        return "$message | ${AppIdentityDiagnostics.buildSummary(this)}"
    }

    private fun looksLikeTuyaSigningFailure(message: String): Boolean {
        return message.contains("SING_VALIDATE_FALED", ignoreCase = true) ||
            message.contains("Permission Verification Failed", ignoreCase = true) ||
            message.contains("signature validation failed", ignoreCase = true)
    }

    private fun ensureBlePermissions(
        onGranted: () -> Unit,
        onDenied: (() -> Unit)? = null
    ) {
        val permissions = requiredBlePermissions()
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        Log.i(bleLogTag, "Checking BLE permissions. required=$permissions missing=$missing")
        if (missing.isEmpty()) {
            Log.i(bleLogTag, "All BLE permissions already granted")
            onGranted()
            return
        }
        pendingBleAction = onGranted
        pendingBleDeniedAction = onDenied
        Log.i(bleLogTag, "Requesting missing BLE permissions: $missing")
        blePermissionLauncher.launch(missing.toTypedArray())
    }

    private fun ensureBleReady(onGranted: () -> Unit) {
        ensureBleReady(onGranted = onGranted, onDenied = null)
    }

    private fun ensureBleReady(
        onGranted: () -> Unit,
        onDenied: (() -> Unit)? = null
    ) {
        ensureBlePermissions(
            onGranted = {
                val bluetoothAdapter = bluetoothAdapter()
                if (bluetoothAdapter == null) {
                    Log.e(bleLogTag, "BLE operation aborted because no Bluetooth adapter is available")
                    setStatus("Status: Bluetooth LE is unavailable on this device")
                    onDenied?.invoke()
                    return@ensureBlePermissions
                }

                val bluetoothEnabled = try {
                    bluetoothAdapter.isEnabled
                } catch (t: Throwable) {
                    Log.e(bleLogTag, "Failed to query Bluetooth adapter state", t)
                    false
                }

                if (bluetoothEnabled) {
                    Log.i(bleLogTag, "Bluetooth is enabled; continuing BLE action")
                    onGranted()
                    return@ensureBlePermissions
                }

                Log.i(bleLogTag, "Requesting user to enable Bluetooth for BLE action")
                pendingBluetoothAction = onGranted
                pendingBluetoothDeniedAction = onDenied
                setStatus("Status: enable Bluetooth to continue Fingerbot pairing")
                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            },
            onDenied = onDenied
        )
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        return try {
            getSystemService(BluetoothManager::class.java)?.adapter
        } catch (t: Throwable) {
            Log.e(bleLogTag, "Failed to resolve Bluetooth adapter", t)
            null
        }
    }

    private fun isBluetoothEnabled(): Boolean {
        return try {
            bluetoothAdapter()?.isEnabled == true
        } catch (t: Throwable) {
            Log.e(bleLogTag, "Failed to determine whether Bluetooth is enabled", t)
            false
        }
    }

    private fun requiredBlePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private fun hasThingSmartCredentials(): Boolean {
        return BuildConfig.THING_SMART_KEY.isNotBlank() &&
            BuildConfig.THING_SMART_SECRET.isNotBlank() &&
            BuildConfig.THING_SMART_COUNTRY_CODE.isNotBlank() &&
            BuildConfig.THING_SMART_USERNAME.isNotBlank() &&
            BuildConfig.THING_SMART_PASSWORD.isNotBlank()
    }

    private fun refreshThingSmartDevicesUi(forceInit: Boolean) {
        Log.i(
            bleLogTag,
            "Refreshing Thing Smart devices UI (forceInit=$forceInit, homeId=$tuyaHomeId, hasCredentials=${hasThingSmartCredentials()})"
        )
        if (!hasThingSmartCredentials()) {
            pairedBluetoothDevicesText.text =
                "Add THING_SMART_* credentials in app.properties to list Smart Life devices."
            return
        }

        val homeId = tuyaHomeId
        if (!forceInit && homeId <= 0L) {
            pairedBluetoothDevicesText.text =
                "Tap Refresh to sign in and load Smart Life devices from your home."
            return
        }

        pairedBluetoothDevicesText.text = "Loading Smart Life devices..."

        val loadDevices = loadDevices@{
            val resolvedHomeId = tuyaHomeId
            if (resolvedHomeId <= 0L) {
                Log.w(bleLogTag, "Cannot load Thing Smart devices because no homeId is available yet")
                runOnUiThread {
                    pairedBluetoothDevicesText.text = "No Smart Life home available yet."
                }
                return@loadDevices
            }

            Log.i(bleLogTag, "Querying Thing Smart devices for homeId=$resolvedHomeId")
            TuyaBleBridge.queryHomeDevices(
                homeId = resolvedHomeId,
                onSuccess = { devices ->
                    maybeAutoBindFingerbotFromDeviceList(devices)
                    Log.i(
                        bleLogTag,
                        "Thing Smart devices loaded for homeId=$resolvedHomeId: total=${devices.size}, ble=${devices.count { it.isBleDevice }}"
                    )
                    runOnUiThread {
                        pairedBluetoothDevicesText.text = formatThingSmartDevices(devices)
                    }
                },
                onError = { error ->
                    Log.e(bleLogTag, "Failed querying Thing Smart devices for homeId=$resolvedHomeId: $error")
                    runOnUiThread {
                        pairedBluetoothDevicesText.text =
                            "Failed to load Smart Life devices: $error"
                    }
                }
            )
        }

        if (forceInit || tuyaHomeId <= 0L) {
            initTuyaAndListHomes {
                loadDevices()
            }
        } else {
            loadDevices()
        }
    }

    private fun maybeAutoBindFingerbotFromDeviceList(devices: List<TuyaDevice>) {
        val savedDevId = fingerbotDevId?.takeIf { it.isNotBlank() }
        if (savedDevId != null && devices.any { it.devId == savedDevId }) {
            return
        }

        val fingerbotCandidates = devices.filter { device ->
            device.isBleDevice && isLikelyFingerbotDevice(device)
        }
        val bleCandidates = devices.filter { it.isBleDevice }

        val selected = when {
            fingerbotCandidates.size == 1 -> fingerbotCandidates.first()
            fingerbotCandidates.size > 1 -> fingerbotCandidates.firstOrNull { it.isOnline == true } ?: fingerbotCandidates.first()
            bleCandidates.size == 1 -> bleCandidates.first()
            else -> null
        } ?: return

        fingerbotDevId = selected.devId
        preferences.edit().putString(PREF_FINGERBOT_DEV_ID, selected.devId).apply()
        Log.i(
            bleLogTag,
            "Auto-selected Fingerbot devId=${selected.devId} from loaded devices (name=${selected.name}, product=${selected.productId})"
        )
        runOnUiThread {
            updateFingerbotSequenceIndicator()
        }
    }

    private fun isLikelyFingerbotDevice(device: TuyaDevice): Boolean {
        val deviceHint = listOf(device.name, device.category, device.productId)
            .joinToString(" ")
            .lowercase(Locale.getDefault())
        return deviceHint.contains("fingerbot") || deviceHint.contains("szjqr")
    }

    private fun formatThingSmartDevices(devices: List<TuyaDevice>): String {
        if (devices.isEmpty()) {
            return "No Smart Life devices found in the current home."
        }

        return devices
            .sortedWith(compareBy({ it.name.lowercase(Locale.getDefault()) }, { it.devId }))
            .joinToString(separator = "\n\n") { device ->
                buildString {
                    append(device.name)
                    append("\n")
                    append("devId: ")
                    append(device.devId)
                    append("\n")
                    append("Product: ")
                    append(device.productId.ifBlank { "unknown" })
                    append("\n")
                    append("Category: ")
                    append(device.category.ifBlank { "unknown" })
                    append("\n")
                    append("Online: ")
                    append(
                        when (device.isOnline) {
                            true -> "yes"
                            false -> "no"
                            null -> "unknown"
                        }
                    )
                    append("\n")
                    append("BLE: ")
                    append(if (device.isBleDevice) "yes" else "no")
                }
            }
    }

    private fun startFingerbotPairing() {
        Log.i(bleLogTag, "User requested Fingerbot pairing (savedHomeId=$tuyaHomeId, savedDevId=${fingerbotDevId.orEmpty()})")
        setStatus("Status: preparing Tuya BLE pairing...")
        initTuyaAndListHomes(onReady = {
            val homeId = tuyaHomeId
            if (homeId <= 0L) {
                Log.e(bleLogTag, "Cannot start Fingerbot pairing because homeId is invalid: $homeId")
                setStatus("Status: no Tuya homeId. Check account login/home list first")
                return@initTuyaAndListHomes
            }

            Log.i(bleLogTag, "Starting Fingerbot BLE scan/pair for homeId=$homeId")
            setStatus("Status: scanning BLE devices for Fingerbot...")
            TuyaBleBridge.startScanAndPair(
                homeId = homeId,
                onProgress = { msg ->
                    Log.d(bleLogTag, "Fingerbot pairing progress: $msg")
                    runOnUiThread { setStatus("Status: $msg") }
                },
                onPaired = { devId ->
                    Log.i(bleLogTag, "Fingerbot pairing succeeded with devId=$devId")
                    fingerbotDevId = devId
                    preferences.edit().putString(PREF_FINGERBOT_DEV_ID, devId).apply()
                    runOnUiThread {
                        updateFingerbotSequenceIndicator()
                        setStatus("Status: Fingerbot paired. devId=$devId")
                        refreshThingSmartDevicesUi(forceInit = false)
                    }
                },
                onError = { err ->
                    Log.e(bleLogTag, "Fingerbot pairing failed: $err")
                    runOnUiThread {
                        setStatus("Status: pairing failed: $err")
                    }
                }
            )
        },
            onError = { err ->
                Log.e(bleLogTag, "Failed to initialize Tuya for BLE pairing: $err")
                setStatus("Status: failed to initialize Tuya: $err")
            })
    }

    private fun activateFingerbot() {
        if (!fingerbotManualActivationInProgress.compareAndSet(false, true)) {
            setStatus("Status: Fingerbot manual activation already in progress")
            return
        }

        requestFingerbotActivation(
            preparingStatus = "Status: preparing Fingerbot activation...",
            onSuccess = {
                runOnUiThread {
                    setStatus("Status: Fingerbot activation finished. Activating again in 1 second...")
                }
                scheduleSecondManualFingerbotActivation()
            },
            onError = { err ->
                completeManualFingerbotActivationSequence()
                runOnUiThread {
                    setStatus("Status: Fingerbot activation failed: $err")
                }
            }
        )
    }

    private fun scheduleSecondManualFingerbotActivation() {
        pendingManualSecondFingerbotActivationJob?.cancel()
        pendingManualSecondFingerbotActivationJob = lifecycleScope.launch {
            delay(FINGERBOT_SECOND_ACTIVATION_DELAY_MS)
            pendingManualSecondFingerbotActivationJob = null
            requestFingerbotActivation(
                preparingStatus = "Status: running second Fingerbot activation...",
                onSuccess = {
                    completeManualFingerbotActivationSequence()
                    runOnUiThread {
                        setStatus("Status: Fingerbot double-press sequence finished")
                    }
                },
                onError = { err ->
                    completeManualFingerbotActivationSequence()
                    runOnUiThread {
                        setStatus("Status: second Fingerbot activation failed: $err")
                    }
                }
            )
        }
    }

    private fun completeManualFingerbotActivationSequence() {
        pendingManualSecondFingerbotActivationJob?.cancel()
        pendingManualSecondFingerbotActivationJob = null
        fingerbotManualActivationInProgress.set(false)
    }

    private fun requestFingerbotActivation(
        preparingStatus: String? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val devId = fingerbotDevId
        Log.i(bleLogTag, "Requesting Fingerbot activation with devId=$devId, homeId=$tuyaHomeId")
        if (devId.isNullOrBlank()) {
            val homeId = tuyaHomeId
            if (homeId > 0L) {
                Log.i(bleLogTag, "No saved Fingerbot devId; attempting auto-resolve from homeId=$homeId device list")
                TuyaBleBridge.queryHomeDevices(
                    homeId = homeId,
                    onSuccess = { devices ->
                        maybeAutoBindFingerbotFromDeviceList(devices)
                        val resolvedDevId = fingerbotDevId
                        if (resolvedDevId.isNullOrBlank()) {
                            Log.w(bleLogTag, "Auto-resolve did not find a Fingerbot candidate in homeId=$homeId")
                            onError("pair Fingerbot first")
                            return@queryHomeDevices
                        }

                        Log.i(bleLogTag, "Auto-resolved Fingerbot devId=$resolvedDevId; retrying activation")
                        requestFingerbotActivation(
                            preparingStatus = preparingStatus,
                            onSuccess = onSuccess,
                            onError = onError
                        )
                    },
                    onError = { err ->
                        Log.w(bleLogTag, "Auto-resolve failed while querying home devices: $err")
                        onError("pair Fingerbot first")
                    }
                )
                return
            }

            Log.w(bleLogTag, "Fingerbot activation aborted because no devId is paired")
            onError("pair Fingerbot first")
            return
        }

        ensureBleReady(
            onGranted = {
                Log.i(bleLogTag, "BLE permissions granted for Fingerbot activation (devId=$devId)")
                preparingStatus?.let(::setStatus)
                initTuyaAndListHomes(
                    onReady = {
                        Log.i(bleLogTag, "Invoking bridge activation for devId=$devId with homeId=$tuyaHomeId")
                        TuyaBleBridge.activateFingerbot(
                            devId = devId,
                            homeId = tuyaHomeId.takeIf { it > 0L },
                            onSuccess = {
                                Log.i(bleLogTag, "Bridge activation completed successfully for devId=$devId")
                                onSuccess()
                            },
                            onError = { error ->
                                Log.e(bleLogTag, "Bridge activation failed for devId=$devId: $error")
                                onError(error)
                            }
                        )
                    },
                    onError = { error ->
                        Log.e(bleLogTag, "Activation setup failed before bridge call for devId=$devId: $error")
                        onError(error)
                    }
                )
            },
            onDenied = {
                Log.w(bleLogTag, "Fingerbot activation blocked because BLE permission was denied")
                onError("missing BLE permission")
            }
        )
    }

    private fun maybeTriggerFingerbotForCatDetection() {
        if (!hasThingSmartCredentials()) return
        if (fingerbotDevId.isNullOrBlank()) return
        if (!fingerbotCatActivationInProgress.compareAndSet(false, true)) {
            Log.i("MainActivity", "Skipping cat-triggered Fingerbot activation; sequence already in progress")
            return
        }
        updateFingerbotSequenceIndicator()

        requestFingerbotActivation(
            preparingStatus = "Status: cat detected, activating Fingerbot...",
            onSuccess = {
                runOnUiThread {
                    setStatus("Status: cat detected, Fingerbot activation finished. Activating again in 1 second...")
                }
                scheduleSecondFingerbotActivation()
            },
            onError = { err ->
                runOnUiThread {
                    setStatus("Status: cat-triggered Fingerbot activation failed: $err")
                }
                completeCatTriggeredFingerbotSequence()
            }
        )
    }

    private fun scheduleSecondFingerbotActivation() {
        pendingSecondFingerbotActivationJob?.cancel()
        pendingSecondFingerbotActivationJob = lifecycleScope.launch {
            delay(FINGERBOT_SECOND_ACTIVATION_DELAY_MS)
            pendingSecondFingerbotActivationJob = null

            requestFingerbotActivation(
                preparingStatus = "Status: cat detected earlier, running second Fingerbot activation...",
                onSuccess = {
                    runOnUiThread {
                        setStatus("Status: second Fingerbot activation command accepted; waiting for sequence to finish...")
                    }
                    scheduleCatTriggeredSequenceCompletion()
                },
                onError = { err ->
                    runOnUiThread {
                        setStatus("Status: second cat-triggered Fingerbot activation failed: $err")
                    }
                    completeCatTriggeredFingerbotSequence()
                }
            )
        }
    }

    private fun scheduleCatTriggeredSequenceCompletion() {
        pendingFingerbotSequenceCompletionJob?.cancel()
        pendingFingerbotSequenceCompletionJob = lifecycleScope.launch {
            delay(FINGERBOT_SEQUENCE_SETTLE_DELAY_MS)
            pendingFingerbotSequenceCompletionJob = null
            runOnUiThread {
                setStatus("Status: cat-triggered Fingerbot sequence finished")
            }
            completeCatTriggeredFingerbotSequence()
        }
    }

    private fun completeCatTriggeredFingerbotSequence() {
        pendingSecondFingerbotActivationJob?.cancel()
        pendingSecondFingerbotActivationJob = null
        pendingFingerbotSequenceCompletionJob?.cancel()
        pendingFingerbotSequenceCompletionJob = null
        fingerbotCatActivationInProgress.set(false)
        updateFingerbotSequenceIndicator()
    }

    private fun unpairFingerbot() {
        val devId = fingerbotDevId
        Log.i(bleLogTag, "Requesting Fingerbot unpair with devId=$devId, homeId=$tuyaHomeId")
        if (devId.isNullOrBlank()) {
            Log.w(bleLogTag, "Fingerbot unpair aborted because no devId is currently paired")
            setStatus("Status: no Fingerbot is currently paired")
            return
        }

        ensureBleReady {
            Log.i(bleLogTag, "BLE permissions granted for Fingerbot unpair (devId=$devId)")
            setStatus("Status: (unpair) removing Fingerbot from Thing Smart...")
            initTuyaAndListHomes(onReady = {
                Log.i(bleLogTag, "(unpair) Invoking bridge unpair for devId=$devId")
                TuyaBleBridge.unpairFingerbot(
                    devId = devId,
                    onSuccess = {
                        Log.i(bleLogTag, "(unpair) Bridge unpair completed successfully for devId=$devId")
                        fingerbotDevId = null
                        preferences.edit().remove(PREF_FINGERBOT_DEV_ID).apply()
                        runOnUiThread {
                            updateFingerbotSequenceIndicator()
                            setStatus("(unpair) Status: Fingerbot unpaired")
                            refreshThingSmartDevicesUi(forceInit = true)
                        }
                    },
                    onError = { err ->
                        Log.e(bleLogTag, "(unpair) Bridge unpair failed for devId=$devId: $err")
                        runOnUiThread {
                            setStatus("(unpair) Status: Fingerbot unpair failed: $err")
                        }
                    }
                )
            },
                onError = { err ->
                    Log.e(bleLogTag, "(unpair) Unpair setup failed before bridge call for devId=$devId: $err")
                    runOnUiThread {
                        setStatus("(unpair) Status: Unpair setup failed: $err")
                    }
                }
            )
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
                    maybePlayAlarm()
                    maybeTriggerFingerbotForCatDetection()
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
        Log.i("MainActivity", text)
        statusText.text = text
    }

    private fun updateFingerbotSequenceIndicator() {
        runOnUiThread {
            fingerbotSequenceIndicator.text = when {
                fingerbotCatActivationInProgress.get() -> getString(R.string.fingerbot_sequence_indicator_running)
                fingerbotDevId.isNullOrBlank() -> getString(R.string.fingerbot_sequence_indicator_unavailable)
                else -> getString(R.string.fingerbot_sequence_indicator_idle)
            }
        }
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
        private const val FINGERBOT_SECOND_ACTIVATION_DELAY_MS = 1_000L
        private const val FINGERBOT_SEQUENCE_SETTLE_DELAY_MS = 1_000L
        private const val PREFS_NAME = "cat_alarm_prefs"
        private const val PREF_AUDIO_URI = "audio_uri"
        private const val PREF_CAMERA = "camera"
        private const val CAMERA_FRONT = "front"
        private const val CAMERA_BACK = "back"
        private const val PREF_TUYA_HOME_ID = "tuya_home_id"
        private const val PREF_FINGERBOT_DEV_ID = "tuya_fingerbot_dev_id"
    }
}

