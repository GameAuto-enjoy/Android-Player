package com.gameautoeditor.player

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject

class SceneGraphEngine(private val service: AutomationService) {
    private val TAG = "GameAuto"
    private var graphData: JSONObject? = null
    private var isRunning = false
    private var workerThread: Thread? = null

    // Remote Logging
    private val logQueue = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
    private var lastLogFlushTime = 0L
    private val networkExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var deviceId: String? = null

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
    private var lastTransitionAction: TransitionAction? = null


    private var currentScriptId: String? = null

    @Synchronized
    fun start(jsonString: String, scriptId: String? = null) {
        this.currentScriptId = scriptId
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
        try {
            graphData = JSONObject(jsonString)
            
            // Initialize Variables from Global Settings
            variables.clear()
            val settingsVars = graphData?.optJSONObject("metadata")
                ?.optJSONObject("settings")
                ?.optJSONObject("variables")
            
            if (settingsVars != null) {
                val keys = settingsVars.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    variables[key] = settingsVars.optInt(key, 0)
                }
            }
            remoteLog("INFO", "🤖 SceneGraphEngine (FSM) 已啟動. 版本: 1.7.29 (Log-Target). 變數: $variables")

            workerThread = Thread { runLoop() }
            workerThread?.start()
        } catch (e: Exception) {
            Log.e(TAG, "解析腳本失敗", e)
            isRunning = false
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        
        // Do not block UI thread too long, but try to join for cleanliness if called from background
        // But usually stop() is called from UI or Service. 
        // Just setting isRunning = false should break the loop.
        
        perceptionSystem.clearCache()
        executionHistory.clear()
        remoteLog("INFO", "⏹️ 已停止")
    }

    private fun runLoop() {
        Thread.sleep(1000)
        var currentSceneId = findRootNodeId()
        
        if (currentSceneId == null) {
            remoteLog("ERROR", "❌ 腳本中找不到起始節點 (Root Node)")
            service.showToast("腳本錯誤：找不到起始節點")
            isRunning = false
            return
        }
        
        // Initial State Report
        reportState(currentSceneId!!)

        while (isRunning) {
            try {
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
                            if (perceptionSystem.isStateActive(screen, targetNode, variables, targetName)) {
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
                                    remoteLog("INFO", "[FSM] ⏳ 轉場中... 目標未現，且畫面仍停在 [$prevName]. 延長等待... ($transitionStuckCount/20)")
                                    
                                    // Retry Logic (User Request)
                                    if (transitionStuckCount % 6 == 0 && lastTransitionAction != null) {
                                         val label = lastTransitionAction?.region?.optString("label") ?: "Unknown"
                                         remoteLog("WARN", "[FSM] 🔄 轉場停滯 (檢測到舊場景). 重試動作: $label")
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
                         remoteLog("DEBUG", "[感知] ⚠️ 盲從模式 (Blind Trust): 強制假設在 $activeId (無 anchors)")
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
                         reportState(activeId!!)
                         
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

                    // 2. Decision (Brain)
                    val action = decideNextAction(screen, activeId!!)
                    
                    if (action != null) {
                        lastTransitionAction = action
                        remoteLog("INFO", "[場景: $activeSceneName] ⚡ 執行動作: '${action.region.optString("label")}' (目標: ${getNodeName(action.targetSceneId)})")
                        
                        // 3. Action (Hand) - Handle CHECK_EXIT (No Click)
                        val actionType = action.region.optJSONObject("action")?.optString("type")
                        if (actionType != "CHECK_EXIT") {
                            val waitBefore = action.region.optLong("wait_before", 0L)
                            if (waitBefore > 0) {
                                remoteLog("INFO", "[場景: $activeSceneName] ⏳ 執行前等待: ${waitBefore}ms")
                                smartSleep(waitBefore)
                            }

                            actionSystem.performAction(action.region.optJSONObject("action") ?: JSONObject(), action.region, getNodeById(activeId)?.optJSONObject("resolution"))
                        } else {
                            remoteLog("INFO", "[場景: $activeSceneName] ⏭️ 純跳轉 (無點擊)")
                        }
                        
                        applySideEffects(action.region)
                        updateHistory(action.region)
                        
                        val waitAfter = action.region.optLong("wait_after", 1000L)
                        remoteLog("INFO", "[場景: $activeSceneName] ⏳ 執行後冷卻: ${waitAfter}ms")
                        smartSleep(waitAfter)

                        // Predictive Transition: Immediately switch state to Target
                        if (action.targetSceneId != null && action.targetSceneId != currentSceneId) {
                             remoteLog("INFO", "[FSM] 🔮 預測性切換: $activeSceneName -> ${getNodeName(action.targetSceneId)}")
                             previousSceneId = currentSceneId
                             currentSceneId = action.targetSceneId
                             reportState(currentSceneId!!)
                             lastTransitionTime = System.currentTimeMillis()
                             transitionStuckCount = 0 // Reset stuck counter for new transition
                        }
                    } else {
                         // Idle in state (Waiting for cooldowns or trigger)
                         smartSleep(500)
                    }
                } else {
                    lostFrameCount++
                    remoteLog("DEBUG", "[場景: 未知] ❓ 無匹配特徵，掃描中... ($lostFrameCount/20)")
                    
                    if (lostFrameCount >= 20) {
                         remoteLog("WARN", "⚠️ 迷航過久 (Lost > 10s). 強制重置回初始場景 (Root) 以重新尋找路徑.")
                         currentSceneId = findRootNodeId()
                         if (currentSceneId != null) reportState(currentSceneId!!)
                         lostFrameCount = 0
                    }
                    smartSleep(500)
                }
                
                screen.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "循環錯誤", e)
                Thread.sleep(1000)
            }
        }
    }
    
    // --- Helper Methods ---

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
                val sceneName = getNodeName(node.getString("id"))
                if (perceptionSystem.isStateActive(screen, node, variables, sceneName)) {
                    Log.d(TAG, "[場景] ⚡ 全域中斷: $sceneName")
                    return node.getString("id")
                }
            }
        }
        
        // Priority 2: Current Scene (Stability & Grace Period)
        if (currentId != null) {
             val currNode = getNodeById(currentId)
             if (currNode != null) {
                 val sceneName = getNodeName(currentId)
                 if (perceptionSystem.isStateActive(screen, currNode, variables, sceneName, verbose = false)) {
                     // Stay
                     return currentId
                 }
                 
                 // GRACE PERIOD: If we just transitioned, hold this state blindly for 3 seconds
                 // This prevents falling back to the previous scene while the new one loads.
                 if (System.currentTimeMillis() - lastTransitionTime < 3000) {
                     remoteLog("DEBUG", "[場景] 🛡️ 轉換保護: 維持在 $sceneName (等待畫面載入...)")
                     return currentId
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
                    val target = regions.getJSONObject(i).optString("target")
                    if (target.isNotEmpty() && target != currentId) {
                        candidates.add(target)
                    }
                }
            }
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
            // Skip globals (already checked) and current (already checked)
            if (node.optJSONObject("data")?.optBoolean("isGlobal") == true) continue
            if (id == currentId) continue
            
            val sceneName = getNodeName(id)
            if (perceptionSystem.isStateActive(screen, node, variables, sceneName)) {
                 remoteLog("DEBUG", "[場景] 🔍 發現狀態: $sceneName")
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
                     remoteLog("DEBUG", "[邏輯] 檢查變數: $v = $valStored")
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
                    
                    for (p in perceptions) {
                         // Combine Region Coords with Perception Config
                        val anchor = JSONObject()
                        anchor.put("x", r.optInt("x", 0))
                        anchor.put("y", r.optInt("y", 0))
                        anchor.put("w", r.optInt("w", 0))
                        anchor.put("h", r.optInt("h", 0))
                        
                        val keys = p.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            anchor.put(key, p.get(key))
                        }
                        
                        if (perceptionSystem.isStateActive(screen, createFakeNode(anchor, resolution), variables, sceneName)) {
                            anyMatch = true
                            break // One match is enough (OR)
                        }
                    }
                    
                    if (!anyMatch) {
                        isRunnable = false
                        val targetStr = if (r.optString("target").isEmpty()) "維持" else getNodeName(r.optString("target"))
                        remoteLog("DEBUG", "[場景: $sceneName] ❌ 跳過動作: '${r.optString("label")}' -> 目標: $targetStr (感知不符 - 檢查了 ${perceptions.size} 個條件)")
                    } else {
                        remoteLog("DEBUG", "[場景: $sceneName] 👁️ 觸發條件符合: '${r.optString("label")}'")
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
        return TransitionAction(best, if (target.isEmpty()) sceneId else target)
    }

    private fun applySideEffects(region: JSONObject) {
        val sideEffect = region.optJSONObject("sideEffect") ?: return
        if (sideEffect.optString("type") == "DECREMENT") {
            val v = sideEffect.optString("variable")
            if (v.isNotEmpty()) {
                val old = variables[v] ?: 0
                val newVal = (old - 1).coerceAtLeast(0)
                variables[v] = newVal
                Log.d(TAG, "[邏輯] 📉 變數遞減: $v ($old -> $newVal)")
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

    private fun findRootNodeId(): String? {
        val nodes = graphData?.optJSONArray("nodes") ?: return null
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

    private fun remoteLog(level: String, message: String, tr: Throwable? = null) {
        // 1. Android Local Log
        when(level) {
            "INFO" -> Log.i(TAG, message)
            "DEBUG" -> Log.d(TAG, message)
            "WARN" -> Log.w(TAG, message)
            "ERROR" -> Log.e(TAG, message, tr)
        }
        
        // 2. Queue for Remote
        val entry = JSONObject()
        entry.put("timestamp", System.currentTimeMillis())
        entry.put("level", level)
        entry.put("message", if (tr != null) "$message\n${Log.getStackTraceString(tr)}" else message)
        
        logQueue.offer(entry)
    }

    private fun reportState(nodeId: String) {
        val payload = JSONObject()
        payload.put("nodeId", nodeId)
        payload.put("timestamp", System.currentTimeMillis())
        
        val packet = JSONObject()
        packet.put("deviceId", getDeviceId())
        packet.put("scriptId", currentScriptId)
        packet.put("type", "state")
        packet.put("payload", payload)
        
        // Send immediately (High Priority)
        networkExecutor.execute {
            sendNetworkRequest(packet)
        }
    }

    private fun flushLogs() {
        if (logQueue.isEmpty()) return
        
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
        packet.put("type", "log")
        packet.put("payload", batch)
        
        networkExecutor.execute {
            sendNetworkRequest(packet)
        }
    }

    private fun sendNetworkRequest(jsonBody: JSONObject) {
        try {
            val url = java.net.URL("https://game-auto-editor.vercel.app/api/log-stream")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF_8")
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
}
