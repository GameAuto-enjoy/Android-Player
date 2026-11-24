package com.gameautoeditor.player

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import android.graphics.Bitmap
import org.json.JSONObject
import kotlin.concurrent.thread

class AutomationService : AccessibilityService() {
    
    private val TAG = "GameAuto"
    private lateinit var sceneGraphEngine: SceneGraphEngine
    private lateinit var scriptEngine: ScriptEngine
    private lateinit var recordingManager: RecordingManager
    private var fleetManager: FleetSyncManager? = null
    
    private var windowManager: android.view.WindowManager? = null
    private var floatingView: android.view.View? = null
    private var floatingLayoutParams: android.view.WindowManager.LayoutParams? = null
    private var isScriptRunning = false
    private val recordingExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile
    private var recordingSessionId: Long = 0L
    @Volatile
    private var isRecordingCaptureBusy: Boolean = false
    @Volatile
    private var lastScreenshotRequestMs: Long = 0L
    private var recModeUpdater: ((String) -> Unit)? = null
    
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

            if (intent?.action == "com.gameautoeditor.UPDATE_RUNTIME_VARS") {
                val customMaxLoops = intent.getIntExtra("custom_max_loops", 0)
                val varsJson = JSONObject().apply {
                    put("custom_max_loops", customMaxLoops)
                }
                if (::sceneGraphEngine.isInitialized) {
                    sceneGraphEngine.updateVariablesFromRemote(varsJson)
                }
                val tvHudLoop = floatingView?.findViewById<android.widget.TextView>(R.id.tvHudLoopValue)
                tvHudLoop?.text = if (customMaxLoops <= 0) "無限" else "${customMaxLoops}回"
                return
            }

            if (intent?.action == "com.gameautoeditor.UPDATE_TASK_CONFIG") {
                if (::sceneGraphEngine.isInitialized) {
                    sceneGraphEngine.reloadTaskConfig()
                }
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
                
                // Clear cache immediately
                pendingScriptJson = null
                pendingScriptId = null
                
                if (scriptJson != null) {
                    Log.i(TAG, "自動跳轉回目標應用程式...")
                    try {
                        val originPkg = getOriginPackageName()
                        if (originPkg != null) {
                            val launchIntent = packageManager.getLaunchIntentForPackage(originPkg)
                            if (launchIntent != null) {
                                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                startActivity(launchIntent)
                            } else {
                                Log.w(TAG, "找不到目標應用程式 ($originPkg) 的啟動 Intent")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "無法切換回目標應用程式", e)
                    }

                    Log.i(TAG, "開始非同步啟動執行緩存的腳本...")
                    android.os.Handler(mainLooper).postDelayed({
                        try {
                            executeScriptJson(scriptJson, scriptIdOrUrl, plan)
                            isScriptRunning = true
                            val btnPlayPause = floatingView?.findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
                            btnPlayPause?.setImageResource(android.R.drawable.ic_media_pause)
                        } catch (t: Throwable) {
                            Log.e(TAG, "執行緩存腳本失敗", t)
                            showToast("執行腳本失敗")
                            isScriptRunning = false
                        }
                    }, 500)
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
        filter.addAction("com.gameautoeditor.UPDATE_RUNTIME_VARS")
        filter.addAction("com.gameautoeditor.UPDATE_TASK_CONFIG")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
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

    private fun resolveOverlayWindowType(): Int {
        val canDrawOverlays = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                android.provider.Settings.canDrawOverlays(this)
            } catch (_: Exception) {
                true
            }
        } else {
            true
        }
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (canDrawOverlays) android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            if (canDrawOverlays) android.view.WindowManager.LayoutParams.TYPE_PHONE
            else android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
    }

    private fun getApiBaseUrl(): String {
        val prefs = getSharedPreferences("GameAutoPrefs", MODE_PRIVATE)
        val editorPrefs = getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
        return (prefs.getString("server_url", null)?.takeIf { it.isNotBlank() }
            ?: editorPrefs.getString("server_url", null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.AI_API_URL).trimEnd('/')
    }

    private fun initFloatingWindow() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            
            val layoutParams = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                resolveOverlayWindowType(),
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            
            layoutParams.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            layoutParams.x = 0
            layoutParams.y = 100
            floatingLayoutParams = layoutParams
            
            // Fix: Use ContextThemeWrapper to provide AppCompat Theme for AppCompat Widgets
            val themeContext = androidx.appcompat.view.ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Light)
            floatingView = android.view.LayoutInflater.from(themeContext).inflate(R.layout.layout_floating_widget, null)
            
            // UI References
            val collapsedContainer = floatingView?.findViewById<android.view.View>(R.id.collapsed_container)
            // For backward compatibility logic: strictly speaking we use container now
            val expandedContainer = floatingView?.findViewById<android.view.View>(R.id.expanded_container)
            val drawerContainer = floatingView?.findViewById<android.view.View>(R.id.drawer_container)
            val statusText = floatingView?.findViewById<android.widget.TextView>(R.id.tv_status)
            
            val btnClose = floatingView?.findViewById<android.view.View>(R.id.btnClose)
            val btnPlayPause = floatingView?.findViewById<android.widget.ImageButton>(R.id.btnPlayPause)
            val btnStop = floatingView?.findViewById<android.widget.ImageButton>(R.id.btnStop)
            val btnSettings = floatingView?.findViewById<android.view.View>(R.id.btnSettings)
            val btnToggleDrawer = floatingView?.findViewById<android.widget.Button>(R.id.btnToggleDrawer)
            val btnHudLoopMinus = floatingView?.findViewById<android.widget.Button>(R.id.btnHudLoopMinus)
            val btnHudLoopPlus = floatingView?.findViewById<android.widget.Button>(R.id.btnHudLoopPlus)
            val tvHudLoopValue = floatingView?.findViewById<android.widget.TextView>(R.id.tvHudLoopValue)
            val btnPushScreenToWeb = floatingView?.findViewById<android.widget.Button>(R.id.btnPushScreenToWeb)
            
            // 1. Expand Logic (Click Floating Ball/Container)
            collapsedContainer?.setOnClickListener {
                collapsedContainer.visibility = android.view.View.GONE
                expandedContainer?.visibility = android.view.View.VISIBLE
            }

            // 2. Collapse Logic (Click X)
            btnClose?.setOnClickListener {
                collapsedContainer?.visibility = android.view.View.VISIBLE
                expandedContainer?.visibility = android.view.View.GONE
                drawerContainer?.visibility = android.view.View.GONE
            }

            // 2b. Floating Control Drawer Toggle & In-Game Controls
            val prefs = getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
            fun syncHudLoopLabel() {
                val loops = prefs.getInt("custom_max_loops", 0)
                tvHudLoopValue?.text = if (loops <= 0) "無限" else "${loops}回"
            }
            syncHudLoopLabel()

            btnToggleDrawer?.setOnClickListener {
                if (drawerContainer?.visibility == android.view.View.VISIBLE) {
                    drawerContainer.visibility = android.view.View.GONE
                } else {
                    syncHudLoopLabel()
                    drawerContainer?.visibility = android.view.View.VISIBLE
                }
            }

            btnHudLoopMinus?.setOnClickListener {
                val cur = prefs.getInt("custom_max_loops", 0)
                val next = if (cur <= 5) 0 else cur - 5
                prefs.edit().putInt("custom_max_loops", next).apply()
                syncHudLoopLabel()
                val varsJson = JSONObject().apply { put("custom_max_loops", next) }
                sceneGraphEngine.updateVariablesFromRemote(varsJson)
            }

            btnHudLoopPlus?.setOnClickListener {
                val cur = prefs.getInt("custom_max_loops", 0)
                val next = cur + 5
                prefs.edit().putInt("custom_max_loops", next).apply()
                syncHudLoopLabel()
                val varsJson = JSONObject().apply { put("custom_max_loops", next) }
                sceneGraphEngine.updateVariablesFromRemote(varsJson)
            }

            btnPushScreenToWeb?.setOnClickListener {
                pushCurrentScreenToWebEditor()
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
                    drawerContainer?.visibility = android.view.View.GONE
                    
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
                 drawerContainer?.visibility = android.view.View.GONE
            }
            
            // 5. Settings (Open Activity)
            btnSettings?.setOnClickListener {
                // Collapse first
                collapsedContainer?.visibility = android.view.View.VISIBLE
                expandedContainer?.visibility = android.view.View.GONE
                drawerContainer?.visibility = android.view.View.GONE
                
                // Launch Activity for Settings
                val intent = android.content.Intent(this, MainActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                intent.action = "OPEN_SETTINGS"
                startActivity(intent)
            }

            // 6. Record Mode Logic ("邊玩邊標記")
            val btnRecord = floatingView?.findViewById<android.view.View>(R.id.btnRecord)
            val recordingContainer = floatingView?.findViewById<android.view.View>(R.id.recording_container)
            val btnStopRecording = floatingView?.findViewById<android.view.View>(R.id.btnStopRecording)
            val btnRecModeClick = floatingView?.findViewById<android.widget.Button>(R.id.btnRecModeClick)
            val btnRecModeCondition = floatingView?.findViewById<android.widget.Button>(R.id.btnRecModeCondition)
            val btnRecModeLoop = floatingView?.findViewById<android.widget.Button>(R.id.btnRecModeLoop)

            fun updateRecModeButtons(mode: String) {
                recordingManager.currentRecordMode = mode
                val activeColor = android.content.res.ColorStateList.valueOf(0xFF4F46E5.toInt())
                val inactiveColor = android.content.res.ColorStateList.valueOf(0xFF334155.toInt())
                btnRecModeClick?.backgroundTintList = if (mode == "click") activeColor else inactiveColor
                btnRecModeCondition?.backgroundTintList = if (mode == "condition") activeColor else inactiveColor
                btnRecModeLoop?.backgroundTintList = if (mode == "loop") activeColor else inactiveColor
            }
            recModeUpdater = { mode -> updateRecModeButtons(mode) }

            btnRecModeClick?.setOnClickListener {
                updateRecModeButtons("click")
                showToast("👆 錄製模式：一般點擊／滑動")
            }

            btnRecModeCondition?.setOnClickListener {
                updateRecModeButtons("condition")
                showToast("👁️ 錄製模式：點選畫面文字／數值條件（錄完自動切回點擊）")
            }

            btnRecModeLoop?.setOnClickListener {
                updateRecModeButtons("loop")
                showToast("🔄 錄製模式：標記循環起點／步驟（結束時自動閉環）")
            }

            btnRecord?.setOnClickListener {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                    showToast("❌ 手機錄製功能需要 Android 11 以上版本")
                    return@setOnClickListener
                }
                if (getUserToken().isNullOrEmpty()) {
                    showToast("⚠️ 請先在 App 登入帳號才能錄製並儲存腳本")
                    return@setOnClickListener
                }
                if (isScriptRunning) {
                    stopExecution()
                    isScriptRunning = false
                    btnPlayPause?.setImageResource(android.R.drawable.ic_media_play)
                }
                recordingSessionId++
                isRecordingCaptureBusy = false
                expandedContainer?.visibility = android.view.View.GONE
                drawerContainer?.visibility = android.view.View.GONE
                recordingContainer?.visibility = android.view.View.VISIBLE

                // Keep expanded recording bar within screen width if user dragged the ball near the right edge
                val (screenW, _) = getRealScreenDimensions()
                val estBarWidthPx = (330f * resources.displayMetrics.density).toInt()
                val maxSafeX = kotlin.math.max(0, screenW - estBarWidthPx)
                if (layoutParams.x > maxSafeX) {
                    layoutParams.x = maxSafeX
                    try {
                        windowManager?.updateViewLayout(floatingView, layoutParams)
                    } catch (_: Exception) {}
                }

                recordingManager.startRecording()
                updateRecModeButtons("click")
                updateStatus("🔴 錄製中 (0 步)")
                showToast("🔴 開始邊玩邊錄製 (可拖曳紅點移動工具列，支援點擊/滑動/條件/循環)")
                showTouchCaptureOverlay(recordingContainer)
            }

            // Allow dragging the recording toolbar out of the way of game buttons via its red handle or bar background
            val ivRecDragHandle = floatingView?.findViewById<android.view.View>(R.id.ivRecDragHandle)
            val recDragListener = object : android.view.View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: android.view.View?, event: android.view.MotionEvent): Boolean {
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            initialX = layoutParams.x
                            initialY = layoutParams.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val (screenW, screenH) = getRealScreenDimensions()
                            val barW = floatingView?.width ?: 0
                            val barH = floatingView?.height ?: 0
                            layoutParams.x = (initialX + (event.rawX - initialTouchX).toInt())
                                .coerceIn(0, kotlin.math.max(0, screenW - barW))
                            layoutParams.y = (initialY + (event.rawY - initialTouchY).toInt())
                                .coerceIn(0, kotlin.math.max(0, screenH - barH))
                            try {
                                windowManager?.updateViewLayout(floatingView, layoutParams)
                            } catch (_: Exception) {}
                            return true
                        }
                        android.view.MotionEvent.ACTION_UP -> return true
                    }
                    return false
                }
            }
            ivRecDragHandle?.setOnTouchListener(recDragListener)
            recordingContainer?.setOnTouchListener(recDragListener)

            btnStopRecording?.setOnClickListener {
                recordingSessionId++
                isRecordingCaptureBusy = false
                if (touchCaptureOverlay != null) {
                    try {
                        windowManager?.removeView(touchCaptureOverlay)
                    } catch (e: Exception) {
                        Log.w(TAG, "移除錄製攔截層失敗: ${e.message}")
                    } finally {
                        touchCaptureOverlay = null
                    }
                }
                floatingView?.visibility = android.view.View.VISIBLE
                recordingContainer?.visibility = android.view.View.GONE
                updateStatus("⏳ 整理錄製步驟中...")

                thread {
                    // Drain any in-flight off-main-thread addStep compression job before finalizing JSON
                    try {
                        if (!recordingExecutor.isShutdown) {
                            recordingExecutor.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "等待錄製步驟收尾逾時或中斷: ${e.message}")
                    }

                    val stepCount = recordingManager.getStepCount()
                    val scriptJson = recordingManager.stopRecording()

                    android.os.Handler(mainLooper).post {
                        if (stepCount <= 0) {
                            collapsedContainer?.visibility = android.view.View.VISIBLE
                            updateStatus("已取消錄製")
                            showToast("⚠️ 尚未錄製任何步驟，已取消儲存")
                        } else {
                            showSaveRecordingDialog(scriptJson)
                        }
                    }
                }
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

    private fun pushCurrentScreenToWebEditor() {
        val deviceId = getAppDeviceId() ?: "UNKNOWN"
        val safeDeviceId = deviceId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(128).ifEmpty { "UNKNOWN" }
        showToast("📸 正在截取當前遊戲畫面...")
        android.os.Handler(mainLooper).post {
            floatingView?.visibility = android.view.View.INVISIBLE
            android.os.Handler(mainLooper).postDelayed({
                captureScreen { bitmap ->
                    android.os.Handler(mainLooper).post {
                        floatingView?.visibility = android.view.View.VISIBLE
                    }
                    if (bitmap == null) {
                        showToast("❌ 截圖失敗，請確認 Android 11+ 無障礙截圖權限")
                        return@captureScreen
                    }
                    thread {
                        val dataUri = try {
                            val baos = java.io.ByteArrayOutputStream()
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, baos)
                            } else {
                                @Suppress("DEPRECATION")
                                bitmap.compress(Bitmap.CompressFormat.WEBP, 92, baos)
                            }
                            val base64Image = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                            "data:image/webp;base64,$base64Image"
                        } catch (e: Exception) {
                            Log.e(TAG, "截圖編碼失敗", e)
                            showToast("❌ 畫面編碼錯誤: ${e.message}")
                            return@thread
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }

                        val uploadBody = JSONObject().apply {
                            put("image", dataUri)
                        }
                        val uploadUrl = "${BuildConfig.AI_API_URL.trimEnd('/')}/api/storage?action=upload"

                        var attempt = 0
                        while (attempt < 2) {
                            attempt++
                            val token = getUserToken()
                            val licenseKey = getLicenseKey()
                            if (token.isNullOrEmpty() && licenseKey.isNullOrEmpty()) {
                                showToast("❌ 請先登入或啟用授權金鑰")
                                return@thread
                            }

                            var conn: java.net.HttpURLConnection? = null
                            try {
                                conn = (java.net.URL(uploadUrl).openConnection() as java.net.HttpURLConnection).apply {
                                    requestMethod = "POST"
                                    doOutput = true
                                    connectTimeout = 10_000
                                    readTimeout = 15_000
                                    setRequestProperty("Content-Type", "application/json")
                                    setRequestProperty("X-Filename", "screenshots/$safeDeviceId.webp")
                                    setRequestProperty("x-device-id", deviceId)
                                    if (!token.isNullOrEmpty()) {
                                        setRequestProperty("Authorization", "Bearer $token")
                                    } else if (!licenseKey.isNullOrEmpty()) {
                                        setRequestProperty("X-Api-Secret", BuildConfig.AI_API_SECRET)
                                        setRequestProperty("x-license-key", licenseKey)
                                    }
                                }
                                conn.outputStream.use { it.write(uploadBody.toString().toByteArray(Charsets.UTF_8)) }

                                val code = conn.responseCode
                                if (code == 401 && !token.isNullOrEmpty() && attempt == 1 && refreshTokenSync(token)) {
                                    continue
                                }
                                if (code in 200..299) {
                                    showToast("✅ 畫面已同步至雲端與網頁編輯器！")
                                } else {
                                    showToast("❌ 畫面同步失敗: HTTP $code")
                                }
                                return@thread
                            } catch (e: Exception) {
                                Log.e(TAG, "上傳畫面失敗", e)
                                showToast("❌ 畫面同步錯誤: ${e.message}")
                                return@thread
                            } finally {
                                conn?.disconnect()
                            }
                        }
                    }
                }
            }, 120)
        }
    }

    private var captureOverlayView: android.view.View? = null
    private var touchCaptureOverlay: android.view.View? = null

    private fun bringFloatingViewToFront() {
        val fv = floatingView ?: return
        val lp = floatingLayoutParams ?: return
        if (fv.windowToken == null) return
        try {
            windowManager?.removeViewImmediate(fv)
        } catch (e: Exception) {
            Log.w(TAG, "暫時移除懸浮列以調整層級失敗: ${e.message}")
            return
        }
        try {
            windowManager?.addView(fv, lp)
        } catch (e: Exception) {
            Log.e(TAG, "重新置頂懸浮工具列失敗: ${e.message}", e)
        }
    }

    private fun isPointInsideFloatingView(rawX: Float, rawY: Float): Boolean {
        val fv = floatingView ?: return false
        if (fv.visibility != android.view.View.VISIBLE || fv.width <= 0 || fv.height <= 0) return false
        val loc = IntArray(2)
        fv.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= (loc[0] + fv.width) &&
            rawY >= loc[1] && rawY <= (loc[1] + fv.height)
    }

    private fun showTouchCaptureOverlay(recordingContainer: android.view.View?) {
        android.os.Handler(mainLooper).post {
            try {
                if (touchCaptureOverlay != null) return@post

                val layoutParams = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    resolveOverlayWindowType(),
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    x = 0
                    y = 0
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        fitInsetsTypes = 0
                    }
                }

                // Transparent full-screen touch capture layer positioned BELOW the recording toolbar
                val interceptView = android.view.View(this)
                interceptView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

                val swipeThresholdPx = kotlin.math.max(60.0, (42f * resources.displayMetrics.density).toDouble())
                var downX = 0f
                var downY = 0f
                var downTimeMs = 0L
                var trackingGesture = false

                interceptView.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            if (isRecordingCaptureBusy || !recordingManager.isRecording) {
                                trackingGesture = false
                                return@setOnTouchListener true
                            }
                            if (isPointInsideFloatingView(event.rawX, event.rawY)) {
                                trackingGesture = false
                                return@setOnTouchListener false
                            }
                            downX = event.rawX
                            downY = event.rawY
                            downTimeMs = android.os.SystemClock.uptimeMillis()
                            trackingGesture = true
                            true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            true
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            trackingGesture = false
                            true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            if (!trackingGesture || isRecordingCaptureBusy || !recordingManager.isRecording) {
                                trackingGesture = false
                                return@setOnTouchListener true
                            }
                            trackingGesture = false

                            val upX = event.rawX
                            val upY = event.rawY
                            val dist = kotlin.math.hypot((upX - downX).toDouble(), (upY - downY).toDouble())
                            val gestureDuration = (android.os.SystemClock.uptimeMillis() - downTimeMs).coerceIn(100L, 1500L)
                            val capturedMode = recordingManager.currentRecordMode
                            val isConditionMode = capturedMode == "condition"
                            val isSwipe = !isConditionMode && dist >= swipeThresholdPx
                            val swipeDuration = gestureDuration.coerceIn(220L, 1200L)
                            val activeSession = recordingSessionId

                            isRecordingCaptureBusy = true

                            // 1. Hide BOTH intercept overlay and floating toolbar so screenshot is 100% clean
                            interceptView.visibility = android.view.View.GONE
                            floatingView?.visibility = android.view.View.INVISIBLE

                            // 2. Respect Android 11+ 1-second takeScreenshot rate limit + compositor flush before capturing
                            val elapsedSinceShot = android.os.SystemClock.elapsedRealtime() - lastScreenshotRequestMs
                            val waitForRateLimitMs = kotlin.math.max(70L, 1050L - elapsedSinceShot)
                            android.os.Handler(mainLooper).postDelayed({
                                if (activeSession != recordingSessionId || !recordingManager.isRecording) {
                                    floatingView?.visibility = android.view.View.VISIBLE
                                    isRecordingCaptureBusy = false
                                    return@postDelayed
                                }

                                captureScreenForRecording(activeSession) { bitmap, errorCode ->
                                    // Restore toolbar right after screenshot capture
                                    floatingView?.visibility = android.view.View.VISIBLE

                                    if (activeSession != recordingSessionId || !recordingManager.isRecording) {
                                        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                                        isRecordingCaptureBusy = false
                                        return@captureScreenForRecording
                                    }

                                    if (bitmap == null) {
                                        // Still forward physical click/swipe so the game never feels frozen on secure screens
                                        if (!isConditionMode) {
                                            if (isSwipe) {
                                                dispatchRealSwipe(downX, downY, upX, upY, swipeDuration)
                                            } else {
                                                dispatchRealClick(downX, downY)
                                            }
                                        }
                                        val errToast = when (errorCode) {
                                            6 -> "⚠️ 此畫面受系統防截圖保護，略過錄製此步驟"
                                            3 -> "⚠️ 操作過快（系統截圖冷卻中），請稍慢半秒再點擊"
                                            else -> "❌ 自動截圖失敗 (錯誤碼 $errorCode)，請確認無障礙權限"
                                        }
                                        showToast(errToast)
                                        val restoreFailDelay = if (!isConditionMode && isSwipe) swipeDuration + 250L else 380L
                                        android.os.Handler(mainLooper).postDelayed({
                                            if (activeSession == recordingSessionId && touchCaptureOverlay === interceptView && recordingManager.isRecording) {
                                                interceptView.visibility = android.view.View.VISIBLE
                                            }
                                            isRecordingCaptureBusy = false
                                        }, restoreFailDelay)
                                        return@captureScreenForRecording
                                    }

                                    // 3. Dispatch real click/swipe to the game immediately while interceptView is GONE
                                    // Condition mode only marks an OCR region without clicking into game UI (e.g. stamina/gold bars)
                                    if (!isConditionMode) {
                                        if (isSwipe) {
                                            dispatchRealSwipe(downX, downY, upX, upY, swipeDuration)
                                        } else {
                                            dispatchRealClick(downX, downY)
                                        }
                                    }

                                    // Map logical display coordinates onto exact screenshot bitmap pixels when resolutions differ
                                    val (realW, realH) = getRealScreenDimensions()
                                    val scaleX = if (realW > 0 && bitmap.width > 0 && realW != bitmap.width) bitmap.width.toDouble() / realW.toDouble() else 1.0
                                    val scaleY = if (realH > 0 && bitmap.height > 0 && realH != bitmap.height) bitmap.height.toDouble() / realH.toDouble() else 1.0
                                    val touchX = (downX * scaleX).toInt().coerceIn(0, kotlin.math.max(0, bitmap.width - 1))
                                    val touchY = (downY * scaleY).toInt().coerceIn(0, kotlin.math.max(0, bitmap.height - 1))
                                    val endX = (upX * scaleX).toInt().coerceIn(0, kotlin.math.max(0, bitmap.width - 1))
                                    val endY = (upY * scaleY).toInt().coerceIn(0, kotlin.math.max(0, bitmap.height - 1))

                                    // 4. Offload crop + JPEG compression off Main Thread to eliminate UI jank/ANR
                                    try {
                                        recordingExecutor.execute {
                                            try {
                                                if (isSwipe) {
                                                    recordingManager.addStep(bitmap, touchX, touchY, endX, endY, swipeDuration)
                                                } else {
                                                    recordingManager.addStep(bitmap, touchX, touchY)
                                                }
                                            } finally {
                                                if (!bitmap.isRecycled) bitmap.recycle()
                                            }
                                            val count = recordingManager.getStepCount()
                                            android.os.Handler(mainLooper).post {
                                                if (activeSession == recordingSessionId && recordingManager.isRecording) {
                                                    updateStatus("🔴 錄製中 ($count 步)")
                                                    if (isConditionMode) {
                                                        recModeUpdater?.invoke("click")
                                                        showToast("👁️ 已標記條件區域（未點擊畫面），自動切回點擊模式")
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        if (!bitmap.isRecycled) bitmap.recycle()
                                        Log.w(TAG, "排程錄製步驟處理失敗: ${e.message}")
                                    }

                                    // 5. Re-enable capture layer after dispatched gesture completes, guarded by session ID
                                    val restoreDelayMs = when {
                                        isConditionMode -> 220L
                                        isSwipe -> swipeDuration + 250L
                                        else -> 380L
                                    }
                                    android.os.Handler(mainLooper).postDelayed({
                                        if (activeSession == recordingSessionId && touchCaptureOverlay === interceptView && recordingManager.isRecording) {
                                            interceptView.visibility = android.view.View.VISIBLE
                                        }
                                        isRecordingCaptureBusy = false
                                    }, restoreDelayMs)
                                }
                            }, waitForRateLimitMs)
                            true
                        }
                        else -> true
                    }
                }

                touchCaptureOverlay = interceptView
                windowManager?.addView(touchCaptureOverlay, layoutParams)
                // Lift floatingView (with recording_container) strictly ABOVE touchCaptureOverlay
                bringFloatingViewToFront()
                recordingContainer?.bringToFront()
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

    private fun dispatchRealSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        val path = android.graphics.Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val safeDuration = durationMs.coerceIn(180L, 1500L)
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, safeDuration)
        val builder = android.accessibilityservice.GestureDescription.Builder()
        builder.addStroke(stroke)
        try {
            dispatchGesture(builder.build(), null, null)
            Log.i(TAG, "已穿透滑動: ($startX, $startY) -> ($endX, $endY) ${safeDuration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "穿透滑動失敗", e)
        }
    }

    private fun showSaveRecordingDialog(scriptJson: String) {
        android.os.Handler(mainLooper).post {
            // Get user plan to enforce visibility rules (also used in error fallback to prevent private leakage)
            val userPlan = (getSharedPreferences("GameAutoEditor", MODE_PRIVATE).getString("user_plan", "free") ?: "free").lowercase()
            val canChoosePrivate = userPlan in listOf("go", "pro", "team", "studio", "enterprise")

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
                if (canChoosePrivate) {
                    checkPublic.text = "公開腳本 (分享至社群)"
                    checkPublic.isChecked = false // 付費方案預設私人，保護創作者腳本
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
                    updateStatus("☁️ 正在上傳錄製腳本...")
                    showToast("☁️ 正在上傳錄製圖片與腳本...")

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
                dialog.window?.setType(resolveOverlayWindowType())
                dialog.show()

            } catch (e: Exception) {
                Log.e(TAG, "顯示儲存對話框失敗", e)
                val fallbackVisibility = if (canChoosePrivate) 0 else 1
                showToast("顯示對話框失敗，將以${if (fallbackVisibility == 0) "私人" else "公開"}預設設定上傳")

                val collapsedContainer = floatingView?.findViewById<android.view.View>(R.id.collapsed_container)
                collapsedContainer?.visibility = android.view.View.VISIBLE

                uploadRecordedScript(
                    scriptJson,
                    "錄製腳本 " + java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
                    "由 Android App 自動錄製產生的腳本",
                    fallbackVisibility
                )
            }
        }
    }

    private fun uploadRecordedScript(scriptJson: String, title: String, description: String, visibility: Int) {
        if (getUserToken().isNullOrEmpty()) {
            showToast("未登入，無法上傳錄製腳本")
            return
        }

        thread {
            val scriptContentObj = try {
                JSONObject(scriptJson)
            } catch (e: Exception) {
                Log.e(TAG, "錄製腳本 JSON 解析失敗", e)
                showToast("❌ 錄製資料格式錯誤")
                return@thread
            }

            // Upload inline base64 screenshots & templates to R2 first so multi-step scripts never hit Vercel 4.5MB limit
            val thumbnailUrl = uploadRecordedImagesToR2(scriptContentObj)

            val body = JSONObject().apply {
                put("name", title)
                put("description", description)
                put("version", "1.0.0")
                put("script_content", scriptContentObj)
                put("visibility", if (visibility == 1) "public" else "private")
                if (!thumbnailUrl.isNullOrEmpty()) {
                    put("thumbnail_url", thumbnailUrl)
                }
            }

            // Safety net: if R2 uploads partially failed and payload still nears Vercel 4.5MB cap, trim inline full backgrounds while keeping templates
            var payloadStr = body.toString()
            if (payloadStr.toByteArray(Charsets.UTF_8).size > 3_800_000) {
                trimInlineSceneBackgrounds(scriptContentObj)
                payloadStr = body.toString()
            }

            val uploadUrl = "${getApiBaseUrl()}/api/scripts"
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val token = getUserToken() ?: return@thread
                var conn: java.net.HttpURLConnection? = null
                try {
                    conn = (java.net.URL(uploadUrl).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 15_000
                        readTimeout = 25_000
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                    java.io.OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { os ->
                        os.write(payloadStr)
                        os.flush()
                    }
                    val code = conn.responseCode
                    if (code == 401 && attempt == 1 && refreshTokenSync(token)) continue
                    when (code) {
                        200, 201 -> {
                            val respText = try {
                                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            } catch (_: Exception) { "" }
                            val newScriptId = try {
                                JSONObject(respText).optString("id", "").takeIf { it.isNotBlank() && it != "null" }
                            } catch (_: Exception) { null }
                            if (newScriptId != null) {
                                getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
                                    .edit()
                                    .putString("script_id", newScriptId)
                                    .putString("script_name", title)
                                    .apply()
                                showToast("🎉 錄製腳本已儲存並自動載入！可直接按 ▶️ 執行或至網頁編輯器微調")
                            } else {
                                showToast("🎉 錄製腳本上傳成功！可至 App 選取執行或進網頁編輯器微調")
                            }
                        }
                        413 -> showToast("❌ 錄製腳本過大，請減少步驟數")
                        403 -> showToast("❌ 方案權限不足（免費方案僅能公開儲存）")
                        else -> showToast("❌ 上傳失敗: HTTP $code")
                    }
                    return@thread
                } catch (e: Exception) {
                    Log.e(TAG, "上傳腳本錯誤: ${e.message}", e)
                    showToast("❌ 上傳時發生錯誤")
                    return@thread
                } finally {
                    conn?.disconnect()
                }
            }
        }
    }

    private fun uploadRecordedImagesToR2(scriptContentObj: JSONObject): String? {
        val nodes = scriptContentObj.optJSONArray("nodes") ?: return null
        val uploadedCache = HashMap<String, String>()
        var firstBgUrl: String? = null

        var totalToUpload = 0
        val seen = HashSet<String>()
        for (i in 0 until nodes.length()) {
            val data = nodes.optJSONObject(i)?.optJSONObject("data") ?: continue
            val bg = data.optString("image")
            if (bg.startsWith("data:image/") && seen.add(bg)) totalToUpload++
            val anchors = data.optJSONArray("anchors")
            if (anchors != null) {
                for (a in 0 until anchors.length()) {
                    val tpl = anchors.optJSONObject(a)?.optString("template") ?: ""
                    if (tpl.startsWith("data:image/") && seen.add(tpl)) totalToUpload++
                }
            }
            val regions = data.optJSONArray("regions")
            if (regions != null) {
                for (r in 0 until regions.length()) {
                    val reg = regions.optJSONObject(r) ?: continue
                    val tpl = reg.optString("template")
                    if (tpl.startsWith("data:image/") && seen.add(tpl)) totalToUpload++
                    val percArr = reg.optJSONArray("perception")
                    if (percArr != null) {
                        for (p in 0 until percArr.length()) {
                            val ptpl = percArr.optJSONObject(p)?.optString("template") ?: ""
                            if (ptpl.startsWith("data:image/") && seen.add(ptpl)) totalToUpload++
                        }
                    } else {
                        val ptpl = reg.optJSONObject("perception")?.optString("template") ?: ""
                        if (ptpl.startsWith("data:image/") && seen.add(ptpl)) totalToUpload++
                    }
                }
            }
        }

        var uploadedCount = 0
        fun resolveUploaded(dataUri: String, prefix: String): String {
            if (!dataUri.startsWith("data:image/")) return dataUri
            uploadedCache[dataUri]?.let { return it }
            val url = uploadImageDataUriToR2(dataUri, prefix)
            if (!url.isNullOrEmpty()) {
                uploadedCache[dataUri] = url
                uploadedCount++
                if (totalToUpload > 0) {
                    updateStatus("☁️ 上傳截圖 ($uploadedCount/$totalToUpload)...")
                }
                return url
            }
            return dataUri
        }

        for (i in 0 until nodes.length()) {
            val data = nodes.optJSONObject(i)?.optJSONObject("data") ?: continue
            val bg = data.optString("image")
            if (bg.startsWith("data:image/")) {
                val resolvedBg = resolveUploaded(bg, "rec-bg")
                data.put("image", resolvedBg)
                if (firstBgUrl == null && resolvedBg.startsWith("http")) {
                    firstBgUrl = resolvedBg
                }
            }
            val anchors = data.optJSONArray("anchors")
            if (anchors != null) {
                for (a in 0 until anchors.length()) {
                    val anc = anchors.optJSONObject(a) ?: continue
                    val tpl = anc.optString("template")
                    if (tpl.startsWith("data:image/")) {
                        anc.put("template", resolveUploaded(tpl, "rec-tpl"))
                    }
                }
            }
            val regions = data.optJSONArray("regions")
            if (regions != null) {
                for (r in 0 until regions.length()) {
                    val reg = regions.optJSONObject(r) ?: continue
                    val tpl = reg.optString("template")
                    if (tpl.startsWith("data:image/")) {
                        reg.put("template", resolveUploaded(tpl, "rec-tpl"))
                    }
                    val percArr = reg.optJSONArray("perception")
                    if (percArr != null) {
                        for (p in 0 until percArr.length()) {
                            val perc = percArr.optJSONObject(p) ?: continue
                            val ptpl = perc.optString("template")
                            if (ptpl.startsWith("data:image/")) {
                                perc.put("template", resolveUploaded(ptpl, "rec-tpl"))
                            }
                        }
                    } else {
                        val percObj = reg.optJSONObject("perception")
                        val ptpl = percObj?.optString("template") ?: ""
                        if (percObj != null && ptpl.startsWith("data:image/")) {
                            percObj.put("template", resolveUploaded(ptpl, "rec-tpl"))
                        }
                    }
                }
            }
        }
        return firstBgUrl
    }

    private fun uploadImageDataUriToR2(dataUri: String, prefix: String): String? {
        val uploadUrl = "${getApiBaseUrl()}/api/storage?action=upload"
        val deviceId = getAppDeviceId() ?: ""
        val filename = "${prefix}-${java.util.UUID.randomUUID().toString().replace("-", "").take(12)}.jpg"
        val payload = JSONObject().put("image", dataUri).toString()

        var attempt = 0
        while (attempt < 2) {
            attempt++
            val token = getUserToken() ?: return null
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = (java.net.URL(uploadUrl).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("X-Filename", filename)
                    if (deviceId.isNotEmpty()) {
                        setRequestProperty("x-device-id", deviceId)
                    }
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code == 401 && attempt == 1 && refreshTokenSync(token)) continue
                if (code in 200..299) {
                    val respText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val url = JSONObject(respText).optString("url")
                    if (url.startsWith("http")) return url
                } else {
                    Log.w(TAG, "錄製圖片上傳 R2 失敗 ($filename): HTTP $code")
                }
                return null
            } catch (e: Exception) {
                Log.w(TAG, "錄製圖片上傳 R2 例外 ($filename): ${e.message}")
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun trimInlineSceneBackgrounds(scriptContentObj: JSONObject) {
        val nodes = scriptContentObj.optJSONArray("nodes") ?: return
        for (i in 0 until nodes.length()) {
            val data = nodes.optJSONObject(i)?.optJSONObject("data") ?: continue
            val bg = data.optString("image")
            if (bg.startsWith("data:image/")) {
                data.remove("image")
            }
        }
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

    fun refreshTokenSync(failedToken: String? = null): Boolean {
        return TokenRefreshHelper.refreshSync(this, failedToken)
    }

    fun getAppDeviceId(): String? {
        val gameAutoPrefs = getSharedPreferences("GameAutoPrefs", MODE_PRIVATE)
        val savedPrefId = gameAutoPrefs.getString("device_id", null)
        if (!savedPrefId.isNullOrEmpty()) return savedPrefId

        val prefs = getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
        // Check for saved 'device_id' (Trusted from Installer or Main Boot)
        val savedId = prefs.getString("device_id", null)
        if (!savedId.isNullOrEmpty()) return savedId
        
        // Fallback to Android ID
        val systemId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        val fallbackId = systemId ?: java.util.UUID.randomUUID().toString()
        gameAutoPrefs.edit().putString("device_id", fallbackId).apply()
        prefs.edit().putString("device_id", fallbackId).apply()
        return fallbackId
    }
    
    fun loadScriptFromNetwork(scriptIdOrUrl: String) {
        if (scriptIdOrUrl.isEmpty() || scriptIdOrUrl == "null") {
            Log.e(TAG, "❌ 無效的腳本 ID/URL")
            return
        }

        thread {
            try {
                val prefs = getSharedPreferences("GameAutoPrefs", MODE_PRIVATE)
                val editorPrefs = getSharedPreferences("GameAutoEditor", MODE_PRIVATE)
                val serverUrl = (prefs.getString("server_url", null)?.takeIf { it.isNotBlank() }
                    ?: editorPrefs.getString("server_url", null)?.takeIf { it.isNotBlank() }
                    ?: BuildConfig.AI_API_URL).trimEnd('/')

                val urlString = if (scriptIdOrUrl.startsWith("http")) {
                    scriptIdOrUrl
                } else {
                    "$serverUrl/api/get-script?id=$scriptIdOrUrl"
                }

                val deviceId = getAppDeviceId()
                var attempt = 0
                while (attempt < 2) {
                    attempt++
                    var connection: java.net.HttpURLConnection? = null
                    try {
                        Log.d(TAG, "正在下載腳本: $urlString")
                        connection = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.instanceFollowRedirects = false // Manual redirect handling to capture headers

                        val token = getUserToken()
                        if (!token.isNullOrEmpty()) {
                            connection.setRequestProperty("Authorization", "Bearer $token")
                        }

                        val licenseKey = prefs.getString("license_key", null) ?: getLicenseKey()
                        if (!licenseKey.isNullOrEmpty()) {
                            connection.setRequestProperty("x-license-key", licenseKey)
                        }
                        if (!deviceId.isNullOrEmpty()) {
                            connection.setRequestProperty("x-device-id", deviceId)
                        }

                        connection.connectTimeout = 10_000
                        connection.readTimeout = 10_000

                        var responseCode = connection.responseCode
                        var userPlan = connection.getHeaderField("X-User-Plan")?.lowercase() ?: "free"
                        if (userPlan == "free" && !licenseKey.isNullOrEmpty()) {
                            userPlan = (editorPrefs.getString("user_plan", "free") ?: "free").lowercase()
                        }
                        Log.d(TAG, "📋 User Plan detected: $userPlan")

                        if (responseCode == 401 && !token.isNullOrEmpty() && attempt == 1 && refreshTokenSync(token)) {
                            continue
                        }

                        // Handle Redirect (302 Found) - Common for R2 Blob Storage
                        if (responseCode == java.net.HttpURLConnection.HTTP_MOVED_TEMP || responseCode == java.net.HttpURLConnection.HTTP_MOVED_PERM) {
                            val location = connection.getHeaderField("Location")
                            Log.d(TAG, "➡️ Redirecting to: $location")
                            connection.disconnect()
                            connection = null

                            if (location != null) {
                                connection = java.net.URL(location).openConnection() as java.net.HttpURLConnection
                                connection.connectTimeout = 10_000
                                connection.readTimeout = 10_000
                                responseCode = connection.responseCode
                            }
                        }

                        if (responseCode == 200 && connection != null) {
                            val scriptJson = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

                            // Cache script
                            try {
                                openFileOutput("cached_script.json", MODE_PRIVATE).use {
                                    it.write(scriptJson.toByteArray(Charsets.UTF_8))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Cache script failed", e)
                            }

                            android.os.Handler(mainLooper).post {
                                Log.i(TAG, "✅ 網路腳本載入成功 (Plan: $userPlan)")

                                val isPaidPlan = userPlan in listOf("go", "pro", "team", "studio", "enterprise") || !getLicenseKey().isNullOrEmpty()
                                if (isPaidPlan) {
                                    Log.i(TAG, "🚀 付費方案/商業授權用戶，跳過廣告直接執行")
                                    executeScriptJson(scriptJson, scriptIdOrUrl, userPlan)
                                    return@post
                                }

                                try {
                                    Log.i(TAG, "🎬 準備播放廣告...")
                                    pendingScriptJson = scriptJson
                                    pendingScriptId = scriptIdOrUrl
                                    pendingUserPlan = userPlan

                                    val adIntent = android.content.Intent(this@AutomationService, AdActivity::class.java).apply {
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    }
                                    startActivity(adIntent)
                                } catch (t: Throwable) {
                                    Log.e(TAG, "⚠️ 喚起廣告頁面失敗，安全降級直接執行腳本: ${t.message}")
                                    executeScriptJson(scriptJson, scriptIdOrUrl, userPlan)
                                }
                            }
                            return@thread
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
                            return@thread
                        }
                    } finally {
                        connection?.disconnect()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 網路請求錯誤: ${e.message}", e)
                android.os.Handler(mainLooper).post {
                    loadScriptFromAssets()
                }
            }
        }
    }
    
    // Extracted shared execution logic to avoid code duplication
    private fun executeScriptJson(scriptJson: String, scriptIdOrUrl: String, userPlan: String = "free") {
        val normalizedPlan = userPlan.lowercase()
        // Unified Engine Routing
        if (scriptJson.contains("\"nodes\"") && scriptJson.contains("\"edges\"")) {
             // Use FSM
             // Hot Reload Check: Same ID + Running = Update Graph Only
             if (sceneGraphEngine.currentStatus == "running" && sceneGraphEngine.scriptId == scriptIdOrUrl) {
                 // Plan Gate: Pro / Team / Studio / Enterprise
                 if (normalizedPlan in listOf("pro", "team", "studio", "enterprise")) {
                    Log.i(TAG, "🔥 觸發熱更新 (Hot Reload) - Plan Valid")
                    sceneGraphEngine.updateScriptGraph(scriptJson)
                 } else {
                    Log.w(TAG, "⚠️ 熱更新僅限 Pro 以上方案 (當前: $normalizedPlan)，執行標準重啟")
                    showToast("🔥 熱更新為 Pro/Team/Studio 專屬功能，正在重新啟動...")
                    sceneGraphEngine.stop()
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
        Log.i(TAG, "🛑 Accessibility Service 已停止")

        try {
            unregisterReceiver(overlayReceiver)
        } catch (_: Exception) {}

        fleetManager?.cleanup()
        fleetManager = null

        if (::scriptEngine.isInitialized) {
            scriptEngine.release()
        }
        if (::sceneGraphEngine.isInitialized) {
            sceneGraphEngine.release()
        }

        recordingSessionId++
        isRecordingCaptureBusy = false
        try {
            recordingExecutor.shutdownNow()
        } catch (e: Exception) {
            Log.w(TAG, "關閉錄製背景執行緒失敗: ${e.message}")
        }

        if (touchCaptureOverlay != null) {
            try {
                windowManager?.removeView(touchCaptureOverlay)
            } catch (e: Exception) {
                Log.w(TAG, "移除錄製攔截層失敗: ${e.message}")
            } finally {
                touchCaptureOverlay = null
            }
        }

        if (floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
            } catch (e: Exception) {
                Log.w(TAG, "移除懸浮窗失敗: ${e.message}")
            } finally {
                floatingView = null
                floatingLayoutParams = null
            }
        }

        super.onDestroy()
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
        if (bitmap.isRecycled) {
            sceneGraphEngine.resume()
            return
        }
        val ownedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (ownedBitmap == null) {
            sceneGraphEngine.resume()
            return
        }

        Thread {
            var conn: java.net.HttpURLConnection? = null
            try {
                sceneGraphEngine.remoteLog("INFO", "🤖 呼叫 AI 自我成長中...")
                updateStatus("🤖 呼叫 AI 自我成長中...")
                showToast("🤖 AI 分析中，請稍候...")

                val mode = sceneGraphEngine.autoGrowthMode
                val encodedImage: String = if (mode == "creator") {
                    sceneGraphEngine.remoteLog("INFO", "🔍 啟動造物主模式 (Creator) - 傳送原始截圖分析中...")
                    bitmapToBase64(ownedBitmap)
                } else {
                    val gridBitmap = ownedBitmap.drawCoordinateGrid()
                    try {
                        sceneGraphEngine.remoteLog("INFO", "🧭 啟動導航模式 (Navigator) - 傳送輔助網格截圖分析中...")
                        bitmapToBase64(gridBitmap)
                    } finally {
                        if (!gridBitmap.isRecycled) gridBitmap.recycle()
                    }
                }

                val url = java.net.URL("${BuildConfig.AI_API_URL.trimEnd('/')}/api/ai-auto-flow")
                conn = url.openConnection() as java.net.HttpURLConnection
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
                    if (!deviceId.isNullOrEmpty()) conn.setRequestProperty("x-device-id", deviceId)
                }

                conn.connectTimeout = 5000
                conn.readTimeout = 30000

                val jsonBody = JSONObject()
                jsonBody.put("scriptId", scriptId)
                jsonBody.put("imageBase64", encodedImage)
                jsonBody.put("mode", mode)

                val objective = sceneGraphEngine.autoGrowthObjective
                if (!objective.isNullOrEmpty()) {
                    jsonBody.put("objective", objective)
                }

                java.io.OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { os ->
                    os.write(jsonBody.toString())
                    os.flush()
                }

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val respJson = JSONObject(resp)

                    if (respJson.optBoolean("success", false)) {
                        val msg = respJson.optString("message", "成功")

                        if (mode == "creator") {
                            val updatedGraph = respJson.optJSONObject("graphData")
                            if (updatedGraph != null) {
                                sceneGraphEngine.remoteLog("INFO", "🤖 AI 造物主成功: $msg")
                                showToast("✅ $msg")
                                sceneGraphEngine.updateScriptGraph(updatedGraph.toString())
                            } else {
                                sceneGraphEngine.remoteLog("WARN", "🤖 AI 回傳格式錯誤: 缺少 graphData")
                            }
                        } else {
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
                conn?.disconnect()
                if (!ownedBitmap.isRecycled) ownedBitmap.recycle()
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
    @Volatile
    private var lastCapturedSize: Pair<Int, Int>? = null

    /**
     * 取得真實物理螢幕尺寸 (與 takeScreenshot / dispatchGesture 座標系完全一致，消除狀態列與導覽列偏移)
     */
    fun getRealScreenDimensions(): Pair<Int, Int> {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bounds = windowManager?.currentWindowMetrics?.bounds
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    return CoordinateGeometry.screenSize(Pair(bounds.width(), bounds.height()), lastCapturedSize, Pair(0, 0))
                }
            }
            @Suppress("DEPRECATION")
            val display = windowManager?.defaultDisplay
            if (display != null) {
                val realMetrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(realMetrics)
                if (realMetrics.widthPixels > 0 && realMetrics.heightPixels > 0) {
                    return CoordinateGeometry.screenSize(Pair(realMetrics.widthPixels, realMetrics.heightPixels), lastCapturedSize, Pair(0, 0))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "無法取得 RealScreenDimensions，退回 displayMetrics", e)
        }
        val dm = resources.displayMetrics
        return CoordinateGeometry.screenSize(null, lastCapturedSize, Pair(dm.widthPixels, dm.heightPixels))
    }

    /**
     * 獲取當前螢幕截圖 (Android 11+)，並回傳錯誤碼以利錄製模式自動重試冷卻
     */
    private fun captureScreenWithStatus(callback: (android.graphics.Bitmap?, Int) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            lastScreenshotRequestMs = android.os.SystemClock.elapsedRealtime()
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var copy: Bitmap? = null
                        var wrappedBitmap: Bitmap? = null
                        try {
                            wrappedBitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            copy = wrappedBitmap?.copy(Bitmap.Config.ARGB_8888, true)
                            if (copy != null && copy.width > 0 && copy.height > 0) {
                                lastCapturedSize = Pair(copy.width, copy.height)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "截圖處理失敗: ${e.message}")
                            copy?.let { if (!it.isRecycled) it.recycle() }
                            copy = null
                        } finally {
                            try { screenshot.hardwareBuffer.close() } catch (_: Exception) {}
                            wrappedBitmap?.let { if (!it.isRecycled) it.recycle() }
                        }
                        callback(copy, if (copy != null) 0 else -1)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "截圖失敗，錯誤碼: $errorCode")
                        callback(null, errorCode)
                    }
                }
            )
        } else {
            Log.w(TAG, "不支援 Android 11 以下版本的截圖")
            showToast("此功能需要 Android 11+")
            callback(null, -2)
        }
    }

    private fun captureScreenForRecording(
        expectedSession: Long,
        attempt: Int = 0,
        callback: (android.graphics.Bitmap?, Int) -> Unit
    ) {
        captureScreenWithStatus { bitmap, errorCode ->
            // Android 11-15 enforces ~1s minimum interval (ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT = 3)
            if (bitmap == null && errorCode == 3 && attempt < 2 &&
                expectedSession == recordingSessionId && recordingManager.isRecording
            ) {
                Log.w(TAG, "錄製截圖觸發系統 1 秒冷卻 (code=3)，自動重試第 ${attempt + 1} 次...")
                android.os.Handler(mainLooper).postDelayed({
                    if (expectedSession == recordingSessionId && recordingManager.isRecording) {
                        captureScreenForRecording(expectedSession, attempt + 1, callback)
                    } else {
                        callback(null, errorCode)
                    }
                }, 480L)
                return@captureScreenWithStatus
            }
            callback(bitmap, errorCode)
        }
    }

    /**
     * 獲取當前螢幕截圖 (Android 11+)
     */
    fun captureScreen(callback: (android.graphics.Bitmap?) -> Unit) {
        captureScreenWithStatus { bitmap, _ -> callback(bitmap) }
    }

    /**
     * 同步獲取截圖 (阻塞直到截圖完成或超時)
     * 用於背景線程的 SceneGraphEngine
     */
    fun captureScreenSync(): android.graphics.Bitmap? {
        val resultRef = java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap?>(null)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)

        captureScreen { bitmap ->
            if (timedOut.get()) {
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            } else {
                resultRef.set(bitmap)
                if (timedOut.get()) {
                    resultRef.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
                }
            }
            latch.countDown()
        }

        try {
            val completed = latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!completed) {
                timedOut.set(true)
                resultRef.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            timedOut.set(true)
            resultRef.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
            Log.e(TAG, "截圖逾時 (中斷)")
        } catch (e: Exception) {
            timedOut.set(true)
            resultRef.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
            Log.e(TAG, "截圖逾時")
        }

        return if (timedOut.get()) {
            resultRef.getAndSet(null)?.let { if (!it.isRecycled) it.recycle() }
            null
        } else {
            resultRef.getAndSet(null)
        }
    }

    /**
     * 乾淨截圖 (自動暫時隱藏懸浮球，避免遮擋遊戲特徵圖)
     */
    fun captureScreenCleanSync(): android.graphics.Bitmap? {
        val hideLatch = java.util.concurrent.CountDownLatch(1)
        val wasVisible = java.util.concurrent.atomic.AtomicBoolean(false)
        val captureDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val mainHandler = android.os.Handler(mainLooper)

        mainHandler.post {
            if (!captureDone.get()) {
                val visible = (floatingView?.visibility == android.view.View.VISIBLE)
                if (visible) {
                    wasVisible.set(true)
                    floatingView?.visibility = android.view.View.INVISIBLE
                }
            }
            hideLatch.countDown()
        }

        return try {
            val hidden = hideLatch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (hidden && wasVisible.get()) {
                Thread.sleep(80)
            }
            captureScreenSync()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } finally {
            captureDone.set(true)
            mainHandler.post {
                if (wasVisible.get()) {
                    floatingView?.visibility = android.view.View.VISIBLE
                }
            }
        }
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

