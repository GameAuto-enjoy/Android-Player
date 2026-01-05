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
                // Global Localization is better for recovery.
                
                val detectedSceneId = identifyScene(screen)
                
                if (detectedSceneId != null) {
                    Log.i(TAG, "📍 Detected Scene: $detectedSceneId")
                    currentSceneId = detectedSceneId
                    
                    // 3. Decide Next Action (Regions)
                    val action = decideNextAction(currentSceneId)
                    
                    if (action != null) {
                        // 4. Perform Action
                        performAction(action)
                        // Wait for transition
                        Thread.sleep(2000) 
                    } else {
                        Log.d(TAG, "No edges/regions to traverse from here or condition not met.")
                        Thread.sleep(1000)
                    }
                } else {
                    Log.d(TAG, "❓ Unknown Scene")
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
        
        val result = ImageMatcher.findTemplate(screen, template!!, 0.8)
        
        return result != null
    }

    data class TransitionAction(val region: JSONObject, val targetSceneId: String)

    private fun decideNextAction(sceneId: String): TransitionAction? {
        val nodes = graphData?.optJSONArray("nodes") ?: return null
        
        // Find current node
        var currentNode: JSONObject? = null
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (node.getString("id") == sceneId) {
                currentNode = node
                break
            }
        }
        
        if (currentNode == null) return null
        
        val regions = currentNode.optJSONObject("data")?.optJSONArray("regions")
        if (regions == null || regions.length() == 0) return null
        
        // Strategy: Pick Random (for testing) or First Available
        val randomIndex = (0 until regions.length()).random()
        val region = regions.getJSONObject(randomIndex)
        val target = region.optString("target")
        
        if (target.isEmpty()) return null
        
        Log.i(TAG, "🤖 Decided to click '${region.optString("label")}' -> Go to $target")
        return TransitionAction(region, target)
    }

    private fun performAction(action: TransitionAction) {
        val r = action.region
        val xPercent = r.getDouble("x")
        val yPercent = r.getDouble("y")
        val wPercent = r.getDouble("w")
        val hPercent = r.getDouble("h")
        
        // Calculate Center
        val centerXPercent = xPercent + (wPercent / 2)
        val centerYPercent = yPercent + (hPercent / 2)
        
        // Convert to Pixels
        val metrics = service.resources.displayMetrics
        val realX = (metrics.widthPixels * centerXPercent / 100).toFloat()
        val realY = (metrics.heightPixels * centerYPercent / 100).toFloat()
        
        Log.i(TAG, "👆 Clicking at ($realX, $realY) [${centerXPercent}%, ${centerYPercent}%]")
        
        // Dispatch Gesture (Must run on main thread or via service context)
        // Since we are in worker thread, verify if dispatchGesture is thread-safe. 
        // It generally is, but let's be safe.
        handler.post {
            val path = Path()
            path.moveTo(realX, realY)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            service.dispatchGesture(gesture, null, null)
        }
    }
}
