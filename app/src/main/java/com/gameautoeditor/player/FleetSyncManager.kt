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
    
    private var deviceId: String = ""
    private var licenseKey: String = ""
    private var groupId: String? = null
    private var userId: String? = null
    private var userToken: String? = null
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val API_BASE = "https://game-auto-ai.vercel.app/api/fleet"
    private val API_SECRET = BuildConfig.AI_API_SECRET
    
    private var isRunning = false
    private var lastHeartbeatTime = 0L
    
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
    private suspend fun reportStatus() = withContext(Dispatchers.IO) {
        val status = JSONObject().apply {
            put("action", "heartbeat")
            put("device_id", deviceId)
            put("license_key", licenseKey)
            put("user_id", userId ?: "")
            put("user_token", userToken ?: "")
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
        
        val request = Request.Builder()
            .url(API_BASE)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Api-Secret", API_SECRET)
            .post(status.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    // 可能從回應中獲取 group_id
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val newGroupId = json.optString("group_id", null)
                    if (newGroupId != null && newGroupId.isNotEmpty()) {
                        groupId = newGroupId
                    }
                    Unit
                } else {
                    Log.w(TAG, "Heartbeat response: ${response.code}")
                    if (response.code == 401) {
                        Log.e(TAG, "❌ Fleet Sync Auth Failed (401). Stopping sync to prevent spam.")
                        isRunning = false
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat network error: ${e.message}")
        }
    }
    
    /**
     * 輪詢並執行待處理指令
     */
    private suspend fun pollAndExecuteCommands() = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("action", "poll_commands")
            put("device_id", deviceId)
            put("license_key", licenseKey)
            put("user_token", userToken ?: "")
            put("group_id", groupId ?: "")
            put("user_id", userId ?: "")
            put("script_id", engine.scriptId ?: "")
        }
        
        val request = Request.Builder()
            .url(API_BASE)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Api-Secret", API_SECRET)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
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
                        Log.e(TAG, "❌ Fleet Sync Poll Auth Failed (401). Stopping sync to prevent spam.")
                        isRunning = false
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Poll network error: ${e.message}")
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
        
        // 切回主線程執行 UI 相關操作
        Handler(Looper.getMainLooper()).post {
            when (cmd) {
                "start" -> {
                    var scriptId = payload?.optString("script_id")
                    
                    if (scriptId.isNullOrEmpty()) {
                        // Fallback to local
                        val prefs = context.getSharedPreferences("GameAutoEditor", Context.MODE_PRIVATE)
                        scriptId = prefs.getString("script_id", null)
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
                "restart" -> engine.restart()
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
                val bitmap = engine.service.captureScreenSync()
                
                if (bitmap == null) {
                    Log.e(TAG, "❌ captureScreenSync returned null")
                    return@launch
                }
                
                // Compress to WebP
                val baos = ByteArrayOutputStream()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, baos)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 80, baos)
                }
                
                val imageBytes = baos.toByteArray()
                val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                val dataUri = "data:image/webp;base64,$base64Image"
                
                val uploadBody = JSONObject().apply {
                    put("image", dataUri)
                }
                
                val request = Request.Builder()
                    .url("https://game-auto-ai.vercel.app/api/storage?action=upload")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Filename", "screenshots/$deviceId.webp")
                    .post(uploadBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            Log.i(TAG, "✅ Screenshot uploaded to R2 successfully")
                            acknowledgeCommand(commandId)
                        } else {
                            Log.e(TAG, "❌ Screenshot upload failed: ${response.code} ${response.body?.string()}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ captureScreen exception: ${e.message}")
            }
        }
    }
    
    /**
     * 確認指令已執行
     */
    private suspend fun acknowledgeCommand(commandId: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("action", "ack_command")
            put("command_id", commandId)
            put("device_id", deviceId)
        }
        
        val request = Request.Builder()
            .url(API_BASE)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Api-Secret", API_SECRET)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        try {
            client.newCall(request).execute().close()
        } catch (e: Exception) {
            Log.w(TAG, "Ack failed: ${e.message}")
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
