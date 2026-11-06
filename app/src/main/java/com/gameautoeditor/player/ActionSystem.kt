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
        val type = actionConfig.optString("type", "CLICK").uppercase()
        val label = region.optString("label", "Unknown")
        val params = actionConfig.optJSONObject("params")

        service.updateStatus("▶ 執行: $label")

        // 1. Handle Special Actions
        if (type == "LAUNCH_APP") {
            val pkg = params?.optString("packageName")?.ifEmpty { null }
                ?: actionConfig.optString("packageName").ifEmpty { null }
                ?: region.optString("packageName").ifEmpty { null }
            if (!pkg.isNullOrEmpty()) {
                try {
                    val launchIntent = service.packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        service.startActivity(launchIntent)
                        Log.i(TAG, "[動作: $label] 🚀 啟動應用程式: $pkg")
                        smartSleep(1500L, 200L)
                    } else {
                        Log.w(TAG, "[動作: $label] ⚠️ 找不到應用程式: $pkg")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "啟動應用程式失敗: $pkg", e)
                }
            }
            return
        }

        if (type == "EXTRACT_VAR") {
            Log.i(TAG, "[動作: $label] 📥 變數提取步驟完成 (無需點擊)")
            return
        }

        if (type == "KEY_EVENT" || type == "BACK" || type == "HOME" || type == "RECENTS") {
            val keyAction = params?.optString("key", type)?.uppercase() ?: type
            val globalAction = when (keyAction) {
                "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
                "RECENTS", "APP_SWITCH" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                else -> AccessibilityService.GLOBAL_ACTION_BACK
            }
            service.performGlobalAction(globalAction)
            Log.i(TAG, "[動作: $label] 🔘 系統按鍵: $keyAction")
            smartSleep(400L, 50L)
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

        // 2. Calculate Coordinates with Precision Visual Lock (_matchedPoint) & Crosshair Offset
        val clickRegion = region.optJSONObject("clickRegion")
        val targetRegion = clickRegion ?: region
        val applyRandom = !targetRegion.optBoolean("exactClick", false) && !(params?.optBoolean("exactClick", false) ?: false)
        val matched = targetRegion.optJSONObject("_matchedPoint")
        if (matched != null && !targetRegion.optBoolean("ignoreVisualLock", false)) {
            val (w, h) = service.getRealScreenDimensions()
            val frameW = matched.optInt("screenWidth", w)
            val frameH = matched.optInt("screenHeight", h)
            if (frameW != w || frameH != h) {
                Log.w(TAG, "跳過過期辨識座標：截圖 ${frameW}x${frameH}，目前 ${w}x${h}；等待重新辨識")
                return
            }
        }
        val targetPoint = calculateTargetPoint(targetRegion, nodeRes, applyRandom)
        
        // 3. Dispatch Gesture
        when (type) {
            "CLICK" -> performClick(targetPoint, params, label, applyRandom)
            "LONG_PRESS" -> performLongPress(targetPoint, params, label)
            "SWIPE" -> performSwipe(targetPoint, params, label)
            "PATH_SWIPE", "MATCH3_SWIPE" -> performPathSwipe(params, label, nodeRes, region)
            "CLICK_MATCHES", "CLICK_ALL" -> performMultiClick(region, params, label, nodeRes)
        }
    }

    private fun performClick(targetPoint: Point, params: JSONObject?, label: String, applyRandom: Boolean = true) {
        val repeat = params?.optInt("repeat", 1) ?: 1
        val repeatDelay = params?.optLong("repeatDelay", 100L) ?: 100L
        val baseDuration = 90L + random.nextInt(70) // 90-160ms crisp tap duration

        for (i in 0 until repeat) {
            var pX = targetPoint.x
            var pY = targetPoint.y
            if (i > 0 && applyRandom) {
                pX += (random.nextFloat() - 0.5f) * 4f // +/- 2.0 pixels
                pY += (random.nextFloat() - 0.5f) * 4f
            }
            
            val (screenW, screenH) = service.getRealScreenDimensions()
            val bounded = CoordinateGeometry.clampPoint(pX.toDouble(), pY.toDouble(), screenW.toDouble(), screenH.toDouble())
            pX = bounded.x.toFloat()
            pY = bounded.y.toFloat()
            val clickPath = Path()
            clickPath.moveTo(pX, pY)

            val stroke = GestureDescription.StrokeDescription(clickPath, 0, baseDuration)
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            
            try {
                service.dispatchGesture(builder.build(), null, null)
                Log.i(TAG, "[動作: $label] 🎯 精準點擊 (${i+1}/$repeat) 於 (${pX.toInt()}, ${pY.toInt()})")
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

            val clickRegion = JSONObject()
            clickRegion.put("x", ptObj.optDouble("x"))
            clickRegion.put("y", ptObj.optDouble("y"))
            clickRegion.put("w", ptObj.optDouble("w", 2.0))
            clickRegion.put("h", ptObj.optDouble("h", 2.0))
            // _dynamicPoints are already in real screen percentages
            val targetPoint = calculateTargetPoint(clickRegion, null)

            performClick(targetPoint, null, "$label (#${i+1})")

            if (i < pointsArray.length() - 1) {
                smartSleep(interval, 50L)
            }
        }
        
        region.remove("_dynamicPoints")
    }

    private fun performLongPress(targetPoint: Point, params: JSONObject?, label: String) {
        val duration = params?.optLong("duration", 1000L) ?: 1000L
        val path = Path()
        path.moveTo(targetPoint.x, targetPoint.y)
        path.lineTo(targetPoint.x + 1f, targetPoint.y + 1f)
        
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        
        try {
            service.dispatchGesture(builder.build(), null, null)
            Log.i(TAG, "[動作: $label] 👆 長按 ${duration}ms 於 (${targetPoint.x.toInt()}, ${targetPoint.y.toInt()})")
            smartSleep(duration, 10L)
        } catch (e: Exception) {
            Log.e(TAG, "長按失敗", e)
        }
    }

    private fun performSwipe(startPoint: Point, params: JSONObject?, label: String) {
        val direction = (params?.optString("direction") ?: "UP").uppercase()
        val durationRange = params?.optLong("duration") ?: 0L
        val duration = if (durationRange > 0) durationRange else (300L + random.nextInt(250))
        val customDist = params?.optDouble("distance", 0.0)?.toFloat() ?: 0f
        val baseDistance = if (customDist > 10f) customDist else (300f + random.nextFloat() * 100f)
        
        val (realW, realH) = service.getRealScreenDimensions()
        val screenW = realW.toFloat()
        val screenH = realH.toFloat()

        var endX = startPoint.x
        var endY = startPoint.y

        if (params != null && params.has("endX") && params.has("endY")) {
            endX = (params.optDouble("endX", 50.0) / 100.0 * screenW).toFloat()
            endY = (params.optDouble("endY", 50.0) / 100.0 * screenH).toFloat()
        } else {
            when (direction) {
                "UP" -> endY -= baseDistance
                "DOWN" -> endY += baseDistance
                "LEFT" -> endX -= baseDistance
                "RIGHT" -> endX += baseDistance
            }
        }

        endX = endX.coerceIn(5f, screenW - 5f)
        endY = endY.coerceIn(5f, screenH - 5f)

        val path = Path()
        path.moveTo(startPoint.x, startPoint.y)

        val midX = (startPoint.x + endX) / 2
        val midY = (startPoint.y + endY) / 2
        val curveIntensity = 0.15f
        val randomOffset = (random.nextGaussian() * baseDistance * curveIntensity).toFloat()

        var controlX = midX
        var controlY = midY

        if (direction == "UP" || direction == "DOWN") {
            controlX = (controlX + randomOffset).coerceIn(5f, screenW - 5f)
        } else {
            controlY = (controlY + randomOffset).coerceIn(5f, screenH - 5f)
        }

        path.quadTo(controlX, controlY, endX, endY)

        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        
        try {
            service.dispatchGesture(builder.build(), null, null)
            Log.i(TAG, "[動作: $label] 👆 曲線滑動 (Bézier) $direction $duration ms")
            smartSleep(duration, 10L)
        } catch (e: Exception) {
            Log.e(TAG, "滑動失敗", e)
        }
    }

    private fun performPathSwipe(params: JSONObject?, label: String, nodeRes: JSONObject?, region: JSONObject? = null) {
        val pointsArray = params?.optJSONArray("points") ?: region?.optJSONArray("_dynamicPoints") ?: return
        if (pointsArray.length() < 2) return
        
        val durationPerSegment = params?.optLong("durationPerSegment", 150L) ?: 150L
        val (realW, realH) = service.getRealScreenDimensions()
        val screenW = realW.toDouble()
        val screenH = realH.toDouble()
        
        var gridLeft = 0.0
        var gridTop = 0.0
        var gridWidth = screenW
        var gridHeight = screenH

        val pt0 = pointsArray.optJSONObject(0)
        if (pt0 != null && pt0.has("anchorX")) {
            val anchorStartX = pt0.optDouble("anchorX", 0.0)
            val anchorStartY = pt0.optDouble("anchorY", 0.0)
            val totalAnchorW = pt0.optDouble("anchorW", 100.0)
            val totalAnchorH = pt0.optDouble("anchorH", 100.0)
            
            val nodeW = nodeRes?.optDouble("w", nodeRes.optDouble("width", 0.0)) ?: 0.0
            val nodeH = nodeRes?.optDouble("h", nodeRes.optDouble("height", 0.0)) ?: 0.0
            if (nodeW > 0 && nodeH > 0) {
                val nodeScale = screenW / nodeW 
                val srcX = anchorStartX / 100.0 * nodeW
                val srcW = totalAnchorW / 100.0 * nodeW
                val srcY = anchorStartY / 100.0 * nodeH
                val srcH = totalAnchorH / 100.0 * nodeH
                
                gridLeft = if (anchorStartX < 33.0) srcX * nodeScale
                else if (anchorStartX > 66.0) screenW - ((nodeW - srcX) * nodeScale)
                else (screenW / 2.0) + ((srcX - (nodeW / 2.0)) * nodeScale)
                
                gridTop = if (anchorStartY < 33.0) srcY * nodeScale
                else if (anchorStartY > 66.0) screenH - ((nodeH - srcY) * nodeScale)
                else (screenH / 2.0) + ((srcY - (nodeH / 2.0)) * nodeScale)
                
                gridWidth = srcW * nodeScale
                gridHeight = srcH * nodeScale
            } else {
                gridLeft = anchorStartX / 100.0 * screenW
                gridTop = anchorStartY / 100.0 * screenH
                gridWidth = totalAnchorW / 100.0 * screenW
                gridHeight = totalAnchorH / 100.0 * screenH
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
            
            val nativeX = (gridLeft + cOffset * cellPixelW).toFloat().coerceIn(1f, (screenW - 2).toFloat())
            val nativeY = (gridTop + rOffset * cellPixelH).toFloat().coerceIn(1f, (screenH - 2).toFloat())
            
            if (i == 0) {
                path.moveTo(nativeX, nativeY)
            } else {
                path.lineTo(nativeX, nativeY)
            }
        }
        
        var totalDuration = durationPerSegment * (pointsArray.length() - 1)
        if (totalDuration > 3000L) {
            totalDuration = 3000L
        }
        
        try {
            val stroke = GestureDescription.StrokeDescription(path, 0, totalDuration)
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            
            service.dispatchGesture(builder.build(), null, null)
            Log.i(TAG, "[動作: $label] 👆 連續滑動 (PathSwipe) ${pointsArray.length()} 個節點, 耗時 $totalDuration ms")
            smartSleep(totalDuration, 10L)
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] 連續滑動失敗", e)
        }
    }

    private data class Point(val x: Float, val y: Float)

    private fun calculateTargetPoint(region: JSONObject, nodeRes: JSONObject?, applyRandom: Boolean = true): Point {
        val (realW, realH) = service.getRealScreenDimensions()
        val screenW = realW.toDouble()
        val screenH = realH.toDouble()

        // 優先使用 OpenCV 視覺匹配實際鎖定的座標 (_matchedPoint)，確保 100% 點擊在畫面真實按鈕上
        val matchedPoint = region.optJSONObject("_matchedPoint")
        val useVisualLock = matchedPoint != null && !region.optBoolean("ignoreVisualLock", false)

        val xPercent = if (useVisualLock) matchedPoint!!.optDouble("x", region.optDouble("x", 0.0)) else region.optDouble("x", 0.0)
        val yPercent = if (useVisualLock) matchedPoint!!.optDouble("y", region.optDouble("y", 0.0)) else region.optDouble("y", 0.0)
        val wPercent = if (useVisualLock) matchedPoint!!.optDouble("w", region.optDouble("w", 0.0)) else region.optDouble("w", region.optDouble("width", 0.0))
        val hPercent = if (useVisualLock) matchedPoint!!.optDouble("h", region.optDouble("h", 0.0)) else region.optDouble("h", region.optDouble("height", 0.0))

        val nodeW = nodeRes?.optDouble("w", nodeRes.optDouble("width", 0.0)) ?: 0.0
        val nodeH = nodeRes?.optDouble("h", nodeRes.optDouble("height", 0.0)) ?: 0.0
        val box = CoordinateGeometry.mapRegion(
            CoordinateGeometry.Rect(xPercent, yPercent, wPercent, hPercent),
            screenW, screenH, nodeW, nodeH, useVisualLock
        )
        val width = box.w
        val height = box.h
        val center = CoordinateGeometry.crosshair(box,
            region.optDouble("clickOffsetX", 50.0), region.optDouble("clickOffsetY", 50.0))
        val centerX = center.x
        val centerY = center.y

        var offsetX = 0.0
        var offsetY = 0.0

        if (applyRandom && width > 4.0 && height > 4.0) {
            val sigmaX = Math.max(1.0, Math.min(8.0, width / 8.0))
            val sigmaY = Math.max(1.0, Math.min(8.0, height / 8.0))

            offsetX = random.nextGaussian() * sigmaX
            offsetY = random.nextGaussian() * sigmaY

            val safeW = Math.min(12.0, width * 0.30)
            val safeH = Math.min(12.0, height * 0.30)

            offsetX = offsetX.coerceIn(-safeW, safeW)
            offsetY = offsetY.coerceIn(-safeH, safeH)
        }

        val finalPoint = CoordinateGeometry.clampPoint(centerX + offsetX, centerY + offsetY, screenW, screenH)
        return Point(finalPoint.x.toFloat(), finalPoint.y.toFloat())
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

