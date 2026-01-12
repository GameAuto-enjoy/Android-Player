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

    // Systems
    private val perceptionSystem = PerceptionSystem(service)
    private val actionSystem = ActionSystem(service)

    // State
    data class ExecutionData(var lastRunTime: Long = 0, var runCount: Int = 0)
    private val executionHistory = mutableMapOf<String, ExecutionData>()
    private val variables = mutableMapOf<String, Int>()

    fun start(jsonString: String) {
        if (isRunning) return
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
            Log.i(TAG, "🤖 SceneGraphEngine (FSM) 已啟動. 版本: 1.6.2. 變數: $variables")

            workerThread = Thread { runLoop() }
            workerThread?.start()
        } catch (e: Exception) {
            Log.e(TAG, "解析腳本失敗", e)
            isRunning = false
        }
    }

    fun stop() {
        isRunning = false
        perceptionSystem.clearCache()
        executionHistory.clear()
        Log.i(TAG, "⏹️ 已停止")
    }

    private fun runLoop() {
        Thread.sleep(1000)
        var currentSceneId = findRootNodeId()
        
        if (currentSceneId == null) {
            Log.e(TAG, "❌ 腳本中找不到起始節點 (Root Node)")
            service.showToast("腳本錯誤：找不到起始節點")
            isRunning = false
            return
        }

        while (isRunning) {
            try {
                // Guardian Logic
                if (!checkAppFocus()) {
                    Thread.sleep(1000)
                    continue
                }

                // 1. Perception (Eye)
                val screen = service.captureScreenSync()
                if (screen == null) {
                    Thread.sleep(500)
                    continue
                }

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
                         // Log.v(TAG, "⚠️ 盲從模式 (Blind Trust): 強制假設在 $activeId")
                     }
                }

                if (activeId != null) {
                    if (activeId != currentSceneId) {
                         Log.i(TAG, "📍 狀態切換: $currentSceneId -> $activeId")
                         currentSceneId = activeId
                    } else {
                         // Log.v(TAG, "⚓ 維持狀態: $activeId")
                    }

                    // 2. Decision (Brain)
                    val action = decideNextAction(screen, activeId!!)
                    
                    if (action != null) {
                        Log.i(TAG, "⚡ [Action] 執行: '${action.region.optString("label")}' -> 前往: ${action.targetSceneId}")
                        // Log.d(TAG, "   優先級: ${action.region.optJSONObject("schedule")?.optInt("priority", 5) ?: 5}")

                        // 3. Action (Hand) - Handle CHECK_EXIT (No Click)
                        val actionType = action.region.optJSONObject("action")?.optString("type")
                        if (actionType != "CHECK_EXIT") {
                            val waitBefore = action.region.optLong("wait_before", 0L)
                            if (waitBefore > 0) {
                                Log.i(TAG, "⏳ [執行前] 睡眠 ${waitBefore}ms...")
                                Thread.sleep(waitBefore)
                            }

                            actionSystem.performAction(action.region.optJSONObject("action") ?: JSONObject(), action.region)
                        } else {
                            Log.i(TAG, "⏭️ 條件符合，執行純跳轉 (No Click)")
                        }
                        
                        applySideEffects(action.region)
                        updateHistory(action.region)
                        
                        val waitAfter = action.region.optLong("wait_after", 1000L)
                        Log.i(TAG, "⏳ [執行後] 睡眠 ${waitAfter}ms...")
                        Thread.sleep(waitAfter)
                    } else {
                         // Idle in state (Waiting for cooldowns or trigger)
                         Thread.sleep(500)
                    }
                } else {
                    Log.i(TAG, "❓ [Unknown] 未知狀態 (無匹配特徵). 掃描中...")
                    Thread.sleep(500)
                }
                
                screen.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "循環錯誤", e)
                Thread.sleep(1000)
            }
        }
    }
    
    // --- Helper Methods ---

    private fun checkAppFocus(): Boolean {
        val originPkg = service.getOriginPackageName()
        val currentPkg = service.getFgPackageName()
        
        if (originPkg != null && currentPkg != null && originPkg != currentPkg && currentPkg != service.packageName) {
            Log.w(TAG, "🛡️ 應用程式偏移: $currentPkg != $originPkg. 嘗試恢復...")
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

    private fun identifyScene(screen: Bitmap, currentId: String?): String? {
        val nodes = graphData?.optJSONArray("nodes") ?: return null
        
        // Priority 1: Global Scenes
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (node.optJSONObject("data")?.optBoolean("isGlobal") == true) {
                if (perceptionSystem.isStateActive(screen, node, variables)) {
                    Log.d(TAG, "⚡ 觸發全域狀態: ${node.getString("id")}")
                    return node.getString("id")
                }
            }
        }
        
        // Priority 2: Current Scene (Stability)
        if (currentId != null) {
             val currNode = getNodeById(currentId)
             if (currNode != null) {
                 if (perceptionSystem.isStateActive(screen, currNode, variables)) {
                     // Log.v(TAG, "⚓ Staying in Current State: $currentId")
                     return currentId
                 }
             }
        }
        
        // Priority 3: Other Scenes
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            val id = node.getString("id")
            if (id == currentId) continue
            if (node.optJSONObject("data")?.optBoolean("isGlobal") == true) continue 
            
            if (perceptionSystem.isStateActive(screen, node, variables)) {
                Log.d(TAG, "🔍 發現新狀態: $id")
                return id
            }
        }
        
        return null // Strict: No match found
    }

    data class TransitionAction(val region: JSONObject, val targetSceneId: String)

    private fun decideNextAction(screen: Bitmap, sceneId: String): TransitionAction? {
        val currentNode = getNodeById(sceneId) ?: return null
        val regions = currentNode.optJSONObject("data")?.optJSONArray("regions")
        if (regions == null || regions.length() == 0) return null
        
        val candidates = mutableListOf<JSONObject>()
        
        for (i in 0 until regions.length()) {
            val r = regions.getJSONObject(i)
            if (!r.optBoolean("enabled", true)) continue
            
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
                     if (valStored <= 0) isRunnable = false
                }
            }
            
            if (isRunnable) {
                // Perception Trigger Check (Eyes on Hand)
                val perception = r.optJSONObject("perception")
                if (perception != null) {
                    // Combine Region Coords with Perception Config
                    val anchor = JSONObject()
                    anchor.put("x", r.optInt("x", 0))
                    anchor.put("y", r.optInt("y", 0))
                    anchor.put("w", r.optInt("w", 0))
                    anchor.put("h", r.optInt("h", 0))
                    
                    val keys = perception.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        anchor.put(key, perception.get(key))
                    }

                    // Check Match
                    if (!perceptionSystem.isStateActive(screen, createFakeNode(anchor), variables)) {
                        isRunnable = false
                    } else {
                        Log.v(TAG, "👁️ 條件觸發符合: ${r.optString("label")}")
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
                Log.d(TAG, "📉 變數遞減: $v ($old -> $newVal)")
            }
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
    private fun createFakeNode(anchorRegion: JSONObject): JSONObject {
        val fakeData = JSONObject()
        val anchors = org.json.JSONArray()
        anchors.put(anchorRegion)
        fakeData.put("anchors", anchors)
        
        val fakeNode = JSONObject()
        fakeNode.put("data", fakeData)
        return fakeNode
    }
}
