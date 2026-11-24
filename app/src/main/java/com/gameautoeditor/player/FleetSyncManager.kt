package com.gameautoeditor.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * FleetSyncManager - 艦隊同步管理器
 * 
 * 負責：
 * 1. 定期心跳上報 (每 5 秒)
 * 2. 定期輪詢控制指令 (每 3 秒)
 * 3. 執行遠端控制指令
 */
class FleetSyncManager(
    private val context: Context,
    private val engine: SceneGraphEngine
) {
    companion object {
        private const val TAG = "GameAuto"
        private const val HEARTBEAT_INTERVAL = 30000L  // 30 秒 (降低 Vercel 消耗)
        private const val POLL_INTERVAL = 15000L       // 15 秒 (降低 Vercel 消耗)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var deviceId: String = ""
    @Volatile
    private var licenseKey: String = ""
    @Volatile
    private var groupId: String? = null
    @Volatile
    private var userId: String? = null
    @Volatile
    private var userToken: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val API_BASE = "${BuildConfig.AI_API_URL.trimEnd('/')}/api/fleet"
    private val API_SECRET = BuildConfig.AI_API_SECRET

    @Volatile
    private var isRunning = false
    @Volatile
    private var lastHeartbeatTime = 0L

    private fun currentUserToken(): String? {
        val fresh = engine.service.getUserToken()
        if (!fresh.isNullOrEmpty()) {
            userToken = fresh
            return fresh
        }
        return userToken?.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun currentLicenseKey(): String {
        val fresh = engine.service.getLicenseKey()
        if (!fresh.isNullOrEmpty()) {
            licenseKey = fresh
            return fresh
        }
        return licenseKey
    }

    private fun currentUserId(): String {
        val prefs = context.getSharedPreferences("GameAutoEditor", Context.MODE_PRIVATE)
        val fresh = prefs.getString("user_id", null)
        if (!fresh.isNullOrEmpty() && fresh != "null") {
            userId = fresh
            return fresh
        }
        return userId?.takeIf { it.isNotEmpty() && it != "null" } ?: ""
    }

    /**
     * 初始化並開始同步
     */
    fun initialize(deviceId: String, licenseKey: String, userId: String?, groupId: String? = null, userToken: String? = null) {
        this.deviceId = deviceId
        this.licenseKey = licenseKey
        this.userId = userId
        this.groupId = groupId
        this.userToken = userToken
        this.isRunning = true

        startHeartbeat()
        startCommandPolling()

        Log.i(TAG, "🛰️ Fleet Sync Initialized: $deviceId")
    }

    /**
     * 更新群組 ID (當從 API 獲取時)
     */
    fun updateGroupId(newGroupId: String?) {
        this.groupId = newGroupId
    }

    /**
     * 定期心跳上報
     */
    private fun startHeartbeat() {
        scope.launch {
            while (isRunning) {
                try {
                    reportStatus()
                    lastHeartbeatTime = System.currentTimeMillis()
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat failed: ${e.message}")
                }
                delay(HEARTBEAT_INTERVAL)
            }
        }
    }

    /**
     * 定期輪詢控制指令
     */
    private fun startCommandPolling() {
        scope.launch {
            while (isRunning) {
                try {
                    pollAndExecuteCommands()
                } catch (e: Exception) {
                    Log.w(TAG, "Command poll failed: ${e.message}")
                }
                delay(POLL_INTERVAL)
            }
        }
    }

    /**
     * 上報當前狀態 (心跳)
     */
    private suspend fun reportStatus(isRetry: Boolean = false): Unit = withContext(Dispatchers.IO) {
        val token = currentUserToken()
        val activeLicense = currentLicenseKey()
        val status = JSONObject().apply {
            put("action", "heartbeat")
            put("device_id", deviceId)
            put("license_key", activeLicense)
            put("user_id", currentUserId())
            put("user_token", token ?: "")
            put("group_id", groupId ?: "")
            put("device_name", Build.MODEL)
            put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("status", engine.currentStatus)
            put("current_scene", engine.currentSceneId ?: "")
            put("current_scene_name", engine.currentSceneName ?: "")
            put("script_id", engine.scriptId ?: "")
            put("script_name", engine.scriptName ?: "")
            put("actions_count", engine.actionsCount)
            put("errors_count", engine.errorsCount)
            put("uptime_seconds", engine.uptimeSeconds)
            put("variables", JSONObject(engine.getVariablesSnapshot()))
            put("last_error", engine.lastError ?: "")
        }

        val reqBuilder = Request.Builder()
            .url(API_BASE)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Api-Secret", API_SECRET)
        if (!token.isNullOrEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer $token")
        }
        val request = reqBuilder
            .post(status.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var shouldRetry = false
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    if (json.has("group_id")) {
                        val newGroupId = if (!json.isNull("group_id")) {
                            json.optString("group_id", "").takeIf { it.isNotEmpty() && it != "null" }
                        } else {
                            null
                        }
                        groupId = newGroupId
                    }
                } else {
                    Log.w(TAG, "Heartbeat response: ${response.code}")
                    if (response.code == 401) {
                        if (!token.isNullOrEmpty() && !isRetry && engine.service.refreshTokenSync(token)) {
                            shouldRetry = true
                        } else {
                            Log.e(TAG, "❌ Fleet Sync Auth Failed (401). Stopping sync to prevent spam.")
                            isRunning = false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat network error: ${e.message}")
        }

        if (shouldRetry) {
            reportStatus(isRetry = true)
        }
    }

    /**
     * 輪詢並執行待處理指令
     */
    private suspend fun pollAndExecuteCommands(isRetry: Boolean = false): Unit = withContext(Dispatchers.IO) {
        val token = currentUserToken()
        val activeLicense = currentLicenseKey()
        val body = JSONObject().apply {
            put("action", "poll_commands")
            put("device_id", deviceId)
            put("license_key", activeLicense)
            put("user_token", token ?: "")
            put("group_id", groupId ?: "")
            put("user_id", currentUserId())
            put("script_id", engine.scriptId ?: "")
        }

        val reqBuilder = Request.Builder()
            .url(API_BASE)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Api-Secret", API_SECRET)
        if (!token.isNullOrEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer $token")
        }
        val request = reqBuilder
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var shouldRetry = false
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val commands = json.optJSONArray("commands") ?: return@use

                    for (i in 0 until commands.length()) {
                        val cmd = commands.getJSONObject(i)
                        val shouldAck = handleCommand(cmd)
                        if (shouldAck) {
                            acknowledgeCommand(cmd.getString("id"))
                        }
                    }
                } else {
                    Log.w(TAG, "Poll response: ${response.code}")
                    if (response.code == 401) {
                        if (!token.isNullOrEmpty() && !isRetry && engine.service.refreshTokenSync(token)) {
                            shouldRetry = true
                        } else {
                            Log.e(TAG, "❌ Fleet Sync Poll Auth Failed (401). Stopping sync to prevent spam.")
                            isRunning = false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Poll network error: ${e.message}")
        }

        if (shouldRetry) {
            pollAndExecuteCommands(isRetry = true)
        }
    }

    /**
     * 處理收到的控制指令
     */
    private fun handleCommand(command: JSONObject): Boolean {
        val cmd = command.getString("command")
        val payload = command.optJSONObject("payload")

        Log.i(TAG, "📡 Command Received: $cmd")

        if (cmd == "capture_screen") {
            handleCaptureScreen(command)
            return false // Ack later when finished
        }

        if (cmd == "restart") {
            scope.launch(Dispatchers.IO) {
                engine.restart()
            }
            return true
        }

        // 切回主線程執行 UI 相關操作
        Handler(Looper.getMainLooper()).post {
            when (cmd) {
                "start" -> {
                    val varsObj = payload?.optJSONObject("variables")
                    if (varsObj != null) {
                        val varsMap = mutableMapOf<String, Any>()
                        val keys = varsObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = varsObj.opt(k)
                            if (v != null && v != JSONObject.NULL) {
                                varsMap[k] = v
                            }
                        }
                        engine.updateVariables(varsMap)
                    }

                    var scriptId = if (payload != null && !payload.isNull("script_id")) {
                        payload.optString("script_id", "").takeIf { it.isNotEmpty() && it != "null" }
                    } else {
                        null
                    }

                    if (scriptId.isNullOrEmpty()) {
                        // Fallback to local
                        val prefs = context.getSharedPreferences("GameAutoPrefs", Context.MODE_PRIVATE)
                        scriptId = prefs.getString("script_id", null)?.takeIf { it.isNotEmpty() && it != "null" }
                            ?: context.getSharedPreferences("GameAutoEditor", Context.MODE_PRIVATE)
                                .getString("script_id", null)?.takeIf { it.isNotEmpty() && it != "null" }
                        Log.w(TAG, "⚠️ Start 指令無 ID，嘗試使用本地: $scriptId")
                    }

                    if (!scriptId.isNullOrEmpty()) {
                        engine.startRemote(scriptId)
                    } else {
                        Log.e(TAG, "❌ 無法執行 Start: 未指定腳本")
                    }
                }
                "pause" -> engine.pause()
                "resume" -> engine.resume()
                "stop" -> engine.stop()
                "enable_log" -> engine.isRemoteLogEnabled = true
                "disable_log" -> engine.isRemoteLogEnabled = false
                "update_vars" -> {
                    val vars = payload?.optJSONObject("variables")
                    engine.updateVariablesFromRemote(vars)
                }
            }
        }
        return true
    }

    /**
     * 處理截圖並上傳至 R2 (Headless AI 視覺同步)
     */
    private fun handleCaptureScreen(command: JSONObject) {
        val commandId = command.getString("id")

        scope.launch {
            try {
                Log.i(TAG, "📸 Capture Screen command started")
                val bitmap = engine.service.captureScreenCleanSync()

                if (bitmap == null) {
                    Log.e(TAG, "❌ captureScreenCleanSync returned null")
                    return@launch
                }

                // Compress to high-clarity WebP (92) for 1:1 template cropping
                val dataUri = try {
                    val baos = ByteArrayOutputStream()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, baos)
                    } else {
                        @Suppress("DEPRECATION")
                        bitmap.compress(Bitmap.CompressFormat.WEBP, 92, baos)
                    }
                    val imageBytes = baos.toByteArray()
                    val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                    "data:image/webp;base64,$base64Image"
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }

                val uploadBody = JSONObject().apply {
                    put("image", dataUri)
                }
                val safeDeviceId = deviceId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(128).ifEmpty { "UNKNOWN" }

                var attempt = 0
                while (attempt < 2) {
                    attempt++
                    val token = currentUserToken()
                    val activeLicense = currentLicenseKey()
                    if (token.isNullOrEmpty() && activeLicense.isEmpty()) {
                        Log.e(TAG, "❌ Screenshot upload aborted: missing credentials")
                        return@launch
                    }

                    val reqBuilder = Request.Builder()
                        .url("${BuildConfig.AI_API_URL.trimEnd('/')}/api/storage?action=upload")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("X-Filename", "screenshots/$safeDeviceId.webp")
                        .addHeader("x-device-id", deviceId)
                    if (!token.isNullOrEmpty()) {
                        reqBuilder.addHeader("Authorization", "Bearer $token")
                    } else {
                        reqBuilder.addHeader("X-Api-Secret", API_SECRET)
                        reqBuilder.addHeader("x-license-key", activeLicense)
                    }
                    val request = reqBuilder
                        .post(uploadBody.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    var shouldRetry = false
                    var uploaded = false
                    withContext(Dispatchers.IO) {
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                Log.i(TAG, "✅ Screenshot uploaded to R2 successfully")
                                uploaded = true
                            } else if (response.code == 401 && !token.isNullOrEmpty() && attempt == 1 && engine.service.refreshTokenSync(token)) {
                                shouldRetry = true
                            } else {
                                Log.e(TAG, "❌ Screenshot upload failed: ${response.code} ${response.body?.string()}")
                            }
                        }
                    }
                    if (uploaded) {
                        acknowledgeCommand(commandId)
                        return@launch
                    }
                    if (!shouldRetry) return@launch
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ captureScreen exception: ${e.message}")
            }
        }
    }

    /**
     * 確認指令已執行
     */
    private suspend fun acknowledgeCommand(commandId: String, isRetry: Boolean = false): Unit = withContext(Dispatchers.IO) {
        val token = currentUserToken()
        val activeLicense = currentLicenseKey()
        val body = JSONObject().apply {
            put("action", "ack_command")
            put("command_id", commandId)
            put("device_id", deviceId)
            put("user_token", token ?: "")
            put("license_key", activeLicense)
        }

        val reqBuilder = Request.Builder()
            .url(API_BASE)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Api-Secret", API_SECRET)
        if (!token.isNullOrEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer $token")
        }
        val request = reqBuilder
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var shouldRetry = false
        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401 && !token.isNullOrEmpty() && !isRetry && engine.service.refreshTokenSync(token)) {
                    shouldRetry = true
                } else if (!response.isSuccessful) {
                    Log.w(TAG, "Ack failed: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ack failed: ${e.message}")
        }

        if (shouldRetry) {
            acknowledgeCommand(commandId, isRetry = true)
        }
    }

    /**
     * 清理資源
     */
    fun cleanup() {
        isRunning = false
        scope.cancel()
        Log.i(TAG, "🛰️ Fleet Sync Stopped")
    }

    /**
     * 檢查是否正在運行
     */
    fun isActive(): Boolean = isRunning
}
