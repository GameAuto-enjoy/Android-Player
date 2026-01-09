package com.gameautoeditor.player

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import android.graphics.BitmapFactory
import android.graphics.Bitmap

class ScriptEngine(private val service: AutomationService) {
    
    private val TAG = "GameAuto"
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val variables = mutableMapOf<String, String>()
    private val imageCache = mutableMapOf<String, Bitmap>()
    
    // Graph Data Structures
    private val nodesMap = mutableMapOf<String, JSONObject>()
    private val edgesList = mutableListOf<JSONObject>()
    
    private var sceneGraphEngine: SceneGraphEngine? = null
    
    fun executeScript(scriptJson: String) {
        try {
            val root = JSONObject(scriptJson)
            isRunning = true
            variables.clear()
            
            // Check Format: Graph (ReactFlow) vs Linear
            if (root.has("nodes") && root.has("edges")) {
                // Heuristic: Check for 'scene' nodes to determine engine
                val nodes = root.getJSONArray("nodes")
                var isSceneGraph = false
                for (i in 0 until nodes.length()) {
                    if (nodes.getJSONObject(i).optString("type") == "scene") {
                        isSceneGraph = true
                        break
                    }
                }

                if (isSceneGraph) {
                    Log.i(TAG, "🔄 Mode: Smart Scene Graph Execution (Delegating to SceneGraphEngine)")
                    if (sceneGraphEngine == null) {
                        sceneGraphEngine = SceneGraphEngine(service)
                    }
                    sceneGraphEngine?.start(scriptJson)
                } else {
                    Log.i(TAG, "➡️ Mode: Standard Flowchart Execution")
                    executeGraph(root)
                }
            } else if (root.has("steps")) {
                Log.i(TAG, "➡️ Mode: Linear Execution (Legacy)")
                executeLinear(root.getJSONArray("steps"))
            } else {
                Log.e(TAG, "❌ Unknown script format")
                service.showToast("Script format not recognized")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Script Error: ${e.message}", e)
            service.showToast("Execution Error: ${e.message}")
            isRunning = false
        }
    }
    
    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        sceneGraphEngine?.stop()
        Log.i(TAG, "⏹️ Script Stopped")
    }

    // ==========================================
    // GRAPH EXECUTION ENGINE (New Standard)
    // ==========================================
    
    private fun executeGraph(root: JSONObject) {
        nodesMap.clear()
        edgesList.clear()
        
        val nodes = root.getJSONArray("nodes")
        val edges = root.getJSONArray("edges")
        
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            nodesMap[node.getString("id")] = node
        }
        
        for (i in 0 until edges.length()) {
            edgesList.add(edges.getJSONObject(i))
        }
        
        // Find Start Node
        var startNodeId: String? = null
        for (id in nodesMap.keys) {
            val node = nodesMap[id]!!
            if (node.optString("type") == "start" || node.optString("type") == "START") {
                startNodeId = id
                break
            }
        }
        
        if (startNodeId != null) {
            Log.i(TAG, "🚀 Starting Graph from node: $startNodeId")
            processGraphNode(startNodeId)
        } else {
            // Fallback: Check for data.isRoot
            for (id in nodesMap.keys) {
                val node = nodesMap[id]!!
                val data = node.optJSONObject("data")
                if (data != null && data.optBoolean("isRoot")) {
                    startNodeId = id
                    break
                }
            }

            if (startNodeId != null) {
                Log.i(TAG, "🚀 Starting Scene Graph from root: $startNodeId")
                processGraphNode(startNodeId)
            } else {
                Log.e(TAG, "❌ No START node found in graph")
                service.showToast("No Start Node Found")
            }
        }
    }
    
    private fun processGraphNode(nodeId: String) {
        if (!isRunning) return
        
        val node = nodesMap[nodeId]
        if (node == null) {
            Log.i(TAG, "✅ End of Flow (No Node)")
            return
        }
        
        val type = node.optString("type").lowercase()
        val data = node.optJSONObject("data") ?: JSONObject()
        val label = data.optString("label", "Node")
        
        Log.i(TAG, "▶️ Executing [$type]: $label")
        // service.showToast("▶ $label") // Toast is unreliable
        service.updateStatus("▶ $label")
        
        when (type) {
            "start", "root" -> {
                moveToNextNode(nodeId, 500)
            }
            "scene" -> {
                // Scene Node Logic:
                // 1. Find next node
                val nextId = findNextNodeId(nodeId)
                if (nextId != null) {
                    // 2. Find which region points to nextId
                    val regions = data.optJSONArray("regions")
                    var clicked = false
                    if (regions != null) {
                        for (i in 0 until regions.length()) {
                            val r = regions.getJSONObject(i)
                            if (r.optString("target") == nextId) {
                                // Found the region! Click it.
                                val x = r.optDouble("x", 0.0)
                                val y = r.optDouble("y", 0.0)
                                val w = r.optDouble("w", 0.0)
                                val h = r.optDouble("h", 0.0)
                                val centerX = x + w / 2
                                val centerY = y + h / 2
                                
                                Log.i(TAG, "🎯 Scene Transition: Click Region ($centerX%, $centerY%)")
                                performClickPercent(centerX, centerY)
                                clicked = true
                                break
                            }
                        }
                    }
                    if (!clicked) {
                        Log.w(TAG, "⚠️ No region found linking to $nextId, just waiting...")
                    }
                    moveToNextNode(nodeId, 2000) // Wait for scene transition
                } else {
                    Log.i(TAG, "🏁 End of Scene Path")
                    service.showToast("Scripts Complete")
                    isRunning = false
                }
            }
            "click" -> {
                val xPercent = data.optDouble("x", 50.0)
                val yPercent = data.optDouble("y", 50.0)
                performClickPercent(xPercent, yPercent)
                moveToNextNode(nodeId, 1000)
            }
            "wait" -> {
                val time = data.optLong("time", 1000)
                moveToNextNode(nodeId, time)
            }
            "loop" -> {
                moveToNextNode(nodeId, 500)
            }
            else -> {
                Log.w(TAG, "⚠️ Unknown node type: $type")
                moveToNextNode(nodeId, 500)
            }
        }
    }
    
    private fun moveToNextNode(currentNodeId: String, delayBox: Long) {
        handler.postDelayed({
            if (!isRunning) return@postDelayed
            val nextId = findNextNodeId(currentNodeId)
            if (nextId != null) {
                processGraphNode(nextId)
            } else {
                Log.i(TAG, "🏁 Flow Finished")
                service.showToast("Flow Finished")
                isRunning = false
            }
        }, delayBox)
    }
    
    private fun findNextNodeId(currentId: String): String? {
        for (edge in edgesList) {
            if (edge.getString("source") == currentId) {
                return edge.getString("target")
            }
        }
        return null
    }

    private fun performClickPercent(xPercent: Double, yPercent: Double) {
        // Convert Percentage (0-100) to Pixels
        val metrics = service.resources.displayMetrics
        val x = (metrics.widthPixels * xPercent / 100.0).toFloat()
        val y = (metrics.heightPixels * yPercent / 100.0).toFloat()
        
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
            
        service.dispatchGesture(gesture, null, null)
        Log.i(TAG, "👆 Click at ($x, $y) [${xPercent}%, ${yPercent}%]")
    }

    // ==========================================
    // LEGACY LINEAR EXECUTION (Backup)
    // ==========================================
    private fun executeLinear(steps: JSONArray) {
        // (Old Logic omitted for brevity, but we can keep basic support if needed)
        // For now, let's just focus onGraph.
        service.showToast("⚠️ Legacy Linear Script not fully implemented in this version")
    }
}
