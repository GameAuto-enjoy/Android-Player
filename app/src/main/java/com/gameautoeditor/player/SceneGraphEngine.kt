package com.gameautoeditor.player

import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class SceneGraphEngine(private val service: AutomationService) {
    private val TAG = "SceneGraphEngine"
    private val anchorTemplates = mutableMapOf<String, Bitmap>()
    private var graphData: JSONObject? = null
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private var workerThread: Thread? = null

    // Scheduling State
    data class ExecutionData(var lastRunTime: Long = 0, var runCount: Int = 0)
    private val executionHistory = mutableMapOf<String, ExecutionData>()

    fun start(jsonString: String) {
        if (isRunning) return
        isRunning = true
        
        try {
            graphData = JSONObject(jsonString)
            Log.i(TAG, "🤖 SceneGraphEngine Started")
            
            // Start worker thread
            workerThread = Thread {
                runLoop()
            }
            workerThread?.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse script: ${e.message}")
            isRunning = false
        }
    }

    fun stop() {
        isRunning = false
        anchorTemplates.clear() // Clear cache
        executionHistory.clear() // Clear history
        Log.i(TAG, "⏹️ SceneGraphEngine Stopped")
    }

    private fun runLoop() {
        Log.i(TAG, "Worker Thread Started")
        
        // Initial delay
        Thread.sleep(1000)

        // Find ROOT node
        val rootNodeId = findRootNodeId()
        if (rootNodeId == null) {
            Log.e(TAG, "No ROOT node found!")
            isRunning = false
            return
        }

        var currentSceneId = rootNodeId

        while (isRunning) {
            try {
                // 1. Capture Screen
                val screen = service.captureScreenSync()
                if (screen == null) {
                    Log.w(TAG, "Capture failed, retrying...")
                    Thread.sleep(1000)
                    continue
                }

                Log.d(TAG, "📸 Screen Captured: ${screen.width}x${screen.height}")

                // 2. Identify Current Scene vs Expected Scene
                // For now, we assume we are at 'currentSceneId' and verify it, 
                // OR we check ALL scenes to find where we are (Global Localization).
                
                var detectedSceneId = identifyScene(screen)
                
                // Fallback: If verification failed, but our 'currentSceneId' has NO anchors defined,
                // we assume we are there (Blind Trust). This allows "Blind Steps" or "Root with no checks".
                if (detectedSceneId == null && currentSceneId != null) {
                     val currentNode = getNodeById(currentSceneId)
                     val anchors = currentNode?.optJSONObject("data")?.optJSONArray("anchors")
                     if (anchors == null || anchors.length() == 0) {
                         Log.w(TAG, "⚠️ Current scene '$currentSceneId' has no anchors. Assuming we are there (Blind Trust).")
                         detectedSceneId = currentSceneId
                     }
                }
                
                if (detectedSceneId != null) {
                    if (detectedSceneId != currentSceneId) {
                         Log.i(TAG, "📍 Context Switch: $currentSceneId -> $detectedSceneId")
                    } else {
                         Log.i(TAG, "📍 Confirmed Scene: $detectedSceneId")
                    }
                    currentSceneId = detectedSceneId
                    
                    // 3. Decide Next Action (Regions)
                    val action = decideNextAction(currentSceneId)
                    
                    if (action != null) {
                        // 4. Perform Action
                        performAction(action)
                        
                        // Wait for transition (Dynamic)
                        val waitTime = action.region.optLong("wait_after", 2000L)
                        Log.d(TAG, "⏳ Waiting ${waitTime}ms...")
                        Thread.sleep(waitTime) 
                    } else {
                        Log.d(TAG, "No edges/regions to traverse from here or condition not met.")
                        Thread.sleep(1000)
                    }
                } else {
                    Log.d(TAG, "❓ Unknown Scene (No matching anchors found)")
                    Thread.sleep(1000)
                }

                screen.recycle()

            } catch (e: Exception) {
                Log.e(TAG, "Error in loop: ${e.message}", e)
                Thread.sleep(2000)
            }
        }
    }

    private fun findRootNodeId(): String? {
        val nodes = graphData?.optJSONArray("nodes") ?: return null
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (node.optJSONObject("data")?.optBoolean("isRoot") == true) {
                return node.getString("id")
            }
        }
        // Fallback: first node
        if (nodes.length() > 0) return nodes.getJSONObject(0).getString("id")
        return null
    }

    private fun identifyScene(screen: Bitmap): String? {
        val nodes = graphData?.optJSONArray("nodes") ?: return null
        
        // Simple strategy: Check all scenes with anchors. 
        // Return first match that satisfies All anchors (or majority).
        
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            val anchors = node.optJSONObject("data")?.optJSONArray("anchors")
            
            if (anchors == null || anchors.length() == 0) continue
            
            var matchCount = 0
            for (j in 0 until anchors.length()) {
                val anchor = anchors.getJSONObject(j)
                if (matchSingleAnchor(screen, anchor, node)) {
                    matchCount++
                }
            }
            
            // Threshold: All, or at least 1? Let's say ALL for now for strictness.
            if (matchCount == anchors.length() && matchCount > 0) {
                return node.getString("id")
            }
        }
        return null
    }

    private fun matchSingleAnchor(screen: Bitmap, anchor: JSONObject, node: JSONObject): Boolean {
        // 1. Get Base64 Template
        val base64Template = anchor.optString("template")
        if (base64Template.isEmpty()) {
            return false 
        }

        // 2. Decode Template (using cache)
        val anchorId = anchor.optString("id")
        if (anchorId.isEmpty()) return false

        var template = anchorTemplates[anchorId]
        
        if (template == null) {
            try {
                // Remove header if present "data:image/png;base64,"
                val cleanBase64 = if (base64Template.contains(",")) 
                    base64Template.split(",")[1] else base64Template
                    
                val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                template = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                
                if (template != null) {
                    anchorTemplates[anchorId] = template
                } else {
                     Log.e(TAG, "Failed to decode bitmap for anchor $anchorId")
                     return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception decoding anchor template", e)
                return false
            }
        }
        
        // 3. Match using ImageMatcher
        // Optimization: Crop screen to expected area if 'x, y, w, h' are small?
        // For now, full screen search is safer but slower.
        
        val result = ImageMatcher.findTemplate(screen, template!!, 0.7)
        
        return result != null
    }

    data class TransitionAction(val region: JSONObject, val targetSceneId: String)

    private fun decideNextAction(sceneId: String): TransitionAction? {
        val nodes = graphData?.optJSONArray("nodes") ?: return null
        
        // Find current node
        val currentNode = getNodeById(sceneId) ?: return null
        
        val regions = currentNode.optJSONObject("data")?.optJSONArray("regions")
        if (regions == null || regions.length() == 0) return null
        
        // Filter and Sort Candidates based on Schedule
        val candidates = mutableListOf<JSONObject>()
        
        for (i in 0 until regions.length()) {
            val r = regions.getJSONObject(i)
            val schedule = r.optJSONObject("schedule")
            val id = r.optString("id") // Ensure ID exists from editor
            
            // Default rules
            var isRunnable = true
            
            if (schedule != null && id.isNotEmpty()) {
                val history = executionHistory.getOrPut(id) { ExecutionData() }
                
                // 1. Check Max Times
                val maxTimes = schedule.optInt("maxTimes", 0)
                if (maxTimes > 0 && history.runCount >= maxTimes) {
                    isRunnable = false
                    Log.d(TAG, "🚫 Skip '$id': Max times reached (${history.runCount}/$maxTimes)")
                }
                
                // 2. Check Interval
                val intervalMin = schedule.optInt("interval", 0)
                if (intervalMin > 0) {
                    val now = System.currentTimeMillis()
                    val intervalMs = intervalMin * 60 * 1000L
                    if (now - history.lastRunTime < intervalMs) {
                        isRunnable = false
                        val remainingSec = (intervalMs - (now - history.lastRunTime)) / 1000
                        Log.d(TAG, "🚫 Skip '$id': Cooldown (${remainingSec}s left)")
                    }
                }
            }
            
            if (isRunnable) {
                candidates.add(r)
            }
        }
        
        if (candidates.isEmpty()) return null
        
        // Sort by Priority (Low number = High Priority). Default 5.
        // If priorities match, random (or could be sequential)
        candidates.sortBy { 
            it.optJSONObject("schedule")?.optInt("priority", 5) ?: 5 
        }
        
        // Pick top priority (first one)
        val bestRegion = candidates[0]
        val target = bestRegion.optString("target")
        
        Log.i(TAG, "🤖 Decided to click '${bestRegion.optString("label")}' (Priority: ${bestRegion.optJSONObject("schedule")?.optInt("priority") ?: 5})")
        
        // Assume target is sceneId if null/empty (Self Loop) for actions like "Click Button"
        // But logic requires valid target? If target is null, we stay in same scene?
        // Let's assume target can be empty for 'stay here'.
        
        return TransitionAction(bestRegion, if (target.isEmpty()) sceneId else target)
    }

    private fun performAction(action: TransitionAction) {
        val r = action.region
        
        // Update History
        val id = r.optString("id")
        if (id.isNotEmpty()) {
            val history = executionHistory.getOrPut(id) { ExecutionData() }
            history.lastRunTime = System.currentTimeMillis()
            history.runCount++
            Log.d(TAG, "📊 Updated History for '$id': Count=${history.runCount}")
        }
        val act = r.optJSONObject("action")
        val type = act?.optString("type") ?: "CLICK"
        val label = r.optString("label", "Action")
        val params = act?.optJSONObject("params")
        
        // Show Prompt
        service.showToast("▶ $label")
        
        // 1. Calculate Base Coordinates (Center of Region)
        val xPercent = r.getDouble("x")
        val yPercent = r.getDouble("y")
        val wPercent = r.getDouble("w")
        val hPercent = r.getDouble("h")

        val metrics = service.resources.displayMetrics
        val centerX = (metrics.widthPixels * (xPercent + wPercent / 2) / 100).toFloat()
        val centerY = (metrics.heightPixels * (yPercent + hPercent / 2) / 100).toFloat()

        Log.i(TAG, "⚡ Performing $type at ($centerX, $centerY)")

        handler.post {
            val path = Path()
            path.moveTo(centerX, centerY)
            
            val builder = GestureDescription.Builder()
            
            when (type) {
                "CLICK" -> {
                    // Standard Click (Tap)
                    builder.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                }
                "LONG_PRESS" -> {
                    // Long Press
                    val duration = params?.optLong("duration") ?: 1000L
                    builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                }
                "SWIPE" -> {
                    // Swipe
                    val direction = params?.optString("direction") ?: "UP"
                    val duration = params?.optLong("duration") ?: 300L
                    val distance = 300f // Pixels, could be configurable
                    
                    var endX = centerX
                    var endY = centerY
                    
                    when (direction) {
                        "UP" -> endY -= distance
                        "DOWN" -> endY += distance
                        "LEFT" -> endX -= distance
                        "RIGHT" -> endX += distance
                    }
                    
                    path.lineTo(endX, endY)
                    builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                }
                "WAIT" -> {
                    // No gesture, just wait (logic handled in loop delay)
                    Log.i(TAG, "⏳ Action is WAIT only")
                    return@post
                }
            }
            
            try {
                service.dispatchGesture(builder.build(), null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Gesture Dispatch Failed", e)
            }
        }
    }
    private fun getNodeById(id: String): JSONObject? {
        val nodes = graphData?.optJSONArray("nodes") ?: return null
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (node.getString("id") == id) {
                return node
            }
        }
        return null
    }
}

