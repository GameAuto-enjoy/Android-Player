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

        if (type == "KEY_EVENT" || type == "BACK" || type == "BACK_KEY" || type == "HOME" || type == "HOME_KEY" || type == "RECENTS") {
            val keyAction = if (params != null && !params.isNull("key")) {
                params.optString("key", "").takeIf { it.isNotBlank() }?.uppercase() ?: type
            } else {
                type
            }
            val globalAction = when (keyAction) {
                "HOME", "HOME_KEY" -> AccessibilityService.GLOBAL_ACTION_HOME
                "RECENTS", "APP_SWITCH" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                "BACK", "BACK_KEY" -> AccessibilityService.GLOBAL_ACTION_BACK
                else -> AccessibilityService.GLOBAL_ACTION_BACK
            }
            service.performGlobalAction(globalAction)
            Log.i(TAG, "[動作: $label] 🔘 系統按鍵: $keyAction")
            smartSleep(400L, 50L)
            return
        }
        
        if (type == "WAIT") {
            val duration = when {
                params != null && !params.isNull("duration") -> params.optLong("duration", 1000L)
                params != null && !params.isNull("waitTime") -> params.optLong("waitTime", 1000L)
                else -> 1000L
            }
            val variance = if (params != null && !params.isNull("variance")) params.optLong("variance", 200L) else 200L
            Log.i(TAG, "[動作: $label] ⏳ Smart Wait: ${duration}ms (±${variance})")
            smartSleep(duration, variance)
            return
        }

        if (type == "CHECK_EXIT") {
            Log.i(TAG, "[動作] ⚡ 條件跳轉觸發，跳過手勢")
            return
        }

        // 2. Dispatch Multi-Target & Path Gestures directly (not gated by single-target _matchedPoint)
        val clickRegion = region.optJSONObject("clickRegion")
        val targetRegion = clickRegion ?: region
        val applyRandom = !targetRegion.optBoolean("exactClick", false) && !(params?.optBoolean("exactClick", false) ?: false)

        when (type) {
            "PATH_SWIPE", "MATCH3_SWIPE" -> {
                performPathSwipe(params, label, nodeRes, region)
                return
            }
            "CLICK_MATCHES", "CLICK_ALL" -> {
                performMultiClick(region, params, label, nodeRes, applyRandom)
                return
            }
        }

        // 3. Calculate Coordinates with Precision Visual Lock (_matchedPoint) & Crosshair Offset
        val matched = targetRegion.optJSONObject("_matchedPoint")
        if (matched != null && !targetRegion.optBoolean("ignoreVisualLock", false)) {
            val (w, h) = service.getRealScreenDimensions()
            if (matched.has("screenWidth") || matched.has("screenHeight")) {
                val frameW = matched.optInt("screenWidth", -1)
                val frameH = matched.optInt("screenHeight", -1)
                if (frameW <= 0 || frameH <= 0 || frameW != w || frameH != h) {
                    Log.w(TAG, "跳過過期辨識座標：截圖 ${frameW}x${frameH}，目前 ${w}x${h}；等待重新辨識")
                    return
                }
            }
        }
        val targetPoint = calculateTargetPoint(targetRegion, nodeRes, applyRandom)
        
        // 4. Dispatch Single-Target Gesture
        when (type) {
            "CLICK" -> performClick(targetPoint, params, label, applyRandom)
            "LONG_PRESS" -> performLongPress(targetPoint, params, label)
            "SWIPE" -> performSwipe(targetPoint, params, label)
        }
    }

    private fun optFiniteDouble(obj: JSONObject?, key: String, fallback: Double): Double {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return fallback
        val value = obj.optDouble(key, Double.NaN)
        return if (value.isFinite()) value else fallback
    }

    private fun performClick(targetPoint: Point, params: JSONObject?, label: String, applyRandom: Boolean = true) {
        if (!targetPoint.x.isFinite() || !targetPoint.y.isFinite()) {
            Log.w(TAG, "[動作: $label] ⚠️ 跳過無效點擊座標: (${targetPoint.x}, ${targetPoint.y})")
            return
        }
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
            if (!pX.isFinite() || !pY.isFinite()) continue
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

    private fun performMultiClick(
        region: JSONObject,
        params: JSONObject?,
        label: String,
        nodeRes: JSONObject?,
        applyRandom: Boolean = true
    ) {
        val pointsArray = region.optJSONArray("_dynamicPoints")
        if (pointsArray == null || pointsArray.length() == 0) {
            region.remove("_dynamicPoints")
            Log.w(TAG, "[動作: $label] ⚠️ 找不到匹配的座標點 (無 _dynamicPoints)")
            return
        }

        val interval = params?.optLong("interval", 1000L) ?: 1000L
        val (realW, realH) = service.getRealScreenDimensions()

        Log.i(TAG, "[動作: $label] 👆 準備連點 ${pointsArray.length()} 個匹配目標...")

        try {
            val safeOffsetX = optFiniteDouble(region, "clickOffsetX", 50.0)
            val safeOffsetY = optFiniteDouble(region, "clickOffsetY", 50.0)

            for (i in 0 until pointsArray.length()) {
                val ptObj = pointsArray.optJSONObject(i) ?: continue
                if (ptObj.has("screenWidth") || ptObj.has("screenHeight")) {
                    val frameW = ptObj.optInt("screenWidth", -1)
                    val frameH = ptObj.optInt("screenHeight", -1)
                    if (frameW <= 0 || frameH <= 0 || frameW != realW || frameH != realH) {
                        Log.w(TAG, "[動作: $label] 跳過過期或無效多目標座標：截圖 ${frameW}x${frameH}，目前 ${realW}x${realH}")
                        continue
                    }
                }

                val px = ptObj.optDouble("x", Double.NaN)
                val py = ptObj.optDouble("y", Double.NaN)
                if (!px.isFinite() || !py.isFinite()) {
                    Log.w(TAG, "[動作: $label] 跳過無效多目標座標 (#${i + 1}): x=$px, y=$py")
                    continue
                }

                val pw = if (ptObj.has("w")) {
                    val rawW = ptObj.optDouble("w", Double.NaN)
                    if (!rawW.isFinite() || rawW < 0.0) {
                        Log.w(TAG, "[動作: $label] 跳過無效多目標寬度 (#${i + 1}): w=$rawW")
                        continue
                    }
                    rawW
                } else {
                    2.0
                }
                val ph = if (ptObj.has("h")) {
                    val rawH = ptObj.optDouble("h", Double.NaN)
                    if (!rawH.isFinite() || rawH < 0.0) {
                        Log.w(TAG, "[動作: $label] 跳過無效多目標高度 (#${i + 1}): h=$rawH")
                        continue
                    }
                    rawH
                } else {
                    2.0
                }

                val clickRegion = JSONObject()
                clickRegion.put("x", px)
                clickRegion.put("y", py)
                clickRegion.put("w", pw)
                clickRegion.put("h", ph)
                if (region.has("clickOffsetX")) {
                    clickRegion.put("clickOffsetX", safeOffsetX)
                }
                if (region.has("clickOffsetY")) {
                    clickRegion.put("clickOffsetY", safeOffsetY)
                }
                // _dynamicPoints are already in real screen percentages (top-left anchored)
                val targetPoint = calculateTargetPoint(clickRegion, null, applyRandom)

                performClick(targetPoint, null, "$label (#${i + 1})", applyRandom)

                if (i < pointsArray.length() - 1) {
                    smartSleep(interval, 50L)
                }
            }
        } finally {
            region.remove("_dynamicPoints")
        }
    }

    private fun performLongPress(targetPoint: Point, params: JSONObject?, label: String) {
        if (!targetPoint.x.isFinite() || !targetPoint.y.isFinite()) {
            Log.w(TAG, "[動作: $label] ⚠️ 跳過無效長按座標: (${targetPoint.x}, ${targetPoint.y})")
            return
        }
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
        if (!startPoint.x.isFinite() || !startPoint.y.isFinite()) {
            Log.w(TAG, "[動作: $label] ⚠️ 跳過無效滑動起點: (${startPoint.x}, ${startPoint.y})")
            return
        }
        val direction = (params?.optString("direction") ?: "UP").uppercase()
        val durationRange = params?.optLong("duration") ?: 0L
        val duration = if (durationRange > 0) durationRange else (300L + random.nextInt(250))
        val customDist = optFiniteDouble(params, "distance", 0.0).toFloat()
        val baseDistance = if (customDist > 10f) customDist else (300f + random.nextFloat() * 100f)
        
        val (realW, realH) = service.getRealScreenDimensions()
        val screenW = realW.toFloat()
        val screenH = realH.toFloat()
        if (screenW < 3f || screenH < 3f) return

        var endX = startPoint.x
        var endY = startPoint.y

        if (params != null && params.has("endX") && params.has("endY")) {
            endX = (optFiniteDouble(params, "endX", 50.0) / 100.0 * screenW).toFloat()
            endY = (optFiniteDouble(params, "endY", 50.0) / 100.0 * screenH).toFloat()
        } else {
            when (direction) {
                "UP" -> endY -= baseDistance
                "DOWN" -> endY += baseDistance
                "LEFT" -> endX -= baseDistance
                "RIGHT" -> endX += baseDistance
            }
        }

        val padX = if (screenW > 10f) 5f else 1f
        val padY = if (screenH > 10f) 5f else 1f
        endX = (if (endX.isFinite()) endX else screenW / 2f).coerceIn(padX, max(padX, screenW - padX))
        endY = (if (endY.isFinite()) endY else screenH / 2f).coerceIn(padY, max(padY, screenH - padY))

        val path = Path()
        path.moveTo(startPoint.x, startPoint.y)

        val midX = (startPoint.x + endX) / 2
        val midY = (startPoint.y + endY) / 2
        val curveIntensity = 0.15f
        val randomOffset = (random.nextGaussian() * baseDistance * curveIntensity).toFloat()

        var controlX = midX
        var controlY = midY

        if (direction == "UP" || direction == "DOWN") {
            controlX = (controlX + randomOffset).coerceIn(padX, max(padX, screenW - padX))
        } else {
            controlY = (controlY + randomOffset).coerceIn(padY, max(padY, screenH - padY))
        }

        if (!controlX.isFinite() || !controlY.isFinite() || !endX.isFinite() || !endY.isFinite()) {
            Log.w(TAG, "[動作: $label] ⚠️ 跳過無效滑動終點/控制點")
            return
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
        if (screenW < 3.0 || screenH < 3.0) return
        
        var gridLeft = 0.0
        var gridTop = 0.0
        var gridWidth = screenW
        var gridHeight = screenH

        val pt0 = pointsArray.optJSONObject(0)
        if (pt0 != null && pt0.has("anchorX")) {
            val anchorStartX = optFiniteDouble(pt0, "anchorX", 0.0)
            val anchorStartY = optFiniteDouble(pt0, "anchorY", 0.0)
            val totalAnchorW = optFiniteDouble(pt0, "anchorW", 100.0).coerceAtLeast(0.0)
            val totalAnchorH = optFiniteDouble(pt0, "anchorH", 100.0).coerceAtLeast(0.0)
            
            val nodeW = optFiniteDouble(nodeRes, "w", optFiniteDouble(nodeRes, "width", 0.0)).coerceAtLeast(0.0)
            val nodeH = optFiniteDouble(nodeRes, "h", optFiniteDouble(nodeRes, "height", 0.0)).coerceAtLeast(0.0)
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
        if (!gridLeft.isFinite() || !gridTop.isFinite() || !cellPixelW.isFinite() || !cellPixelH.isFinite()) return

        val path = Path()
        var validPoints = 0
        
        for (i in 0 until pointsArray.length()) {
            val ptObj = pointsArray.optJSONObject(i) ?: continue
            val cOffset = optFiniteDouble(ptObj, "colOffset", Double.NaN)
            val rOffset = optFiniteDouble(ptObj, "rowOffset", Double.NaN)
            if (!cOffset.isFinite() || !rOffset.isFinite()) continue
            
            val rawX = gridLeft + cOffset * cellPixelW
            val rawY = gridTop + rOffset * cellPixelH
            if (!rawX.isFinite() || !rawY.isFinite()) continue
            val nativeX = rawX.coerceIn(1.0, screenW - 2.0).toFloat()
            val nativeY = rawY.coerceIn(1.0, screenH - 2.0).toFloat()
            if (!nativeX.isFinite() || !nativeY.isFinite()) continue
            
            if (validPoints == 0) {
                path.moveTo(nativeX, nativeY)
            } else {
                path.lineTo(nativeX, nativeY)
            }
            validPoints++
        }

        if (validPoints < 2) return
        
        var totalDuration = durationPerSegment * (validPoints - 1)
        if (totalDuration > 3000L) {
            totalDuration = 3000L
        }
        
        try {
            val stroke = GestureDescription.StrokeDescription(path, 0, totalDuration)
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            
            service.dispatchGesture(builder.build(), null, null)
            Log.i(TAG, "[動作: $label] 👆 連續滑動 (PathSwipe) $validPoints 個節點, 耗時 $totalDuration ms")
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

        val fallbackX = optFiniteDouble(region, "x", 0.0)
        val fallbackY = optFiniteDouble(region, "y", 0.0)
        val fallbackW = optFiniteDouble(region, "w", optFiniteDouble(region, "width", 0.0)).coerceAtLeast(0.0)
        val fallbackH = optFiniteDouble(region, "h", optFiniteDouble(region, "height", 0.0)).coerceAtLeast(0.0)

        val xPercent = if (useVisualLock) optFiniteDouble(matchedPoint, "x", fallbackX) else fallbackX
        val yPercent = if (useVisualLock) optFiniteDouble(matchedPoint, "y", fallbackY) else fallbackY
        val wPercent = (if (useVisualLock) optFiniteDouble(matchedPoint, "w", fallbackW) else fallbackW).coerceAtLeast(0.0)
        val hPercent = (if (useVisualLock) optFiniteDouble(matchedPoint, "h", fallbackH) else fallbackH).coerceAtLeast(0.0)

        val nodeW = optFiniteDouble(nodeRes, "w", optFiniteDouble(nodeRes, "width", 0.0)).coerceAtLeast(0.0)
        val nodeH = optFiniteDouble(nodeRes, "h", optFiniteDouble(nodeRes, "height", 0.0)).coerceAtLeast(0.0)
        val box = CoordinateGeometry.mapRegion(
            CoordinateGeometry.Rect(xPercent, yPercent, wPercent, hPercent),
            screenW, screenH, nodeW, nodeH, useVisualLock
        )
        val width = box.w
        val height = box.h
        val clickOffsetX = optFiniteDouble(region, "clickOffsetX", 50.0)
        val clickOffsetY = optFiniteDouble(region, "clickOffsetY", 50.0)
        val center = CoordinateGeometry.crosshair(box, clickOffsetX, clickOffsetY)
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

