package com.example.catalarm

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class CronitorHeartbeat(
    private val scope: CoroutineScope
) {
    private var heartbeatJob: Job? = null

    fun start() {
        if (BuildConfig.CRONITOR_API_KEY.isBlank() || heartbeatJob?.isActive == true) {
            return
        }

        heartbeatJob = scope.launch {
            while (isActive) {
                sendHeartbeat()
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private suspend fun sendHeartbeat() = withContext(Dispatchers.IO) {
        val url = URL(buildHeartbeatUrl())
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                Log.w(TAG, "Cronitor heartbeat failed with HTTP $responseCode")
            }
            Unit
        } catch (error: Exception) {
            Log.w(TAG, "Cronitor heartbeat failed", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildHeartbeatUrl(): String {
        val encodedMonitorKey = URLEncoder.encode(BuildConfig.CRONITOR_MONITOR_KEY, Charsets.UTF_8.name())
        val encodedEnv = URLEncoder.encode(BuildConfig.CRONITOR_ENV, Charsets.UTF_8.name())
        return "https://cronitor.link/p/${BuildConfig.CRONITOR_API_KEY}/$encodedMonitorKey?env=$encodedEnv"
    }

    companion object {
        private const val TAG = "CronitorHeartbeat"
        private const val USER_AGENT = "CatAlarmApp/1.0"
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
    }
}