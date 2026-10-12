package com.gameautoeditor.player

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray

class SceneGraphEngine(val service: AutomationService) {
    private val TAG = "GameAuto"
    private var graphData: JSONObject? = null
    private var isRunning = false
    private var workerThread: Thread? = null

    // Remote Logging
    private val logQueue = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
    private var lastLogFlushTime = 0L
    private val networkExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var deviceId: String? = null
    var isRemoteLogEnabled: Boolean = false

    // Systems
    private val perceptionSystem = PerceptionSystem(service) { level, msg ->
        remoteLog(level, msg)
    }
    private val actionSystem = ActionSystem(service)

    // State
    data class ExecutionData(var lastRunTime: Long = 0, var runCount: Int = 0)
    private val executionHistory = mutableMapOf<String, ExecutionData>()
    private val variables = mutableMapOf<String, Int>()
    private var previousSceneId: String? = null
    private var lostFrameCount = 0
    private var transitionStuckCount = 0
    private var consecutiveFallbackCount = 0
    private var lastTransitionAction: TransitionAction? = null
    private var autoGrowthEnabled: Boolean = false
    var autoGrowthObjective: String? = null
    var autoGrowthMode: String = "navigator" // "navigator" or "creator"
    private var lastAutoGrowthTime: Long = 0L
    private var idleFrameCount = 0

    private var currentScriptId: String? = null
    
    // === Fleet 狀態追蹤 (Fleet Status Tracking) ===
    var currentStatus: String = "idle"      // idle / running / paused / error
        private set
    var currentSceneId: String? = null      // 當前場景 ID (公開給 FleetSyncManager)
        private set
    var currentSceneName: String? = null    // 當前場景名稱
        private set
    var scriptId: String? = null            // 當前腳本 ID
        get() = currentScriptId
        private set
    var scriptName: String? = null          // 當前腳本名稱
        private set
    var actionsCount: Int = 0               // 本次執行的動作數
        private set
    var errorsCount: Int = 0                // 錯誤次數
        private set
    var uptimeSeconds: Int = 0              // 運行時間 (秒)
        private set
    var lastError: String? = null           // 最後錯誤訊息
        private set
    private var startTime: Long = 0         // 開始執行的時間戳
    private var isPaused: Boolean = false   // 暫停狀態

    @Synchronized
    fun start(jsonString: String, scriptId: String? = null, scriptDisplayName: String? = null) {
        this.currentScriptId = scriptId
        this.scriptName = scriptDisplayName ?: "Unknown"
        if (isRunning) {
            Log.w(TAG, "⚠️ 引擎已在運行中，忽略啟動請求")
            return
        }
        
        // Ensure previous thread is truly dead
        if (workerThread != null && workerThread!!.isAlive) {
            Log.w(TAG, "⚠️ 舊的 Worker Thread 尚未結束，強制停止...")
            isRunning = false
            try {
                workerThread?.join(1000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }

        isRunning = true
        isPaused = false
        currentStatus = "running"
        startTime = System.currentTimeMillis()
        actionsCount = 0
        errorsCount = 0
        lastError = null
        
        try {
            graphData = JSONObject(jsonString)
            
            // Initialize Variables from Global Settings
            variables.clear()
            val settingsObj = graphData?.optJSONObject("metadata")?.optJSONObject("settings")
            autoGrowthEnabled = settingsObj?.optBoolean("autoGrowth", false) ?: false
            autoGrowthObjective = settingsObj?.optString("autoGrowthObjective", "")
            autoGrowthMode = settingsObj?.optString("autoGrowthMode", "navigator") ?: "navigator"
            
            val settingsVars = settingsObj?.optJSONObject("variables")
            
            if (settingsVars != null) {
                val keys = settingsVars.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    variables[key] = settingsVars.optInt(key, 0)
                }
            }
            remoteLog("INFO", "🤖 SceneGraphEngine (FSM) 已啟動. 版本: ${BuildConfig.VERSION_NAME} (Log-Target). 變數: $variables")

            workerThread = Thread { runLoop() }
            workerThread?.start()
        } catch (e: Exception) {
            Log.e(TAG, "解析腳本失敗", e)
            isRunning = false
            currentStatus = "error"
            lastError = e.message
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning && currentStatus == "idle") return
        isRunning = false
        isPaused = false
        currentStatus = "idle"
        
        // Do not block UI thread too long, but try to join for cleanliness if called from background
        // But usually stop() is called from UI or Service. 
        // Just setting isRunning = false should break the loop.
        
        perceptionSystem.clearCache()
        executionHistory.clear()
        remoteLog("INFO", "⏹️ 已停止")
    }

    private fun runLoop() {
        Thread.sleep(1000)
        currentSceneId = findRootNodeId()
        
        if (currentSceneId == null) {
            remoteLog("ERROR", "❌ 腳本中找不到起始節點 (Root Node)")
            service.showToast("腳本錯誤：找不到起始節點")
            isRunning = false
            currentStatus = "error"
            lastError = "找不到起始節點 (Root Node)"
            return
        }
        
        currentSceneName = getNodeName(currentSceneId)
        
        // Initial State Report
        reportState(currentSceneId!!)

        while (isRunning) {
            try {
                if (isPaused) {
                    Thread.sleep(500)
                    continue
                }

                // Guardian Logic
                if (!checkAppFocus()) {
                    Thread.sleep(1000)
                    continue
                }
                
                // Flush Logs periodically (every 1s)
                if (System.currentTimeMillis() - lastLogFlushTime > 1000) {
                    flushLogs()
                    lastLogFlushTime = System.currentTimeMillis()
                }

                // 1. Perception (Eye)
                val screen = service.captureScreenSync()
                if (screen == null) {
                    Thread.sleep(500)
                    continue
                }

                // --- Smart Transition Wait (v2: Overlay Support) ---
                if (previousSceneId != null && System.currentTimeMillis() - lastTransitionTime < 3000) {
                    var targetResolved = false
                    
                    // 1. Priority Check: Is Target (currentSceneId) ALREADY visible?
                    // If yes, it means we are in an Overlay situation (New window popped up, old background still there).
                    // We should accept this as "Arrived" and NOT wait.
                    val targetNode = if (currentSceneId != null) getNodeById(currentSceneId!!) else null
                    if (targetNode != null) {
                        val hasAnchors = (targetNode.optJSONObject("data")?.optJSONArray("anchors")?.length() ?: 0) > 0
                        
                        if (hasAnchors) {
                            val targetName = getNodeName(currentSceneId)
                            if (perceptionSystem.isStateActive(screen, targetNode, variables, targetName, verbose = false)) {
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
                            // Quiet check for previous scene
                            if (perceptionSystem.isStateActive(screen, prevNode, variables, prevName, verbose = false)) {
                                transitionStuckCount++
                                if (transitionStuckCount <= 20) { // Max 10 seconds (20 * 500ms)
                                    remoteLog("INFO", "⏳ 轉場中... 還在 [$prevName] ($transitionStuckCount/20)")
                                    
                                    // Retry Logic (User Request)
                                    if (transitionStuckCount % 6 == 0 && lastTransitionAction != null) {
                                         val label = lastTransitionAction?.region?.optString("label") ?: "Unknown"
                                         remoteLog("WARN", "🔄 卡太久了，重試動作: $label")
                                         val actionConfig = lastTransitionAction?.region?.optJSONObject("action")
                                         if (actionConfig != null) {
                                             actionSystem.performAction(actionConfig, lastTransitionAction!!.region, prevNode.optJSONObject("resolution"))
                                         }
                                    }

                                    lastTransitionTime = System.currentTimeMillis() // Keep extending
                                    screen.recycle()
                                    smartSleep(500)
                                    continue // Skip this frame
                                } else {
                                    remoteLog("WARN", "[FSM] ⚠️ 轉場逾時 (Stuck > 10s). 放棄等待，強制執行下一步判定.")
                                    lastTransitionTime = 0 // Stop waiting
                                    
                                    // Trigger Auto-Growth if stuck in transition
                                    if (autoGrowthEnabled && currentScriptId != null) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastAutoGrowthTime > 300000) { // 5 minutes cooldown
                                            val modeName = if (autoGrowthMode == "creator") "Creator" else "Navigator"
                                            remoteLog("INFO", "🤖 [$modeName] 轉場嚴重卡頓 (Stuck Transition). 請求 AI 介入找路...")
                                            lastAutoGrowthTime = now
                                            pause() // Pause FSM
                                            service.triggerAutoGrowth(screen, currentScriptId!!)
                                            // Don't recycle screen here as it's passed to AI
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
                val detectedId = identifyScene(screen, currentSceneId)
                
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
                                 if (now - lastAutoGrowthTime > 300000) { // 5 minutes cooldown
                                     val modeName = if (autoGrowthMode == "creator") "Creator" else "Navigator"
                                     remoteLog("INFO", "🤖 [$modeName] 預測轉場連續失效 (卡在動作迴圈). 請求 AI 介入脫困...")
                                     lastAutoGrowthTime = now
                                     // We pause FSM so perception loop doesn't override the AI popup
                                     pause() 
                                     service.triggerAutoGrowth(screen, currentScriptId!!)
                                 } else {
                                     val leftSec = (300000 - (now - lastAutoGrowthTime)) / 1000
                                     remoteLog("WARN", "⚠️ AI 仍在冷卻中 (${leftSec}s)，放棄呼叫。")
                                 }
                                 consecutiveFallbackCount = 0 // Reset after trigger attempt
                             }
                         } else {
                             // Successful transition to a truly new scene
                             consecutiveFallbackCount = 0
                         }
                         // --------------------------------------
                         
                         // Check for Parent Group (Loop Region)
                         val activeNode = getNodeById(activeId!!)
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
                    val action = decideNextAction(screen, activeId!!)
                    
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
                        
                        val originalType = actionObj.optString("type")
                        
                        if (dynPoints != null && originalType != "CLICK_MATCHES") {
                            // Clone actionObj to avoid mutating original script (only for PATH_SWIPE injection)
                            actionObj = JSONObject(actionObj.toString())
                            actionObj.put("type", "PATH_SWIPE")
                            val params = actionObj.optJSONObject("params") ?: JSONObject()
                            params.put("points", dynPoints)
                            actionObj.put("params", params)
                            action.region.remove("_dynamicPoints") // consume since it's now in params array
                            remoteLog("INFO", "🔍 [DEBUG] Successfully assigned PATH_SWIPE. pointsArray length: ${dynPoints.length()}")
                        }
                        
                        val actionType = actionObj.optString("type")
                        remoteLog("INFO", "🔍 [DEBUG] actionType is: $actionType")
                        
                        if (actionType != "CHECK_EXIT") {
                            val waitBefore = resolveWaitTime(action.region, "wait_before", 0L)
                            if (waitBefore > 0) {
                                remoteLog("INFO", "[場景: $activeSceneName] ⏳ 執行前等待: ${waitBefore}ms")
                                smartSleep(waitBefore)
                            }

                            actionSystem.performAction(actionObj, action.region, getNodeById(activeId)?.optJSONObject("resolution"))
                            incrementActionsCount() // Fleet Stats
                        } else {
                            remoteLog("INFO", "[場景: $activeSceneName] ⏭️ 純跳轉 (無點擊)")
                        }
                        
                        applySideEffects(action.region)
                        updateHistory(action.region)
                        
                        val waitAfter = resolveWaitTime(action.region, "wait_after", 1000L)
                        remoteLog("INFO", "[場景: $activeSceneName] ⏳ 執行後冷卻: ${waitAfter}ms")
                        smartSleep(waitAfter)

                        // Predictive Transition: Immediately switch state to Target
                        if (action.targetSceneId != null && action.targetSceneId != currentSceneId) {
                             remoteLog("INFO", "🔮 預測切換: $activeSceneName -> ${getNodeName(action.targetSceneId)}")
                             previousSceneId = currentSceneId
                             currentSceneId = action.targetSceneId
                             currentSceneName = getNodeName(currentSceneId) // Sync Fleet status
                             reportState(currentSceneId!!)
                             lastTransitionTime = System.currentTimeMillis()
                             transitionStuckCount = 0 // Reset stuck counter for new transition
                        }
                    } else {
                         // Idle in state (Waiting for cooldowns or trigger)
                         idleFrameCount++
                         if (idleFrameCount >= 20 && autoGrowthEnabled && currentScriptId != null) {
                             val now = System.currentTimeMillis()
                             if (now - lastAutoGrowthTime > 300000) { // 5 minutes cooldown
                                 val modeName = if (autoGrowthMode == "creator") "Creator" else "Navigator"
                                 remoteLog("INFO", "🤖 [$modeName] 停留過久無動作 (Stuck Idle). 請求 AI 介入...")
                                 lastAutoGrowthTime = now
                                 pause() // 暫停 FSM，避免在 AI 分析期間繼續截圖掃描
                                 service.triggerAutoGrowth(screen, currentScriptId!!)
                             } else {
                                 val leftSec = (300000 - (now - lastAutoGrowthTime)) / 1000
                                 remoteLog("WARN", "⚠️ AI 仍在冷卻中 (${leftSec}s)，放棄呼叫。")
                             }
                             idleFrameCount = 0
                         }
                         smartSleep(500)
                    }
                } else {
                    lostFrameCount++
                    val lostThreshold = 5 // 2.5s lost
                    
                    if (lostFrameCount < lostThreshold) {
                         remoteLog("DEBUG", "❓ 畫面陌生，正在尋找特徵... ($lostFrameCount/$lostThreshold)")
                         smartSleep(500)
                    } else {
                         // --- Smart Navigation Recovery: Global Scan (v1.9.15) ---
                         // We are officially lost. Stop guessing, start scanning EVERYTHING.
                         remoteLog("WARN", "⚠️ 迷路確認 (Lost Track). 啟動全圖定位掃描...")
                         
                         val recoveredId = globalScan(screen)
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
                                      if (now - lastAutoGrowthTime > 300000) { // 5 minutes cooldown
                                          remoteLog("INFO", "🤖 Auto-Growth 已啟用，請求 AI 協助修復腳本...")
                                          lastAutoGrowthTime = now
                                          pause() // 暫停 FSM，避免在 AI 分析期間繼續截圖掃描
                                          service.triggerAutoGrowth(screen, currentScriptId!!)
                                      } else {
                                          val leftSec = (300000 - (now - lastAutoGrowthTime)) / 1000
                                          remoteLog("WARN", "⚠️ AI 仍在冷卻中 (${leftSec}s)，放棄呼叫並決定回到起點 (Root) 重新開始。")
                                          currentSceneId = findRootNodeId()
                                          if (currentSceneId != null) reportState(currentSceneId!!)
                                      }
                                      lostFrameCount = 0 // 重置以避免頻繁重複觸發
                                  } else {
                                      remoteLog("ERROR", "決定回到起點 (Root) 重新開始。")
                                      currentSceneId = findRootNodeId()
                                      if (currentSceneId != null) reportState(currentSceneId!!)
                                      lostFrameCount = 0
                                  }
                             }
                         }
                         smartSleep(500)
                    }
                }
                
                screen.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "循環錯誤", e)
                Thread.sleep(1000)
            }
        }
    }
    
    // --- Helper Methods ---

    /**
     * Scan ALL nodes to find where we are.
     * Prioritize: Root, Hubs (Nodes with many outgoing edges), then others.
     */
    private fun globalScan(screen: Bitmap): String? {
        val nodes = graphData?.optJSONArray("nodes") ?: return null
        
        // Strategy: 
        // 1. Check Root (Most likely reset point)
        val rootId = findRootNodeId()
        if (rootId != null) {
            val rootNode = getNodeById(rootId)
            if (rootNode != null && perceptionSystem.isStateActive(screen, rootNode, variables, getNodeName(rootId))) {
                return rootId
            }
        }
        
        // 2. Check "Hub" nodes (Nodes with > 2 outgoing connections are likely menus/lobbies)
        // Optimization: Sort nodes by number of edges (connectivity)? 
        // For now, just linear scan all enabled nodes.
        
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            val id = node.getString("id")
            if (id == rootId) continue // Already checked
            
            // Skip disabled
            if (!node.optBoolean("enabled", true)) continue
            val nodeData = node.optJSONObject("data")
            if (nodeData?.optBoolean("enabled", true) == false) continue
            
            // Perform Check
            // Use verbose=false to reduce log spam during scan
            if (perceptionSystem.isStateActive(screen, node, variables, getNodeName(id), verbose = false)) {
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

    private fun identifyScene(screen: Bitmap, currentId: String?): String? {
        val nodes = graphData?.optJSONArray("nodes") ?: return null
        
        // Priority 1: Global Scenes (Interrupts)
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (node.optJSONObject("data")?.optBoolean("isGlobal") == true) {
                // Optimization: Allow Scoped Monitors to run by skipping self in Global Check
                // Self will be checked in Priority 2 (Stability)
                if (currentId != null && node.getString("id") == currentId) continue
                val sceneName = getNodeName(node.getString("id"))
                if (perceptionSystem.isStateActive(screen, node, variables, sceneName)) {
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
                    val node = nodes.getJSONObject(i)
                    val nodeData = node.optJSONObject("data")
                    val nodeGroupId = node.optString("parentNode")
                    
                    // Debug Log for Scoped Check
                    Log.d(TAG, "[監聽] 🔍 檢查: 節點=${getNodeName(node.optString("id"))} 群組=${getNodeName(nodeGroupId)} 當前群組=${getNodeName(currentGroupId)} 區域監聽=${nodeData?.optBoolean("isScoped")}")

                    if (nodeData != null && nodeData.optBoolean("isScoped") && nodeGroupId == currentGroupId) {
                         val sceneName = getNodeName(node.getString("id"))
                         if (perceptionSystem.isStateActive(screen, node, variables, sceneName)) {
                             Log.d(TAG, "[場景] 🛡️ 區域監聽触發 (Scope: $currentGroupId): $sceneName")
                             return node.getString("id")
                         }
                    }
                }
            }
        }
        
        // Priority 2: Current Scene (Stability & Grace Period)
        if (currentId != null) {
             val currNode = getNodeById(currentId)
             if (currNode != null) {
                 val sceneName = getNodeName(currentId)
                     val inGracePeriod = System.currentTimeMillis() - lastTransitionTime < 3000
                     
                     // Status Check (Priority 2): Current Scene (Stability)
                     // 修復：不再使用 retryOnFail，直接傳入 screen 判定
                     if (perceptionSystem.isStateActive(screen, currNode, variables, sceneName, verbose = false)) {
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
                     }
             }
        }
        
        // Priority 3: Hierarchical Search (Neighbors + Root)
        // If we are in a state, only look where we can go, plus Root (in case of reset)
        // If we are lost (currentId == null), look everywhere (or just Root?) -> Let's look Root Priority, then All.
        
        val candidates = mutableSetOf<String>()
        var foundRootId: String? = null
        
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
                     foundRootId = rootId
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
            if (perceptionSystem.isStateActive(screen, node, variables, sceneName)) {
                 remoteLog("DEBUG", "[場景] 🔍 發現狀態: $sceneName [NodeID: $id]")
                 return id
            }
        }
        
        return null // Strict: No match found
    }

    data class TransitionAction(val region: JSONObject, val targetSceneId: String)

    private fun decideNextAction(screen: Bitmap, sceneId: String): TransitionAction? {
        val currentNode = getNodeById(sceneId) ?: return null
        val sceneName = getNodeName(sceneId)
        val regions = currentNode.optJSONObject("data")?.optJSONArray("regions")
        if (regions == null || regions.length() == 0) return null
        
        val candidates = mutableListOf<JSONObject>()
        
        for (i in 0 until regions.length()) {
            val r = regions.getJSONObject(i)
            if (!r.optBoolean("enabled", true)) continue
            
            // 0. User Override Check (Runtime Toggle)
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
            
            var isRunnable = true
            
            // Schedule Checks
            val schedule = r.optJSONObject("schedule")
            val id = r.optString("id")
            if (schedule != null && id.isNotEmpty()) {
                val history = executionHistory.getOrPut(id) { ExecutionData() }
                val mode = schedule.optString("mode", "NONE")
                
                if (mode == "INTERVAL") {
                    val intervalSec = schedule.optInt("interval", 0)
                    val now = System.currentTimeMillis()
                    if (now - history.lastRunTime < intervalSec * 1000L) isRunnable = false
                } else if (mode == "COUNT") {
                    val max = schedule.optInt("maxTimes", 0)
                    if (max > 0 && history.runCount >= max) isRunnable = false
                }
            }
            
            // Logic Condition
            val condition = r.optJSONObject("condition")
            if (condition != null) {
                val v = condition.optString("variable")
                if (v.isNotEmpty()) {
                     val valStored = variables[v] ?: 0
                     remoteLog("DEBUG", "[邏輯] 檢查變數: $v (目前數值: $valStored)")
                     if (valStored <= 0) isRunnable = false
                }
            }
            
            if (isRunnable) {
                // Perception Trigger Check (Eyes on Hand)
                // Support Multiple Perceptions (OR Logic)
                
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
                
                if (perceptions.isNotEmpty()) {
                    val resolution = currentNode.optJSONObject("resolution")
                    var anyMatch = false
                    
                    for ((k, p) in perceptions.withIndex()) {
                         // Combine Region Coords with Perception Config
                        val anchor = JSONObject()
                        anchor.put("x", r.optDouble("x", 0.0))
                        anchor.put("y", r.optDouble("y", 0.0))
                        anchor.put("w", r.optDouble("w", 0.0))
                        anchor.put("h", r.optDouble("h", 0.0))
                        
                        val keys = p.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            anchor.put(key, p.get(key))
                        }
                        
                        // Log the check being performed
                        remoteLog("DEBUG", "[Perception Check] #${k+1}: Type=${p.optString("matchType")} Target=${p.optString("targetText")}${p.optString("targetColor")}")

                        // Perform Perception Check
                        if (perceptionSystem.isStateActive(screen, createFakeNode(anchor, resolution), variables, sceneName, true)) {
                            val dynamicPoints = anchor.optJSONArray("_dynamicPoints")
                            if (dynamicPoints != null) {
                                r.put("_dynamicPoints", dynamicPoints)
                            }
                            
                            anyMatch = true
                            remoteLog("INFO", "✅ [$sceneName] 條件 #${k+1} 符合 (OR Logic)")
                            break // One match is enough (OR)
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
                }
            }

            if (isRunnable) {
                candidates.add(r)
            }
        }
        
        if (candidates.isEmpty()) return null
        
        // Sort by Priority (Low = High)
        candidates.sortWith(compareBy<JSONObject> { it.optJSONObject("schedule")?.optInt("priority", 5) ?: 5 })
        
        val best = candidates[0]
        val target = best.optString("target")
        // Fix: Treat "null" string as Stay (empty) to prevent state loss
        val finalTarget = if (target.isEmpty() || target == "null") sceneId else target
        return TransitionAction(best, finalTarget)
    }

    private fun applySideEffects(region: JSONObject) {
        val sideEffect = region.optJSONObject("sideEffect") ?: return
        if (sideEffect.optString("type") == "DECREMENT") {
            val v = sideEffect.optString("variable")
            if (v.isNotEmpty()) {
                val old = variables[v] ?: 0
                val newVal = (old - 1).coerceAtLeast(0)
                variables[v] = newVal
                Log.d(TAG, "[邏輯] 📉 變數扣除: $v ($old -> $newVal)")
            }
        }
    }

    private fun smartSleep(ms: Long) {
        if (ms <= 0) return
        val startTime = System.currentTimeMillis()
        var elapsed = 0L
        
        // Chunked sleep to allow fast stop()
        while (isRunning && elapsed < ms) {
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
        
        if (ms >= 5000 && isRunning) {
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
            deviceId = android.provider.Settings.Secure.getString(service.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEVICE"
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
        networkExecutor.execute {
            sendNetworkRequest(packet)
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
        
        networkExecutor.execute {
            sendNetworkRequest(packet)
        }
    }

    private fun sendNetworkRequest(jsonBody: JSONObject) {
        try {
            val url = java.net.URL("https://game-auto-ai.vercel.app/api/log-stream")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF_8")
            // Auth Header (Consistent with PerceptionSystem)
            conn.setRequestProperty("x-api-secret", BuildConfig.AI_API_SECRET) 
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            
            val os = java.io.OutputStreamWriter(conn.outputStream, "UTF-8")
            os.write(jsonBody.toString())
            os.flush()
            os.close()
            
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                 Log.e(TAG, "⛔ 伺服器拒絕訪問 ($code). 授權可能已過期或被封鎖. 強制停止腳本.")
                 isRunning = false
                 currentStatus = "error"
                 lastError = "Authorization Failed ($code)"
                 // Optional: Notify Service
                 Handler(Looper.getMainLooper()).post {
                     service.showToast("⛔ 授權無效，停止執行")
                     service.updateStatus("Auth Failed")
                 }
            } else if (code != 200) {
                // If remote fails, fallback to local log (don't retry endlessly to avoid loops)
                Log.w(TAG, "Remote Log Failed: $code")
            }
            conn.disconnect()
        } catch(e: Exception) {
            Log.w(TAG, "Remote Log Network Error: ${e.message}")
        }
    }
    
    // === Fleet 遠端控制方法 (Fleet Remote Control) ===
    
    /**
     * 暫停執行
     */
    @Synchronized
    fun pause() {
        if (!isRunning) return
        isPaused = true
        currentStatus = "paused"
        remoteLog("INFO", "⏸️ Fleet Command: Paused")
    }
    
    /**
     * 繼續執行
     */
    @Synchronized
    fun resume() {
        if (!isRunning) return
        isPaused = false
        currentStatus = "running"
        remoteLog("INFO", "▶️ Fleet Command: Resumed")
    }

    private fun resolveWaitTime(obj: JSONObject, key: String, defaultMs: Long): Long {
        val raw = obj.opt(key)
        if (raw is Int) return raw.toLong()
        if (raw is Long) return raw
        if (raw is String) {
             if (raw.startsWith("$")) {
                 val varName = raw.substring(1)
                 val stored = variables[varName]
                 // Variable stores INT (seconds likely). Convert to MS.
                 if (stored != null) {
                      remoteLog("DEBUG", "[變數] ⏳ 使用動態等待時間: \$$varName = ${stored}s -> ${stored * 1000}ms")
                      return stored * 1000L
                 }
             }
             // Try parse string number
             return raw.toLongOrNull() ?: defaultMs
        }
        return defaultMs
    }
    
    /**
     * 重啟執行 (停止後重新開始)
     */
    fun restart() {
        val savedGraph = graphData?.toString()
        val savedScriptId = currentScriptId
        val savedScriptName = scriptName
        
        stop()
        Thread.sleep(500)
        
        if (savedGraph != null) {
            start(savedGraph, savedScriptId, savedScriptName)
        }
        remoteLog("INFO", "🔄 Fleet Command: Restarted")
    }
    
    /**
     * 從遠端更新變數
     */
    fun updateVariablesFromRemote(vars: JSONObject?) {
        if (vars == null) return
        
        val keys = vars.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = vars.opt(key)
            
            val intValue = when (value) {
                is Int -> value
                is Double -> value.toInt()
                is Boolean -> if (value) 1 else 0
                is String -> value.toIntOrNull() ?: 0
                else -> 0
            }
            
            variables[key] = intValue
            Log.d(TAG, "[Fleet] 變數更新: $key = $intValue")
        }
        
        remoteLog("INFO", "🔄 Fleet Command: Variables Updated (${vars.length()} vars)")
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
        actionsCount++
    }
    
    /**
     * 增加錯誤計數
     */
    fun incrementErrorsCount() {
        errorsCount++
    }
    
    /**
     * 設置最後錯誤訊息
     */
    fun setLastError(error: String) {
        lastError = error
        errorsCount++
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
                        actionsCount++
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
                        actionsCount++
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
