package com.gameautoeditor.player

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.util.Log
import org.json.JSONObject
import kotlin.random.Random

class ActionSystem(private val service: AutomationService) {
    private val TAG = "GameAuto.Action"

    /**
     * 執行動作 (Hands)
     * 封裝了點擊、滑動等操作，並加入擬人化隨機偏移
     */
    fun performAction(actionConfig: JSONObject, region: JSONObject) {
        val type = actionConfig.optString("type", "CLICK")
        val label = region.optString("label", "Unknown")
        val params = actionConfig.optJSONObject("params")

        service.updateStatus("▶ 執行: $label")

        // 1. Handle Special Actions
        if (type == "LAUNCH_APP") {
            // ... logic to launch app (keep existing logic or simplified)
            // Ideally we move high level Android intents elsewhere, but Hands can do it.
            return
        }
        
        if (type == "WAIT") {
            Log.i(TAG, "⏳ Wait action, skipping gesture")
            return
        }

        // 2. Calculate Coordinates with Randomization
        val targetPoint = calculateTargetPoint(region)
        
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
                        Log.i(TAG, "👆 Click (${i+1}/$repeat) at (${targetPoint.x}, ${targetPoint.y})")
                    } catch (e: Exception) {
                        Log.e(TAG, "Click Failed", e)
                    }

                    if (i < repeat - 1) {
                        Thread.sleep(repeatDelay)
                    }
                }
            }
            "LONG_PRESS" -> {
                val duration = params?.optLong("duration") ?: 1000L
                builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
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
                Log.i(TAG, "👆 Swipe $direction")
            }
        }
        
        try {
            service.dispatchGesture(builder.build(), null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Gesture Failed", e)
        }
    }

    private data class Point(val x: Float, val y: Float)

    private fun calculateTargetPoint(region: JSONObject): Point {
        val xPercent = region.optDouble("x", 0.0)
        val yPercent = region.optDouble("y", 0.0)
        val wPercent = region.optDouble("w", 0.0)
        val hPercent = region.optDouble("h", 0.0)

        val metrics = service.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        // Calculate Bounds
        val left = (xPercent / 100.0 * screenW)
        val top = (yPercent / 100.0 * screenH)
        val width = (wPercent / 100.0 * screenW)
        val height = (hPercent / 100.0 * screenH)

        // Gaussian Randomization (Center biased)
        // Mean = center, StdDev = width/6 (99% within bounds typically)
        
        val centerX = left + width / 2
        val centerY = top + height / 2
        
        // Simple randomization: Center +/- 30% of size uniformly
        // Logic: Don't click exact center
        
        val randomX = centerX + (Random.nextDouble() - 0.5) * (width * 0.6) 
        val randomY = centerY + (Random.nextDouble() - 0.5) * (height * 0.6)

        return Point(randomX.toFloat(), randomY.toFloat())
    }

    private fun randomDuration(min: Long, max: Long): Long {
        return Random.nextLong(min, max)
    }
}
