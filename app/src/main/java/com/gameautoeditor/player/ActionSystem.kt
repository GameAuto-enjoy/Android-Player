package com.gameautoeditor.player

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.util.Log
import org.json.JSONArray
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
        val clickRegion = region.optJSONObject("clickRegion")
        val targetRegion = clickRegion ?: region
        val targetPoint = calculateTargetPoint(targetRegion, nodeRes)
        
        // 3. Dispatch Gesture
        when (type) {
            "CLICK" -> performClick(targetPoint, params, label)
            "LONG_PRESS" -> performLongPress(targetPoint, params, label)
            "SWIPE" -> performSwipe(targetPoint, params, label)
            "PATH_SWIPE" -> performPathSwipe(params, label, nodeRes)
            "CLICK_MATCHES" -> performMultiClick(region, params, label, nodeRes)
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

    private fun performMultiClick(region: JSONObject, params: JSONObject?, label: String, nodeRes: JSONObject?) {
        val pointsArray = region.optJSONArray("_dynamicPoints")
        if (pointsArray == null || pointsArray.length() == 0) {
            Log.w(TAG, "[動作: $label] ⚠️ 找不到匹配的座標點 (無 _dynamicPoints)")
            return
        }

        val interval = params?.optLong("interval", 1000L) ?: 1000L

        Log.i(TAG, "[動作: $label] 👆 準備連點 ${pointsArray.length()} 個匹配目標...")

        for (i in 0 until pointsArray.length()) {
            val ptObj = pointsArray.optJSONObject(i) ?: continue

            // Create a pseudo-region representing the target match area
            val clickRegion = JSONObject()
            clickRegion.put("x", ptObj.optDouble("x"))
            clickRegion.put("y", ptObj.optDouble("y"))
            clickRegion.put("w", ptObj.optDouble("w", 2.0))
            clickRegion.put("h", ptObj.optDouble("h", 2.0))
            
            val targetPoint = calculateTargetPoint(clickRegion, nodeRes)

            performClick(targetPoint, null, "$label (#${i+1})")

            if (i < pointsArray.length() - 1) {
                smartSleep(interval, 50L) // Wait between sequential clicks
            }
        }
        
        // Clean up the dynamic points after consumption
        region.remove("_dynamicPoints")
    }

    private fun performLongPress(targetPoint: Point, params: JSONObject?, label: String) {
        val duration = params?.optLong("duration", 1000L) ?: 1000L
        val path = Path()
        path.moveTo(targetPoint.x, targetPoint.y)
        // 許多遊戲引擎會過濾掉超過數秒且毫無移動的觸控 (視為誤觸)
        // 加入 1 pixel 的微小移動，強迫系統在長按期間派發 ACTION_MOVE，維持觸控存活
        path.lineTo(targetPoint.x + 1f, targetPoint.y + 1f)
        
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        
        try {
            service.dispatchGesture(builder.build(), null, null)
            Log.i(TAG, "[動作: $label] 👆 長按 ${duration}ms")
            // 阻擋執行緒直到手勢完成，避免引擎在手勢途中提前截圖
            smartSleep(duration, 10L)
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
            // 阻擋執行緒直到手勢完成，避免引擎在手勢途中提前截圖
            smartSleep(duration, 10L)
        } catch (e: Exception) {
            Log.e(TAG, "滑動失敗", e)
        }
    }

    private fun performPathSwipe(params: JSONObject?, label: String, nodeRes: JSONObject?) {
        Log.i(TAG, "[DEBUG] performPathSwipe called with params: ${params != null}")
        val pointsArray = params?.optJSONArray("points") ?: return
        Log.i(TAG, "[DEBUG] pointsArray length: ${pointsArray.length()}")
        if (pointsArray.length() < 2) return
        
        val durationPerSegment = params.optLong("durationPerSegment", 150L)
        
        val metrics = service.resources.displayMetrics
        
        var gridLeft = 0.0
        var gridTop = 0.0
        var gridWidth = metrics.widthPixels.toDouble()
        var gridHeight = metrics.heightPixels.toDouble()

        if (pointsArray.length() > 0) {
            val pt0 = pointsArray.optJSONObject(0)
            if (pt0 != null && pt0.has("anchorX")) {
                val anchorStartX = pt0.optDouble("anchorX", 0.0)
                val anchorStartY = pt0.optDouble("anchorY", 0.0)
                val totalAnchorW = pt0.optDouble("anchorW", 100.0)
                val totalAnchorH = pt0.optDouble("anchorH", 100.0)
                
                if (nodeRes != null && nodeRes.optDouble("w", 0.0) > 0) {
                    val screenW = metrics.widthPixels.toDouble()
                    val screenH = metrics.heightPixels.toDouble()
                    val nodeW = nodeRes.optDouble("w", 0.0)
                    val nodeH = nodeRes.optDouble("h", 0.0)
                    val nodeScale = screenW / nodeW 
                    
                    val srcX = anchorStartX / 100.0 * nodeW
                    val srcW = totalAnchorW / 100.0 * nodeW
                    val srcY = anchorStartY / 100.0 * nodeH
                    val srcH = totalAnchorH / 100.0 * nodeH
                    
                    if (anchorStartX < 33.0) gridLeft = srcX * nodeScale
                    else if (anchorStartX > 66.0) gridLeft = screenW - ((nodeW - srcX) * nodeScale)
                    else gridLeft = (screenW / 2.0) + ((srcX - (nodeW / 2.0)) * nodeScale)
                    
                    if (anchorStartY < 33.0) gridTop = srcY * nodeScale
                    else if (anchorStartY > 66.0) gridTop = screenH - ((nodeH - srcY) * nodeScale)
                    else gridTop = (screenH / 2.0) + ((srcY - (nodeH / 2.0)) * nodeScale)
                    
                    gridWidth = srcW * nodeScale
                    gridHeight = srcH * nodeScale
                } else {
                    gridLeft = anchorStartX / 100.0 * metrics.widthPixels
                    gridTop = anchorStartY / 100.0 * metrics.heightPixels
                    gridWidth = totalAnchorW / 100.0 * metrics.widthPixels
                    gridHeight = totalAnchorH / 100.0 * metrics.heightPixels
                }
            } else {
                Log.w(TAG, "[PathSwipe] Fallback to legacy calculation methods (missing anchor details)")
                return // Safe failure
            }
        } else {
             return
        }

        val cellPixelW = gridWidth / 6.0
        val cellPixelH = gridHeight / 5.0

        val path = Path()
        
        for (i in 0 until pointsArray.length()) {
            val ptObj = pointsArray.optJSONObject(i) ?: continue
            val cOffset = ptObj.optDouble("colOffset", 0.0)
            val rOffset = ptObj.optDouble("rowOffset", 0.0)
            
            val nativeX = (gridLeft + cOffset * cellPixelW).toFloat()
            val nativeY = (gridTop + rOffset * cellPixelH).toFloat()
            
            if (i == 0) {
                path.moveTo(nativeX, nativeY)
            } else {
                path.lineTo(nativeX, nativeY)
            }
        }
        
        var totalDuration = durationPerSegment * (pointsArray.length() - 1)
        
        // 確保總時間不超過 3000 毫秒 (3 秒)，因為無障礙服務或遊戲內部可能會對過長的手勢判定失效或超時
        if (totalDuration > 3000L) {
            totalDuration = 3000L
        }
        
        Log.i(TAG, "[DEBUG] totalDuration: $totalDuration (Capped at 3s)")
        
        try {
            val stroke = GestureDescription.StrokeDescription(path, 0, totalDuration)
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            
            service.dispatchGesture(builder.build(), null, null)
            Log.i(TAG, "[動作: $label] 👆 連續滑動 (PathSwipe) ${pointsArray.length()} 個節點, 耗時 $totalDuration ms")
            // 阻擋執行緒直到手勢完成，避免引擎在手勢途中提前截圖，導致網格分析到轉動中的模糊殘影
            smartSleep(totalDuration, 10L)
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] 連續滑動失敗", e)
        }
    }

    private data class Point(val x: Float, val y: Float)

    private fun calculateTargetPoint(region: JSONObject, nodeRes: JSONObject?, applyRandom: Boolean = true): Point {
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

        var offsetX = 0.0
        var offsetY = 0.0

        if (applyRandom) {
            // Standard Deviation (Sigma)
            val sigmaX = Math.max(1.0, width / 6.0)
            val sigmaY = Math.max(1.0, height / 6.0)

            // Generate Gaussian offset
            offsetX = random.nextGaussian() * sigmaX
            offsetY = random.nextGaussian() * sigmaY

            // --- Safety Clamp ---
            val safeW = width * 0.45 
            val safeH = height * 0.45

            if (offsetX > safeW) offsetX = safeW
            if (offsetX < -safeW) offsetX = -safeW
            if (offsetY > safeH) offsetY = safeH
            if (offsetY < -safeH) offsetY = -safeH
        }

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
