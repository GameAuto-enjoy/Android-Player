package com.gameautoeditor.player

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import android.graphics.Bitmap
import org.json.JSONObject

class AutomationService : AccessibilityService() {
    
    private val TAG = "GameAuto"
    private lateinit var sceneGraphEngine: SceneGraphEngine
    private lateinit var scriptEngine: ScriptEngine
    private lateinit var recordingManager: RecordingManager
    private var fleetManager: FleetSyncManager? = null
    
    private var windowManager: android.view.WindowManager? = null
    private var floatingView: android.view.View? = null
    private var isScriptRunning = false
    
    // Cached script data for deferred execution after Ad
    private var pendingScriptJson: String? = null
    private var pendingScriptId: String? = null
    private var pendingUserPlan: String = "free"

    private val overlayReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.gameautoeditor.INIT_FLEET") {
                Log.i(TAG, "📢 收到廣播: INIT_FLEET")
                initFleet()
                return
            }

            if (intent?.action == "com.gameautoeditor.SHOW_OVERLAY") {
                Log.i(TAG, "📢 收到廣播: SHOW_OVERLAY")
                
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
                            showToast("控制面板已刷新")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "顯示懸浮窗錯誤", e)
                        initFloatingWindow()
                    }
                }
            } else if (intent?.action == "com.gameautoeditor.START_SCRIPT_AFTER_AD") {
                Log.i(TAG, "📢 收到廣播: START_SCRIPT_AFTER_AD")
                val scriptJson = pendingScriptJson
                val scriptIdOrUrl = pendingScriptId ?: ""
                val plan = pendingUserPlan
                
                if (scriptJson != null) {
                    Log.i(TAG, "自動跳轉回目標應用程式...")
                    try {
                        val originPkg = getOriginPackageName()
                        if (originPkg != null) {
                            val launchIntent = packageManager.getLaunchIntentForPackage(originPkg)
                            if (launchIntent != null) {
                                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                startActivity(launchIntent)
                                // Give it a little time to switch before starting the engine
                                Thread.sleep(1000)
                            } else {
                                Log.w(TAG, "找不到目標應用程式 ($originPkg) 的啟動 Intent")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "無法切換回目標應用程式", e)
                    }

                    Log.i(TAG, "開始執行緩存的腳本...")
                    // Ensure tracking matches intention
                    sceneGraphEngine.start(scriptJson, scriptIdOrUrl)
                    isScriptRunning = true
                    android.os.Handler(mainLooper).post {
                        val btnPlayPause = floatingView?.findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
                        btnPlayPause?.setImageResource(android.R.drawable.ic_media_pause)
                    }
                    
                    // Clear cache
                    pendingScriptJson = null
                    pendingScriptId = null
                } else {
                    Log.e(TAG, "錯誤: 找不到緩存的腳本資料")
                    showToast("執行失敗：遺失腳本資料")
                }
            }
        }
    }



    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "✅ Accessibility Service 已啟動")

        // Register Receiver
        val filter = android.content.IntentFilter("com.gameautoeditor.SHOW_OVERLAY")
        filter.addAction("com.gameautoeditor.INIT_FLEET")
        filter.addAction("com.gameautoeditor.START_SCRIPT_AFTER_AD")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            registerReceiver(overlayReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(overlayReceiver, filter)
        }
        
        scriptEngine = ScriptEngine(this)
        sceneGraphEngine = SceneGraphEngine(this)
        recordingManager = RecordingManager(this)
        
        // Fleet Integration
        initFleet()
        
        // 初始化懸浮窗
        initFloatingWindow()
    }


    private fun initFleet() {
        val licenseKey = getLicenseKey()
        val userToken = getUserToken()
        
        if (!licenseKey.isNullOrEmpty() || !userToken.isNullOrEmpty()) {
            if (fleetManager != null) {
                Log.i(TAG, "⚠️ Fleet 收到重新初始化請求，正在清理舊連線...")
                fleetManager?.cleanup()
                fleetManager = null
            }

            val deviceId = getAppDeviceId() ?: "UNKNOWN"
            val prefs = getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
            val userId = prefs.getString("user_id", null) 
            
            fleetManager = FleetSyncManager(this, sceneGraphEngine)
            fleetManager?.initialize(deviceId, licenseKey ?: "", userId, null, userToken)
            Log.i(TAG, "✅ Fleet 初始化/重啟成功")
        } else {
            Log.w(TAG, "⚠️ 無授權金鑰也無帳號登入，跳過 Fleet 初始化")
            if (fleetManager != null) {
                fleetManager?.cleanup()
                fleetManager = null
            }
        }
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
            
            // Fix: Use ContextThemeWrapper to provide AppCompat Theme for AppCompat Widgets
            val themeContext = androidx.appcompat.view.ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light)
            floatingView = android.view.LayoutInflater.from(themeContext).inflate(R.layout.layout_floating_widget, null)
            
            // UI References
            val collapsedContainer = floatingView?.findViewById<android.view.View>(R.id.collapsed_container)
            // For backward compatibility logic: strictly speaking we use container now
            val expandedContainer = floatingView?.findViewById<android.view.View>(R.id.expanded_container)
            val statusText = floatingView?.findViewById<android.widget.TextView>(R.id.tv_status)
            
            val btnClose = floatingView?.findViewById<android.view.View>(R.id.btnClose)
            val btnPlayPause = floatingView?.findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
            val btnStop = floatingView?.findViewById<android.widget.ImageButton>(R.id.btnStop)
            val btnSettings = floatingView?.findViewById<android.view.View>(R.id.btnSettings)
            
            // 1. Expand Logic (Click Floating Ball/Container)
            collapsedContainer?.setOnClickListener {
                collapsedContainer.visibility = android.view.View.GONE
                expandedContainer?.visibility = android.view.View.VISIBLE
            }

            // 2. Collapse Logic (Click X)
            btnClose?.setOnClickListener {
                collapsedContainer?.visibility = android.view.View.VISIBLE
                expandedContainer?.visibility = android.view.View.GONE
            }

            // Long Press X to Remove Widget completely
            btnClose?.setOnLongClickListener {
                if (floatingView != null) {
                    windowManager?.removeView(floatingView)
                    floatingView = null
                    showToast("懸浮窗已關閉")
                }
                true
            }

            // Long Press Ball to Remove Widget (optional convenience)
            collapsedContainer?.setOnLongClickListener {
                if (floatingView != null) {
                    windowManager?.removeView(floatingView)
                    floatingView = null
                    showToast("懸浮窗已關閉")
                }
                true
            }
            
            // 3. Play/Pause
            btnPlayPause?.setOnClickListener {
                if (isScriptRunning) {
                    stopExecution()
                    btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                    isScriptRunning = false
                } else {
                    // Auto-collapse to reveal the HUD (Status Text)
                    collapsedContainer?.visibility = android.view.View.VISIBLE
                    expandedContainer?.visibility = android.view.View.GONE
                    
                    // Check if we have a script to run
                    val scriptId = getScriptId()
                    if (scriptId.isNullOrEmpty() || scriptId == "null") {
                        val msg = "⚠️ 未指定，請先在 App 選定腳本"
                        updateStatus(msg)
                        showToast(msg)
                        return@setOnClickListener
                    }
                    
                    loadAndExecuteScript()
                    btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                    isScriptRunning = true
                }
            }
            
            // 4. Stop
            btnStop?.setOnClickListener {
                 stopExecution()
                 btnPlayPause?.setImageResource(android.R.drawable.ic_media_play)
                 isScriptRunning = false
                 showToast("已停止")
                 
                 collapsedContainer?.visibility = android.view.View.VISIBLE
                 expandedContainer?.visibility = android.view.View.GONE
            }
            
            // 5. Settings (Open Activity)
            btnSettings?.setOnClickListener {
                // Collapse first
                collapsedContainer?.visibility = android.view.View.VISIBLE
                expandedContainer?.visibility = android.view.View.GONE
                
                // Launch Activity for Settings
                val intent = android.content.Intent(this, MainActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                intent.action = "OPEN_SETTINGS"
                startActivity(intent)
            }

            // 6. Record Mode Logic
            val btnRecord = floatingView?.findViewById<android.view.View>(R.id.btnRecord)
            val recordingContainer = floatingView?.findViewById<android.view.View>(R.id.recording_container)
            val btnStopRecording = floatingView?.findViewById<android.view.View>(R.id.btnStopRecording)

            btnRecord?.setOnClickListener {
                if (isScriptRunning) stopExecution()
                expandedContainer?.visibility = android.view.View.GONE
                recordingContainer?.visibility = android.view.View.VISIBLE
                recordingManager.startRecording()
                updateStatus("自動錄製中...")
                showToast("🔴 開始自動錄製")
                showTouchCaptureOverlay(recordingContainer)
            }

            btnStopRecording?.setOnClickListener {
                if (touchCaptureOverlay != null) {
                    try {
                        windowManager?.removeView(touchCaptureOverlay)
                        touchCaptureOverlay = null
                    } catch (e: Exception) {}
                }
                val scriptJson = recordingManager.stopRecording()
                recordingContainer?.visibility = android.view.View.GONE
                showSaveRecordingDialog(scriptJson)
            }
            
            // 7. Drag Logic (Only on Collapsed View for better UX)
            collapsedContainer?.setOnTouchListener(object : android.view.View.OnTouchListener {
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
                            return true // Consume event
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            val Xdiff = (event.rawX - initialTouchX).toInt()
                            val Ydiff = (event.rawY - initialTouchY).toInt()

                            // If drag was small, treat as click
                            if (Math.abs(Xdiff) < 10 && Math.abs(Ydiff) < 10) {
                                v?.performClick()
                            }
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
            Log.i(TAG, "懸浮窗已添加")
            
        } catch (e: Exception) {
            Log.e(TAG, "建立懸浮窗失敗: ${e.message}", e)
        }
    }
    
    fun forceStop(reason: String) {
        showToast(reason)
        stopExecution()
        
        android.os.Handler(mainLooper).post {
            val btnPlayPause = floatingView?.findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
            btnPlayPause?.setImageResource(android.R.drawable.ic_media_play)
            isScriptRunning = false
        }
    }

    private var captureOverlayView: android.view.View? = null
    private var touchCaptureOverlay: android.view.View? = null

    private fun showTouchCaptureOverlay(recordingContainer: android.view.View?) {
        android.os.Handler(mainLooper).post {
            try {
                if (touchCaptureOverlay != null) return@post

                val layoutParams = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        android.view.WindowManager.LayoutParams.TYPE_PHONE,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT
                )

                // Create a completely transparent view to intercept touches
                val interceptView = android.view.View(this)
                interceptView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

                interceptView.setOnTouchListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        val touchX = event.rawX.toInt()
                        val touchY = event.rawY.toInt()

                        // 1. Temporarily hide the overlay so we don't screenshot it (even though it's transparent, it's safer)
                        // Actually, since we need to dispatch a real click to the game beneath, we MUST hide it.
                        interceptView.visibility = android.view.View.GONE
                        
                        // 2. Wait a tiny bit for the UI to settle, then capture screen and dispatch real click
                        android.os.Handler(mainLooper).postDelayed({
                            captureScreen { bitmap ->
                                if (bitmap != null) {
                                    recordingManager.addStep(bitmap, touchX, touchY)
                                    bitmap.recycle()
                                    
                                    // 3. Dispatch real click to the game
                                    dispatchRealClick(touchX.toFloat(), touchY.toFloat())
                                    
                                    // 4. Show the overlay again after a delay allowing game to react safely
                                    // We wait 500ms before putting the glass back to avoid intercepting multi-touch or double clicks too quickly
                                    android.os.Handler(mainLooper).postDelayed({
                                        interceptView.visibility = android.view.View.VISIBLE
                                    }, 500)
                                } else {
                                    showToast("自動截圖失敗")
                                    interceptView.visibility = android.view.View.VISIBLE
                                }
                            }
                        }, 50)
                    }
                    true // Consume touch
                }

                touchCaptureOverlay = interceptView
                windowManager?.addView(touchCaptureOverlay, layoutParams)
            } catch (e: Exception) {
                Log.e(TAG, "顯示全螢幕觸控攔截層失敗: ${e.message}", e)
                showToast("開啟自動錄製層失敗")
            }
        }
    }

    private fun dispatchRealClick(x: Float, y: Float) {
        val path = android.graphics.Path()
        path.moveTo(x, y)
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100)
        val builder = android.accessibilityservice.GestureDescription.Builder()
        builder.addStroke(stroke)
        try {
            dispatchGesture(builder.build(), null, null)
            Log.i(TAG, "已穿透點擊: ($x, $y)")
        } catch (e: Exception) {
            Log.e(TAG, "穿透點擊失敗", e)
        }
    }

    private fun showSaveRecordingDialog(scriptJson: String) {
        android.os.Handler(mainLooper).post {
            try {
                val themeContext = androidx.appcompat.view.ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light)
                val builder = android.app.AlertDialog.Builder(themeContext)
                builder.setTitle("儲存錄製腳本")

                val layout = android.widget.LinearLayout(themeContext)
                layout.orientation = android.widget.LinearLayout.VERTICAL
                val padding = (16 * resources.displayMetrics.density).toInt()
                layout.setPadding(padding, padding, padding, padding)

                val editTitle = android.widget.EditText(themeContext)
                editTitle.hint = "腳本標題"
                editTitle.setText("錄製腳本 " + java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()))
                layout.addView(editTitle)

                val editDesc = android.widget.EditText(themeContext)
                editDesc.hint = "腳本描述"
                editDesc.setText("由 Android App 自動錄製產生的腳本")
                layout.addView(editDesc)

                val checkPublic = android.widget.CheckBox(themeContext)
                
                // Get user plan to enforce visibility rules
                val userPlan = getSharedPreferences("GameAutoEditor", MODE_PRIVATE).getString("user_plan", "free") ?: "free"
                val canChoosePrivate = userPlan == "go" || userPlan == "pro" || userPlan == "studio" || userPlan == "enterprise"
                
                if (canChoosePrivate) {
                    checkPublic.text = "公開腳本 (分享至社群)"
                    checkPublic.isChecked = true // 預設公開
                } else {
                    checkPublic.text = "公開腳本 (免費方案必須公開)"
                    checkPublic.isChecked = true
                    checkPublic.isEnabled = false
                }
                
                layout.addView(checkPublic)

                builder.setView(layout)

                builder.setPositiveButton("儲存並上傳") { dialog, _ ->
                    val title = editTitle.text.toString().takeIf { it.isNotEmpty() } ?: "未命名腳本"
                    val desc = editDesc.text.toString()
                    val isPublic = checkPublic.isChecked
                    val visibility = if (isPublic) 1 else 0 // 1 public, 0 private

                    val collapsedContainer = floatingView?.findViewById<android.view.View>(R.id.collapsed_container)
                    collapsedContainer?.visibility = android.view.View.VISIBLE
                    updateStatus("錄製完成")
                    showToast("正在上傳腳本...")

                    uploadRecordedScript(scriptJson, title, desc, visibility)
                    dialog.dismiss()
                }
                
                builder.setNegativeButton("取消") { dialog, _ ->
                    dialog.dismiss()
                    val collapsedContainer = floatingView?.findViewById<android.view.View>(R.id.collapsed_container)
                    collapsedContainer?.visibility = android.view.View.VISIBLE
                    updateStatus("已取消儲存")
                }

                builder.setOnCancelListener {
                    val collapsedContainer = floatingView?.findViewById<android.view.View>(R.id.collapsed_container)
                    collapsedContainer?.visibility = android.view.View.VISIBLE
                    updateStatus("已取消儲存")
                }

                val dialog = builder.create()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                } else {
                    dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_PHONE)
                }
                dialog.show()

            } catch (e: Exception) {
                Log.e(TAG, "顯示儲存對話框失敗", e)
                showToast("顯示對話框失敗，將使用預設設定上傳")
                
                val collapsedContainer = floatingView?.findViewById<android.view.View>(R.id.collapsed_container)
                collapsedContainer?.visibility = android.view.View.VISIBLE
                
                uploadRecordedScript(scriptJson, "錄製腳本 " + java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()), "由 Android App 自動錄製產生的腳本", 1)
            }
        }
    }

    private fun uploadRecordedScript(scriptJson: String, title: String, description: String, visibility: Int) {
        val userToken = getUserToken()
        if (userToken.isNullOrEmpty()) {
            showToast("未登入，無法上傳錄製腳本")
            return
        }

        Thread {
            try {
                val url = java.net.URL("https://game-auto-ai.vercel.app/api/scripts")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $userToken")

                val body = JSONObject().apply {
                    put("name", title)
                    put("description", description)
                    put("version", "1.0.0")
                    put("script_content", scriptJson)
                    put("visibility", if (visibility == 1) "public" else "private")
                }

                val os = java.io.OutputStreamWriter(conn.outputStream)
                os.write(body.toString())
                os.flush()
                os.close()

                if (conn.responseCode == 200 || conn.responseCode == 201) {
                    android.os.Handler(mainLooper).post {
                        showToast("🎉 錄製腳本上傳成功！")
                    }
                } else {
                    android.os.Handler(mainLooper).post {
                        showToast("❌ 上傳失敗: HTTP ${conn.responseCode}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "上傳腳本錯誤: ${e.message}", e)
                android.os.Handler(mainLooper).post {
                    showToast("❌ 上傳時發生錯誤")
                }
            }
        }.start()
    }

    private fun stopExecution() {
        scriptEngine.stop()
        sceneGraphEngine.stop()
        updateStatus("已停止") // Show stopped status instead of hiding it
        Log.i(TAG, "使用者已停止執行")
    }

    private fun loadAndExecuteScript() {
        // Record the app where the script was started
        recordScriptOrigin()

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

    fun getLicenseKey(): String? {
        val prefs = getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
        // Check for 'license_key' (standard) or legacy keys
        return prefs.getString("license_key", null)
    }

    fun getUserToken(): String? {
        val prefs = getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
        val token = prefs.getString("user_token", null)
        if (token == "null" || token?.trim()?.isEmpty() == true) return null
        return token
    }

    fun refreshTokenSync(): Boolean {
        val prefs = getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
        val refreshToken = prefs.getString("refresh_token", null)
        if (refreshToken.isNullOrEmpty()) return false
        
        try {
            val url = java.net.URL("https://game-auto-ai.vercel.app/api/auth/refresh-token")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            
            val jsonBody = org.json.JSONObject()
            jsonBody.put("refresh_token", refreshToken)
            
            val os = java.io.OutputStreamWriter(conn.outputStream)
            os.write(jsonBody.toString())
            os.flush()
            os.close()
            
            if (conn.responseCode == 200) {
                val resp = conn.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(resp)
                val session = json.optJSONObject("session")
                if (session != null) {
                    val newAccessToken = session.getString("access_token")
                    val newRefreshToken = session.optString("refresh_token", refreshToken)
                    prefs.edit()
                        .putString("user_token", newAccessToken)
                        .putString("refresh_token", newRefreshToken)
                        .apply()
                    Log.i(TAG, "♻️ Token 刷新成功！")
                    return true
                }
            } else {
                Log.e(TAG, "♻️ Token 刷新失敗: HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "♻️ Token 刷新錯誤", e)
        }
        return false
    }

    fun getAppDeviceId(): String? {
        val prefs = getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
        // Check for saved 'device_id' (Trusted from Installer or Main Boot)
        val savedId = prefs.getString("device_id", null)
        if (!savedId.isNullOrEmpty()) return savedId
        
        // Fallback to Android ID
        val systemId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        val fallbackId = systemId ?: java.util.UUID.randomUUID().toString()
        prefs.edit().putString("device_id", fallbackId).apply()
        return fallbackId
    }
    
    fun loadScriptFromNetwork(scriptIdOrUrl: String) {
        if (scriptIdOrUrl.isEmpty() || scriptIdOrUrl == "null") {
            Log.e(TAG, "❌ 無效的腳本 ID/URL")
            return
        }

        Thread {
            try {
                // 支援直接輸入網址 (HTTP/HTTPS) 或 ID
                var urlString = if (scriptIdOrUrl.startsWith("http")) {
                    scriptIdOrUrl
                } else {
                    "https://game-auto-ai.vercel.app/api/get-script?id=$scriptIdOrUrl"
                }

                Log.d(TAG, "正在下載腳本: $urlString")
                
                var connection = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false // Manual redirect handling to capture headers

                // Add Authorization Header from Preferences
                val token = getUserToken()
                if (!token.isNullOrEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                }

                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                var responseCode = connection.responseCode
                
                // 1. Capture Plan Header from API (Before Redirect)
                var userPlan = connection.getHeaderField("X-User-Plan")?.lowercase() ?: "free"
                Log.d(TAG, "📋 User Plan detected: $userPlan")

                // 2. Handle Redirect (302 Found) - Common for R2 Blob Storage
                if (responseCode == java.net.HttpURLConnection.HTTP_MOVED_TEMP || responseCode == java.net.HttpURLConnection.HTTP_MOVED_PERM) {
                    val location = connection.getHeaderField("Location")
                    Log.d(TAG, "➡️ Redirecting to: $location")
                    connection.disconnect()
                    
                    if (location != null) {
                        connection = java.net.URL(location).openConnection() as java.net.HttpURLConnection
                        // No Auth header needed for R2 public/signed url usually, but keep if needed? 
                        // Actually R2 signed URLs don't need Bearer token.
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
                        responseCode = connection.responseCode
                    }
                }

                if (responseCode == 200) {
                    val scriptJson = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()
                    
                    // Cache script
                    try {
                        openFileOutput("cached_script.json", MODE_PRIVATE).use {
                            it.write(scriptJson.toByteArray())
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Cache script failed", e)
                    }
                    
                    // Main Thread Execution
                    android.os.Handler(mainLooper).post {
                        Log.i(TAG, "✅ 網路腳本載入成功 (Plan: $userPlan)")
                        
                        Log.i(TAG, "🎬 準備播放廣告...")
                        pendingScriptJson = scriptJson
                        pendingScriptId = scriptIdOrUrl
                        pendingUserPlan = userPlan
                        
                        val adIntent = android.content.Intent(this@AutomationService, AdActivity::class.java)
                        adIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        // Optional: clear top so it doesnt stack
                        adIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(adIntent)
                    }
                } else {
                    Log.e(TAG, "❌ 網路載入失敗，HTTP $responseCode")
                    if (responseCode == 401 || responseCode == 403) {
                        android.os.Handler(mainLooper).post {
                            forceStop("登入驗證失敗或已過期，請返回首頁重新登入")
                        }
                    } else {
                        android.os.Handler(mainLooper).post {
                            loadScriptFromAssets()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 網路請求錯誤: ${e.message}", e)
                android.os.Handler(mainLooper).post {
                    loadScriptFromAssets()
                }
            }
        }.start()
    }
    
    // Extracted shared execution logic to avoid code duplication
    private fun executeScriptJson(scriptJson: String, scriptIdOrUrl: String, userPlan: String = "free") {
        // Unified Engine Routing
        if (scriptJson.contains("\"nodes\"") && scriptJson.contains("\"edges\"")) {
             // Use FSM
             // Hot Reload Check: Same ID + Running = Update Graph Only
             if (sceneGraphEngine.currentStatus == "running" && sceneGraphEngine.scriptId == scriptIdOrUrl) {
                 // Plan Gate: Pro / Studio / Enterprise
                 if (userPlan == "pro" || userPlan == "studio" || userPlan == "enterprise") {
                    Log.i(TAG, "🔥 觸發熱更新 (Hot Reload) - Plan Valid")
                    sceneGraphEngine.updateScriptGraph(scriptJson)
                 } else {
                    Log.w(TAG, "⚠️ 熱更新僅限 Pro 以上方案 (當前: $userPlan)，執行標準重啟")
                    showToast("🔥 熱更新為 Pro/Studio 專屬功能，正在重新啟動...")
                    // Stop first to ensure clean state
                    sceneGraphEngine.stop()
                    // Small delay to allow stop to process
                    android.os.Handler(mainLooper).postDelayed({
                        sceneGraphEngine.start(scriptJson, scriptIdOrUrl)
                    }, 500)
                 }
             } else {
                 sceneGraphEngine.start(scriptJson, scriptIdOrUrl)
             }
        } else {
             scriptEngine.executeScript(scriptJson)
        }
    }
    
    private fun loadScriptFromAssets() {
        try {
            val inputStream = assets.open("script.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val scriptJson = reader.readText()
            reader.close()
            
            Log.i(TAG, "📄 Assets 腳本載入成功")
            
            showToast("開始執行 (Assets)")
            // Unified Routing
            scriptEngine.executeScript(scriptJson)
            /*
            if (scriptJson.contains("\"nodes\"")) {
                sceneGraphEngine.start(scriptJson)
            } else {
                scriptEngine.executeScript(scriptJson)
            }
            */
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 載入 assets 腳本失敗: ${e.message}", e)
            showToast("找不到腳本檔案")
            // Reset UI
            val btnPlayPause = floatingView?.findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
            btnPlayPause?.setImageResource(android.R.drawable.ic_media_play)
            isScriptRunning = false
        }
    }
    
    private var lastForegroundPackage: String? = null
    private var scriptOriginPackage: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Capture package name from events to track foreground app
        if (event?.packageName != null) {
            val pkg = event.packageName.toString()
            
            // Ignore valid system packages or self
            // com.android.systemui = Notification shade / Status bar (Allow tracking this? No, we don't want to switch back if user pulls status bar temporarily? Actually we DO want to switch back if they stay there. But SystemUI usually overlays. Let's ignore it.)
            if (pkg != packageName && pkg != "com.android.systemui" && !pkg.contains("inputmethod")) {
                lastForegroundPackage = pkg
                
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    Log.v(TAG, "📱 前台應用變更: $pkg")
                }
            }
        }
    }

    fun getOriginPackageName(): String? {
        return scriptOriginPackage
    }

    fun getFgPackageName(): String? {
        return lastForegroundPackage
    }

    // Call this when script starts
    private fun recordScriptOrigin() {
        // Fallback: Try to get from root window if null
        if (lastForegroundPackage == null) {
             try {
                val root = rootInActiveWindow
                if (root != null && root.packageName != null) {
                    val pkg = root.packageName.toString()
                    if (pkg != packageName) {
                        lastForegroundPackage = pkg
                    }
                }
             } catch(e: Exception) {}
        }

        scriptOriginPackage = lastForegroundPackage
        Log.i(TAG, "🔒 已鎖定原始遊戲包名: $scriptOriginPackage")
        if (scriptOriginPackage == null) {
            showToast("⚠️ 無法偵測原始遊戲，請手動確認")
        } else {
            // Optional: Show toast to confirm
            // showToast("🔒 鎖定遊戲: $scriptOriginPackage")
        }
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "⚠️ 服務已中斷")
        stopExecution()
    }
    
    override fun onDestroy() {
        if (::sceneGraphEngine.isInitialized) sceneGraphEngine.stop() // Ensure clean stop
        fleetManager?.cleanup()
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
            try {
                Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Toast 顯示失敗: ${e.message}")
            }
            // Fallback for devices with disabled notifications (e.g. Suppressing toast from package)
            // Sync critical feedback to the floating widget's status bar
            updateStatus(message)
        }
    }

    fun updateStatus(text: String?) {
        android.os.Handler(mainLooper).post {
            val statusText = floatingView?.findViewById<android.widget.TextView>(R.id.tv_status)
            if (text.isNullOrEmpty()) {
                statusText?.visibility = android.view.View.GONE
            } else {
                statusText?.text = text
                statusText?.visibility = android.view.View.VISIBLE
            }
        }
    }

    /**
     * Trigger AI Auto-Growth when FSM is completely lost.
     */
    fun triggerAutoGrowth(bitmap: Bitmap, scriptId: String) {
        val base64 = bitmapToBase64(bitmap)
        val urlStr = "https://game-auto-ai.vercel.app/api/ai-auto-flow"
        
        Thread {
            try {
                sceneGraphEngine.remoteLog("INFO", "🤖 呼叫 AI 自我成長中...")
                updateStatus("🤖 呼叫 AI 自我成長中...")
                showToast("🤖 AI 分析中，請稍候...")
                
                // Check mode
                val mode = sceneGraphEngine.autoGrowthMode
                val base64: String
                if (mode == "creator") {
                    // Creator mode: clean screenshot for accurate JSON generation
                    base64 = bitmapToBase64(bitmap)
                    sceneGraphEngine.remoteLog("INFO", "🔍 啟動造物主模式 (Creator) - 傳送原始截圖分析中...")
                } else {
                    // Navigator mode (default): Draw a 10x10 Set-of-Mark coordinate grid
                    val gridBitmap = bitmap.drawCoordinateGrid()
                    base64 = bitmapToBase64(gridBitmap)
                    gridBitmap.recycle()
                    sceneGraphEngine.remoteLog("INFO", "🧭 啟動導航模式 (Navigator) - 傳送輔助網格截圖分析中...")
                }
                
                val url = java.net.URL(BuildConfig.AI_API_URL + "/api/ai-auto-flow")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-api-secret", BuildConfig.AI_API_SECRET)
                
                val userToken = getUserToken()
                val licenseKey = getLicenseKey()
                val deviceId = getAppDeviceId()

                if (!userToken.isNullOrEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer $userToken")
                    if (!deviceId.isNullOrEmpty()) conn.setRequestProperty("x-device-id", deviceId)
                } else if (!licenseKey.isNullOrEmpty()) {
                    conn.setRequestProperty("x-license-key", licenseKey)
                }

                conn.connectTimeout = 5000
                conn.readTimeout = 30000 // Give AI more time
                
                val jsonBody = JSONObject()
                jsonBody.put("scriptId", scriptId)
                jsonBody.put("imageBase64", base64)
                jsonBody.put("mode", mode)
                
                val objective = sceneGraphEngine.autoGrowthObjective
                if (!objective.isNullOrEmpty()) {
                    jsonBody.put("objective", objective)
                }

                val os = java.io.OutputStreamWriter(conn.outputStream)
                os.write(jsonBody.toString())
                os.flush()
                os.close()

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader().readText()
                    val respJson = JSONObject(resp)
                    
                    if (respJson.optBoolean("success", false)) {
                        val msg = respJson.optString("message", "成功")
                        
                        if (mode == "creator") {
                            // Creator Mode: expects full graphData to overwrite local script
                            val updatedGraph = respJson.optJSONObject("graphData")
                            if (updatedGraph != null) {
                                sceneGraphEngine.remoteLog("INFO", "🤖 AI 造物主成功: $msg")
                                showToast("✅ $msg")
                                sceneGraphEngine.updateScriptGraph(updatedGraph.toString())
                            } else {
                                sceneGraphEngine.remoteLog("WARN", "🤖 AI 回傳格式錯誤: 缺少 graphData")
                            }
                        } else {
                            // Navigator Mode: expects actions array
                            val actions = respJson.optJSONArray("actions")
                            if (actions != null && actions.length() > 0) {
                                sceneGraphEngine.remoteLog("INFO", "🤖 AI 導航成功: $msg (共 ${actions.length()} 個動作)")
                                showToast("✅ AI: 嘗試脫困")
                                sceneGraphEngine.executeNavigationSequence(actions)
                            } else {
                                sceneGraphEngine.remoteLog("WARN", "🤖 AI 回傳格式錯誤: 缺少 actions 陣列")
                            }
                        }
                    } else {
                        sceneGraphEngine.remoteLog("WARN", "🤖 AI 無法提供協助: ${respJson.optString("message")}")
                        showToast("🤖 AI 無法辨識此場景")
                    }
                } else {
                    sceneGraphEngine.remoteLog("ERROR", "🤖 AI Auto-Growth API 失敗: HTTP ${conn.responseCode}")
                }
            } catch (e: Exception) {
                sceneGraphEngine.remoteLog("ERROR", "🤖 AI Auto-Growth 連線錯誤: ${e.message}")
            } finally {
                sceneGraphEngine.resume()
            }
        }.start()
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        return android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /**
     * Draws a 10x10 Set-of-Mark coordinate grid on the provided bitmap.
     * This dramatically improves AI spatial reasoning (e.g. GPT-4o Vision)
     * by allowing it to read the exact X/Y percentage coordinates from the image itself.
     */
    private fun Bitmap.drawCoordinateGrid(): Bitmap {
        val mutableBitmap = this.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(mutableBitmap)
        val paintLine = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(128, 0, 255, 0) // Semi-transparent green
            strokeWidth = 2f
            style = android.graphics.Paint.Style.STROKE
        }
        val paintTextBg = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(200, 0, 0, 0) // Dark background for text
            style = android.graphics.Paint.Style.FILL
        }
        val paintText = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val highlightLine = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(180, 255, 0, 0) // Semi-transparent red for 50% marks
            strokeWidth = 3f
            style = android.graphics.Paint.Style.STROKE
        }

        val width = mutableBitmap.width
        val height = mutableBitmap.height

        // Draw Vertical Lines (X-axis percentages)
        for (i in 1..9) {
            val currentX = width * (i / 10f)
            val isCenter = i == 5
            canvas.drawLine(currentX, 0f, currentX, height.toFloat(), if (isCenter) highlightLine else paintLine)
            
            // Draw label at top and bottom
            val text = "${i}0"
            val textYTop = 30f
            val textYBottom = height - 10f
            
            canvas.drawRect(currentX - 15f, textYTop - 24f, currentX + 15f, textYTop + 6f, paintTextBg)
            canvas.drawText(text, currentX, textYTop, paintText)
            
            canvas.drawRect(currentX - 15f, textYBottom - 24f, currentX + 15f, textYBottom + 6f, paintTextBg)
            canvas.drawText(text, currentX, textYBottom, paintText)
        }

        // Draw Horizontal Lines (Y-axis percentages)
        for (i in 1..9) {
            val currentY = height * (i / 10f)
            val isCenter = i == 5
            canvas.drawLine(0f, currentY, width.toFloat(), currentY, if (isCenter) highlightLine else paintLine)
            
            // Draw label at left and right
            val text = "${i}0"
            val textXLeft = 20f
            val textXRight = width - 20f
            
            canvas.drawRect(textXLeft - 20f, currentY - 12f, textXLeft + 20f, currentY + 12f, paintTextBg)
            canvas.drawText(text, textXLeft, currentY + 8f, paintText)
            
            canvas.drawRect(textXRight - 20f, currentY - 12f, textXRight + 20f, currentY + 12f, paintTextBg)
            canvas.drawText(text, textXRight, currentY + 8f, paintText)
        }

        return mutableBitmap
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
            Log.e(TAG, "截圖逾時")
        }
        return result
    }
    /**
     * Expose root node for Structural Perception
     */
    fun getRootNode(): android.view.accessibility.AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        }
    }
}
