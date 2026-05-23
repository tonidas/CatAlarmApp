package com.ton.catalarm

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.thingclips.sdk.bluetooth.dbpbdpp
import com.thingclips.sdk.device.bddqdbd
import com.thingclips.smart.android.ble.IThingBleManager
import com.thingclips.smart.android.ble.IThingBleOperator
import com.thingclips.smart.android.ble.builder.BleConnectBuilder
import com.thingclips.smart.android.ble.builder.BlueConnectParam
import com.thingclips.smart.android.ble.api.BleConnectStatusListener
import com.thingclips.smart.android.ble.api.BleScanResponse
import com.thingclips.smart.android.ble.api.LeConnectResponse
import com.thingclips.smart.android.ble.api.LeScanSetting
import com.thingclips.smart.android.ble.api.ScanDeviceBean
import com.thingclips.smart.android.ble.api.ScanType
import com.thingclips.smart.android.user.api.ILoginCallback
import com.thingclips.smart.android.user.api.IRegisterCallback
import com.thingclips.smart.android.user.bean.User
import com.thingclips.smart.home.sdk.bean.HomeBean
import com.thingclips.smart.home.sdk.callback.IThingResultCallback
import com.thingclips.smart.home.sdk.callback.IThingGetHomeListCallback
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback
import com.thingclips.smart.home.sdk.ThingHomeSdk
import com.thingclips.smart.sdk.api.IBleActivatorListener
import com.thingclips.smart.sdk.api.IDevListener
import com.thingclips.smart.sdk.api.IResultCallback
import com.thingclips.smart.sdk.api.IThingDevice
import com.thingclips.smart.sdk.api.IThingUser
import com.thingclips.smart.sdk.bean.DeviceBean
import com.thingclips.smart.sdk.bean.BleActivatorBean
import com.thingclips.smart.sdk.enums.ThingDevicePublishModeEnum
import com.thingclips.smart.sdk.enums.LowPowerAwakeRsp
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import java.util.zip.ZipFile

data class TuyaHome(val id: Long, val name: String)

data class TuyaDevice(
    val devId: String,
    val name: String,
    val productId: String,
    val category: String,
    val isOnline: Boolean?,
    val isBleDevice: Boolean,
    val isCloudOnline: Boolean?
)

object TuyaBleBridge {
    private const val TAG = "TuyaBleBridge"
    private const val BLE_PAIR_SCAN_TIMEOUT_MS = 60_000L
    private const val BLE_PAIR_RESCAN_DELAY_MS = 250L
    private const val FINGERBOT_COMPLETION_POLL_INTERVAL_MS = 250L
    private const val FINGERBOT_COMPLETION_TIMEOUT_MS = 10_000L
    private const val FINGERBOT_COMPLETION_SYNC_INTERVAL_MS = 1_000L

    private enum class ActivationPublishKind {
        COMMAND,
        DP
    }

    private data class ActivationAttempt(
        val kind: ActivationPublishKind,
        val key: String,
        val label: String,
        val priority: Int
    )

    private data class ActivationPlan(
        val attempts: List<ActivationAttempt>,
        val description: String
    )

    private data class FingerbotStateSnapshot(
        val values: Map<String, String>
    )

    private data class BleConnectHint(
        val address: String?,
        val uuid: String?,
        val productId: String?,
        val deviceType: Int?,
        val flag: Int?
    )

    private data class BleConnectTarget(
        val address: String?,
        val uuid: String?,
        val productId: String?
    )

    private val pairingInProgress = AtomicBoolean(false)
    private val pairingSessionCounter = AtomicLong(0L)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val bleConnectHintsByDevId = linkedMapOf<String, BleConnectHint>()
    private val thingSecurityDependencyLibraries = listOf(
        "libthing_security_algorithm.so",
        "libmbedcrypto.so",
        "libmbedtls.so",
        "libmbedx509.so"
    )
    private val requiredNativeLibraries = listOf(
        "libthing_security.so",
        "libthing_security_algorithm.so",
        "libmbedcrypto.so",
        "libmbedtls.so",
        "libmbedx509.so"
    )

    @Volatile
    private var lastInitError: String? = null

    @Volatile
    private var sdkInitialized = false

    private fun logDebug(message: String) {
        Log.d(TAG, message)
    }

    private fun logInfo(message: String) {
        Log.i(TAG, message)
    }

    private fun logWarn(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(TAG, message, throwable)
        } else {
            Log.w(TAG, message)
        }
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }

    private fun formatHomeId(homeId: Long?): String = homeId?.takeIf { it > 0L }?.toString() ?: "none"

    private fun summarizeScanBean(scanBean: ScanDeviceBean?): String {
        if (scanBean == null) return "scanBean=null"
        return buildString {
            append("name=")
            append(scanBean.name.orEmpty().ifBlank { "unknown" })
            append(", address=")
            append(scanBean.address.orEmpty().ifBlank { "unknown" })
            append(", uuid=")
            append(scanBean.uuid.orEmpty().ifBlank { "unknown" })
            append(", productId=")
            append(scanBean.productId.orEmpty().ifBlank { "unknown" })
            append(", deviceType=")
            append(scanBean.deviceType.toString())
            append(", flag=")
            append(scanBean.flag)
            append(", configType=")
            append(scanBean.configType.orEmpty().ifBlank { "unknown" })
        }
    }

    private fun summarizeHint(hint: BleConnectHint?): String {
        if (hint == null) return "hint=null"
        return "address=${hint.address.orEmpty().ifBlank { "unknown" }}, uuid=${hint.uuid.orEmpty().ifBlank { "unknown" }}, productId=${hint.productId.orEmpty().ifBlank { "unknown" }}, deviceType=${hint.deviceType ?: "unknown"}, flag=${hint.flag ?: "unknown"}"
    }

    private fun summarizeTarget(target: BleConnectTarget?): String {
        if (target == null) return "target=null"
        return "address=${target.address.orEmpty().ifBlank { "unknown" }}, uuid=${target.uuid.orEmpty().ifBlank { "unknown" }}, productId=${target.productId.orEmpty().ifBlank { "unknown" }}"
    }

    private fun describePairingCandidate(scanBean: ScanDeviceBean): String {
        return scanBean.name?.trim()?.takeIf { it.isNotBlank() }
            ?: scanBean.productId?.trim()?.takeIf { it.isNotBlank() }
            ?: scanBean.address?.trim()?.takeIf { it.isNotBlank() }
            ?: "unnamed BLE device"
    }

    private fun buildPairingCandidateKey(scanBean: ScanDeviceBean): String {
        val parts = listOf(
            scanBean.address?.trim()?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT),
            scanBean.uuid?.trim()?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT),
            scanBean.productId?.trim()?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT),
            scanBean.name?.trim()?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)
        ).filterNotNull()
        return if (parts.isEmpty()) summarizeScanBean(scanBean) else parts.joinToString("|")
    }

    private fun isLikelyFingerbotPairingCandidate(scanBean: ScanDeviceBean): Boolean {
        val fingerprint = listOf(scanBean.name, scanBean.productId, scanBean.uuid)
            .map { it.orEmpty().trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        Log.i(TAG, "Evaluating BLE scan result for Fingerbot pairing candidacy: ${summarizeScanBean(scanBean)}, fingerprint='$fingerprint'")
        if (fingerprint.isBlank()) return true

        return fingerprint.contains("fingerbot") ||
            fingerprint.contains("szjqr") ||
            fingerprint.contains("adaprox") ||
            fingerprint.contains("ltak7e1p")
    }

    private fun summarizeAttempts(attempts: List<ActivationAttempt>): String {
        if (attempts.isEmpty()) return "none"
        return attempts.joinToString { "${it.kind}:${it.key}" }
    }

    private val staticFingerbotActivationPlan = ActivationPlan(
        attempts = listOf(
            ActivationAttempt(
                kind = ActivationPublishKind.COMMAND,
                key = "click",
                label = "command `click` (click) from static-plan",
                priority = 400
            )
        ),
        description = "Static activation candidate for Fingerbot: command `click` (click)"
    )


    fun initSdk(application: Application, appKey: String, appSecret: String): Boolean {
        detectNativeLibraryIssue(application)?.let { nativeLibraryIssue ->
            logError("Thing SDK init blocked by native library issue: $nativeLibraryIssue")
            lastInitError = nativeLibraryIssue
            sdkInitialized = false
            return false
        }

        return try {
            logInfo("Initializing Thing SDK (appKeyPresent=${appKey.isNotBlank()}, appSecretPresent=${appSecret.isNotBlank()})")
            ThingHomeSdk.init(application, appKey, appSecret)
            ThingHomeSdk.setDebugMode(true)
            lastInitError = null
            sdkInitialized = true
            logInfo("Thing SDK initialized successfully and debug mode enabled")
            true
        } catch (t: Throwable) {
            sdkInitialized = false
            lastInitError = describeInitFailure(application, unwrapThrowable(t))
            logError("Thing SDK init failed: ${lastInitError.orEmpty()}", t)
            false
        }
    }

    private fun detectNativeLibraryIssue(application: Application): String? {
        val supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val packagedLibrariesByAbi = findPackagedNativeLibraries(application)

        if (packagedLibrariesByAbi.isEmpty()) {
            return null
        }

        val packagedAbis = packagedLibrariesByAbi.keys.sorted()
        val matchedAbi = supportedAbis.firstOrNull { supportedAbi ->
            packagedLibrariesByAbi[supportedAbi]?.isNotEmpty() == true
        }

        if (matchedAbi == null) {
            if (supportedAbis.any { it.startsWith("x86") } && packagedAbis.any { it.startsWith("arm") }) {
                return "ThingClips native libraries are unavailable for device ABI ${supportedAbis.joinToString()}. " +
                    "The current SDK only packaged ARM `.so` files (${
                        packagedAbis.joinToString()
                    }), so run BLE pairing on a real ARM device or an ARM emulator."
            }
            return null
        }

        val packagedLibraries = packagedLibrariesByAbi[matchedAbi].orEmpty()
        val missingLibraries = requiredNativeLibraries.filterNot(packagedLibraries::contains)
        val hasThingSecurity = "libthing_security.so" in packagedLibraries
        val missingThingSecurityDependencies = thingSecurityDependencyLibraries.filterNot(packagedLibraries::contains)

        if (hasThingSecurity && missingThingSecurityDependencies.isNotEmpty()) {
            return "ThingClips SDK package is incomplete: libthing_security.so is present, " +
                "but its required native dependencies are missing from the packaged libs for ABI $matchedAbi: ${missingThingSecurityDependencies.joinToString()}. " +
                "In the tested ThingClips 7.5.6 repositories for this project, no published companion artifact provided those libraries. " +
                "Add the vendor-provided `.so` files under app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}."
        }

        if (missingLibraries.isEmpty()) {
            return null
        }

        return "ThingClips native libraries are incomplete for ABI $matchedAbi. " +
            "Missing from packaged libs: ${missingLibraries.joinToString()}. " +
            "Packaged libs for $matchedAbi: ${packagedLibraries.sorted().joinToString()}"
    }

    private fun findPackagedNativeLibraries(application: Application): Map<String, Set<String>> {
        val apkPaths = buildList {
            application.applicationInfo.sourceDir?.takeIf { it.isNotBlank() }?.let(::add)
            application.applicationInfo.splitSourceDirs
                ?.filterNotNull()
                ?.filter { it.isNotBlank() }
                ?.let(::addAll)
        }
        val librariesByAbi = linkedMapOf<String, MutableSet<String>>()

        for (apkPath in apkPaths) {
            try {
                ZipFile(apkPath).use { zipFile ->
                    val entries = zipFile.entries()
                    while (entries.hasMoreElements()) {
                        val entryName = entries.nextElement().name
                        if (!entryName.startsWith("lib/") || !entryName.endsWith(".so")) continue
                        val segments = entryName.split('/')
                        if (segments.size < 3) continue
                        val abi = segments[1]
                        val libraryName = segments.last()
                        librariesByAbi.getOrPut(abi) { linkedSetOf() }.add(libraryName)
                    }
                }
            } catch (_: Throwable) {
            }
        }

        return librariesByAbi.mapValues { (_, libraries) -> libraries.toSet() }
    }

    private fun unwrapThrowable(throwable: Throwable): Throwable {
        var current = throwable
        while (true) {
            current = current.cause ?: return current
        }
    }

    private fun signatureValidationFailureMessage(code: String, message: String): String {
        val prefix = listOf(code, message)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        val detail = "Tuya signature validation failed. Verify the Android package name, signing SHA fingerprint, and THING_SMART_KEY/THING_SMART_SECRET belong to the same Tuya/ThingClips mobile app project."

        return if (prefix.isBlank()) detail else "$prefix — $detail"
    }

    private fun isSignatureValidationFailure(code: String, message: String): Boolean {
        val combined = listOf(code, message).joinToString(" ")
        return combined.contains("SING_VALIDATE_FALED", ignoreCase = true) ||
            combined.contains("Permission Verification Failed", ignoreCase = true)
    }

    private fun describeInitFailure(application: Application, throwable: Throwable): String {
        val message = throwable.message.orEmpty()
        if (throwable is UnsatisfiedLinkError || message.contains("UnsatisfiedLinkError")) {
            if (message.contains("libthing_security_algorithm.so")) {
                return "ThingClips SDK package is incomplete: libthing_security_algorithm.so is missing at runtime. " +
                    "No published companion artifact was found for the tested ThingClips 7.5.6 repositories in this project, " +
                    "so add the vendor-provided `.so` file under app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}."
            }

            if (message.contains("libmbedcrypto.so") || message.contains("libmbedtls.so") || message.contains("libmbedx509.so")) {
                return "ThingClips SDK package is incomplete: required mbedtls native libraries are missing at runtime. " +
                    "Expected vendor libraries include libmbedcrypto.so, libmbedtls.so, and libmbedx509.so. " +
                    "No published companion artifact was found for the tested ThingClips 7.5.6 repositories in this project, " +
                    "so add the vendor-provided `.so` files under app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}."
            }

            if (message.contains("libthing_security.so")) {
                return "ThingClips SDK could not load libthing_security.so. In this project the usual cause is that " +
                    "its companion native libraries are missing from the packaged SDK for the current ABI. " +
                    "Expected vendor libraries include libthing_security_algorithm.so, libmbedcrypto.so, libmbedtls.so, and libmbedx509.so. " +
                    "No published companion artifact was found for the tested ThingClips 7.5.6 repositories in this project, " +
                    "so add the vendor-provided `.so` files under app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}."
            }

            val nativeLibraryError = detectNativeLibraryIssue(application)
            if (nativeLibraryError != null) {
                return nativeLibraryError
            }
        }

        return message.ifBlank { throwable.javaClass.name }
    }

    fun getLastInitError(): String? = lastInitError

    fun isSdkInitialized(): Boolean = sdkInitialized

    private fun ensureSdkInitialized(operation: String, onError: ((String) -> Unit)? = null): Boolean {
        if (sdkInitialized) return true
        val message = "Thing SDK is not initialized yet"
        logWarn("$operation aborted because $message")
        onError?.invoke(message)
        return false
    }

    fun isUserLoggedIn(): Boolean {
        if (!sdkInitialized) {
            logDebug("isUserLoggedIn called before Thing SDK initialization; returning false")
            return false
        }
        return try {
            val user: IThingUser = ThingHomeSdk.getUserInstance() ?: return false
            user.isLogin
        } catch (t: Throwable) {
            logWarn("Failed to query Thing user login state; returning false", t)
            false
        }
    }

    fun registerAccountIfNeeded(
        countryCode: String,
        username: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!ensureSdkInitialized("registerAccountIfNeeded", onError)) return
        if (isUserLoggedIn()) {
            onSuccess()
            return
        }

        if (countryCode.isBlank() || username.isBlank() || password.isBlank()) {
            onError("Thing Smart registration requires THING_SMART_COUNTRY_CODE, THING_SMART_USERNAME, and THING_SMART_PASSWORD in app.properties.")
            return
        }

        try {
            ThingHomeSdk.getUserInstance()?.registerAccountWithEmail(
                countryCode,
                username,
                password,
                object : IRegisterCallback {
                    override fun onSuccess(user: User?) {
                        onSuccess()
                    }

                    override fun onError(code: String?, error: String?) {
                        val safeCode = code.orEmpty()
                        val safeMessage = error.orEmpty()
                        if (looksLikeExistingAccountRegistrationError(safeCode, safeMessage)) {
                            onSuccess()
                        } else {
                            onError("$safeCode $safeMessage".trim())
                        }
                    }
                }
            ) ?: onError("Tuya user API not available")
        } catch (t: Throwable) {
            val root = unwrapThrowable(t)
            onError(root.message ?: root.javaClass.simpleName)
        }
    }

    private fun looksLikeExistingAccountRegistrationError(code: String, message: String): Boolean {
        val combined = listOf(code, message)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()

        val exactCodes = setOf(
            "user_already_exists",
            "account_already_exists",
            "register_user_exist",
            "user_exist"
        )

        if (code.lowercase() in exactCodes) {
            return true
        }

        return ("exist" in combined || "already" in combined) &&
            ("user" in combined || "account" in combined || "email" in combined || "register" in combined)
    }

    fun loginIfNeeded(
        countryCode: String,
        username: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!ensureSdkInitialized("loginIfNeeded", onError)) return
        if (isUserLoggedIn()) {
            onSuccess()
            return
        }

        if (countryCode.isBlank() || username.isBlank() || password.isBlank()) {
            onError("Tuya login required. Add THING_SMART_COUNTRY_CODE, THING_SMART_USERNAME, and THING_SMART_PASSWORD to app.properties.")
            return
        }

        try {
            ThingHomeSdk.getUserInstance()?.loginWithEmail(
                countryCode,
                username,
                password,
                object : ILoginCallback {
                    override fun onSuccess(user: User?) {
                        onSuccess()
                    }

                    override fun onError(code: String?, error: String?) {
                        val safeCode = code.orEmpty()
                        val safeMessage = error.orEmpty()
                        onError(
                            if (isSignatureValidationFailure(safeCode, safeMessage)) {
                                signatureValidationFailureMessage(safeCode, safeMessage)
                            } else {
                                "$safeCode $safeMessage".trim()
                            }
                        )
                    }
                }
            ) ?: onError("Tuya user API not available")
        } catch (t: Throwable) {
            val root = unwrapThrowable(t)
            onError(root.message ?: root.javaClass.simpleName)
        }
    }

    fun queryHomeList(
        onSuccess: (List<TuyaHome>) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!ensureSdkInitialized("queryHomeList", onError)) return
        try {
            ThingHomeSdk.getHomeManagerInstance().queryHomeList(
                object : IThingGetHomeListCallback {
                    override fun onSuccess(homeBeans: MutableList<HomeBean>?) {
                        val homes = homeBeans
                            .orEmpty()
                            .mapNotNull(::toTuyaHome)
                        onSuccess(homes)
                    }

                    override fun onError(code: String?, error: String?) {
                        onError("${code.orEmpty()} ${error.orEmpty()}".trim())
                    }
                }
            )
        } catch (t: Throwable) {
            val root = unwrapThrowable(t)
            if (!isUserLoggedIn()) {
                onError("Tuya user login required. Add THING_SMART_COUNTRY_CODE, THING_SMART_USERNAME, and THING_SMART_PASSWORD to app.properties.")
            } else {
                onError(root.message ?: root.javaClass.simpleName)
            }
        }
    }

    fun createHomeIfNeeded(
        homeName: String,
        roomNames: List<String> = listOf("Living Room"),
        onSuccess: (TuyaHome) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!ensureSdkInitialized("createHomeIfNeeded", onError)) return
        val normalizedHomeName = homeName.ifBlank { "Home" }
        val normalizedRooms = roomNames.filter { it.isNotBlank() }.ifEmpty { listOf("Living Room") }

        try {
            ThingHomeSdk.getHomeManagerInstance().createHome(
                normalizedHomeName,
                0.0,
                0.0,
                "",
                normalizedRooms,
                object : IThingHomeResultCallback {
                    override fun onSuccess(homeBean: HomeBean?) {
                        val home = toTuyaHome(homeBean)
                        if (home == null) {
                            onError("home created but response was empty")
                        } else {
                            onSuccess(home)
                        }
                    }

                    override fun onError(code: String?, error: String?) {
                        onError("${code.orEmpty()} ${error.orEmpty()}".trim())
                    }
                }
            )
        } catch (t: Throwable) {
            val root = unwrapThrowable(t)
            onError(root.message ?: root.javaClass.simpleName)
        }
    }

    fun queryHomeDevices(
        homeId: Long,
        onSuccess: (List<TuyaDevice>) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!ensureSdkInitialized("queryHomeDevices", onError)) return
        if (homeId <= 0L) {
            logWarn("queryHomeDevices called with invalid homeId=$homeId")
            onError("Tuya homeId is missing")
            return
        }

        try {
            logInfo("Querying home devices for homeId=$homeId")
            ThingHomeSdk.newHomeInstance(homeId).getHomeDetail(
                object : IThingHomeResultCallback {
                    override fun onSuccess(homeBean: HomeBean?) {
                        val devices = when {
                            homeBean != null -> extractHomeDevices(homeBean)
                            else -> ThingHomeSdk.getDataInstance()
                                .getHomeDeviceList(homeId)
                                .orEmpty()
                                .mapNotNull(::toTuyaDevice)
                        }
                        logInfo(
                            "Loaded ${devices.size} home devices for homeId=$homeId (bleDevices=${devices.count { it.isBleDevice }})"
                        )
                        onSuccess(devices.sortedBy { it.name.lowercase() })
                    }

                    override fun onError(code: String?, error: String?) {
                        logWarn("Home detail query failed for homeId=$homeId: code=${code.orEmpty()} error=${error.orEmpty()}")
                        try {
                            val cachedDevices = ThingHomeSdk.getDataInstance()
                                .getHomeDeviceList(homeId)
                                .orEmpty()
                                .mapNotNull(::toTuyaDevice)
                                .sortedBy { it.name.lowercase() }
                            if (cachedDevices.isNotEmpty()) {
                                logInfo(
                                    "Falling back to ${cachedDevices.size} cached devices for homeId=$homeId (bleDevices=${cachedDevices.count { it.isBleDevice }})"
                                )
                                onSuccess(cachedDevices)
                            } else {
                                onError("${code.orEmpty()} ${error.orEmpty()}".trim())
                            }
                        } catch (t: Throwable) {
                            val root = unwrapThrowable(t)
                            logError("Failed to load cached home devices for homeId=$homeId", root)
                            val fallback = listOf(code.orEmpty(), error.orEmpty(), root.message.orEmpty())
                                .filter { it.isNotBlank() }
                                .joinToString(" ")
                            onError(fallback.ifBlank { root.javaClass.simpleName })
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            val root = unwrapThrowable(t)
            logError("queryHomeDevices crashed for homeId=$homeId", root)
            onError(root.message ?: root.javaClass.simpleName)
        }
    }

    fun startScanAndPair(
        homeId: Long,
        onProgress: (String) -> Unit,
        onPaired: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!ensureSdkInitialized("startScanAndPair", onError)) return
        logInfo("Starting BLE scan for pairing on homeId=$homeId")
        stopScan()
        pairingInProgress.set(false)
        try {
            val pairingSessionId = pairingSessionCounter.incrementAndGet()
            val bleOperator: IThingBleOperator = ThingHomeSdk.getBleOperator()
            val scanSetting = buildScanSetting()
            val pairingFinished = AtomicBoolean(false)
            val attemptedCandidates = linkedSetOf<String>()
            val scanStartedAt = SystemClock.elapsedRealtime()
            lateinit var scanCallback: BleScanResponse

            fun isCurrentPairingSession(): Boolean = pairingSessionCounter.get() == pairingSessionId

            fun finishWithError(message: String) {
                if (!isCurrentPairingSession()) return
                if (!pairingFinished.compareAndSet(false, true)) return
                pairingInProgress.set(false)
                stopScan()
                onError(message)
            }

            fun finishWithSuccess(devId: String) {
                if (!isCurrentPairingSession()) return
                if (!pairingFinished.compareAndSet(false, true)) return
                pairingInProgress.set(false)
                stopScan()
                onPaired(devId)
            }

            fun startPairingScan(progressMessage: String) {
                if (!isCurrentPairingSession()) return
                if (pairingFinished.get()) return
                pairingInProgress.set(false)
                stopScan()
                logDebug("BLE scan settings prepared for pairing: timeout=$BLE_PAIR_SCAN_TIMEOUT_MS scanType=SINGLE")
                bleOperator.startLeScan(scanSetting, scanCallback)
                logInfo("BLE scan started for pairing on homeId=$homeId")
                onProgress(progressMessage)
            }

            runDelayed(BLE_PAIR_SCAN_TIMEOUT_MS) {
                if (!isCurrentPairingSession()) return@runDelayed
                if (!pairingFinished.compareAndSet(false, true)) return@runDelayed
                pairingInProgress.set(false)
                stopScan()
                val message = if (attemptedCandidates.isEmpty()) {
                    "No Fingerbot candidate was found. Keep the Fingerbot awake and nearby, then try again."
                } else {
                    "BLE pairing timed out after trying ${attemptedCandidates.size} candidate(s). Keep the Fingerbot in pairing mode and try again."
                }
                logWarn("BLE pairing scan timed out for homeId=$homeId after ${SystemClock.elapsedRealtime() - scanStartedAt}ms")
                onError(message)
            }

            scanCallback = object : BleScanResponse {
                override fun onResult(scanBean: ScanDeviceBean?) {
                    Log.i(TAG, "BLE scan result received: ${summarizeScanBean(scanBean)}")
                    if (!isCurrentPairingSession()) return
                    if (pairingFinished.get()) return

                    val safeBean = scanBean ?: return
                    val singleDevice = isBleSingleDevice(safeBean)
                    logDebug("BLE scan result received during pairing: ${summarizeScanBean(safeBean)}, singleDevice=$singleDevice")
                    if (!singleDevice) {
                        logDebug("Ignoring non-single BLE device during pairing scan")
                        return
                    }
                    if (!isLikelyFingerbotPairingCandidate(safeBean)) {
                        logDebug("Ignoring BLE scan result because it does not look like a Fingerbot candidate")
                        return
                    }

                    val candidateKey = buildPairingCandidateKey(safeBean)
                    if (candidateKey in attemptedCandidates) {
                        logDebug("Skipping BLE pairing candidate that already failed earlier: $candidateKey")
                        return
                    }

                    if (!pairingInProgress.compareAndSet(false, true)) {
                        logDebug("Ignoring BLE scan result because pairing is already in progress")
                        return
                    }

                    attemptedCandidates += candidateKey
                    val candidateLabel = describePairingCandidate(safeBean)
                    logInfo("Found BLE pairing candidate for homeId=$homeId: ${summarizeScanBean(safeBean)}")
                    onProgress("device found ($candidateLabel), starting pairing")
                    stopScan()
                    pairScanDevice(
                        homeId = homeId,
                        scanBean = safeBean,
                        onPaired = { devId ->
                            finishWithSuccess(devId)
                        },
                        onError = { error ->
                            if (!isCurrentPairingSession()) return@pairScanDevice
                            if (pairingFinished.get()) return@pairScanDevice

                            pairingInProgress.set(false)
                            logWarn("BLE pairing attempt failed for candidate=$candidateLabel homeId=$homeId: $error")

                            val elapsed = SystemClock.elapsedRealtime() - scanStartedAt
                            val remaining = BLE_PAIR_SCAN_TIMEOUT_MS - elapsed
                            if (remaining <= BLE_PAIR_RESCAN_DELAY_MS) {
                                finishWithError("BLE pairing failed after trying ${attemptedCandidates.size} candidate(s): $error")
                                return@pairScanDevice
                            }

                            onProgress("pairing failed for $candidateLabel, scanning again")
                            runDelayed(BLE_PAIR_RESCAN_DELAY_MS) {
                                if (pairingFinished.get()) return@runDelayed
                                try {
                                    startPairingScan("BLE scan resumed")
                                } catch (t: Throwable) {
                                    finishWithError(t.message ?: t.javaClass.simpleName)
                                }
                            }
                        }
                    )
                }
            }

            startPairingScan("BLE scan started")
        } catch (t: Throwable) {
            pairingInProgress.set(false)
            logError("Failed to start BLE scan for pairing on homeId=$homeId", t)
            onError(t.message ?: t.javaClass.simpleName)
        }
    }

    fun stopScan() {
        if (!ensureSdkInitialized("stopScan")) return
        try {
            logDebug("Stopping BLE scan")
            ThingHomeSdk.getBleOperator().stopLeScan()
        } catch (t: Throwable) {
            logWarn("stopScan failed: ${t.message.orEmpty()}", t)
        }
    }

    fun activateFingerbot(
        devId: String,
        homeId: Long? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!ensureSdkInitialized("activateFingerbot", onError)) return
        try {
            logInfo("Starting Fingerbot activation for devId=$devId homeId=${formatHomeId(homeId)}")
            val device = ThingHomeSdk.newDeviceInstance(devId)
            val completed = AtomicBoolean(false)

            fun finishSuccess() {
                if (!completed.compareAndSet(false, true)) return
                logInfo("Fingerbot activation succeeded for devId=$devId")
                try {
                    device.onDestroy()
                } catch (_: Throwable) {
                }
                onSuccess()
            }

            fun finishError(message: String) {
                if (!completed.compareAndSet(false, true)) return
                logError("Fingerbot activation failed for devId=$devId: $message")
                try {
                    device.onDestroy()
                } catch (_: Throwable) {
                }
                onError(message)
            }

            val plan = staticFingerbotActivationPlan
            logInfo(
                "Resolved Fingerbot activation plan for devId=$devId: attempts=${plan.attempts.size}, order=${summarizeAttempts(plan.attempts)} description=${plan.description}"
            )
            prepareFingerbotActivationChannel(
                devId = devId,
                homeId = homeId,
                onReady = {
                    logInfo("BLE activation channel ready for devId=$devId")
                    attemptFingerbotActivation(
                        devId = devId,
                        homeId = homeId,
                        device = device,
                        attempts = plan.attempts,
                        attemptIndex = 0,
                        planDescription = plan.description,
                        channelWakeRetried = false,
                        onSuccess = ::finishSuccess,
                        onError = ::finishError
                    )
                },
                onError = ::finishError
            )
        } catch (t: Throwable) {
            logError("activateFingerbot crashed for devId=$devId homeId=${formatHomeId(homeId)}", t)
            onError(t.message ?: t.javaClass.simpleName)
        }
    }

    fun unpairFingerbot(
        devId: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "unpair Fingerbot called with devId=$devId")
        logInfo("Starting unpairing of Fingerbot with devId=$devId")
        if (!ensureSdkInitialized("unpairFingerbot", onError)) return
        if (devId.isBlank()) {
            logWarn("unpairFingerbot called with blank devId")
            onError("Fingerbot devId is missing")
            return
        }

        try {
            logInfo("Starting Fingerbot unpair for devId=$devId")
            val device = ThingHomeSdk.newDeviceInstance(devId)
            device.removeDevice(
                object : IResultCallback {
                    override fun onSuccess() {
                        logInfo("Fingerbot unpair succeeded for devId=$devId")
                        bleConnectHintsByDevId.remove(devId)
                        try {
                            device.onDestroy()
                        } catch (_: Throwable) {
                        }
                        onSuccess()
                    }

                    override fun onError(code: String?, msg: String?) {
                        logError("Fingerbot unpair failed for devId=$devId: code=${code.orEmpty()} msg=${msg.orEmpty()}")
                        onError("$code $msg".trim())
                    }
                }
            )
        } catch (t: Throwable) {
            val root = unwrapThrowable(t)
            logError("unpairFingerbot crashed for devId=$devId", root)
            onError(root.message ?: root.javaClass.simpleName)
        }
    }

    private fun resolveDeviceBean(devId: String, homeId: Long?): DeviceBean? {
        return try {
            val dataManager = ThingHomeSdk.getDataInstance()
            dataManager.getDeviceBean(devId)
                ?: homeId?.takeIf { it > 0L }
                    ?.let(dataManager::getHomeDeviceList)
                    ?.firstOrNull { it.getDevId() == devId }
        } catch (_: Throwable) {
            null
        }
    }

    private fun JSONObject.findInt(key: String): Int? {
        this[key]?.let { value ->
            when (value) {
                is Number -> return value.toInt()
                else -> value.toString().toIntOrNull()?.let { return it }
            }
        }

        val rangeObject = getJSONObject("range")
        rangeObject?.get(key)?.let { value ->
            when (value) {
                is Number -> return value.toInt()
                else -> value.toString().toIntOrNull()?.let { return it }
            }
        }

        return null
    }

    private fun runDelayed(delayMillis: Long, action: () -> Unit): ScheduledFuture<*> {
        return scheduler.schedule(
            {
                try {
                    action()
                } catch (t: Throwable) {
                    logWarn("Delayed action crashed: ${t.message.orEmpty()}", t)
                }
            },
            delayMillis,
            TimeUnit.MILLISECONDS
        )
    }

    private fun resolveBleManager(): IThingBleManager? = try { ThingHomeSdk.getBleManager() } catch (_: Throwable) { null }

    private fun resolveBleOperator(): IThingBleOperator? = try { ThingHomeSdk.getBleOperator() } catch (_: Throwable) { null }

    private fun prepareFingerbotActivationChannel(
        devId: String,
        homeId: Long?,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        logInfo("Preparing BLE activation channel for devId=$devId homeId=${formatHomeId(homeId)}")
        wakeDeviceChannelIfNeeded(
            devId = devId,
            onReady = {
                logDebug("Device wake completed for devId=$devId; ensuring BLE connection")
                ensureBleDeviceConnected(devId, homeId, onReady, onError)
            },
            onError = {
                logWarn("Device wake failed or unavailable for devId=$devId: $it. Falling back to BLE connect path")
                ensureBleDeviceConnected(
                    devId = devId,
                    homeId = homeId,
                    onReady = onReady,
                    onError = onError
                )
            }
        )
    }

    private fun ensureBleDeviceConnected(
        devId: String,
        homeId: Long?,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val bleManager = resolveBleManager()
            logInfo(
                "Ensuring BLE device is connected for devId=$devId homeId=${formatHomeId(homeId)} (bleManagerAvailable=${bleManager != null})"
            )

            fun fallbackToStandardConnect() {
                val bleConnectAddress = resolveBleConnectAddress(devId, homeId)
                logDebug(
                    "Falling back to standard BLE connect for devId=$devId resolvedAddress=${bleConnectAddress.orEmpty().ifBlank { "none" }}"
                )
                if (bleConnectAddress == null) {
                    logWarn("No direct BLE address available for devId=$devId; trying SDK connect-by-devId")
                    if (!tryConnectBleDeviceByDevId(devId, onReady, onError)) {
                        logError("SDK connect-by-devId unavailable for devId=$devId")
                        onError("BLE local channel is unavailable for $devId")
                    }
                    return
                }

                if (tryConnectBleDeviceByDevId(devId, onReady, onError)) {
                    logDebug("SDK connect-by-devId initiated successfully for devId=$devId")
                    return
                }

                val bleOperator = resolveBleOperator() ?: run {
                    logWarn("BLE operator unavailable; continuing without explicit BLE operator connect for devId=$devId")
                    onReady()
                    return
                }

                val finished = AtomicBoolean(false)
                var timeoutTask: ScheduledFuture<*>? = null
                var unregisterStatusListener: (() -> Unit)? = null

                fun finishReady(delayMillis: Long = 100L) {
                    if (!finished.compareAndSet(false, true)) return
                    logInfo("Standard BLE connect path marked ready for devId=$devId after delay=${delayMillis}ms")
                    timeoutTask?.cancel(false)
                    unregisterStatusListener?.invoke()
                    primeBleActivationState(devId) {
                        runDelayed(delayMillis) {
                            awaitBleLocalChannelReady(
                                devId = devId,
                                onReady = onReady,
                                onNotReady = {
                                    onError("BLE local channel not ready for $devId ($bleConnectAddress)")
                                }
                            )
                        }
                    }
                }

                fun finishError(message: String) {
                    if (!finished.compareAndSet(false, true)) return
                    logError("Standard BLE connect path failed for devId=$devId: $message")
                    timeoutTask?.cancel(false)
                    unregisterStatusListener?.invoke()
                    onError(message)
                }

                if (bleManager != null) {
                    val statusListener = object : BleConnectStatusListener {
                        override fun onConnectStatusChanged(devIdArg: String?, status: String?) {
                            logDebug("BLE status listener update for devId=$devId via standard path: devIdArg=${devIdArg.orEmpty()} status=${status.orEmpty()}")
                            if (status.equals("CONNECTED", ignoreCase = true)) {
                                finishReady(500L)
                            }
                        }
                    }

                    try {
                        logDebug("Registering BLE status listener for standard connect path, devId=$devId")
                        bleManager.registerDeviceConnectStatus(devId, statusListener)
                        unregisterStatusListener = {
                            try {
                                bleManager.unregisterDeviceConnectStatus(devId, statusListener)
                            } catch (_: Throwable) {
                            }
                        }
                    } catch (t: Throwable) {
                        logWarn("Failed to register BLE status listener for devId=$devId: ${t.message.orEmpty()}", t)
                        unregisterStatusListener = null
                    }
                }

                timeoutTask = runDelayed(15_000L) {
                    finishError("BLE connect timed out for $devId ($bleConnectAddress)")
                }

                val connectCallback = object : LeConnectResponse {
                    override fun onConnnectResult(address: String?, success: Boolean) {
                        logInfo(
                            "BLE operator connect callback for devId=$devId address=${address.orEmpty().ifBlank { bleConnectAddress }} success=$success"
                        )
                        if (success) {
                            finishReady(if (bleManager == null) 750L else 500L)
                        } else {
                            finishError("BLE connect failed for $devId ($bleConnectAddress)")
                        }
                    }
                }

                logInfo("Invoking BLE operator connectBleDevice for devId=$devId address=$bleConnectAddress")
                bleOperator.connectBleDevice(bleConnectAddress, connectCallback)
            }

            val isBleLocalOnline = try {
                bleManager?.isBleLocalOnline(devId)
            } catch (t: Throwable) {
                logWarn("Failed checking bleLocalOnline for devId=$devId: ${t.message.orEmpty()}", t)
                null
            }
            logDebug("BLE local online check for devId=$devId returned $isBleLocalOnline")

            if (isBleLocalOnline == true) {
                logInfo("BLE local channel already online for devId=$devId")
                primeBleActivationState(devId) {
                    awaitBleLocalChannelReady(
                        devId = devId,
                        onReady = onReady,
                        onNotReady = {
                            onError("BLE local channel not ready for $devId")
                        }
                    )
                }
                return
            }

            val cachedHint = bleConnectHintsByDevId[devId]
            logDebug("Cached BLE connect hint for devId=$devId: ${summarizeHint(cachedHint)}")
            if (cachedHint != null && tryDirectConnectBleDevice(devId, cachedHint, onReady, onError)) {
                logInfo("Started BLE direct connect using cached hint for devId=$devId")
                return
            }

            discoverBleConnectHint(
                devId = devId,
                homeId = homeId,
                onFound = { discoveredHint ->
                    logInfo("Discovered BLE connect hint for devId=$devId: ${summarizeHint(discoveredHint)}")
                    bleConnectHintsByDevId[devId] = discoveredHint
                    if (!tryDirectConnectBleDevice(devId, discoveredHint, onReady, onError)) {
                        logWarn("Direct BLE connect could not be started with discovered hint for devId=$devId; using standard connect")
                        fallbackToStandardConnect()
                    }
                },
                onNotFound = {
                    logWarn("Unable to discover BLE connect hint for devId=$devId; using standard connect")
                    fallbackToStandardConnect()
                }
            )
        } catch (t: Throwable) {
            val root = unwrapThrowable(t)
            val message = root.message ?: root.javaClass.simpleName
            logError("ensureBleDeviceConnected crashed for devId=$devId: $message", root)
            if (looksLikeInvalidBluetoothAddress(message)) {
                onError("BLE local channel is unavailable for $devId")
            } else {
                onError(message)
            }
        }
    }

    private fun extractBleConnectHint(scanBean: ScanDeviceBean): BleConnectHint? {
        val address = scanBean.address?.trim()?.takeIf { it.isNotBlank() }
        val uuid = scanBean.uuid?.trim()?.takeIf { it.isNotBlank() }
        val productId = scanBean.productId?.trim()?.takeIf { it.isNotBlank() }
        val deviceType = scanBean.deviceType
        val flag = scanBean.flag
        if (address == null && uuid == null && productId == null) return null
        return BleConnectHint(
            address = address,
            uuid = uuid,
            productId = productId,
            deviceType = deviceType,
            flag = flag
        )
    }

    private fun resolveBleConnectTarget(devId: String, homeId: Long?): BleConnectTarget? {
        val deviceBean = resolveDeviceBean(devId, homeId) ?: return null
        val address = deviceBean.getMac()?.trim()?.takeIf(::isValidBluetoothAddress)
        val uuid = deviceBean.getUuid()?.trim()?.takeIf { it.isNotBlank() }
        val productId = deviceBean.getProductId()?.trim()?.takeIf { it.isNotBlank() }
        if (address == null && uuid == null && productId == null) return null
        return BleConnectTarget(address = address, uuid = uuid, productId = productId)
    }

    private fun matchesBleConnectTarget(scanBean: ScanDeviceBean, target: BleConnectTarget): Boolean {
        val hint = extractBleConnectHint(scanBean) ?: return false
        if (target.address != null && hint.address.equals(target.address, ignoreCase = true)) return true
        if (target.uuid != null && hint.uuid.equals(target.uuid, ignoreCase = true)) return true
        if (target.productId != null && target.address == null && target.uuid == null) {
            return hint.productId.equals(target.productId, ignoreCase = true)
        }
        return false
    }

    private fun discoverBleConnectHint(
        devId: String,
        homeId: Long?,
        onFound: (BleConnectHint) -> Unit,
        onNotFound: () -> Unit
    ) {
        val target = resolveBleConnectTarget(devId, homeId) ?: run {
            logWarn("No BLE connect target could be resolved for devId=$devId homeId=${formatHomeId(homeId)}")
            onNotFound()
            return
        }

        try {
            val bleOperator = resolveBleOperator() ?: run {
                logWarn("BLE operator unavailable while discovering connect hint for devId=$devId")
                onNotFound()
                return
            }
            val scanSetting = buildScanSetting()
            logInfo("Discovering BLE connect hint for devId=$devId target=${summarizeTarget(target)}")

            val finished = AtomicBoolean(false)
            val timeoutTask = runDelayed(12_000L) {
                if (!finished.compareAndSet(false, true)) return@runDelayed
                logWarn("Timed out discovering BLE connect hint for devId=$devId target=${summarizeTarget(target)}")
                stopScan()
                onNotFound()
            }

            val callback = object : BleScanResponse {
                override fun onResult(scanBean: ScanDeviceBean?) {
                    val safeBean = scanBean ?: return
                    val singleDevice = isBleSingleDevice(safeBean)
                    logDebug(
                        "Hint discovery scan result for devId=$devId: ${summarizeScanBean(safeBean)}, singleDevice=$singleDevice"
                    )
                    if (!singleDevice) return
                    if (matchesBleConnectTarget(safeBean, target)) {
                        val hint = extractBleConnectHint(safeBean)
                        if (hint != null && finished.compareAndSet(false, true)) {
                            logInfo("Matched BLE connect hint for devId=$devId: ${summarizeHint(hint)}")
                            timeoutTask.cancel(false)
                            stopScan()
                            onFound(hint)
                        }
                    }
                }
            }

            logDebug("Starting BLE scan to discover connect hint for devId=$devId")
            bleOperator.startLeScan(scanSetting, callback)
        } catch (t: Throwable) {
            logError("discoverBleConnectHint crashed for devId=$devId", t)
            onNotFound()
        }
    }

    private fun tryDirectConnectBleDevice(
        devId: String,
        hint: BleConnectHint,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        val bleManager = resolveBleManager() ?: run {
            logWarn("BLE manager unavailable for direct connect, devId=$devId")
            return false
        }

        logInfo("Attempting BLE direct connect for devId=$devId using ${summarizeHint(hint)}")

        val finished = AtomicBoolean(false)
        var unregisterStatusListener: (() -> Unit)? = null

        fun finishReady() {
            if (!finished.compareAndSet(false, true)) return
            logInfo("BLE direct connect ready for devId=$devId")
            unregisterStatusListener?.invoke()
            primeBleActivationState(devId) {
                awaitBleLocalChannelReady(
                    devId = devId,
                    onReady = onReady,
                    onNotReady = {
                        onError("BLE local channel not ready for $devId (${hint.address.orEmpty()})")
                    }
                )
            }
        }

        fun finishError(message: String) {
            if (!finished.compareAndSet(false, true)) return
            logError("BLE direct connect failed for devId=$devId: $message")
            unregisterStatusListener?.invoke()
            onError(message)
        }

        val timeoutTask = runDelayed(15_000L) {
            finishError("BLE direct connect timed out for $devId (${hint.address.orEmpty()})")
        }

        val statusListener = object : BleConnectStatusListener {
            override fun onConnectStatusChanged(devIdArg: String?, status: String?) {
                logDebug("BLE direct connect status update for devId=$devId: devIdArg=${devIdArg.orEmpty()} status=${status.orEmpty()}")
                if (status.equals("CONNECTED", ignoreCase = true)) {
                    timeoutTask.cancel(false)
                    finishReady()
                }
            }
        }

        return try {
            logDebug("Registering BLE direct connect status listener for devId=$devId")
            bleManager.registerDeviceConnectStatus(devId, statusListener)
            unregisterStatusListener = {
                try {
                    bleManager.unregisterDeviceConnectStatus(devId, statusListener)
                } catch (_: Throwable) {
                }
                timeoutTask.cancel(false)
            }

            val extInfo = BleConnectBuilder.ExtInfo().apply {
                address = hint.address.orEmpty()
                deviceType = hint.deviceType ?: 0
                flag = hint.flag ?: 0
            }
            val builder = BleConnectBuilder()
                .setDevId(devId)
                .setDirectConnect(true)
                .setScanTimeout(15_000)
                .setLevel(BleConnectBuilder.Level.FORCE)
                .setExtInfo(extInfo)
            hint.uuid?.takeIf { it.isNotBlank() }?.let(builder::setUuid)

            logInfo("Invoking directConnectBleDevice for devId=$devId with ${summarizeHint(hint)}")
            bleManager.directConnectBleDevice(builder)
            true
        } catch (t: Throwable) {
            logError("Failed to start BLE direct connect for devId=$devId", t)
            unregisterStatusListener?.invoke()
            false
        }
    }

    private fun attemptFingerbotActivation(
        devId: String,
        homeId: Long?,
        device: IThingDevice,
        attempts: List<ActivationAttempt>,
        attemptIndex: Int,
        planDescription: String,
        channelWakeRetried: Boolean,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (attemptIndex >= attempts.size) {
            logError("Exhausted Fingerbot activation attempts for devId=$devId. $planDescription")
            onError("Fingerbot activation failed. No valid writable DPS accepted by the SDK. $planDescription")
            return
        }

        val attempt = attempts[attemptIndex]
        val baselineState = captureFingerbotStateSnapshot(devId, homeId)
        logInfo(
            "Running Fingerbot activation attempt ${attemptIndex + 1}/${attempts.size} for devId=$devId via ${attempt.label}"
        )
        val payload = linkedMapOf<String, Any>(attempt.key to true)
        val callback = object : IResultCallback {
            override fun onSuccess() {
                logInfo("Fingerbot activation command accepted for devId=$devId via ${attempt.label}; waiting for completion state")
                awaitFingerbotActivationCompletion(
                    devId = devId,
                    homeId = homeId,
                    device = device,
                    attempt = attempt,
                    baselineState = baselineState,
                    onComplete = onSuccess,
                    payload = payload
                )
            }

            override fun onError(code: String?, msg: String?) {
                val safeCode = code.orEmpty()
                val safeMessage = msg.orEmpty()
                val detail = "$safeCode $safeMessage".trim()
                logWarn(
                    "Fingerbot activation attempt failed for devId=$devId via ${attempt.label}: code=$safeCode msg=$safeMessage"
                )
                if (looksLikeNoChannelAvailable(safeCode, safeMessage)) {
                    if (!channelWakeRetried) {
                        logWarn("No BLE channel available for devId=$devId; retrying after channel preparation")
                        prepareFingerbotActivationChannel(
                            devId = devId,
                            homeId = homeId,
                            onReady = {
                                logInfo("BLE channel retry succeeded for devId=$devId; retrying same activation attempt")
                                attemptFingerbotActivation(
                                    devId = devId,
                                    homeId = homeId,
                                    device = device,
                                    attempts = attempts,
                                    attemptIndex = attemptIndex,
                                    planDescription = planDescription,
                                    channelWakeRetried = true,
                                    onSuccess = onSuccess,
                                    onError = onError
                                )
                            },
                            onError = { wakeError ->
                                logError("BLE channel retry failed for devId=$devId after activation failure: $wakeError")
                                onError(
                                    buildString {
                                        append("Fingerbot activation failed via ")
                                        append(attempt.label)
                                        if (detail.isNotBlank()) {
                                            append(": ")
                                            append(detail)
                                        }
                                        append(" | BLE channel retry failed: ")
                                        append(wakeError)
                                        append(" | ")
                                        append(planDescription)
                                    }
                                )
                            }
                        )
                    } else {
                        logError("BLE/local channel still unavailable for devId=$devId after retry via ${attempt.label}")
                        onError(
                            buildString {
                                append("Fingerbot activation failed via ")
                                append(attempt.label)
                                if (detail.isNotBlank()) {
                                    append(": ")
                                    append(detail)
                                }
                                append(" | BLE/local channel is still unavailable after reconnect attempt")
                                append(" | ")
                                append(planDescription)
                            }
                        )
                    }
                } else if (shouldTryNextActivationAttempt(safeCode, safeMessage) && attemptIndex + 1 < attempts.size) {
                    logWarn("Trying next Fingerbot activation attempt for devId=$devId after ${attempt.label}")
                    attemptFingerbotActivation(
                        devId = devId,
                        homeId = homeId,
                        device = device,
                        attempts = attempts,
                        attemptIndex = attemptIndex + 1,
                        planDescription = planDescription,
                        channelWakeRetried = channelWakeRetried,
                        onSuccess = onSuccess,
                        onError = onError
                    )
                } else {
                    logError("No more activation fallbacks for devId=$devId after ${attempt.label}")
                    onError(
                        buildString {
                            append("Fingerbot activation failed via ")
                            append(attempt.label)
                            if (detail.isNotBlank()) {
                                append(": ")
                                append(detail)
                            }
                            append(" | ")
                            append(planDescription)
                        }
                    )
                }
            }
        }

        val callbackNoOp = object : IResultCallback {
            override fun onSuccess() {
            }

            override fun onError(code: String?, msg: String?) {
            }
        }

        when (attempt.kind) {
            ActivationPublishKind.COMMAND -> {
                logDebug("Publishing Fingerbot command for devId=$devId via ${attempt.label}: $payload")
                device.publishCommands(payload, callback)
            }

            ActivationPublishKind.DP -> {
                val dps = "{\"${attempt.key}\":true}"
                logDebug("Publishing Fingerbot DP for devId=$devId via ${attempt.label}: $dps")
                val publishedWithMode = try {
                    device.publishDps(dps, ThingDevicePublishModeEnum.ThingDevicePublishModeAuto, callback)
                    true
                } catch (t: Throwable) {
                    logWarn("Auto-mode DP publish threw for devId=$devId via ${attempt.label}: ${t.message.orEmpty()}", t)
                    false
                }

                if (!publishedWithMode) {
                    val publishedLocally = try {
                        val bleManager = resolveBleManager()
                        val isBleLocalOnline = try {
                            bleManager?.isBleLocalOnline(devId)
                        } catch (t: Throwable) {
                            logWarn("Failed checking BLE local online before local publish for devId=$devId", t)
                            null
                        }
                        logDebug("Local publish availability for devId=$devId: bleManagerAvailable=${bleManager != null}, bleLocalOnline=$isBleLocalOnline")
                        if (bleManager != null && isBleLocalOnline == true) {
                            logDebug("Publishing Fingerbot DP locally for devId=$devId via ${attempt.label}")
                            bleManager.publishDps(devId, dps, callback)
                            true
                        } else {
                            false
                        }
                    } catch (t: Throwable) {
                        logWarn("Local BLE DP publish threw for devId=$devId via ${attempt.label}: ${t.message.orEmpty()}", t)
                        false
                    }

                    if (!publishedLocally) {
                        logDebug("Publishing Fingerbot DP through device instance fallback for devId=$devId via ${attempt.label}")
                        device.publishDps(dps, callback)
                    }
                }
            }
        }
    }

    private fun awaitFingerbotActivationCompletion(
        devId: String,
        homeId: Long?,
        device: IThingDevice,
        attempt: ActivationAttempt,
        baselineState: FingerbotStateSnapshot,
        onComplete: () -> Unit,
        payload: LinkedHashMap<String, Any>
    ) {
        val finished = AtomicBoolean(false)
        val startedAt = SystemClock.elapsedRealtime()
        var lastActivityAt = startedAt
        var lastSyncAt = 0L
        var observedTrustworthyUpdate = false
        var lastSnapshot = baselineState
        var listenerRegistered = false
        var pollTask: ScheduledFuture<*>? = null

        val callbackNoOp = object : IResultCallback {
            override fun onSuccess() {
            }

            override fun onError(code: String?, msg: String?) {
            }
        }

        fun cleanup() {
            if (listenerRegistered) {
                try {
                    device.unRegisterDevListener()
                } catch (_: Throwable) {
                }
            }
        }

        fun finishWithConfirmation(message: String) {
            if (!finished.compareAndSet(false, true)) return
            pollTask?.cancel(false)
            cleanup()
            logInfo("Fingerbot activation finished for devId=$devId via ${attempt.label}: $message")
            onComplete()
        }

        fun observeActivity(source: String, details: String) {
            observedTrustworthyUpdate = true
            lastActivityAt = SystemClock.elapsedRealtime()
            logInfo("Observed Fingerbot DP/state activity for devId=$devId via ${attempt.label} from $source: $details")
        }

        fun syncDeviceState() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSyncAt < FINGERBOT_COMPLETION_SYNC_INTERVAL_MS) return
            lastSyncAt = now
            requestFingerbotCompletionStateSync(devId)
        }

        val listener = object : IDevListener {
            override fun onDpUpdate(devIdArg: String?, dpStr: String?) {
                logDebug("Fingerbot status dp update callback for devId=$devId during completion wait: devIdArg=${devIdArg.orEmpty()} (devid=${devId}) dpStr=${dpStr.orEmpty()}")
                if (!devIdArg.equals(devId, ignoreCase = true)) return

                val payload = parseFingerbotDpPayload(dpStr)
                val changes = describeDpPayloadChanges(payload, lastSnapshot)
                if (changes.isNotEmpty()) {
                    observeActivity("onDpUpdate", changes.joinToString())
                    lastSnapshot = mergeSnapshotWithDpPayload(lastSnapshot, payload)
                    logDebug("Fingerbot status dp update callback for devId=$devId during completion wait: payload=$payload, changes=${changes.joinToString()}")

                    finishWithConfirmation("confirmed by DP/state update after observing changes: ${changes.joinToString()}")
                }
            }

            override fun onRemoved(devId: String?) {}

            override fun onStatusChanged(devIdArg: String?, online: Boolean) {
                if (!devIdArg.equals(devId, ignoreCase = true)) return
                logDebug("Fingerbot status callback for devId=$devId during completion wait: online=$online")
            }

            override fun onNetworkStatusChanged(devIdArg: String?, status: Boolean) {
                if (!devIdArg.equals(devId, ignoreCase = true)) return
                logDebug("Fingerbot network-status callback for devId=$devId during completion wait: status=$status")
            }

            override fun onDevInfoUpdate(devIdArg: String?) {
                if (!devIdArg.equals(devId, ignoreCase = true)) return
                val currentSnapshot = captureFingerbotStateSnapshot(devId, homeId)
                val changes = describeSnapshotChanges(lastSnapshot, currentSnapshot)
                if (changes.isNotEmpty()) {
                    observeActivity("onDevInfoUpdate", changes.joinToString())
                    lastSnapshot = currentSnapshot
                }
            }
        }

        try {
            device.registerDevListener(listener)
            listenerRegistered = true
        } catch (t: Throwable) {
            logWarn("Failed to register Fingerbot completion listener for devId=$devId: ${t.message.orEmpty()}", t)
        }

        fun pollForCompletion() {
            if (finished.get()) return
            val currentSnapshot = captureFingerbotStateSnapshot(devId, homeId)
            val changes = describeSnapshotChanges(lastSnapshot, currentSnapshot)
            if (changes.isNotEmpty()) {
                observeActivity("snapshot-poll", changes.joinToString())
                lastSnapshot = currentSnapshot
            }

            val now = SystemClock.elapsedRealtime()
            when {
                observedTrustworthyUpdate -> {
                    finishWithConfirmation("confirmed by DP/state update after ${now - startedAt}ms")
                }

                now - startedAt >= FINGERBOT_COMPLETION_TIMEOUT_MS -> {
                    finishWithConfirmation(
                        "no trustworthy DP/state update observed; using timeout fallback after ${now - startedAt}ms"
                    )
                }

                else -> {
                    syncDeviceState()
                    device.publishCommands(payload, callbackNoOp)
                    pollTask = runDelayed(FINGERBOT_COMPLETION_POLL_INTERVAL_MS, ::pollForCompletion)
                }
            }
        }

        syncDeviceState()
        pollTask = runDelayed(FINGERBOT_COMPLETION_POLL_INTERVAL_MS, ::pollForCompletion)
    }

    private fun captureFingerbotStateSnapshot(devId: String, homeId: Long?): FingerbotStateSnapshot {
        val deviceBean = resolveDeviceBean(devId, homeId)
        val values = linkedMapOf<String, String>()

        deviceBean?.getDps()
            .orEmpty()
            .forEach { (dpId, value) ->
                values["dp:${dpId.trim()}"] = normalizeFingerbotStateValue(value)
            }

        deviceBean?.getDpCodes()
            .orEmpty()
            .forEach { (code, value) ->
                val normalizedCode = code.trim().lowercase()
                if (normalizedCode.isNotBlank()) {
                    values["code:$normalizedCode"] = normalizeFingerbotStateValue(value)
                }
            }

        return FingerbotStateSnapshot(values)
    }

    private fun normalizeFingerbotStateValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> value.trim()
            else -> value.toString().trim()
        }
    }

    private fun parseFingerbotDpPayload(dpStr: String?): Map<String, String> {
        val payload = dpStr?.trim().orEmpty()
        if (payload.isBlank()) return emptyMap()

        return try {
            JSON.parseObject(payload)
                ?.entries
                ?.associate { (key, value) -> key.trim() to normalizeFingerbotStateValue(value) }
                .orEmpty()
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun describeDpPayloadChanges(
        payload: Map<String, String>,
        snapshot: FingerbotStateSnapshot
    ): List<String> {
        if (payload.isEmpty()) return emptyList()

        return payload.map { (rawKey, value) ->
            val trimmedKey = rawKey.trim()
            val dpKey = "dp:$trimmedKey"
            val previousValue = snapshot.values[dpKey]
            when {
                previousValue == null -> "$dpKey=<unknown>->$value"
                previousValue != value -> "$dpKey=$previousValue->$value"
                else -> ""
            }
        }.filter { it.isNotBlank() }
    }

    private fun mergeSnapshotWithDpPayload(
        snapshot: FingerbotStateSnapshot,
        payload: Map<String, String>
    ): FingerbotStateSnapshot {
        if (payload.isEmpty()) return snapshot
        val merged = snapshot.values.toMutableMap()
        payload.forEach { (rawKey, value) ->
            merged["dp:${rawKey.trim()}"] = value
        }
        return FingerbotStateSnapshot(merged)
    }

    private fun describeSnapshotChanges(
        previous: FingerbotStateSnapshot,
        current: FingerbotStateSnapshot
    ): List<String> {
        if (current.values.isEmpty()) return emptyList()

        val keys = linkedSetOf<String>()
        keys += previous.values.keys
        keys += current.values.keys

        return keys.mapNotNull { key ->
            val previousValue = previous.values[key]
            val currentValue = current.values[key]
            when {
                previousValue == currentValue -> null
                previousValue == null -> "$key=<missing>->$currentValue"
                currentValue == null -> "$key=$previousValue-><missing>"
                else -> "$key=$previousValue->$currentValue"
            }
        }
    }

    private fun requestFingerbotCompletionStateSync(devId: String) {
        val bleManager = resolveBleManager() ?: return
        try {
            logDebug("Requesting Fingerbot completion sync: recoverDeviceStatus for devId=$devId")
            bleManager.recoverDeviceStatus(devId)
        } catch (t: Throwable) {
            logWarn("Fingerbot completion sync recoverDeviceStatus failed for devId=$devId: ${t.message.orEmpty()}", t)
        }
        try {
            logDebug("Requesting Fingerbot completion sync: syncDeviceAllDps for devId=$devId")
            bleManager.syncDeviceAllDps(devId)
        } catch (t: Throwable) {
            logWarn("Fingerbot completion sync syncDeviceAllDps failed for devId=$devId: ${t.message.orEmpty()}", t)
        }
    }

    private fun looksLikeNoChannelAvailable(code: String, message: String): Boolean {
        val combined = listOf(code, message)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()
        return code == "11005" ||
            combined.contains("11005") ||
            combined.contains("no channel available") ||
            combined.contains("send error,no channel available")
    }

    private fun wakeDeviceChannelIfNeeded(
        devId: String,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            logInfo("Waking low-power BLE channel for devId=$devId")
            val operator = bddqdbd.bdpdqbp()

            try {
                logDebug("Sending immediate awake packet for devId=$devId")
                operator.justSendAwakeDevice(devId)
            } catch (t: Throwable) {
                logWarn("Immediate awake packet failed for devId=$devId: ${t.message.orEmpty()}", t)
            }

            val callback = object : IThingResultCallback<LowPowerAwakeRsp> {
                override fun onSuccess(result: LowPowerAwakeRsp?) {
                    logInfo("Low-power awake succeeded for devId=$devId result=${result?.toString().orEmpty().ifBlank { "null" }}")
                    onReady()
                }

                override fun onError(code: String?, msg: String?) {
                    logWarn("Low-power awake failed for devId=$devId: code=${code.orEmpty()} msg=${msg.orEmpty()}")
                    onError("$code $msg".trim())
                }
            }

            logDebug("Invoking lowPowerDeviceAwake for devId=$devId timeoutMs=15000")
            operator.lowPowerDeviceAwake(devId, 15_000L, callback)
        } catch (t: Throwable) {
            val root = unwrapThrowable(t)
            logError("wakeDeviceChannelIfNeeded crashed for devId=$devId", root)
            onError(root.message ?: root.javaClass.simpleName)
        }
    }

    private fun shouldTryNextActivationAttempt(code: String, message: String): Boolean {
        val combined = listOf(code, message)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .lowercase()
        return looksLikeNoChannelAvailable(code, message) ||
            code == "11001" ||
            combined.contains("11001") ||
            combined.contains("dps invalid") ||
            combined.contains("dps_list") ||
            combined.contains("schema") ||
            combined.contains("dp invalid") ||
            combined.contains("instruction")
    }

    private fun resolveBleConnectAddress(devId: String, homeId: Long?): String? {
        val deviceBean = resolveDeviceBean(devId, homeId) ?: return null
        return deviceBean.getMac()
            ?.trim()
            ?.takeIf(::isValidBluetoothAddress)
    }

    private fun isValidBluetoothAddress(address: String): Boolean {
        return try {
            BluetoothAdapter.checkBluetoothAddress(address)
        } catch (_: Throwable) {
            false
        }
    }

    private fun looksLikeInvalidBluetoothAddress(message: String): Boolean {
        return message.contains("not a valid Bluetooth address", ignoreCase = true)
    }

    private fun tryConnectBleDeviceByDevId(
        devId: String,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        val connectManager = dbpbdpp.getInstance() ?: run {
            logWarn("BLE connect manager unavailable for connect-by-devId, devId=$devId")
            return false
        }

        logInfo("Attempting BLE connect-by-devId for devId=$devId")

        val finished = AtomicBoolean(false)

        fun finishReady(delayMillis: Long = 500L) {
            if (!finished.compareAndSet(false, true)) return
            logInfo("BLE connect-by-devId ready for devId=$devId after delay=${delayMillis}ms")
            primeBleActivationState(devId) {
                runDelayed(delayMillis) {
                    awaitBleLocalChannelReady(devId, onReady)
                }
            }
        }

        fun finishError(message: String) {
            if (!finished.compareAndSet(false, true)) return
            logError("BLE connect-by-devId failed for devId=$devId: $message")
            if (looksLikeInvalidBluetoothAddress(message)) {
                runDelayed(750L, onReady)
            } else {
                onError(message)
            }
        }

        val timeoutTask = runDelayed(15_000L) {
            finishError("BLE connect timed out for $devId")
        }

        val callback = object : IResultCallback {
            override fun onSuccess() {
                logInfo("BLE connect-by-devId success callback for devId=$devId")
                timeoutTask.cancel(false)
                finishReady()
            }

            override fun onError(code: String?, msg: String?) {
                logWarn("BLE connect-by-devId error callback for devId=$devId: code=${code.orEmpty()} msg=${msg.orEmpty()}")
                timeoutTask.cancel(false)
                finishError("$code $msg".trim())
            }
        }

        return try {
            val param = BlueConnectParam.Builder()
                .setDevId(devId)
                .setConnectType(BlueConnectParam.CONNECT_TYPE_BLE_ONLY)
                .setTimeoutMillis(15_000L)
                .build()
            logDebug("Invoking connectDeviceWithCallback for devId=$devId timeoutMs=15000 type=BLE_ONLY")
            connectManager.connectDeviceWithCallback(param, callback)
            true
        } catch (t: Throwable) {
            logError("Failed to start BLE connect-by-devId for devId=$devId", t)
            timeoutTask.cancel(false)
            false
        }
    }

    private fun primeBleActivationState(devId: String, onReady: () -> Unit) {
        val bleManager = resolveBleManager()
        if (bleManager == null) {
            logWarn("BLE manager unavailable while priming activation state for devId=$devId; continuing after delay")
            runDelayed(250L, onReady)
            return
        }

        try {
            logDebug("Priming BLE activation state: recoverDeviceStatus for devId=$devId")
            bleManager.recoverDeviceStatus(devId)
        } catch (t: Throwable) {
            logWarn("recoverDeviceStatus during priming failed for devId=$devId: ${t.message.orEmpty()}", t)
        }

        try {
            logDebug("Priming BLE activation state: syncDeviceAllDps for devId=$devId")
            bleManager.syncDeviceAllDps(devId)
        } catch (t: Throwable) {
            logWarn("syncDeviceAllDps during priming failed for devId=$devId: ${t.message.orEmpty()}", t)
        }

        try {
            logDebug("Priming BLE activation state: publishSystemTimeWithDeviceId for devId=$devId")
            bleManager.publishSystemTimeWithDeviceId(devId)
        } catch (t: Throwable) {
            logWarn("publishSystemTimeWithDeviceId failed for devId=$devId: ${t.message.orEmpty()}", t)
        }

        val completed = AtomicBoolean(false)
        fun finish() {
            if (completed.compareAndSet(false, true)) {
                logDebug("Finished priming BLE activation state for devId=$devId")
                runDelayed(350L, onReady)
            }
        }
        finish()
        /*
        val callback = object : IResultCallback {
            override fun onSuccess() = finish()
            override fun onError(code: String?, error: String?) {
                logWarn("activeExtenModuleByBLEActived callback error for devId=$devId: code=${code.orEmpty()} error=${error.orEmpty()}")
                finish()
            }
        }

        try {
            logDebug("Invoking activeExtenModuleByBLEActived for devId=$devId")
            bleManager.activeExtenModuleByBLEActived(devId, callback)
            runDelayed(1_500L, ::finish)
        } catch (t: Throwable) {
            logWarn("activeExtenModuleByBLEActived threw for devId=$devId: ${t.message.orEmpty()}", t)
            finish()
        }*/
    }

    private fun awaitBleLocalChannelReady(
        devId: String,
        onReady: () -> Unit,
        onNotReady: () -> Unit = onReady,
        remainingAttempts: Int = 8
    ) {
        val bleManager = resolveBleManager()
        if (bleManager == null) {
            logWarn("BLE manager unavailable while awaiting local channel for devId=$devId")
            runDelayed(500L, onNotReady)
            return
        }

        val isReady = try {
            val localOnline = bleManager.isBleLocalOnline(devId)
            val directOnline = try {
                bleManager.isDirectSubDeviceOnline(devId)
            } catch (t: Throwable) {
                logWarn("isDirectSubDeviceOnline failed for devId=$devId: ${t.message.orEmpty()}", t)
                null
            }
            logDebug(
                "Awaiting BLE channel for devId=$devId: localOnline=$localOnline directOnline=$directOnline remainingAttempts=$remainingAttempts"
            )
            localOnline == true || directOnline == true
        } catch (t: Throwable) {
            logWarn("BLE readiness check failed for devId=$devId: ${t.message.orEmpty()}", t)
            false
        }

        if (isReady) {
            logInfo("BLE local/direct channel ready for devId=$devId")
            runDelayed(500L, onReady)
            return
        }

        if (remainingAttempts <= 0) {
            logError("BLE local/direct channel never became ready for devId=$devId")
            runDelayed(500L, onNotReady)
            return
        }

        logDebug("BLE local/direct channel not ready yet for devId=$devId; retrying in 500ms")
        runDelayed(500L) {
            awaitBleLocalChannelReady(devId, onReady, onNotReady, remainingAttempts - 1)
        }
    }

    private fun buildScanSetting(): LeScanSetting {
        return LeScanSetting.Builder()
            .setTimeout(BLE_PAIR_SCAN_TIMEOUT_MS)
            .addScanType(ScanType.SINGLE)
            .build()
    }

    private fun isBleSingleDevice(scanBean: ScanDeviceBean): Boolean {
        val configType = scanBean.configType
        return configType == null || configType == "config_type_single"
    }

    private fun pairScanDevice(
        homeId: Long,
        scanBean: ScanDeviceBean,
        onPaired: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            logInfo("Starting BLE activator pairing for homeId=$homeId with ${summarizeScanBean(scanBean)}")
            val activatorBean = BleActivatorBean(scanBean)
            val scanHint = extractBleConnectHint(scanBean)
            activatorBean.homeId = homeId
            activatorBean.address = scanBean.address
            activatorBean.deviceType = scanBean.deviceType
            activatorBean.uuid = scanBean.uuid
            activatorBean.productId = scanBean.productId
            activatorBean.isShare = ((scanBean.flag shr 2) and 0x01 == 1)
            activatorBean.timeout = 120_000L

            val bleActivator = ThingHomeSdk.getActivator().newBleActivator()
            bleActivator.startActivator(
                activatorBean,
                object : IBleActivatorListener {
                    override fun onSuccess(deviceBean: DeviceBean?) {
                        pairingInProgress.set(false)
                        val devId = deviceBean?.devId.orEmpty()
                        logInfo(
                            "BLE activator pairing succeeded for homeId=$homeId devId=${devId.ifBlank { "missing" }} hint=${summarizeHint(scanHint)}"
                        )
                        if (devId.isBlank()) {
                            onError("paired but devId missing")
                        } else {
                            scanHint?.let { bleConnectHintsByDevId[devId] = it }
                            onPaired(devId)
                        }
                    }

                    override fun onFailure(code: Int, msg: String?, handle: Any?) {
                        pairingInProgress.set(false)
                        logError("BLE activator pairing failed for homeId=$homeId code=$code msg=${msg.orEmpty()}")
                        onError("$code ${msg.orEmpty()}".trim())
                    }
                }
            )
        } catch (t: Throwable) {
            pairingInProgress.set(false)
            logError("pairScanDevice crashed for homeId=$homeId with ${summarizeScanBean(scanBean)}", t)
            onError(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun toTuyaHome(bean: HomeBean?): TuyaHome? {
        if (bean == null) return null
        val id = bean.homeId
        val name = bean.name.orEmpty().ifBlank { "Home $id" }
        return TuyaHome(id, name)
    }
    private fun extractHomeDevices(homeBean: HomeBean): List<TuyaDevice> {
        Log.d(TAG, "Tuya Extracting devices from home ${homeBean.homeId} with ${homeBean.getDeviceList().orEmpty().size} direct devices and ${homeBean.getSharedDeviceList().orEmpty().size} shared devices")
        return buildList {
            addAll(homeBean.getDeviceList().orEmpty().mapNotNull(::toTuyaDevice))
            addAll(homeBean.getSharedDeviceList().orEmpty().mapNotNull(::toTuyaDevice))
        }.distinctBy { it.devId }
    }

    private fun toTuyaDevice(bean: DeviceBean?): TuyaDevice? {
        if (bean == null) return null
        val devId = bean.getDevId().orEmpty().ifBlank { return null }
        val name = bean.getName().orEmpty().ifBlank { "Device $devId" }
        val bleHint = listOf(
            name,
            bean.getCategory().orEmpty(),
            bean.getProductId().orEmpty(),
            bean.getUuid().orEmpty()
        ).joinToString(" ").lowercase()
        return TuyaDevice(
            devId = devId,
            name = name,
            productId = bean.getProductId().orEmpty(),
            category = bean.getCategory().orEmpty(),
            isOnline = bean.getIsOnline(),
            isBleDevice = try {
                bean.isBluetooth() || bean.isSingleBle()
            } catch (_: Throwable) {
                false
            } || bleHint.contains("ble") || bleHint.contains("fingerbot") || bleHint.contains("bluetooth"),
            isCloudOnline = try {
                bean.isCloudOnline()
            } catch (_: Throwable) {
                null
            }
        )
    }

}


