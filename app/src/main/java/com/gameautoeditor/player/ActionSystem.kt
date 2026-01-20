package com.gameautoeditor.player

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.util.Log
import org.json.JSONObject
import java.util.Random
import kotlin.math.max
import kotlin.math.sqrt

// Use alias to avoid conflict if needed, or just select Java Random
// We use java.util.Random for gaussian
typealias JavaRandom = java.util.Random

class ActionSystem(private val service: AutomationService) {
    private val TAG = "GameAuto"
    private val random = JavaRandom()

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
            val duration = params?.optLong("duration", 1000L) ?: 1000L
            val variance = params?.optLong("variance", 200L) ?: 200L
            Log.i(TAG, "[動作: $label] ⏳ Smart Wait: ${duration}ms (±${variance})")
            smartSleep(duration, variance)
            return
        }

        if (type == "CHECK_EXIT") {
            Log.i(TAG, "[動作] ⚡ 條件跳轉觸發，跳過手勢")
            return
        }

        // 2. Calculate Coordinates with Gaussian Randomization
        val targetPoint = calculateTargetPoint(region, nodeRes)
        
        // 3. Dispatch Gesture
        when (type) {
            "CLICK" -> performClick(targetPoint, params, label)
            "LONG_PRESS" -> performLongPress(targetPoint, params, label)
            "SWIPE" -> performSwipe(targetPoint, params, label)
        }
    }

    private fun performClick(targetPoint: Point, params: JSONObject?, label: String) {
        val repeat = params?.optInt("repeat", 1) ?: 1
        val repeatDelay = params?.optLong("repeatDelay", 100L) ?: 100L
        val baseDuration = 100L + random.nextInt(100) // 100-200ms duration

        for (i in 0 until repeat) {
            // Micro-movements for repeated clicks (Simulate finger vibration)
            var pX = targetPoint.x
            var pY = targetPoint.y
            if (i > 0) {
                pX += (random.nextFloat() - 0.5f) * 5f // +/- 2.5 pixels
                pY += (random.nextFloat() - 0.5f) * 5f
            }
            
            val clickPath = Path()
            clickPath.moveTo(pX, pY)

            val stroke = GestureDescription.StrokeDescription(clickPath, 0, baseDuration)
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            
            try {
                service.dispatchGesture(builder.build(), null, null)
                Log.i(TAG, "[動作: $label] 👆 點擊 (${i+1}/$repeat) 於 (${pX.toInt()}, ${pY.toInt()})")
            } catch (e: Exception) {
                Log.e(TAG, "點擊失敗", e)
            }

            if (i < repeat - 1) {
                smartSleep(repeatDelay, 50)
            }
        }
    }

    private fun performLongPress(targetPoint: Point, params: JSONObject?, label: String) {
        val duration = params?.optLong("duration", 1000L) ?: 1000L
        val path = Path()
        path.moveTo(targetPoint.x, targetPoint.y)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        
        try {
            service.dispatchGesture(builder.build(), null, null)
            Log.i(TAG, "[動作: $label] 👆 長按 ${duration}ms")
        } catch (e: Exception) {
            Log.e(TAG, "長按失敗", e)
        }
    }

    private fun performSwipe(startPoint: Point, params: JSONObject?, label: String) {
        val direction = params?.optString("direction") ?: "UP"
        val durationRange = params?.optLong("duration") ?: 0L
        val duration = if (durationRange > 0) durationRange else (300L + random.nextInt(300)) // 300-600ms
        val baseDistance = 300f + random.nextFloat() * 100f
        
        var endX = startPoint.x
        var endY = startPoint.y
        
        when (direction) {
            "UP" -> endY -= baseDistance
            "DOWN" -> endY += baseDistance
            "LEFT" -> endX -= baseDistance
            "RIGHT" -> endX += baseDistance
        }

        // --- Bézier Curve For Non-Linear Swipe ---
        val path = Path()
        path.moveTo(startPoint.x, startPoint.y)

        // Calculate Control Point (Midpoint with random perpendicular offset)
        val midX = (startPoint.x + endX) / 2
        val midY = (startPoint.y + endY) / 2
        

        // Calculate offset (Perpendicular to direction)
        val curveIntensity = 0.2f // 20% of distance
        // use toFloat() to resolve ambiguity
        val randomOffset = (random.nextGaussian() * baseDistance * curveIntensity).toFloat()

        var controlX = midX
        var controlY = midY

        if (direction == "UP" || direction == "DOWN") {
            controlX += randomOffset // Curve Left/Right
        } else {
            controlY += randomOffset // Curve Up/Down
        }

        // Quadratic Bezier (1 control point)
        path.quadTo(controlX, controlY, endX, endY)

        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        
        try {
            service.dispatchGesture(builder.build(), null, null)
            Log.i(TAG, "[動作: $label] 👆 曲線滑動 (Bézier) $direction $duration ms")
        } catch (e: Exception) {
            Log.e(TAG, "滑動失敗", e)
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

        var left: Double
        var top: Double
        var width: Double
        var height: Double

        if (nodeRes != null) {
            val nodeW = nodeRes.optDouble("w", 0.0)
            val nodeH = nodeRes.optDouble("h", 0.0)
            
            if (nodeW > 0.0 && nodeH > 0.0) {
                 // Smart Alignment Logic
                 val scale = screenW / nodeW 
                 val srcX = xPercent / 100.0 * nodeW
                 val srcW = wPercent / 100.0 * nodeW
                 val srcY = yPercent / 100.0 * nodeH
                 val srcH = hPercent / 100.0 * nodeH

                 // X-Axis Alignment
                 if (xPercent < 33.0) { // Left
                     left = srcX * scale
                 } else if (xPercent > 66.0) { // Right
                     val distRight = nodeW - srcX
                     left = screenW - (distRight * scale)
                 } else { // Center
                     val distCenter = srcX - (nodeW / 2.0)
                     left = (screenW / 2.0) + (distCenter * scale)
                 }
                 width = srcW * scale

                 // Y-Axis Alignment
                 if (yPercent < 33.0) { // Top
                     top = srcY * scale
                 } else if (yPercent > 66.0) { // Bottom
                     val distBottom = nodeH - srcY
                     top = screenH - (distBottom * scale)
                 } else { // Center
                     val distCenterY = srcY - (nodeH / 2.0)
                     top = (screenH / 2.0) + (distCenterY * scale)
                 }
                 height = srcH * scale
            } else {
                left = (xPercent / 100.0 * screenW)
                top = (yPercent / 100.0 * screenH)
                width = (wPercent / 100.0 * screenW)
                height = (hPercent / 100.0 * screenH)
            }
        } else {
             left = (xPercent / 100.0 * screenW)
             top = (yPercent / 100.0 * screenH)
             width = (wPercent / 100.0 * screenW)
             height = (hPercent / 100.0 * screenH)
        }

        // --- Gaussian Distribution (Fitts's Law Phase) ---
        // Center of the target
        val centerX = left + width / 2
        val centerY = top + height / 2

        // Standard Deviation (Sigma)
        val sigmaX = max(1.0, width / 6.0)
        val sigmaY = max(1.0, height / 6.0)

        // Generate Gaussian offset
        var offsetX = random.nextGaussian() * sigmaX
        var offsetY = random.nextGaussian() * sigmaY

        // --- Safety Clamp ---
        val safeW = width * 0.45 
        val safeH = height * 0.45

        if (offsetX > safeW) offsetX = safeW
        if (offsetX < -safeW) offsetX = -safeW
        if (offsetY > safeH) offsetY = safeH
        if (offsetY < -safeH) offsetY = -safeH

        val finalX = (centerX + offsetX).coerceIn(0.0, screenW - 1).toFloat()
        val finalY = (centerY + offsetY).coerceIn(0.0, screenH - 1).toFloat()

        return Point(finalX, finalY)
    }

    /**
     * Smart Sleep with Variance
     * @param base 基礎等待時間
     * @param variance 變異數 (標準差)
     */
    private fun smartSleep(base: Long, variance: Long) {
        if (base <= 0) return
        
        // Gaussian distribution for time
        val jitter = random.nextGaussian() * (variance / 3.0)
        var finalDelay = (base + jitter).toLong()
        
        // Hard limits
        if (finalDelay < 10) finalDelay = 10
        
        try {
            Thread.sleep(finalDelay)
        } catch (e: InterruptedException) {
            // Ignore
        }
    }
}
