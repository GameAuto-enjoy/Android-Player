package com.gameautoeditor.player

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

// ... (existing imports)

    private fun matchSingleAnchor(screen: Bitmap, anchor: JSONObject, node: JSONObject): Boolean {
        // 1. Get Base64 Template
        val base64Template = anchor.optString("template")
        if (base64Template.isEmpty()) {
            // Log.w(TAG, "Anchor ${anchor.optString("id")} has no template.")
            return false 
        }

        // 2. Decode Template (using cache)
        val anchorId = anchor.getString("id")
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
        
        // 3. Define ROI (Region of Interest) on Screen to optimize search
        val screenW = screen.width
        val screenH = screen.height
        
        val axPer = anchor.getDouble("x")
        val ayPer = anchor.getDouble("y")
        // Template size in pixels (based on current screen logic? No, template is fixed size from recording)
        // Problem: Template size depends on the RECORDING device resolution.
        // Screen size depends on PLAYBACK device resolution.
        // Direct pixel matching fails if resolutions differ significantly.
        // Ideally, we should scale the template OR the screen.
        // For POC, we assume similar resolution or use `resize`... or just Search.
        
        // If we search Full Screen, it's safer but slower.
        // Let's Search Full Screen for robustness in POC.
        
        // 4. Match!
        // We use a slightly lower threshold for cross-device resilience
        val result = ImageMatcher.findTemplate(screen, template, 0.7)
        
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
        
        if (currentNode == null) {
            Log.e(TAG, "❌ Current Scene $sceneId not found in graph!")
            return null
        }
        
        val regions = currentNode.optJSONObject("data")?.optJSONArray("regions")
        if (regions == null || regions.length() == 0) return null
        
        // Strategy: Pick Random (for testing)
        // In real app, this would be "Pick path to Target"
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
        
        // Dispatch Gesture (Must run on main thread or via service)
        handler.post {
            val path = Path()
            path.moveTo(realX, realY)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            service.dispatchGesture(gesture, null, null)
        }
    }

    fun stop() {
        isRunning = false
        Log.i(TAG, "⏹️ SceneGraphEngine Stopped")
    }
}
