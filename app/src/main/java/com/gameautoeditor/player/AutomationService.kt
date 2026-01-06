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
    
    private var windowManager: android.view.WindowManager? = null
    private var floatingView: android.view.View? = null
    private var isScriptRunning = false

    private val overlayReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.gameautoeditor.SHOW_OVERLAY") {
                Log.i(TAG, "📢 Broadcast Received: SHOW_OVERLAY")
                
                if (floatingView == null) {
                    initFloatingWindow()
                } else {
                    try {
                        // Check if attached
                        if (floatingView?.windowToken == null) {
                             // Not attached, try adding? Or re-init
                             initFloatingWindow()
                        } else {
                            floatingView?.visibility = android.view.View.VISIBLE
                            showToast("Controls Refreshed")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error showing overlay", e)
                        initFloatingWindow()
                    }
                }
            }
        }
    }



    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "✅ Accessibility Service 已啟動")

        // Register Receiver
        val filter = android.content.IntentFilter("com.gameautoeditor.SHOW_OVERLAY")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            registerReceiver(overlayReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(overlayReceiver, filter)
        }
        
        scriptEngine = ScriptEngine(this)
        sceneGraphEngine = SceneGraphEngine(this)
        
        // 初始化懸浮窗
        initFloatingWindow()
    }

    private fun initFloatingWindow() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            
            val layoutParams = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) 
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                else 
                    android.view.WindowManager.LayoutParams.TYPE_PHONE,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            
            layoutParams.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            layoutParams.x = 0
            layoutParams.y = 100
            
            floatingView = android.view.LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)
            
            // UI Controls
            val btnPlayPause = floatingView?.findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
            val btnStop = floatingView?.findViewById<android.widget.ImageButton>(R.id.btnStop)
            val iconDrag = floatingView?.findViewById<android.view.View>(R.id.iconDrag)
            
            btnPlayPause?.setOnClickListener {
                if (isScriptRunning) {
                    // Pause/Stop
                    stopExecution()
                    btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    isScriptRunning = false
                } else {
                    // Start
                    loadAndExecuteScript()
                    btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                    isScriptRunning = true
                }
            }
            
            btnStop?.setOnClickListener {
                 stopExecution()
                 btnPlayPause?.setImageResource(android.R.drawable.ic_media_play)
                 isScriptRunning = false
                 showToast("已停止")
            }
            
            // Drag Logic
            iconDrag?.setOnTouchListener(object : android.view.View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: android.view.View?, event: android.view.MotionEvent): Boolean {
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            initialX = layoutParams.x
                            initialY = layoutParams.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                            layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager?.updateViewLayout(floatingView, layoutParams)
                            return true
                        }
                    }
                    return false
                }
            })
            
            windowManager?.addView(floatingView, layoutParams)
            Log.i(TAG, "悬浮窗已添加")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create floating window: ${e.message}")
        }
    }
    
    private fun stopExecution() {
        scriptEngine.stop()
        sceneGraphEngine.stop()
        Log.i(TAG, "Execution stopped by user")
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
             // Reset UI state if failed
             val btnPlayPause = floatingView?.findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
             btnPlayPause?.setImageResource(android.R.drawable.ic_media_play)
             isScriptRunning = false
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
                        showToast("腳本載入成功，開始執行") // Remove 3s delay for manual control
                            
                        // 判斷是 Scene Graph 還是 舊版 Linear Script
                        if (scriptJson.contains("\"nodes\"") && scriptJson.contains("\"edges\"")) {
                            Log.i(TAG, "🔄 偵測到 Scene Graph 格式")
                            sceneGraphEngine.start(scriptJson)
                        } else {
                            Log.i(TAG, "➡️ 偵測到線性腳本格式")
                            scriptEngine.executeScript(scriptJson)
                        }
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
            
            showToast("開始執行 (Assets)")
            if (scriptJson.contains("\"nodes\"")) {
                sceneGraphEngine.start(scriptJson)
            } else {
                scriptEngine.executeScript(scriptJson)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 載入 assets 腳本失敗: ${e.message}", e)
            showToast("找不到腳本檔案")
            // Reset UI
            val btnPlayPause = floatingView?.findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
            btnPlayPause?.setImageResource(android.R.drawable.ic_media_play)
            isScriptRunning = false
        }
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可在此監聽畫面變化事件
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "⚠️ Service interrupted")
        stopExecution()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "🛑 Accessibility Service 已停止")
        
        try {
            unregisterReceiver(overlayReceiver)
        } catch (e: Exception) {}

        stopExecution()
        
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
        }
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
