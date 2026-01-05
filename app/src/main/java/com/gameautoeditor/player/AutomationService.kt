package com.gameautoeditor.player

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader

class AutomationService : AccessibilityService() {
    
    private val TAG = "AutomationService"
    private lateinit var sceneGraphEngine: SceneGraphEngine
    private lateinit var scriptEngine: ScriptEngine
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "✅ Accessibility Service 已啟動")
        
        scriptEngine = ScriptEngine(this)
        sceneGraphEngine = SceneGraphEngine(this)
        
        // 從 assets 載入預先打包的腳本
        loadAndExecuteScript()
    }
    
    private fun loadAndExecuteScript() {
        try {
            // 優先從網路載入腳本（支援預編譯模板）
            val scriptId = getScriptId()
            
            if (scriptId != null) {
                Log.i(TAG, "📡 從網路載入腳本: $scriptId")
                loadScriptFromNetwork(scriptId)
            } else {
                // 降級：從 assets 載入（向後相容）
                Log.i(TAG, "📄 從 assets 載入內建腳本")
                loadScriptFromAssets()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 載入腳本失敗: ${e.message}", e)
            showToast("腳本載入失敗")
        }
    }
    
    private fun getScriptId(): String? {
        // 從 SharedPreferences 或 Intent 獲取 script_id
        val prefs = getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
        return prefs.getString("script_id", null)
    }
    
    private fun loadScriptFromNetwork(scriptIdOrUrl: String) {
        Thread {
            try {
                // 支援直接輸入網址 (HTTP/HTTPS) 或 ID
                val urlString = if (scriptIdOrUrl.startsWith("http")) {
                    scriptIdOrUrl
                } else {
                    "https://game-auto-editor.vercel.app/api/get-script?id=$scriptIdOrUrl"
                }

                Log.d(TAG, "Fetching script from: $urlString")
                val connection = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val scriptJson = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()
                    
                    // 在主線程執行腳本
                    android.os.Handler(mainLooper).post {
                        Log.i(TAG, "✅ 網路腳本載入成功")
                        android.os.Handler(mainLooper).postDelayed({
                            showToast("開始執行自動化腳本")
                            
                            // 判斷是 Scene Graph 還是 舊版 Linear Script
                            if (scriptJson.contains("\"nodes\"") && scriptJson.contains("\"edges\"")) {
                                Log.i(TAG, "🔄 偵測到 Scene Graph 格式")
                                sceneGraphEngine.start(scriptJson)
                            } else {
                                Log.i(TAG, "➡️ 偵測到線性腳本格式")
                                scriptEngine.executeScript(scriptJson)
                            }
                            
                        }, 3000)
                    }
                } else {
                    Log.e(TAG, "❌ 網路載入失敗，HTTP $responseCode")
                    // 降級到 assets
                    android.os.Handler(mainLooper).post {
                        loadScriptFromAssets()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 網路請求錯誤: ${e.message}", e)
                // 降級到 assets
                android.os.Handler(mainLooper).post {
                    loadScriptFromAssets()
                }
            }
        }.start()
    }
    
    private fun loadScriptFromAssets() {
        try {
            val inputStream = assets.open("script.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val scriptJson = reader.readText()
            reader.close()
            
            Log.i(TAG, "📄 Assets 腳本載入成功")
            
            // 延遲 3 秒後自動執行
            android.os.Handler(mainLooper).postDelayed({
                showToast("開始執行自動化腳本")
                if (scriptJson.contains("\"nodes\"")) {
                    sceneGraphEngine.start(scriptJson)
                } else {
                    scriptEngine.executeScript(scriptJson)
                }
            }, 3000)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 載入 assets 腳本失敗: ${e.message}", e)
            showToast("找不到腳本檔案")
        }
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可在此監聽畫面變化事件
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "⚠️ Service interrupted")
        scriptEngine.stop()
        sceneGraphEngine.stop()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "🛑 Accessibility Service 已停止")
        scriptEngine.stop()
        sceneGraphEngine.stop()
    }
    
    fun showToast(message: String) {
        android.os.Handler(mainLooper).post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 獲取當前螢幕截圖 (Android 11+)
     */
    fun captureScreen(callback: (android.graphics.Bitmap?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            ) 
                            // 複製一份，因為 hardware buffer 不能直接用於 OpenCV
                            val copy = bitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                            screenshot.hardwareBuffer.close()
                            callback(copy)
                        } catch (e: Exception) {
                            Log.e(TAG, "截圖處理失敗: ${e.message}")
                            callback(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "截圖失敗，錯誤碼: $errorCode")
                        callback(null)
                    }
                }
            )
        } else {
            Log.w(TAG, "不支援 Android 11 以下版本的截圖")
            showToast("此功能需要 Android 11+")
            callback(null)
        }
    }

    /**
     * 同步獲取截圖 (阻塞直到截圖完成或超時)
     * 用於背景線程的 SceneGraphEngine
     */
    fun captureScreenSync(): android.graphics.Bitmap? {
        var result: android.graphics.Bitmap? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        
        captureScreen { bitmap ->
            result = bitmap
            latch.countDown()
        }
        
        try {
            latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot timeout")
        }
        return result
    }
}
