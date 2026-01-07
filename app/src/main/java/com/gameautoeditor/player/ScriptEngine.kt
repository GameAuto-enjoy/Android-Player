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
    
    private val TAG = "ScriptEngine"
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val variables = mutableMapOf<String, String>()
    private val imageCache = mutableMapOf<String, Bitmap>()
    
    // Graph Data Structures
    private val nodesMap = mutableMapOf<String, JSONObject>()
    private val edgesList = mutableListOf<JSONObject>()
    
    fun executeScript(scriptJson: String) {
        try {
            val root = JSONObject(scriptJson)
            isRunning = true
            variables.clear()
            
            // Check Format: Graph (ReactFlow) vs Linear
            if (root.has("nodes") && root.has("edges")) {
                Log.i(TAG, "🔄 Mode: Graph Execution (Unified Schema)")
                executeGraph(root)
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
            Log.e(TAG, "❌ No START node found in graph")
            service.showToast("No Start Node Found")
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
        service.showToast("▶ $label")
        
        // Execute Action based on Type
        // Standardize params: data.x, data.y, data.time, etc
        
        when (type) {
            "start" -> {
                moveToNextNode(nodeId, 500)
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
                // Simplified Loop: assume count is passed in data
                // Real implementation needs to handle iteration state
                // For this demo, we just pass through
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
        // Find edge where source == currentId
        // Future: Handle multiple edges for branching (check condition)
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
