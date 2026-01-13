package com.gameautoeditor.player

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.util.Log
import org.json.JSONObject
import kotlin.random.Random

class ActionSystem(private val service: AutomationService) {
    private val TAG = "GameAuto"

    /**
     * 執行動作 (Hands)
     * 封裝了點擊、滑動等操作，並加入擬人化隨機偏移
     */
    fun performAction(actionConfig: JSONObject, region: JSONObject, nodeRes: JSONObject? = null) {
        val type = actionConfig.optString("type", "CLICK")
        val label = region.optString("label", "Unknown")
        val params = actionConfig.optJSONObject("params")

        service.updateStatus("▶ 執行: $label")

        // 1. Handle Special Actions
        if (type == "LAUNCH_APP") {
            // ... logic to launch app
            return
        }
        
        if (type == "WAIT") {
            Log.i(TAG, "[動作] ⏳ 等待動作，跳過手勢")
            return
        }

        if (type == "CHECK_EXIT") {
            Log.i(TAG, "[動作] ⚡ 條件跳轉觸發，跳過手勢")
            return
        }

        // 2. Calculate Coordinates with Randomization
        val targetPoint = calculateTargetPoint(region, nodeRes)
        
        // 3. Dispatch Gesture
        val path = Path()
        path.moveTo(targetPoint.x, targetPoint.y)
        
        val builder = GestureDescription.Builder()
        
        when (type) {
            "CLICK" -> {
                val repeat = params?.optInt("repeat", 1) ?: 1
                val repeatDelay = params?.optLong("repeatDelay", 100L) ?: 100L
                val baseDuration = randomDuration(50, 150) // Random tap duration

                for (i in 0 until repeat) {
                    val clickPath = Path()
                    // Re-calculate target point slightly for micro-movements if desired, 
                    // or use the same point. Currently using same point for reliability but new path object.
                    clickPath.moveTo(targetPoint.x, targetPoint.y)
                    
                    val stroke = GestureDescription.StrokeDescription(clickPath, 0, baseDuration)
                    val clickBuilder = GestureDescription.Builder()
                    clickBuilder.addStroke(stroke)
                    
                    try {
                        service.dispatchGesture(clickBuilder.build(), null, null)
                        Log.i(TAG, "[動作: $label] 👆 點擊 (${i+1}/$repeat) 於 (${targetPoint.x.toInt()}, ${targetPoint.y.toInt()})")
                    } catch (e: Exception) {
                        Log.e(TAG, "點擊失敗", e)
                    }

                    if (i < repeat - 1) {
                        Thread.sleep(repeatDelay)
                    }
                }
                return // Exit after CLICK
            }
            "LONG_PRESS" -> {
                val duration = params?.optLong("duration") ?: 1000L
                builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                Log.i(TAG, "[動作: $label] 👆 長按 ${duration}ms 於 (${targetPoint.x.toInt()}, ${targetPoint.y.toInt()})")
            }
            "SWIPE" -> {
                val direction = params?.optString("direction") ?: "UP"
                val duration = params?.optLong("duration") ?: randomDuration(300, 500)
                val distance = 300f + Random.nextFloat() * 100f // Random distance
                
                var endX = targetPoint.x
                var endY = targetPoint.y
                
                when (direction) {
                    "UP" -> endY -= distance
                    "DOWN" -> endY += distance
                    "LEFT" -> endX -= distance
                    "RIGHT" -> endX += distance
                }
                
                path.lineTo(endX, endY)
                builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                Log.i(TAG, "[動作: $label] 👆 滑動 $direction (${duration}ms)")
            }
        }
        
        try {
            service.dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            Log.e(TAG, "手勢失敗", e)
        }
    }

    private data class Point(val x: Float, val y: Float)

    private fun calculateTargetPoint(region: JSONObject, nodeRes: JSONObject?): Point {
        val xPercent = region.optDouble("x", 0.0)
        val yPercent = region.optDouble("y", 0.0)
        val wPercent = region.optDouble("w", 0.0)
        val hPercent = region.optDouble("h", 0.0)

        val metrics = service.resources.displayMetrics
        val screenW = metrics.widthPixels.toDouble()
        val screenH = metrics.heightPixels.toDouble()

        var left = 0.0
        var top = 0.0
        var width = 0.0
        var height = 0.0

        if (nodeRes != null) {
            val nodeW = nodeRes.optDouble("w", 0.0)
            val nodeH = nodeRes.optDouble("h", 0.0)
            
            if (nodeW > 0.0 && nodeH > 0.0) {
                 val scaleX = screenW / nodeW
                 // Use Scale based on Width (usually scenes are fit to width or height)
                 // Or we can use independent scales if stretching? No, games usually aspect fit.
                 // Let's use the same logic as Perception: Scale from Width for now.
                 // Wait, for Action, we need exact coordinates.
                 
                 val scale = screenW / nodeW 
                 
                 // --- Smart Alignment Calculation ---
                 
                 // WIDTH / X
                 val srcX = xPercent / 100.0 * nodeW
                 val srcW = wPercent / 100.0 * nodeW
                 
                 // Calculate Target X
                 if (xPercent < 33.0) { // Left Aligned
                     left = srcX * scale
                 } else if (xPercent > 66.0) { // Right Aligned
                     val distRight = nodeW - srcX
                     left = screenW - (distRight * scale)
                 } else { // Center Aligned
                     val distCenter = srcX - (nodeW / 2.0)
                     left = (screenW / 2.0) + (distCenter * scale)
                 }
                 
                 width = srcW * scale
                 
                 // HEIGHT / Y
                 // Usually games maintain Aspect Ratio, so we should use same scale?
                 // But device might be taller/shorter.
                 // Let's use smart vertical alignment too.
                 
                 val srcY = yPercent / 100.0 * nodeH
                 val srcH = hPercent / 100.0 * nodeH
                 
                 if (yPercent < 33.0) { // Top Aligned
                     top = srcY * scale
                 } else if (yPercent > 66.0) { // Bottom Aligned
                     val distBottom = nodeH - srcY
                     top = screenH - (distBottom * scale)
                 } else { // Center
                     val distCenterY = srcY - (nodeH / 2.0)
                     top = (screenH / 2.0) + (distCenterY * scale)
                 }
                 
                 height = srcH * scale
            } else {
                // Fallback
                left = (xPercent / 100.0 * screenW)
                top = (yPercent / 100.0 * screenH)
                width = (wPercent / 100.0 * screenW)
                height = (hPercent / 100.0 * screenH)
            }
        } else {
             // Fallback: Pure Percentage
             left = (xPercent / 100.0 * screenW)
             top = (yPercent / 100.0 * screenH)
             width = (wPercent / 100.0 * screenW)
             height = (hPercent / 100.0 * screenH)
        }

        // Gaussian Randomization (Center biased)
        val centerX = left + width / 2
        val centerY = top + height / 2
        
        // Ensure click is within bounds? or mostly within bounds.
        // Random +/- 40% of size
        val randomX = centerX + (Random.nextDouble() - 0.5) * (width * 0.8) 
        val randomY = centerY + (Random.nextDouble() - 0.5) * (height * 0.8)

        // Clamp to screen
        val finalX = randomX.coerceIn(0.0, screenW - 1).toFloat()
        val finalY = randomY.coerceIn(0.0, screenH - 1).toFloat()

        return Point(finalX, finalY)
    }

    private fun randomDuration(min: Long, max: Long): Long {
        return Random.nextLong(min, max)
    }
}
