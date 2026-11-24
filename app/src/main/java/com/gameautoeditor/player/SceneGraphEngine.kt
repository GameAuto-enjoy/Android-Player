package com.gameautoeditor.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SceneGraphEngine(val service: AutomationService) {
    private val context: Context get() = service
    private val TAG = "GameAuto"

    @Volatile
    private var graphData: JSONObject? = null
    @Volatile
    private var isRunning = false
    @Volatile
    private var workerThread: Thread? = null
    @Volatile
    private var isReleased = false
    @Volatile
    private var releasePending = false
    @Volatile
    private var workerGeneration = 0L
    private val lifecycleLock = Any()
    private var activeWorkerCount = 0
    private var perceptionReleased = false
    private var isStarting = false
    private var startingGen = 0L

    // Remote Logging
    private val logQueue = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
    @Volatile
    private var lastLogFlushTime = 0L
    private val networkExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile
    private var deviceId: String? = null
    @Volatile
    var isRemoteLogEnabled: Boolean = false

    // Systems
    private val perceptionSystem = PerceptionSystem(service) { level, msg ->
        remoteLog(level, msg)
    }
    private val actionSystem = ActionSystem(service)

    // State
    data class ExecutionData(var lastRunTime: Long = 0, var runCount: Int = 0)
    private val executionHistory = ConcurrentHashMap<String, ExecutionData>()
    private val variables = ConcurrentHashMap<String, Int>()
    private val appliedLocalTaskKeys = ConcurrentHashMap.newKeySet<String>()
    private val taskPriorities = ConcurrentHashMap<String, Int>()
    private val remoteOverrides = ConcurrentHashMap<String, Any>()
    @Volatile
    private var previousSceneId: String? = null
    private var lostFrameCount = 0
    private var transitionStuckCount = 0
    private var consecutiveFallbackCount = 0
    private var lastTransitionAction: TransitionAction? = null
    @Volatile
    private var autoGrowthEnabled: Boolean = false
    @Volatile
    var autoGrowthObjective: String? = null
    @Volatile
    var autoGrowthMode: String = "navigator" // "navigator" or "creator"
    private var lastAutoGrowthTime: Long = 0L
    private var idleFrameCount = 0

    @Volatile
    private var currentScriptId: String? = null
    
    // === Fleet 狀態追蹤 (Fleet Status Tracking) ===
    @Volatile
    var currentStatus: String = "idle"      // idle / running / paused / error
        private set
    @Volatile
    var currentSceneId: String? = null      // 當前場景 ID (公開給 FleetSyncManager)
        private set
    @Volatile
    var currentSceneName: String? = null    // 當前場景名稱
        private set
    var scriptId: String? = null            // 當前腳本 ID
        get() = currentScriptId
        private set
    @Volatile
    var scriptName: String? = null          // 當前腳本名稱
        private set
    private val actionsCounter = AtomicInteger(0)
    val actionsCount: Int                   // 本次執行的動作數
        get() = actionsCounter.get()
    private val errorsCounter = AtomicInteger(0)
    val errorsCount: Int                    // 錯誤次數
        get() = errorsCounter.get()
    @Volatile
    var uptimeSeconds: Int = 0              // 運行時間 (秒)
        private set
    @Volatile
    var lastError: String? = null           // 最後錯誤訊息
        private set
    @Volatile
    private var startTime: Long = 0         // 開始執行的時間戳
    @Volatile
    private var isPaused: Boolean = false   // 暫停狀態

    fun start(jsonString: String, scriptId: String? = null, scriptDisplayName: String? = null) {
        start(jsonString, scriptId, scriptDisplayName, null, null)
    }

    private fun start(
        jsonString: String,
        scriptId: String?,
        scriptDisplayName: String?,
        expectedGen: Long?,
        onStarted: (() -> Unit)?
    ) {
        val startGen: Long
        val existingWorker: Thread?
        synchronized(lifecycleLock) {
            if (isReleased) {
                Log.w(TAG, "⚠️ 引擎已釋放，忽略啟動請求")
                return
            }
            if (expectedGen != null && workerGeneration != expectedGen) {
                return
            }
            if (isRunning || isStarting) {
                Log.w(TAG, "⚠️ 引擎已在運行中，忽略啟動請求")
                return
            }
            isStarting = true
            startGen = ++workerGeneration
            startingGen = startGen
            existingWorker = workerThread
        }

        var workerStarted = false
        try {
            // Ensure previous thread is truly dead (never hold lifecycleLock during join)
            if (existingWorker != null && existingWorker.isAlive) {
                Log.w(TAG, "⚠️ 舊的 Worker Thread 尚未結束，強制停止...")
                existingWorker.interrupt()
                try {
                    existingWorker.join(1500)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            if (synchronized(lifecycleLock) { isReleased || workerGeneration != startGen }) {
                return
            }

            // Parse graph & pre-read SharedPreferences task config OUTSIDE lifecycleLock without mutating engine fields
            val parsedGraph = JSONObject(jsonString)
            val settingsObj = parsedGraph.optJSONObject("metadata")?.optJSONObject("settings")
                ?: parsedGraph.optJSONObject("settings")
            val parsedAutoGrowthEnabled = settingsObj?.optBoolean("autoGrowth", false) ?: false
            val parsedAutoGrowthObjective = settingsObj?.optString("autoGrowthObjective", "")
            val parsedAutoGrowthMode = settingsObj?.optString("autoGrowthMode", "navigator") ?: "navigator"
            val parsedVars = mutableMapOf<String, Int>()
            val settingsVars = settingsObj?.optJSONObject("variables")
            if (settingsVars != null) {
                val keys = settingsVars.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    parsedVars[key] = settingsVars.optInt(key, 0)
                }
            }
            val taskConfigSnapshot = readTaskConfigSnapshot(scriptId)

            val myGen: Long
            val newWorker: Thread
            val committedVarsSnapshot: String
            synchronized(lifecycleLock) {
                if (isReleased || workerGeneration != startGen) {
                    return
                }
                if (activeWorkerCount > 0) {
                    currentStatus = "error"
                    lastError = "舊工作執行緒尚未結束，請稍後再試"
                    isStarting = false
                    if (startingGen == startGen) startingGen = 0L
                    return
                }

                // Atomic pre-clear while activeWorkerCount == 0 (strictly acyclic lifecycleLock -> cacheLock)
                if (!perceptionReleased) {
                    perceptionSystem.clearCache()
                    executionHistory.clear()
                }

                // Advance generation at commit to strictly invalidate any prior stopGen/exitGen
                myGen = ++workerGeneration
                newWorker = Thread({ runLoop(myGen) }, "SceneGraphWorker")

                this.currentScriptId = scriptId
                this.scriptName = scriptDisplayName ?: "Unknown"
                this.graphData = parsedGraph

                variables.clear()
                appliedLocalTaskKeys.clear()
                taskPriorities.clear()
                autoGrowthEnabled = parsedAutoGrowthEnabled
                autoGrowthObjective = parsedAutoGrowthObjective
                autoGrowthMode = parsedAutoGrowthMode
                variables.putAll(parsedVars)
                if (taskConfigSnapshot != null) {
                    for ((key, value) in taskConfigSnapshot.enableVars) {
                        variables[key] = value
                        appliedLocalTaskKeys.add(key)
                    }
                    for ((label, priority) in taskConfigSnapshot.priorities) {
                        taskPriorities[label] = priority
                    }
                }
                if (remoteOverrides.isNotEmpty()) {
                    for ((key, rawVal) in remoteOverrides) {
                        variables[key] = coerceToInt(rawVal)
                    }
                }
                committedVarsSnapshot = variables.toString()

                // Reset per-run FSM tracking state cleanly
                currentSceneId = null
                currentSceneName = null
                previousSceneId = null
                lastTransitionTime = 0L
                lostFrameCount = 0
                transitionStuckCount = 0
                consecutiveFallbackCount = 0
                lastTransitionAction = null
                lastAutoGrowthTime = 0L
                idleFrameCount = 0
                uptimeSeconds = 0

                workerThread = newWorker
                activeWorkerCount++
                isRunning = true
                isPaused = false
                isStarting = false
                startingGen = 0L
                currentStatus = "running"
                startTime = System.currentTimeMillis()
                actionsCounter.set(0)
                errorsCounter.set(0)
                lastError = null
            }

            try {
                newWorker.start()
                workerStarted = true
            } catch (t: Throwable) {
                var shouldReleaseNow = false
                synchronized(lifecycleLock) {
                    if (activeWorkerCount > 0) activeWorkerCount--
                    if (workerThread === newWorker) workerThread = null
                    if (workerGeneration == myGen) {
                        isRunning = false
                        isPaused = false
                        if (!isReleased) {
                            currentStatus = "error"
                            lastError = t.message ?: "啟動工作執行緒失敗"
                        }
                    }
                    if (activeWorkerCount == 0 && !perceptionReleased) {
                        if (releasePending || isReleased) {
                            perceptionReleased = true
                            shouldReleaseNow = true
                        } else if (!isRunning && !isStarting) {
                            perceptionSystem.clearCache()
                            executionHistory.clear()
                        }
                    }
                }
                if (shouldReleaseNow) {
                    perceptionSystem.release()
                    executionHistory.clear()
                }
                throw t
            }

            try {
                remoteLog("INFO", "🤖 SceneGraphEngine (FSM) 已啟動. 版本: ${BuildConfig.VERSION_NAME} (Log-Target). 變數: $committedVarsSnapshot")
                onStarted?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "記錄啟動日誌失敗: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析腳本失敗", e)
            synchronized(lifecycleLock) {
                if (!isReleased && !workerStarted && workerGeneration == startGen) {
                    isRunning = false
                    currentStatus = "error"
                    lastError = e.message
                }
            }
        } finally {
            synchronized(lifecycleLock) {
                if (startingGen == startGen) {
                    isStarting = false
                    startingGen = 0L
                    if (activeWorkerCount == 0 && !isRunning && !perceptionReleased && !isReleased) {
                        perceptionSystem.clearCache()
                        executionHistory.clear()
                    }
                }
            }
        }
    }

    private data class TaskConfigSnapshot(
        val enableVars: Map<String, Int>,
        val priorities: Map<String, Int>
    )

    fun reloadTaskConfig() {
        val snapshot = readTaskConfigSnapshot(currentScriptId) ?: return
        applyTaskConfigSnapshot(snapshot)
    }

    private fun readTaskConfigSnapshot(targetScriptId: String?): TaskConfigSnapshot? {
        return try {
            val prefs = context.getSharedPreferences("GameAutoEditor", Context.MODE_PRIVATE)
            val enableVars = mutableMapOf<String, Int>()
            val priorities = mutableMapOf<String, Int>()

            val scriptKey = targetScriptId?.takeIf { it.isNotBlank() } ?: "default"
            val perScriptRaw = prefs.getString("task_config_$scriptKey", null)
            if (!perScriptRaw.isNullOrBlank()) {
                val configObj = JSONObject(perScriptRaw)
                val labels = configObj.keys()
                while (labels.hasNext()) {
                    val label = labels.next()
                    val entry = configObj.optJSONObject(label) ?: continue
                    if (entry.has("enabled")) {
                        val enableKey = "enable_$label"
                        enableVars[enableKey] = if (entry.optBoolean("enabled", true)) 1 else 0
                    }
                    if (entry.has("priority")) {
                        priorities[label] = entry.optInt("priority", 5)
                    }
                }
            } else {
                // First-run legacy migration fallback ONLY when no per-script task_config_* exists yet
                val hasAnyPerScriptConfig = prefs.all.keys.any { it.startsWith("task_config_") }
                if (!hasAnyPerScriptConfig) {
                    for ((key, value) in prefs.all) {
                        if (key.startsWith("enable_") && value is Int) {
                            enableVars[key] = value
                        } else if (key.startsWith("priority_") && value is Int) {
                            val label = key.removePrefix("priority_")
                            if (label.isNotEmpty()) {
                                priorities[label] = value
                            }
                        }
                    }
                }
            }
            TaskConfigSnapshot(enableVars, priorities)
        } catch (e: Exception) {
            Log.w(TAG, "載入任務設定失敗: ${e.message}")
            null
        }
    }

    private fun applyTaskConfigSnapshot(snapshot: TaskConfigSnapshot) {
        for (prevKey in appliedLocalTaskKeys) {
            variables.remove(prevKey)
        }
        appliedLocalTaskKeys.clear()
        taskPriorities.clear()

        for ((key, value) in snapshot.enableVars) {
            variables[key] = value
            appliedLocalTaskKeys.add(key)
        }
        for ((label, priority) in snapshot.priorities) {
            taskPriorities[label] = priority
        }

        // Re-apply remote variable overrides so Fleet console overrides always take precedence
        if (remoteOverrides.isNotEmpty()) {
            for ((key, rawVal) in remoteOverrides) {
                variables[key] = coerceToInt(rawVal)
            }
        }
    }

    fun stop() {
        val wasActive: Boolean
        val currentWorker: Thread?
        synchronized(lifecycleLock) {
            wasActive = isRunning || isStarting || currentStatus != "idle"
            ++workerGeneration
            isRunning = false
            isPaused = false
            isStarting = false
            startingGen = 0L
            currentStatus = "idle"
            currentWorker = workerThread
            if (activeWorkerCount == 0 && !perceptionReleased) {
                perceptionSystem.clearCache()
                executionHistory.clear()
            }
        }
        currentWorker?.interrupt()
        if (wasActive) {
            remoteLog("INFO", "⏹️ 已停止")
        }
    }

    fun release() {
        val currentWorker: Thread?
        val shouldReleaseNow: Boolean
        synchronized(lifecycleLock) {
            isReleased = true
            releasePending = true
            workerGeneration++
            isRunning = false
            isPaused = false
            isStarting = false
            startingGen = 0L
            currentStatus = "idle"
            currentWorker = workerThread
            shouldReleaseNow = if (activeWorkerCount == 0 && !perceptionReleased) {
                perceptionReleased = true
                true
            } else {
                false
            }
        }
        currentWorker?.interrupt()
        if (shouldReleaseNow) {
            perceptionSystem.release()
            executionHistory.clear()
        }
        try {
            networkExecutor.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "關閉遠端日誌執行緒池失敗: ${e.message}")
        }
    }

    private fun runLoop(myGen: Long) {
        try {
            if (!isRunning || workerGeneration != myGen || isReleased) return
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                if (!isRunning || workerGeneration != myGen || isReleased) return
            }
            if (!isRunning || workerGeneration != myGen || isReleased) return

            currentSceneId = findRootNodeId()
            
            if (currentSceneId == null) {
                remoteLog("ERROR", "❌ 腳本中找不到起始節點 (Root Node)")
                service.showToast("腳本錯誤：找不到起始節點")
                synchronized(lifecycleLock) {
                    if (workerGeneration == myGen && !isReleased) {
                        isRunning = false
                        currentStatus = "error"
                        lastError = "找不到起始節點 (Root Node)"
                    }
                }
                return
            }
            
            currentSceneName = getNodeName(currentSceneId)
            
            // Initial State Report
            reportState(currentSceneId!!)

            while (isRunning && workerGeneration == myGen && !isReleased) {
                try {
                    if (isPaused) {
                        Thread.sleep(500)
                        continue
                    }

                    // Guardian Logic
                    if (!checkAppFocus()) {
                        if (!isRunning || workerGeneration != myGen || isReleased) break
                        Thread.sleep(1000)
                        continue
                    }
                    if (!isRunning || workerGeneration != myGen || isReleased) break
                    
                    // Flush Logs periodically (every 1s)
                    if (System.currentTimeMillis() - lastLogFlushTime > 1000) {
                        flushLogs()
                        lastLogFlushTime = System.currentTimeMillis()
                    }

                    // 1. Perception (Eye)
                    val screen = service.captureScreenSync()
                    if (screen == null) {
                        if (!isRunning || workerGeneration != myGen || isReleased) break
                        Thread.sleep(500)
                        continue
                    }

                    try {
                        if (!isRunning || workerGeneration != myGen || isReleased) break
                        // --- Smart Transition Wait (v2: Overlay Support) ---
                        if (previousSceneId != null && System.currentTimeMillis() - lastTransitionTime < 3000) {
                            var targetResolved = false
                            
                            // 1. Priority Check: Is Target (currentSceneId) ALREADY visible?
                            val targetNode = if (currentSceneId != null) getNodeById(currentSceneId!!) else null
                            if (targetNode != null) {
                                val hasAnchors = (targetNode.optJSONObject("data")?.optJSONArray("anchors")?.length() ?: 0) > 0
                                
                                if (hasAnchors) {
                                    val targetName = getNodeName(currentSceneId)
                                    val targetActive = perceptionSystem.isStateActive(screen, targetNode, variables, targetName, verbose = false)
                                    if (!isRunning || workerGeneration != myGen || isReleased) break
                                    if (targetActive) {
                                        remoteLog("DEBUG", "[FSM] 🚀 目標場景 [$targetName] 已確認出現 (Overlay mode). 停止等待.")
                                        lastTransitionTime = 0 // Clear timer, transition complete
                                        targetResolved = true
                                    }
                                }
                            }

                            // 2. Fallback: If Target NOT visible (or Blind), check if Previous Scene is STUCK.
                            if (!targetResolved) {
                                val prevNode = getNodeById(previousSceneId!!)
                                if (prevNode != null) {
                                    val prevName = getNodeName(previousSceneId)
                                    val prevActive = perceptionSystem.isStateActive(screen, prevNode, variables, prevName, verbose = false)
                                    if (!isRunning || workerGeneration != myGen || isReleased) break
                                    if (prevActive) {
                                        transitionStuckCount++
                                        if (transitionStuckCount <= 20) { // Max 10 seconds (20 * 500ms)
                                            remoteLog("INFO", "⏳ 轉場中... 還在 [$prevName] ($transitionStuckCount/20)")
                                            
                                            // Retry Logic
                                            if (transitionStuckCount % 6 == 0 && lastTransitionAction != null) {
                                                val label = lastTransitionAction?.region?.optString("label") ?: "Unknown"
                                                remoteLog("WARN", "🔄 卡太久了，重試動作: $label")
                                                val actionConfig = lastTransitionAction?.region?.optJSONObject("action")
                                                if (actionConfig != null) {
                                                    val prevRes = prevNode.optJSONObject("resolution")
                                                        ?: prevNode.optJSONObject("data")?.optJSONObject("resolution")
                                                    if (!isRunning || workerGeneration != myGen || isReleased) break
                                                    actionSystem.performAction(actionConfig, lastTransitionAction!!.region, prevRes)
                                                    if (!isRunning || workerGeneration != myGen || isReleased) break
                                                }
                                            }

                                            lastTransitionTime = System.currentTimeMillis()
                                            smartSleep(500, myGen)
                                            continue // Skip this frame (finally recycles screen)
                                        } else {
                                            remoteLog("WARN", "[FSM] ⚠️ 轉場逾時 (Stuck > 10s). 放棄等待，強制執行下一步判定.")
                                            lastTransitionTime = 0 // Stop waiting
                                            
                                            // Trigger Auto-Growth if stuck in transition
                                            if (autoGrowthEnabled && currentScriptId != null) {
                                                val now = System.currentTimeMillis()
                                                if (now - lastAutoGrowthTime > 300000) { // 5 minutes cooldown
                                                    if (!isRunning || workerGeneration != myGen || isReleased) break
                                                    val modeName = if (autoGrowthMode == "creator") "Creator" else "Navigator"
                                                    remoteLog("INFO", "🤖 [$modeName] 轉場嚴重卡頓 (Stuck Transition). 請求 AI 介入找路...")
                                                    lastAutoGrowthTime = now
                                                    pause() // Pause FSM
                                                    service.triggerAutoGrowth(screen, currentScriptId!!)
                                                    continue
                                                } else {
                                                    val leftSec = (300000 - (now - lastAutoGrowthTime)) / 1000
                                                    remoteLog("WARN", "⚠️ AI 仍在冷卻中 (${leftSec}s)，放棄呼叫。")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // -----------------------------

                // Identify State (Where am I?)
                // Pass 'variables' to allow Perception to update them (Extraction)
                val detectedId = identifyScene(screen, currentSceneId, myGen)
                if (!isRunning || workerGeneration != myGen || isReleased) break
                
                // Blind Trust fallback logic
                var activeId = detectedId
                if (activeId == null && currentSceneId != null) {
                     val node = getNodeById(currentSceneId!!)
                     val anchors = node?.optJSONObject("data")?.optJSONArray("anchors")
                     // If current state has NO anchors defined, we assume strict adherence (Blind State)
                     if (anchors == null || anchors.length() == 0) {
                         activeId = currentSceneId
                         remoteLog("DEBUG", "🙈 盲從模式: 因為此場景無特徵，直接假設我們在 [$activeId] [NodeID: $activeId]")
                     }
                }

                // Global Exit Recovery (v1.7.15)
                // When we leave a Global State (Interrupt) and find nothing (activeId == null),
                // we FORCE reset to Root. This allows the FSM to "find from beginning" (re-scan Root and its neighbors).
                if (activeId == null && currentSceneId != null) {
                    val currNode = getNodeById(currentSceneId!!)
                    if (currNode?.optJSONObject("data")?.optBoolean("isGlobal") == true) {
                        remoteLog("INFO", "[FSM] ⚡ 全域事件結束 (Global Exit). 重置回初始場景 (Root) 以重新確認位置...")
                        activeId = findRootNodeId()
                    }
                }

                if (activeId != null) {
                    lostFrameCount = 0 // Reset lost counter
                    val activeSceneName = getNodeName(activeId)
                    if (activeId != currentSceneId) {
                         remoteLog("INFO", "[場景] 📍 切換: ${getNodeName(currentSceneId)} -> $activeSceneName")
                         currentSceneId = activeId
                         currentSceneName = activeSceneName  // Sync Fleet status
                         reportState(activeId!!)
                         idleFrameCount = 0
                         
                         // --- Fallback Action Loop Detection ---
                         val previousSceneName = getNodeName(previousSceneId)
                         if (activeId == previousSceneId || (previousSceneId != null && activeSceneName == previousSceneName)) {
                             consecutiveFallbackCount++
                             remoteLog("WARN", "⚠️ 預測轉場失敗，退回原場景 [$activeSceneName] (連續 $consecutiveFallbackCount 次)")
                             
                                 if (consecutiveFallbackCount >= 2 && autoGrowthEnabled && currentScriptId != null) {
                                     val now = System.currentTimeMillis()
                                     consecutiveFallbackCount = 0 // Reset after trigger attempt
                                     if (now - lastAutoGrowthTime > 300000) { // 5 minutes cooldown
                                         if (!isRunning || workerGeneration != myGen || isReleased) break
                                         val modeName = if (autoGrowthMode == "creator") "Creator" else "Navigator"
                                         remoteLog("INFO", "🤖 [$modeName] 預測轉場連續失效 (卡在動作迴圈). 請求 AI 介入脫困...")
                                         lastAutoGrowthTime = now
                                         pause()
                                         service.triggerAutoGrowth(screen, currentScriptId!!)
                                         continue
                                     } else {
                                         val leftSec = (300000 - (now - lastAutoGrowthTime)) / 1000
                                         remoteLog("WARN", "⚠️ AI 仍在冷卻中 (${leftSec}s)，放棄呼叫。")
                                     }
                                 }
                             } else {
                                 // Successful transition to a truly new scene
                                 consecutiveFallbackCount = 0
                             }
                             // --------------------------------------
                             
                             // Check for Parent Group (Loop Region)
                             val activeNode = getNodeById(activeId)
                             val parentId = activeNode?.optString("parentNode")
                             if (!parentId.isNullOrEmpty()) {
                                 val parentNode = getNodeById(parentId)
                                 val parentLabel = parentNode?.optJSONObject("data")?.optString("label") ?: parentId
                                 if (parentNode?.optString("type") == "group") {
                                    remoteLog("INFO", "[Flow] 📂 位於群組/迴圈區域: $parentLabel")
                                 }
                             }
                        }
                        
                        // Update Fleet uptime
                        updateUptime()

                        // 2. Decision (Brain)
                        val action = decideNextAction(screen, activeId, myGen)
                        if (!isRunning || workerGeneration != myGen || isReleased) break
                        
                        if (action != null) {
                            idleFrameCount = 0
                            lastTransitionAction = action
                            remoteLog("INFO", "🎯 [$activeSceneName] 執行: '${action.region.optString("label")}' -> 前往 [${getNodeName(action.targetSceneId)}] [NodeID: $activeId]")
                            
                            // 3. Action (Hand) - Handle CHECK_EXIT (No Click)
                            var actionObj = action.region.optJSONObject("action") ?: JSONObject()
                            
                            // Try region logic
                            var dynPoints = action.region.optJSONArray("_dynamicPoints")
                            
                            // Fallback to Scene Anchor logic (Matrix is on the scene)
                            if (dynPoints == null) {
                                val activeAnchors = getNodeById(activeId)?.optJSONObject("data")?.optJSONArray("anchors")
                                if (activeAnchors != null) {
                                    for (i in 0 until activeAnchors.length()) {
                                        val anc = activeAnchors.getJSONObject(i)
                                        val points = anc.optJSONArray("_dynamicPoints")
                                        if (points != null) {
                                            dynPoints = points
                                            // For anchors, we must move it to the region so actionSystem can see it
                                            action.region.put("_dynamicPoints", points)
                                            anc.remove("_dynamicPoints") // consume from anchor
                                            break
                                        }
                                    }
                                }
                            }
                            
                            val originalType = actionObj.optString("type").uppercase()
                            
                            if (dynPoints != null && originalType != "CLICK_MATCHES" && originalType != "CLICK_ALL") {
                                // Clone actionObj to avoid mutating original script (only for PATH_SWIPE injection)
                                actionObj = JSONObject(actionObj.toString())
                                actionObj.put("type", "PATH_SWIPE")
                                val params = actionObj.optJSONObject("params") ?: JSONObject()
                                params.put("points", dynPoints)
                                actionObj.put("params", params)
                                action.region.remove("_dynamicPoints") // consume since it's now in params array
                                remoteLog("INFO", "🔍 [DEBUG] Successfully assigned PATH_SWIPE. pointsArray length: ${dynPoints.length()}")
                            }
                            
                            val actionType = actionObj.optString("type").uppercase()
                            remoteLog("INFO", "🔍 [DEBUG] actionType is: $actionType")
                            
                            if (actionType != "CHECK_EXIT") {
                                val waitBefore = resolveWaitTime(action.region, "wait_before", 0L)
                                if (waitBefore > 0) {
                                    remoteLog("INFO", "[場景: $activeSceneName] ⏳ 執行前等待: ${waitBefore}ms")
                                    smartSleep(waitBefore, myGen)
                                }
                                if (!isRunning || workerGeneration != myGen || isReleased) break

                                val activeNode = getNodeById(activeId)
                                val activeRes = activeNode?.optJSONObject("resolution")
                                    ?: activeNode?.optJSONObject("data")?.optJSONObject("resolution")
                                actionSystem.performAction(actionObj, action.region, activeRes)
                                if (!isRunning || workerGeneration != myGen || isReleased) break
                                incrementActionsCount() // Fleet Stats
                            } else {
                                remoteLog("INFO", "[場景: $activeSceneName] ⏭️ 純跳轉 (無點擊)")
                            }
                            
                            if (!isRunning || workerGeneration != myGen || isReleased) break
                            applySideEffects(action.region)
                            updateHistory(action.region)
                            
                            val waitAfter = resolveWaitTime(action.region, "wait_after", 1000L)
                            remoteLog("INFO", "[場景: $activeSceneName] ⏳ 執行後冷卻: ${waitAfter}ms")
                            smartSleep(waitAfter, myGen)
                            if (!isRunning || workerGeneration != myGen || isReleased) break

                            // Predictive Transition: Immediately switch state to Target
                            if (action.targetSceneId != currentSceneId) {
                                 remoteLog("INFO", "🔮 預測切換: $activeSceneName -> ${getNodeName(action.targetSceneId)}")
                                 previousSceneId = currentSceneId
                                 currentSceneId = action.targetSceneId
                                 currentSceneName = getNodeName(currentSceneId) // Sync Fleet status
                                 reportState(action.targetSceneId)
                                 lastTransitionTime = System.currentTimeMillis()
                                 transitionStuckCount = 0 // Reset stuck counter for new transition
                            }
                        } else {
                             // Idle in state (Waiting for cooldowns or trigger)
                             idleFrameCount++
                             if (idleFrameCount >= 20 && autoGrowthEnabled && currentScriptId != null) {
                                 val now = System.currentTimeMillis()
                                 idleFrameCount = 0
                                 if (now - lastAutoGrowthTime > 300000) { // 5 minutes cooldown
                                     if (!isRunning || workerGeneration != myGen || isReleased) break
                                     val modeName = if (autoGrowthMode == "creator") "Creator" else "Navigator"
                                     remoteLog("INFO", "🤖 [$modeName] 停留過久無動作 (Stuck Idle). 請求 AI 介入...")
                                     lastAutoGrowthTime = now
                                     pause()
                                     service.triggerAutoGrowth(screen, currentScriptId!!)
                                     continue
                                 } else {
                                     val leftSec = (300000 - (now - lastAutoGrowthTime)) / 1000
                                     remoteLog("WARN", "⚠️ AI 仍在冷卻中 (${leftSec}s)，放棄呼叫。")
                                 }
                             }
                             smartSleep(500, myGen)
                        }
                    } else {
                        lostFrameCount++
                        val lostThreshold = 5 // 2.5s lost
                        
                        if (lostFrameCount < lostThreshold) {
                             remoteLog("DEBUG", "❓ 畫面陌生，正在尋找特徵... ($lostFrameCount/$lostThreshold)")
                             smartSleep(500, myGen)
                        } else {
                             // --- Smart Navigation Recovery: Global Scan (v1.9.15) ---
                             remoteLog("WARN", "⚠️ 迷路確認 (Lost Track). 啟動全圖定位掃描...")
                             
                             val recoveredId = globalScan(screen, myGen)
                             if (!isRunning || workerGeneration != myGen || isReleased) break
                             if (recoveredId != null) {
                                 val recoveredName = getNodeName(recoveredId)
                                 remoteLog("INFO", "🧭 定位成功! 我們在: [$recoveredName]")
                                 
                                 // Teleport State
                                 currentSceneId = recoveredId
                                 currentSceneName = recoveredName
                                 reportState(recoveredId)
                                 
                                 // Reset counters
                                 lostFrameCount = 0
                                 transitionStuckCount = 0
                                 consecutiveFallbackCount = 0
                                 lastTransitionTime = 0 // Stop waiting for old transition
                             } else {
                                 remoteLog("WARN", "❌ 全圖定位失敗. 嘗試 Generic Back 或重置...")
                                 
                                 if (lostFrameCount >= 20) { // 10s total lost
                                      remoteLog("ERROR", "🚨 迷路太久了 (超過 10 秒)！")
                                      
                                      if (autoGrowthEnabled && currentScriptId != null) {
                                          val now = System.currentTimeMillis()
                                          lostFrameCount = 0
                                          if (now - lastAutoGrowthTime > 300000) { // 5 minutes cooldown
                                              if (!isRunning || workerGeneration != myGen || isReleased) break
                                              remoteLog("INFO", "🤖 Auto-Growth 已啟用，請求 AI 協助修復腳本...")
                                              lastAutoGrowthTime = now
                                              pause()
                                              service.triggerAutoGrowth(screen, currentScriptId!!)
                                              continue
                                          } else {
                                              val leftSec = (300000 - (now - lastAutoGrowthTime)) / 1000
                                              remoteLog("WARN", "⚠️ AI 仍在冷卻中 (${leftSec}s)，放棄呼叫並決定回到起點 (Root) 重新開始。")
                                              currentSceneId = findRootNodeId()
                                              if (currentSceneId != null) reportState(currentSceneId!!)
                                          }
                                      } else {
                                          remoteLog("ERROR", "決定回到起點 (Root) 重新開始。")
                                          currentSceneId = findRootNodeId()
                                          if (currentSceneId != null) reportState(currentSceneId!!)
                                          lostFrameCount = 0
                                      }
                                 }
                             }
                             smartSleep(500, myGen)
                        }
                    }
                    } finally {
                        if (!screen.isRecycled) screen.recycle()
                    }
                } catch (e: InterruptedException) {
                    if (!isRunning || workerGeneration != myGen || isReleased) break
                } catch (e: Exception) {
                    if (!isRunning || workerGeneration != myGen || isReleased) break
                    Log.e(TAG, "循環錯誤", e)
                    try { Thread.sleep(1000) } catch (_: InterruptedException) {
                        if (!isRunning || workerGeneration != myGen || isReleased) break
                    }
                }
            }
        } finally {
            var shouldRelease = false
            synchronized(lifecycleLock) {
                if (activeWorkerCount > 0) activeWorkerCount--
                if (workerThread === Thread.currentThread()) workerThread = null
                if (workerGeneration == myGen && isRunning) {
                    isRunning = false
                    isPaused = false
                    if (!isReleased) {
                        currentStatus = "error"
                        if (lastError == null) lastError = "工作執行緒異常終止"
                    }
                }
                if (activeWorkerCount == 0 && !perceptionReleased) {
                    if (releasePending || isReleased) {
                        perceptionReleased = true
                        shouldRelease = true
                    } else if (!isRunning && !isStarting) {
                        perceptionSystem.clearCache()
                        executionHistory.clear()
                    }
                }
            }
            if (shouldRelease) {
                perceptionSystem.release()
                executionHistory.clear()
            }
        }
    }
    
    // --- Helper Methods ---

    /**
     * Scan ALL nodes to find where we are.
     * Prioritize: Root, Hubs (Nodes with many outgoing edges), then others.
     */
    private fun globalScan(screen: Bitmap, myGen: Long = workerGeneration): String? {
        if (!isRunning || isReleased || workerGeneration != myGen) return null
        val nodes = graphData?.optJSONArray("nodes") ?: return null
        
        // Strategy: 
        // 1. Check Root (Most likely reset point)
        val rootId = findRootNodeId()
        if (rootId != null) {
            val rootNode = getNodeById(rootId)
            if (rootNode != null) {
                val rootActive = perceptionSystem.isStateActive(screen, rootNode, variables, getNodeName(rootId))
                if (!isRunning || isReleased || workerGeneration != myGen) return null
                if (rootActive) {
                    return rootId
                }
            }
        }
        
        // 2. Check "Hub" nodes (Nodes with > 2 outgoing connections are likely menus/lobbies)
        // Optimization: Sort nodes by number of edges (connectivity)? 
        // For now, just linear scan all enabled nodes.
        
        for (i in 0 until nodes.length()) {
            if (!isRunning || isReleased || workerGeneration != myGen) return null
            val node = nodes.getJSONObject(i)
            val id = node.getString("id")
            if (id == rootId) continue // Already checked
            
            // Skip disabled
            if (!node.optBoolean("enabled", true)) continue
            val nodeData = node.optJSONObject("data")
            if (nodeData?.optBoolean("enabled", true) == false) continue
            
            // Perform Check
            // Use verbose=false to reduce log spam during scan
            val nodeActive = perceptionSystem.isStateActive(screen, node, variables, getNodeName(id), verbose = false)
            if (!isRunning || isReleased || workerGeneration != myGen) return null
            if (nodeActive) {
                return id
            }
        }
        
        return null
    }

    private fun getNodeName(id: String?): String {
        if (id == null) return "未知"
        val node = getNodeById(id) ?: return id
        val label = node.optJSONObject("data")?.optString("label")
        return if (label.isNullOrEmpty()) id else label
    }

    private fun checkAppFocus(): Boolean {
        val originPkg = service.getOriginPackageName()
        val currentPkg = service.getFgPackageName()
        
        if (originPkg != null && currentPkg != null && originPkg != currentPkg && currentPkg != service.packageName) {
            Log.w(TAG, "🛡️ 應用程式失焦: $currentPkg != $originPkg. 嘗試恢復...")
            try {
                val intent = service.packageManager.getLaunchIntentForPackage(originPkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    service.startActivity(intent)
                    Thread.sleep(3000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "恢復失敗", e)
            }
            return false
        }
        return true
    }

    private var lastTransitionTime: Long = 0L

    private fun identifyScene(screen: Bitmap, currentId: String?, myGen: Long = workerGeneration): String? {
        if (!isRunning || isReleased || workerGeneration != myGen) return null
        val nodes = graphData?.optJSONArray("nodes") ?: return null
        
        // Priority 1: Global Scenes (Interrupts)
        for (i in 0 until nodes.length()) {
            if (!isRunning || isReleased || workerGeneration != myGen) return null
            val node = nodes.getJSONObject(i)
            if (node.optJSONObject("data")?.optBoolean("isGlobal") == true) {
                // Optimization: Allow Scoped Monitors to run by skipping self in Global Check
                // Self will be checked in Priority 2 (Stability)
                if (currentId != null && node.getString("id") == currentId) continue
                val sceneName = getNodeName(node.getString("id"))
                val matched = perceptionSystem.isStateActive(screen, node, variables, sceneName)
                if (!isRunning || isReleased || workerGeneration != myGen) return null
                if (matched) {
                    Log.d(TAG, "[場景] ⚡ 全域中斷: $sceneName")
                    return node.getString("id")
                }
            }
        }
        
        // Priority 1.5: Scoped Monitors (Group Specific Interrupts)
        if (currentId != null) {
            val currNode = getNodeById(currentId)
            val currentGroupId = currNode?.optString("parentNode") // ReactFlow parentNode is the Group ID
            
            if (!currentGroupId.isNullOrEmpty()) {
                for (i in 0 until nodes.length()) {
                    if (!isRunning || isReleased || workerGeneration != myGen) return null
                    val node = nodes.getJSONObject(i)
                    val nodeData = node.optJSONObject("data")
                    val nodeGroupId = node.optString("parentNode")
                    
                    // Debug Log for Scoped Check
                    Log.d(TAG, "[監聽] 🔍 檢查: 節點=${getNodeName(node.optString("id"))} 群組=${getNodeName(nodeGroupId)} 當前群組=${getNodeName(currentGroupId)} 區域監聽=${nodeData?.optBoolean("isScoped")}")

                    if (nodeData != null && nodeData.optBoolean("isScoped") && nodeGroupId == currentGroupId) {
                         val sceneName = getNodeName(node.getString("id"))
                         val matched = perceptionSystem.isStateActive(screen, node, variables, sceneName)
                         if (!isRunning || isReleased || workerGeneration != myGen) return null
                         if (matched) {
                             Log.d(TAG, "[場景] 🛡️ 區域監聽触發 (Scope: $currentGroupId): $sceneName")
                             return node.getString("id")
                         }
                    }
                }
            }
        }
        
        // Priority 2: Current Scene (Stability & Grace Period)
        if (currentId != null) {
             if (!isRunning || isReleased || workerGeneration != myGen) return null
             val currNode = getNodeById(currentId)
             if (currNode != null) {
                 val sceneName = getNodeName(currentId)
                     val inGracePeriod = System.currentTimeMillis() - lastTransitionTime < 3000
                     
                     // Status Check (Priority 2): Current Scene (Stability)
                     // 修復：不再使用 retryOnFail，直接傳入 screen 判定
                     val stable = perceptionSystem.isStateActive(screen, currNode, variables, sceneName, verbose = false)
                     if (!isRunning || isReleased || workerGeneration != myGen) return null
                     if (stable) {
                         // Stay
                         return currentId
                     }
                     
                     // GRACE PERIOD: If we just transitioned, hold this state blindly for 3 seconds
                     // This prevents falling back to the previous scene while the new one loads.
                     if (inGracePeriod) {
                         remoteLog("DEBUG", "[場景] 🛡️ 轉換保護: 維持在 $sceneName (等待畫面載入...)")
                         return currentId
                     } else {
                         // Grace period over, unexpected loss!
                         remoteLog("WARN", "⚠️ [$sceneName] 視覺丟失! (Grace Period Expired) [NodeID: $currentId]")
                         // Debug: Why lost? (Verbose Check)
                         perceptionSystem.isStateActive(screen, currNode, variables, sceneName, verbose = true)
                         if (!isRunning || isReleased || workerGeneration != myGen) return null
                     }
             }
        }
        
        // Priority 3: Hierarchical Search (Neighbors + Root)
        // If we are in a state, only look where we can go, plus Root (in case of reset)
        // If we are lost (currentId == null), look everywhere (or just Root?) -> Let's look Root Priority, then All.
        
        val candidates = mutableSetOf<String>()
        
        if (currentId != null) {
            // 3a. Add Neighbors
            val currNode = getNodeById(currentId)
            val regions = currNode?.optJSONObject("data")?.optJSONArray("regions")
            if (regions != null) {
                for (i in 0 until regions.length()) {
                    val r = regions.getJSONObject(i)
                    if (!r.optBoolean("enabled", true)) continue // Skip disabled transitions
                    
                    val target = r.optString("target")
                    if (target.isNotEmpty() && target != currentId) {
                        candidates.add(target)
                    }
                }
            }
        }
        
        // Add previous scene to candidates as well, in case we failed a transition and wasn't a neighbor
        if (previousSceneId != null && previousSceneId != currentId) {
             candidates.add(previousSceneId!!)
        }
        
        // Always add Root to candidates (Common fallback)
        for (i in 0 until nodes.length()) {
             val node = nodes.getJSONObject(i)
             val isRoot = node.optJSONObject("data")?.optBoolean("isRoot") == true
             if (isRoot) {
                 val rootId = node.getString("id")
                 if (rootId != currentId) {
                     candidates.add(rootId)
                 }
             }
             // If we are completely lost, add everything?
             if (currentId == null) {
                 candidates.add(node.getString("id"))
             }
        }

        // Execute Search on Candidates
        for (id in candidates) {
            if (!isRunning || isReleased || workerGeneration != myGen) return null
            val node = getNodeById(id) ?: continue
            val nodeData = node.optJSONObject("data")
            
            // Skip disabled nodes (Check both root and data, and both conventions)
            if (!node.optBoolean("enabled", true)) continue
            if (node.optBoolean("disabled", false)) continue
            if (nodeData != null) {
                 if (!nodeData.optBoolean("enabled", true)) continue
                 if (nodeData.optBoolean("disabled", false)) continue
            }

            // Skip globals (already checked) and current (already checked)
            if (nodeData?.optBoolean("isGlobal") == true) continue
            if (id == currentId) continue
            
            val sceneName = getNodeName(id)
            val matched = perceptionSystem.isStateActive(screen, node, variables, sceneName)
            if (!isRunning || isReleased || workerGeneration != myGen) return null
            if (matched) {
                 remoteLog("DEBUG", "[場景] 🔍 發現狀態: $sceneName [NodeID: $id]")
                 return id
            }
        }
        
        return null // Strict: No match found
    }

    data class TransitionAction(val region: JSONObject, val targetSceneId: String)

    private fun decideNextAction(screen: Bitmap, sceneId: String, myGen: Long = workerGeneration): TransitionAction? {
        if (!isRunning || isReleased || workerGeneration != myGen) return null
        val currentNode = getNodeById(sceneId) ?: return null
        val sceneName = getNodeName(sceneId)
        val regions = currentNode.optJSONObject("data")?.optJSONArray("regions")
        if (regions == null || regions.length() == 0) return null
        
        val candidates = mutableListOf<JSONObject>()
        
        for (i in 0 until regions.length()) {
            if (!isRunning || isReleased || workerGeneration != myGen) return null
            val r = regions.getJSONObject(i)
            if (!r.optBoolean("enabled", true)) continue
            
            // 0. User Override Check (Runtime Toggle & exposedParamKey)
            // Variable: "enable_{label}" (1=On, 0=Off). Default to On if null.
            val label = r.optString("label")
            if (label.isNotEmpty()) {
                val overrideKey = "enable_$label"
                if (variables.containsKey(overrideKey)) {
                    val overrideVal = variables[overrideKey] ?: 1
                    if (overrideVal == 0) {
                        Log.d(TAG, "[場景: $sceneName] 🚫 用戶設定停用: '$label' (變數: $overrideKey=0)")
                        continue
                    }
                }
            }

            val exposedParamKey = r.optString("exposedParamKey")
            val hasExposedOverride = exposedParamKey.isNotEmpty() && variables.containsKey(exposedParamKey)
            val exposedOverrideVal = if (hasExposedOverride) (variables[exposedParamKey] ?: 0) else null
            if (hasExposedOverride && exposedOverrideVal != null && exposedOverrideVal <= 0 && r.optJSONObject("schedule") == null && r.optJSONObject("condition") == null) {
                Log.d(TAG, "[場景: $sceneName] 🚫 exposedParamKey 停用: '$label' ($exposedParamKey=$exposedOverrideVal)")
                continue
            }
            
            var isRunnable = true
            
            // Schedule Checks
            val schedule = r.optJSONObject("schedule")
            val id = r.optString("id")
            if (schedule != null && id.isNotEmpty()) {
                val history = executionHistory.getOrPut(id) { ExecutionData() }
                val mode = schedule.optString("mode", "NONE")
                
                if (mode == "INTERVAL") {
                    val intervalSec = if (hasExposedOverride && exposedOverrideVal != null) exposedOverrideVal else schedule.optInt("interval", 0)
                    val now = System.currentTimeMillis()
                    if (now - history.lastRunTime < intervalSec * 1000L) isRunnable = false
                } else if (mode == "COUNT") {
                    val max = if (hasExposedOverride && exposedOverrideVal != null) exposedOverrideVal else schedule.optInt("maxTimes", 0)
                    if (hasExposedOverride && exposedOverrideVal != null && exposedOverrideVal <= 0) {
                        isRunnable = false
                    } else if (max > 0 && history.runCount >= max) {
                        isRunnable = false
                    }
                }
            } else if (hasExposedOverride && exposedOverrideVal != null && id.isNotEmpty()) {
                // If region has exposedParamKey and no explicit schedule block, enforce repeat count if > 0 or disable if <= 0
                val history = executionHistory.getOrPut(id) { ExecutionData() }
                if (exposedOverrideVal <= 0 || history.runCount >= exposedOverrideVal) {
                    isRunnable = false
                }
            }
            
            val perceptionObj = r.optJSONObject("perception")
            val perceptionArr = r.optJSONArray("perception")
            val perceptions = mutableListOf<JSONObject>()
            if (perceptionArr != null) {
                for (k in 0 until perceptionArr.length()) {
                    perceptions.add(perceptionArr.getJSONObject(k))
                }
            } else if (perceptionObj != null) {
                perceptions.add(perceptionObj)
            }

            val perceptionExtractsVar = perceptions.any {
                it.optString("storeVariable").isNotEmpty() || it.optString("variableName").isNotEmpty()
            }

            // Logic Condition (Pre-check if perception does not extract a variable)
            val condition = r.optJSONObject("condition")
            if (isRunnable && condition != null && !perceptionExtractsVar) {
                if (!evaluateCondition(condition, hasExposedOverride, exposedParamKey, exposedOverrideVal)) {
                    isRunnable = false
                }
            }
            
            if (isRunnable) {
                // Perception Trigger Check (Eyes on Hand)
                // Support Multiple Perceptions (OR Logic)
                val resolution = currentNode.optJSONObject("resolution") ?: currentNode.optJSONObject("data")?.optJSONObject("resolution")
                if (perceptions.isNotEmpty()) {
                    var anyMatch = false
                    
                    for ((k, p) in perceptions.withIndex()) {
                        if (!isRunning || isReleased || workerGeneration != myGen) return null
                        val anchor = JSONObject()
                        anchor.put("x", r.optDouble("x", 0.0))
                        anchor.put("y", r.optDouble("y", 0.0))
                        anchor.put("w", r.optDouble("w", r.optDouble("width", 0.0)))
                        anchor.put("h", r.optDouble("h", r.optDouble("height", 0.0)))
                        
                        val keys = p.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            anchor.put(key, p.get(key))
                        }
                        
                        remoteLog("DEBUG", "[Perception Check] #${k+1}: Type=${p.optString("matchType")} Target=${p.optString("targetText")}${p.optString("targetColor")}")

                        val matched = perceptionSystem.isStateActive(screen, createFakeNode(anchor, resolution), variables, sceneName, true)
                        if (!isRunning || isReleased || workerGeneration != myGen) return null
                        if (matched) {
                            val dynamicPoints = anchor.optJSONArray("_dynamicPoints")
                            if (dynamicPoints != null) {
                                r.put("_dynamicPoints", dynamicPoints)
                            }
                            val matchedPoint = anchor.optJSONObject("_matchedPoint")
                            if (matchedPoint != null) {
                                r.put("_matchedPoint", matchedPoint)
                            }
                            
                            anyMatch = true
                            remoteLog("INFO", "✅ [$sceneName] 條件 #${k+1} 符合 (OR Logic)")
                            break
                        } else {
                            remoteLog("DEBUG", "❌ [$sceneName] 條件 #${k+1} 不符合")
                        }
                    }

                    if (!anyMatch) {
                        isRunnable = false
                        remoteLog("DEBUG", "⚠️ [$sceneName] 跳過 '${r.optString("label")}' (動作條件未滿足)")
                    } else {
                        remoteLog("DEBUG", "✅ [$sceneName] 條件符合，準備執行: '${r.optString("label")}'")
                    }
                } else {
                    // Inherit _matchedPoint from scene anchors if coordinates match
                    val nodeAnchors = currentNode.optJSONObject("data")?.optJSONArray("anchors")
                    if (nodeAnchors != null) {
                        val rx = r.optDouble("x", -100.0)
                        val ry = r.optDouble("y", -100.0)
                        for (aIdx in 0 until nodeAnchors.length()) {
                            val aObj = nodeAnchors.optJSONObject(aIdx) ?: continue
                            val mp = aObj.optJSONObject("_matchedPoint") ?: continue
                            if (kotlin.math.abs(aObj.optDouble("x", -200.0) - rx) < 5.0 &&
                                kotlin.math.abs(aObj.optDouble("y", -200.0) - ry) < 5.0) {
                                r.put("_matchedPoint", mp)
                                break
                            }
                        }
                    }
                }
            }

            // Logic Condition (Post-check after OCR/AI variable extraction)
            if (isRunnable && condition != null && perceptionExtractsVar) {
                if (!evaluateCondition(condition, hasExposedOverride, exposedParamKey, exposedOverrideVal)) {
                    isRunnable = false
                }
            }

            if (isRunnable) {
                candidates.add(r)
            }
        }
        
        if (candidates.isEmpty()) return null
        
        // Sort by Priority (Low = High), honoring durable local Task Config overrides
        candidates.sortWith(compareBy<JSONObject> { r ->
            val lbl = r.optString("label")
            val customPriority = if (lbl.isNotEmpty()) taskPriorities[lbl] else null
            customPriority ?: (r.optJSONObject("schedule")?.optInt("priority", 5) ?: 5)
        })
        
        val best = candidates[0]
        val target = best.optString("target")
        // Fix: Treat "null" string as Stay (empty) to prevent state loss
        val finalTarget = if (target.isEmpty() || target == "null") sceneId else target
        return TransitionAction(best, finalTarget)
    }

    private fun evaluateCondition(
        condition: JSONObject,
        hasExposedOverride: Boolean,
        exposedParamKey: String,
        exposedOverrideVal: Int?
    ): Boolean {
        val v = condition.optString("variable").ifEmpty {
            if (hasExposedOverride) exposedParamKey else ""
        }
        if (v.isEmpty()) return true

        val valStored = variables[v] ?: 0
        val op = condition.optString("operator", ">").trim()
        val rawTarget = if (hasExposedOverride && exposedOverrideVal != null && condition.optString("variable").isEmpty()) {
            exposedOverrideVal.toString()
        } else {
            condition.optString("value", "0").trim()
        }
        val targetNum = rawTarget.toDoubleOrNull() ?: 0.0
        val currentNum = valStored.toDouble()

        val passed = when (op) {
            "<" -> currentNum < targetNum
            "<=" -> currentNum <= targetNum
            ">" -> currentNum > targetNum
            ">=" -> currentNum >= targetNum
            "==", "=" -> if (rawTarget.toDoubleOrNull() != null) currentNum == targetNum else valStored.toString() == rawTarget
            "!=" -> if (rawTarget.toDoubleOrNull() != null) currentNum != targetNum else valStored.toString() != rawTarget
            "contains" -> valStored.toString().contains(rawTarget, ignoreCase = true)
            else -> valStored > 0
        }
        remoteLog("DEBUG", "[邏輯] 檢查變數: $v ($valStored $op $rawTarget) -> ${if (passed) "通過" else "未通過"}")
        return passed
    }

    private fun applySideEffects(region: JSONObject) {
        val sideEffect = region.optJSONObject("sideEffect") ?: return
        val effectType = sideEffect.optString("type", "DECREMENT").uppercase()
        val exposedKey = region.optString("exposedParamKey")
        val v = if (exposedKey.isNotEmpty() && variables.containsKey(exposedKey)) exposedKey else sideEffect.optString("variable")
        if (v.isEmpty()) return

        val old = variables[v] ?: 0
        val delta = sideEffect.optInt("value", 1)
        val newVal = when (effectType) {
            "INCREMENT", "ADD" -> old + delta
            "SET" -> sideEffect.optInt("value", 0)
            else -> (old - delta).coerceAtLeast(0)
        }
        variables[v] = newVal
        Log.d(TAG, "[邏輯] 🔄 變數更新 ($effectType): $v ($old -> $newVal)")
    }

    private fun smartSleep(ms: Long, myGen: Long) {
        if (ms <= 0) return
        val startTime = System.currentTimeMillis()
        var elapsed = 0L
        
        // Chunked sleep to allow fast stop() or generation preemption
        while (isRunning && workerGeneration == myGen && !isReleased && elapsed < ms) {
            val remaining = ms - elapsed
            val chunk = if (remaining > 200) 200 else remaining
            try {
                Thread.sleep(chunk)
            } catch (e: InterruptedException) {
                // Thread interrupted
                break
            }
            elapsed = System.currentTimeMillis() - startTime
        }
        
        if (ms >= 5000 && isRunning && workerGeneration == myGen && !isReleased) {
             Log.d(TAG, "⏰ 休眠結束 (Planned: ${ms}ms, Actual: ${elapsed}ms)")
        }
    }
    private fun updateHistory(region: JSONObject) {
        val id = region.optString("id")
        if (id.isNotEmpty()) {
            val h = executionHistory.getOrPut(id) { ExecutionData() }
            h.lastRunTime = System.currentTimeMillis()
            h.runCount++
        }
    }

    @Synchronized
    fun updateScriptGraph(jsonString: String) {
        try {
            val newGraph = JSONObject(jsonString)
            // Validate Root exists in new graph
            if (findRootNodeId(newGraph) == null) {
                remoteLog("ERROR", "❌ 熱更新失敗: 新腳本中找不到起始節點 (Root Node)")
                return
            }
            
            this.graphData = newGraph
            
            // 1. 保留當前運行中的變數數值 (Preserve Runtime Values)
            // 2. 僅新增新腳本中定義的新變數 (Add New Defaults)
            val settingsObj = graphData?.optJSONObject("metadata")?.optJSONObject("settings")
            
            // Sync Auto-Growth Settings
            if (settingsObj != null) {
                autoGrowthEnabled = settingsObj.optBoolean("autoGrowth", false)
                autoGrowthObjective = settingsObj.optString("autoGrowthObjective", "")
                autoGrowthMode = settingsObj.optString("autoGrowthMode", "navigator")
                remoteLog("DEBUG", "♻️ 同步 AI 設定: Enabled=$autoGrowthEnabled, Mode=$autoGrowthMode")
            }
            
            val settingsVars = settingsObj?.optJSONObject("variables")
            
            if (settingsVars != null) {
                val keys = settingsVars.keys()
                var addedCount = 0
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!variables.containsKey(key)) {
                        variables[key] = settingsVars.optInt(key, 0)
                        addedCount++
                    }
                }
                if (addedCount > 0) remoteLog("DEBUG", "➕ 熱更新新增了 $addedCount 個新變數")
            }

            reloadTaskConfig()
            
            remoteLog("INFO", "🔥 腳本熱更新成功 (Hot Reload). 運行狀態與變數已保留.")
            service.showToast("⚡ 腳本已無縫更新版本")
            
        } catch (e: Exception) {
            remoteLog("ERROR", "❌ 熱更新解析失敗: ${e.message}")
        }
    }

    private fun findRootNodeId(source: JSONObject? = null): String? {
        val data = source ?: graphData
        val nodes = data?.optJSONArray("nodes") ?: return null
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (node.optJSONObject("data")?.optBoolean("isRoot") == true) return node.getString("id")
        }
        if (nodes.length() > 0) return nodes.getJSONObject(0).getString("id")
        return null
    }

    private fun getNodeById(id: String): JSONObject? {
        val nodes = graphData?.optJSONArray("nodes") ?: return null
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (node.getString("id") == id) return node
        }
        return null
    }
    private fun createFakeNode(anchorRegion: JSONObject, resolution: JSONObject? = null): JSONObject {
        val fakeData = JSONObject()
        val anchors = org.json.JSONArray()
        anchors.put(anchorRegion)
        fakeData.put("anchors", anchors)
        
        val fakeNode = JSONObject()
        fakeNode.put("data", fakeData)
        if (resolution != null) {
            fakeNode.put("resolution", resolution)
        }
        return fakeNode
    }

    private fun getDeviceId(): String {
        if (deviceId == null) {
            val savedId = context.getSharedPreferences("GameAutoPrefs", Context.MODE_PRIVATE).getString("device_id", null)
                ?: service.getAppDeviceId()
            deviceId = if (!savedId.isNullOrEmpty()) {
                savedId
            } else {
                android.provider.Settings.Secure.getString(service.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
            }
        }
        return deviceId!!
    }

    fun remoteLog(level: String, message: String, tr: Throwable? = null) {
        // 1. Android Local Log
        when(level) {
            "INFO" -> Log.i(TAG, message)
            "DEBUG" -> Log.d(TAG, message)
            "WARN" -> Log.w(TAG, message)
            "ERROR" -> Log.e(TAG, message, tr)
        }
        
        // 2. Queue for Remote
        if (isRemoteLogEnabled) {
            val entry = JSONObject()
            entry.put("timestamp", System.currentTimeMillis())
            entry.put("level", level)
            entry.put("message", if (tr != null) "$message\n${Log.getStackTraceString(tr)}" else message)
            
            logQueue.offer(entry)
        }
    }

    private fun reportState(nodeId: String) {
        val payload = JSONObject()
        payload.put("nodeId", nodeId)
        payload.put("timestamp", System.currentTimeMillis())
        
        val packet = JSONObject()
        packet.put("deviceId", getDeviceId())
        packet.put("scriptId", currentScriptId)
        packet.put("licenseKey", service.getLicenseKey()) // Add License Key for Live Debug
        packet.put("type", "state")
        packet.put("payload", payload)
        
        // Send immediately (High Priority)
        if (!networkExecutor.isShutdown) {
            try {
                networkExecutor.execute {
                    sendNetworkRequest(packet)
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // Ignore dispatch raced with engine release()
            }
        }
    }

    private fun flushLogs() {
        if (!isRemoteLogEnabled || logQueue.isEmpty()) return
        
        val batch = org.json.JSONArray()
        // Take up to 50 logs
        var count = 0
        while(!logQueue.isEmpty() && count < 50) {
            batch.put(logQueue.poll())
            count++
        }
        
        if (batch.length() == 0) return

        val packet = JSONObject()
        packet.put("deviceId", getDeviceId())
        packet.put("scriptId", currentScriptId)
        packet.put("licenseKey", service.getLicenseKey()) // Add License Key for Live Debug
        packet.put("type", "log")
        packet.put("payload", batch)
        
        if (!networkExecutor.isShutdown) {
            try {
                networkExecutor.execute {
                    sendNetworkRequest(packet)
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // Ignore log flush raced with engine release()
            }
        }
    }

    private fun sendNetworkRequest(jsonBody: JSONObject, isRetry: Boolean = false) {
        val userToken = service.getUserToken()
        val licenseKey = service.getLicenseKey()
        if (userToken.isNullOrEmpty() && licenseKey.isNullOrEmpty()) {
            return
        }

        var conn: java.net.HttpURLConnection? = null
        try {
            val url = java.net.URL("${BuildConfig.AI_API_URL.trimEnd('/')}/api/log-stream")
            conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("x-api-secret", BuildConfig.AI_API_SECRET)
            conn.setRequestProperty("x-device-id", getDeviceId())

            if (!userToken.isNullOrEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $userToken")
            } else if (!licenseKey.isNullOrEmpty()) {
                conn.setRequestProperty("x-license-key", licenseKey)
            }

            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            java.io.OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { os ->
                os.write(jsonBody.toString())
                os.flush()
            }

            val code = conn.responseCode
            if (code == 401 && !userToken.isNullOrEmpty() && !isRetry) {
                conn.disconnect()
                conn = null
                if (service.refreshTokenSync(userToken)) {
                    sendNetworkRequest(jsonBody, isRetry = true)
                    return
                }
            }

            if (code == 401 || code == 403) {
                Log.e(TAG, "⛔ 伺服器拒絕訪問 ($code). 授權可能已過期或被封鎖. 強制停止腳本.")
                val currentWorker: Thread?
                synchronized(lifecycleLock) {
                    workerGeneration++
                    isRunning = false
                    isPaused = false
                    isStarting = false
                    startingGen = 0L
                    currentWorker = workerThread
                    if (!isReleased) {
                        currentStatus = "error"
                        lastError = "Authorization Failed ($code)"
                    }
                    if (activeWorkerCount == 0 && !perceptionReleased && !isReleased) {
                        perceptionSystem.clearCache()
                        executionHistory.clear()
                    }
                }
                currentWorker?.interrupt()
                Handler(Looper.getMainLooper()).post {
                    service.showToast("⛔ 授權無效，停止執行")
                    service.updateStatus("Auth Failed")
                }
            } else if (code != 200) {
                Log.w(TAG, "Remote Log Failed: $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote Log Network Error: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
    
    // === Fleet 遠端控制方法 (Fleet Remote Control) ===
    
    /**
     * 暫停執行
     */
    fun pause() {
        val paused = synchronized(lifecycleLock) {
            if (!isRunning || isReleased) return
            isPaused = true
            currentStatus = "paused"
            true
        }
        if (paused) {
            remoteLog("INFO", "⏸️ Fleet Command: Paused")
        }
    }
    
    /**
     * 繼續執行
     */
    fun resume() {
        val resumed = synchronized(lifecycleLock) {
            if (!isRunning || isReleased) return
            isPaused = false
            currentStatus = "running"
            true
        }
        if (resumed) {
            remoteLog("INFO", "▶️ Fleet Command: Resumed")
        }
    }

    private fun resolveWaitTime(obj: JSONObject, key: String, defaultMs: Long): Long {
        val raw = when {
            obj.has(key) && !obj.isNull(key) -> obj.opt(key)
            key == "wait_after" && obj.has("delayAfter") && !obj.isNull("delayAfter") -> obj.opt("delayAfter")
            key == "wait_before" && obj.has("delayBefore") && !obj.isNull("delayBefore") -> obj.opt("delayBefore")
            else -> null
        }
        if (raw is Number) return raw.toLong().coerceAtLeast(0L)
        if (raw is String) {
             if (raw.startsWith("$")) {
                 val varName = raw.substring(1)
                 val stored = variables[varName]
                 // Variable stores INT (seconds likely). Convert to MS.
                 if (stored != null) {
                      remoteLog("DEBUG", "[變數] ⏳ 使用動態等待時間: \$$varName = ${stored}s -> ${stored * 1000}ms")
                      return (stored * 1000L).coerceAtLeast(0L)
                 }
             }
             // Try parse string number
             return raw.toLongOrNull()?.coerceAtLeast(0L) ?: defaultMs
        }
        return defaultMs
    }
    
    private data class RestartSnapshot(
        val graphObj: JSONObject,
        val scriptId: String?,
        val scriptName: String?,
        val prevWorker: Thread?,
        val gen: Long
    )

    private fun stopForRestartLocked(): RestartSnapshot? {
        val savedGraphObj = graphData ?: return null
        val savedScriptId = currentScriptId
        val savedScriptName = scriptName
        val prevWorker = workerThread
        val restartGen = ++workerGeneration
        isRunning = false
        isPaused = false
        isStarting = false
        startingGen = 0L
        currentStatus = "idle"
        if (activeWorkerCount == 0 && !perceptionReleased) {
            perceptionSystem.clearCache()
            executionHistory.clear()
        }
        return RestartSnapshot(savedGraphObj, savedScriptId, savedScriptName, prevWorker, restartGen)
    }

    /**
     * 重啟執行 (非阻塞停止後重新開始，絕不於主執行緒休眠)
     */
    fun restart() {
        val snap = synchronized(lifecycleLock) {
            if (isReleased) return
            stopForRestartLocked() ?: return
        }
        val prevWorker = snap.prevWorker
        prevWorker?.interrupt()

        Thread({
            var interrupted = false
            try {
                prevWorker?.join(1500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                interrupted = true
            }
            val stillAuthorized = !interrupted && synchronized(lifecycleLock) {
                !isReleased && workerGeneration == snap.gen && !isRunning && !isStarting
            }
            if (stillAuthorized) {
                val savedGraph = try { snap.graphObj.toString() } catch (_: Exception) { null }
                if (savedGraph != null) {
                    start(savedGraph, snap.scriptId, snap.scriptName, snap.gen) {
                        if (!isReleased) {
                            remoteLog("INFO", "🔄 Fleet Command: Restarted")
                        }
                    }
                }
            }
        }, "SceneGraphRestart").start()
    }
    
    private fun coerceToInt(value: Any?): Int {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is Float -> value.toInt()
            is Boolean -> if (value) 1 else 0
            is String -> value.toIntOrNull() ?: (if (value.equals("true", ignoreCase = true)) 1 else 0)
            else -> 0
        }
    }

    /**
     * 更新變數並持久化至 remoteOverrides (確保 start 時不會被覆蓋)
     */
    fun updateVariables(newVars: Map<String, Any>) {
        if (newVars.isEmpty()) return
        remoteOverrides.putAll(newVars)
        for ((key, value) in newVars) {
            val intValue = coerceToInt(value)
            variables[key] = intValue
            Log.d(TAG, "[Fleet] 變數更新: $key = $intValue")
        }
        remoteLog("INFO", "🔄 Fleet Command: Variables Updated (${newVars.size} vars)")
    }

    /**
     * 從遠端更新變數
     */
    fun updateVariablesFromRemote(vars: JSONObject?) {
        if (vars == null) return
        val newVars = mutableMapOf<String, Any>()
        val keys = vars.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = vars.opt(key)
            if (value != null && value != JSONObject.NULL) {
                newVars[key] = value
            }
        }
        updateVariables(newVars)
    }
    
    /**
     * 從遠端啟動 (可能需要載入新腳本)
     */
    fun startRemote(scriptIdToLoad: String?) {
        // 如果提供了 scriptId，需要先載入腳本
        if (scriptIdToLoad != null) {
            remoteLog("INFO", "📡 Fleet Command: Start with script $scriptIdToLoad")
            // 通知 Service 載入並執行指定腳本
            service.loadScriptFromNetwork(scriptIdToLoad)
        } else if (graphData != null) {
            // 使用現有腳本繼續
            resume()
        } else {
            remoteLog("WARN", "⚠️ Fleet Command: No script to start")
        }
    }
    
    /**
     * 獲取變數快照 (供 FleetSyncManager 使用)
     */
    fun getVariablesSnapshot(): Map<String, Int> {
        return variables.toMap()
    }
    
    /**
     * 更新運行時間統計
     */
    fun updateUptime() {
        if (startTime > 0 && isRunning) {
            uptimeSeconds = ((System.currentTimeMillis() - startTime) / 1000).toInt()
        }
    }
    
    /**
     * 增加動作計數
     */
    fun incrementActionsCount() {
        actionsCounter.incrementAndGet()
    }
    
    /**
     * 增加錯誤計數
     */
    fun incrementErrorsCount() {
        errorsCounter.incrementAndGet()
    }
    
    /**
     * 設置最後錯誤訊息
     */
    fun setLastError(error: String) {
        lastError = error
        errorsCounter.incrementAndGet()
    }
    
    /**
     * 檢查是否暫停
     */
    fun isPaused(): Boolean = isPaused

    /**
     * Executes a one-time Navigator sequence based on AI actions,
     * bypassing the script mutating logic.
     */
    fun executeNavigationSequence(actions: JSONArray) {
        lostFrameCount = 0 // Reset lost frame logic so FSM has a chance to rediscover
        
        for (i in 0 until actions.length()) {
            val action = actions.optJSONObject(i) ?: continue
            val type = action.optString("type", "click").lowercase()
            val reason = action.optString("reason", "脫困操作")
            
            remoteLog("INFO", "👉 AI 序列動作 [${i+1}/${actions.length()}]: $type - $reason")
            
            when (type) {
                "click" -> {
                    val x = action.optString("x", "-1.0").toDoubleOrNull() ?: -1.0
                    val y = action.optString("y", "-1.0").toDoubleOrNull() ?: -1.0
                    if (x >= 0 && y >= 0) {
                        val actionConfig = JSONObject().apply { put("type", "CLICK") }
                        val regionConfig = JSONObject().apply {
                            put("label", "AI 導航: $reason")
                            put("x", x as Any)
                            put("y", y as Any)
                            put("w", 2.0 as Any)
                            put("h", 2.0 as Any)
                        }
                        incrementActionsCount()
                        actionSystem.performAction(actionConfig, regionConfig)
                        
                        // Default wait after click if not the last action
                        if (i < actions.length() - 1) {
                            try { Thread.sleep(1000) } catch (e: Exception) {}
                        }
                    }
                }
                "swipe" -> {
                    val x = action.optString("x", "-1.0").toDoubleOrNull() ?: -1.0
                    val y = action.optString("y", "-1.0").toDoubleOrNull() ?: -1.0
                    val direction = action.optString("direction", "UP").uppercase()
                    val duration = action.optString("duration", "500").toLongOrNull() ?: 500L
                    
                    if (x >= 0 && y >= 0) {
                        val actionConfig = JSONObject().apply { 
                            put("type", "SWIPE")
                            put("params", JSONObject().apply {
                                put("direction", direction)
                                put("duration", duration)
                            })
                        }
                        val regionConfig = JSONObject().apply {
                            put("label", "AI 導航: $reason")
                            put("x", x as Any)
                            put("y", y as Any)
                            put("w", 2.0 as Any)
                            put("h", 2.0 as Any)
                        }
                        incrementActionsCount()
                        actionSystem.performAction(actionConfig, regionConfig)
                        
                        if (i < actions.length() - 1) {
                            try { Thread.sleep(1000) } catch (e: Exception) {}
                        }
                    }
                }
                "wait" -> {
                    val duration = action.optString("duration", "1000").toLongOrNull() ?: 1000L
                    try { Thread.sleep(duration) } catch (e: Exception) {}
                }
            }
        }
    }
}
