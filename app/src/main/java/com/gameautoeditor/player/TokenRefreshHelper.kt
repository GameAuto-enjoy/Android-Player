package com.gameautoeditor.player

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Serialized token refresh helper shared across AutomationService, MainActivity, and FleetSyncManager.
 * Prevents concurrent 401 responses from racing on single-use rotating refresh tokens.
 */
object TokenRefreshHelper {
    private const val TAG = "GameAuto"
    private const val PREFS_NAME = "GameAutoEditor"
    private const val HTTP_CONNECT_TIMEOUT_MS = 10_000
    private const val HTTP_READ_TIMEOUT_MS = 10_000

    @Synchronized
    fun refreshSync(context: Context, failedToken: String? = null): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!failedToken.isNullOrEmpty()) {
            val currentToken = prefs.getString("user_token", null)
            if (!currentToken.isNullOrEmpty() && currentToken != failedToken) {
                Log.i(TAG, "♻️ Token 已由其他請求完成刷新，直接使用新憑證")
                return true
            }
        }

        val refreshToken = prefs.getString("refresh_token", null)
        if (refreshToken.isNullOrBlank()) return false

        var conn: HttpURLConnection? = null
        return try {
            val url = URL("${BuildConfig.AI_API_URL.trimEnd('/')}/api/auth/refresh-token")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                readTimeout = HTTP_READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
            }

            val jsonBody = JSONObject().put("refresh_token", refreshToken)
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonBody.toString())
                writer.flush()
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(resp)
                val session = json.optJSONObject("session")
                val newAccessToken = session?.optString("access_token", "")
                if (session != null && !newAccessToken.isNullOrBlank()) {
                    val newRefreshToken = session.optString("refresh_token", "").ifBlank { refreshToken }
                    val editor = prefs.edit()
                        .putString("user_token", newAccessToken)
                        .putString("refresh_token", newRefreshToken)
                    val userId = session.optJSONObject("user")?.optString("id", "")
                    if (!userId.isNullOrBlank() && userId != "null") {
                        editor.putString("user_id", userId)
                    }
                    editor.commit()
                    Log.i(TAG, "♻️ Token 刷新成功！")
                    true
                } else {
                    Log.e(TAG, "♻️ Token 刷新回應缺少 access_token")
                    false
                }
            } else {
                Log.e(TAG, "♻️ Token 刷新失敗: HTTP ${conn.responseCode}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "♻️ Token 刷新錯誤", e)
            false
        } finally {
            conn?.disconnect()
        }
    }
}
