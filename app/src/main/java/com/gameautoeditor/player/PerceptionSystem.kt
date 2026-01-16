package com.gameautoeditor.player

import android.graphics.Bitmap
import android.util.Log
import org.json.JSONObject
import android.graphics.BitmapFactory
import android.util.Base64

class PerceptionSystem(private val service: AutomationService, private val logger: ((String, String) -> Unit)? = null) {
    private val TAG = "GameAuto"
    
    // Cache for Decoded Template Bitmaps (Config Layer asset)
    private val templateCache = mutableMapOf<String, Bitmap>()

    private fun remoteLog(level: String, message: String) {
        // 1. Local Logcat
        when (level) {
            "INFO" -> Log.i(TAG, message)
            "DEBUG" -> Log.d(TAG, message)
            "WARN" -> Log.w(TAG, message)
            "ERROR" -> Log.e(TAG, message)
        }
        // 2. Remote
        logger?.invoke(level, message)
    }

    /**
     * 感知 (Eyes): 檢查當前畫面是否符合某個 State (Scene Node) 的特徵
     */
    fun isStateActive(screen: Bitmap, stateNode: JSONObject, variables: MutableMap<String, Int>, sceneName: String = "Unknown", verbose: Boolean = true): Boolean {
        val data = stateNode.optJSONObject("data")
        val anchors = data?.optJSONArray("anchors")
        if (anchors == null || anchors.length() == 0) return false

        var matchCount = 0
        val totalAnchors = anchors.length()

        // Multi-Feature Logic (v1.7.14)
        var minMatches = data.optInt("minMatches", totalAnchors)
        if (minMatches <= 0) minMatches = totalAnchors

        // Calculate Expected Scale
        val nodeRes = stateNode.optJSONObject("resolution")
        var expectedScale: Double? = null
        if (nodeRes != null) {
            val w = nodeRes.optDouble("w", 0.0)
            if (w > 0) {
                 expectedScale = service.resources.displayMetrics.widthPixels.toDouble() / w
            }
        }

        for (i in 0 until totalAnchors) {
            val anchor = anchors.getJSONObject(i)
            // Pass verbose flag and index
            if (checkAnchor(screen, anchor, variables, sceneName, expectedScale, nodeRes, verbose, i + 1)) {
                matchCount++
            }
            
            // Short-Circuit Optimization (Quick Fail)
            // If the remaining anchors are not enough to reach 'minMatches', abort early.
            // This prevents expensive checks (AI) if a simple check (Image) has already failed.
            val remainingAnchors = totalAnchors - (i + 1)
            if (matchCount + remainingAnchors < minMatches) {
                if (verbose) remoteLog("DEBUG", "[感知] ⚡ 快速失敗 (Quick Fail): 已匹配 $matchCount, 剩餘 $remainingAnchors, 目標 $minMatches. 中止檢查.")
                return false
            }
        }

        return matchCount >= minMatches
    }
    
    fun clearCache() {
        templateCache.clear()
        // ImageMatcher has its own logic, but we might want to clear local cache
    }

    private fun checkAnchor(screen: Bitmap, anchor: JSONObject, variables: MutableMap<String, Int>, sceneName: String, scale: Double?, nodeRes: JSONObject?, verbose: Boolean, index: Int): Boolean {
        val matchType = anchor.optString("matchType", "image")
        val variableName = anchor.optString("variableName")
        
        val result = when (matchType.lowercase()) {
            "color" -> Pair(checkColor(screen, anchor), null)
            "text" -> checkText(screen, anchor)
            "ai" -> checkAi(screen, anchor)
            else -> Pair(checkImage(screen, anchor, sceneName, scale, nodeRes, verbose, index), null)
        }

        // If defined, EXTRACT value into variable
        if (result.first && variableName.isNotEmpty() && result.second != null) {
            try {
                // Try to parse as integer for now (variables map is <String, Int>)
                // Only digits
                val cleanVal = Regex("[^0-9]").replace(result.second!!, "")
                if (cleanVal.isNotEmpty()) {
                    val intVal = cleanVal.toInt()
                    variables[variableName] = intVal
                    remoteLog("INFO", "📥 變數提取成功 [$variableName] = $intVal (原始值: ${result.second})")
                }
            } catch (e: Exception) {
                remoteLog("WARN", "無法解析提取的數值 '${result.second}' 為整數")
            }
        }

        return result.first
    }

    // --- Specific Perception Methods ---

    private fun checkImage(screen: Bitmap, anchor: JSONObject, sceneName: String, scale: Double?, nodeRes: JSONObject?, verbose: Boolean, index: Int): Boolean {
        val base64Template = anchor.optString("template")
        if (base64Template.isEmpty()) return false

        val anchorId = anchor.optString("id")
        var template = templateCache[anchorId]
        
        if (template == null) {
            template = decodeTemplate(base64Template)
            if (template != null && anchorId.isNotEmpty()) {
                templateCache[anchorId] = template
            }
        }

        if (template == null) return false

        // Use ImageMatcher (OpenCV)
        val result = ImageMatcher.findTemplate(screen, template, 0.7, scale)
        if (result == null) {
            return false
        }
        
        if (result.score >= 0.9) {
             if (verbose) remoteLog("INFO", "[場景: $sceneName][特徵#$index] ⚡ 高分特赦 (Score: ${String.format("%.4f", result.score)} >= 0.9). 忽略位置檢查.")
             return true
        }
        
        // Verify Position
        val metrics = service.resources.displayMetrics
        val expectedX = anchor.optDouble("x", -1.0)
        val expectedY = anchor.optDouble("y", -1.0)
        
        if (expectedX >= 0 && expectedY >= 0) {
            val toleranceParams = 0.25 // 25% Screen Tolerance (Relaxed for Aspect Ratio)
            
            // SMART ALIGNMENT CHECK (Resolution Aware)
            if (nodeRes != null && scale != null) {
                val nodeW = nodeRes.optDouble("w", 0.0)
                val nodeH = nodeRes.optDouble("h", 0.0)
                
                if (nodeW > 0 && nodeH > 0) {
                    val srcPixelX = (expectedX / 100.0) * nodeW
                    val srcPixelY = (expectedY / 100.0) * nodeH
                    
                    val devW = metrics.widthPixels.toDouble()
                    val devH = metrics.heightPixels.toDouble()
                    
                    // --- X Axis Logic ---
                    // Left (< 33%)
                    val targetDevX: Double = if (expectedX < 33.0) {
                        srcPixelX * scale // Distance from Left
                    } 
                    // Right (> 66%)
                    else if (expectedX > 66.0) {
                        val distFromRight = nodeW - srcPixelX
                        devW - (distFromRight * scale) // Distance from Right
                    } 
                    // Center
                    else {
                        val distFromCenter = srcPixelX - (nodeW / 2.0)
                        (devW / 2.0) + (distFromCenter * scale)
                    }

                    // --- Y Axis Logic ---
                     val targetDevY: Double = if (expectedY < 33.0) { // Top
                         srcPixelY * scale
                     } else if (expectedY > 66.0) { // Bottom
                         val distFromBottom = nodeH - srcPixelY
                         devH - (distFromBottom * scale)
                     } else {
                         val distFromCenterY = srcPixelY - (nodeH / 2.0)
                         (devH / 2.0) + (distFromCenterY * scale)
                     }
                     
                     // Absolute Difference Check (Pixels)
                     // Allow 15% of device dimension as tolerance
                     val absTolX = devW * toleranceParams
                     val absTolY = devH * toleranceParams
                     
                     if (kotlin.math.abs(result.x - targetDevX) > absTolX || kotlin.math.abs(result.y - targetDevY) > absTolY) {
                         Log.w(TAG, "[場景: $sceneName] ❌ (Smart)位置偏差: 預期(${targetDevX.toInt()}, ${targetDevY.toInt()}) 實際(${result.x.toInt()}, ${result.y.toInt()})")
                         return false
                     } else {
                         if (verbose) Log.d(TAG, "[場景: $sceneName][特徵#$index] ✅ (Smart)位置符合")
                         return true
                     }
                }
            }

            // Fallback: Percentage Check
            val foundXPercent = (result.x.toDouble() / metrics.widthPixels.toDouble())
            val foundYPercent = (result.y.toDouble() / metrics.heightPixels.toDouble())
            val targetXPercent = expectedX / 100.0
            val targetYPercent = expectedY / 100.0
            
            val diffX = kotlin.math.abs(foundXPercent - targetXPercent)
            val diffY = kotlin.math.abs(foundYPercent - targetYPercent)
            
            if (diffX > toleranceParams || diffY > toleranceParams) {
                 Log.w(TAG, "[場景: $sceneName] ❌ 圖片位置偏差過大: 預期(%):(${ (targetXPercent*100).toInt() }, ${ (targetYPercent*100).toInt() }) 實際:(${ (foundXPercent*100).toInt() }, ${ (foundYPercent*100).toInt() }) 容許:${ (toleranceParams*100).toInt() }%")
                 return false
            } else {
                 if (verbose) Log.d(TAG, "[場景: $sceneName][特徵#$index] ✅ 圖片匹配: 實際(%):(${ (foundXPercent*100).toInt() }, ${ (foundYPercent*100).toInt() })")
            }
        } else {
             if (verbose) Log.d(TAG, "[場景: $sceneName][特徵#$index] ✅ 圖片匹配 (無座標檢查)")
        }
        
        return true
    }

    private fun decodeTemplate(base64: String): Bitmap? {
        return try {
            if (base64.startsWith("http")) {
                 ImageMatcher.downloadBitmap(base64)
            } else {
                val clean = if (base64.contains(",")) base64.split(",")[1] else base64
                val decodedBytes = Base64.decode(clean, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "模板解碼失敗", e)
            null
        }
    }

    private fun checkColor(screen: Bitmap, anchor: JSONObject): Boolean {
        // ... (Move logic from SceneGraphEngine)
        // Simplified for brevity, needs actual logic
        val targetColor = anchor.optString("targetColor")
        if (targetColor.isEmpty()) return false
        
        try {
            val color = android.graphics.Color.parseColor(targetColor)
            val tr = android.graphics.Color.red(color)
            val tg = android.graphics.Color.green(color)
            val tb = android.graphics.Color.blue(color)

            val xPercent = anchor.optDouble("x", 0.0)
            val yPercent = anchor.optDouble("y", 0.0)
            val wPercent = anchor.optDouble("w", 0.0)
            val hPercent = anchor.optDouble("h", 0.0)
             
            val w = (wPercent / 100.0 * screen.width).toInt()
            val h = (hPercent / 100.0 * screen.height).toInt()
            val startX = (xPercent / 100.0 * screen.width).toInt()
            val startY = (yPercent / 100.0 * screen.height).toInt()

            if (w <= 0 || h <= 0) return false

            // Sample center
            val cx = startX + w/2
            val cy = startY + h/2
            if (cx >= screen.width || cy >= screen.height) return false

            val pixel = screen.getPixel(cx, cy)
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)

            // Euclidean distance
            val dist = kotlin.math.sqrt(
                ((r-tr)*(r-tr) + (g-tg)*(g-tg) + (b-tb)*(b-tb)).toDouble()
            )
            if (dist < 50.0) {
                Log.d(TAG, "✅ 顏色匹配成功: ${anchor.optString("label")} Dist:$dist")
                return true
            } else {
                 // Log.v(TAG, "❌ 顏色匹配失敗: ${anchor.optString("label")} Dist:$dist Target:$targetColor Found:RGB($r,$g,$b)")
                return false
            }
        } catch (e: Exception) {
            return false
        }
    }

    private fun checkText(screen: Bitmap, anchor: JSONObject): Pair<Boolean, String?> {
        val targetText = anchor.optString("targetText")
        // If targetText is empty, we might be in "Extract Mode", but checks usually imply matching.
        // If empty, maybe returns true? For now, assume we need non-empty target for a check.
        if (targetText.isEmpty()) return Pair(false, null)

        // 1. Crop Region
        val region = getRegionBitmap(screen, anchor) ?: return Pair(false, null)

        val latch = java.util.concurrent.CountDownLatch(1)
        var isMatch = false
        var recognizedText = ""

        try {
            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(region, 0)
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build()
            )

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    recognizedText = visionText.text.replace("\n", "").trim()
                    // Simple Contains Check
                    if (recognizedText.contains(targetText, ignoreCase = true)) {
                        isMatch = true
                    }
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR 識別失敗", e)
                    latch.countDown()
                }

            // Wait max 3 seconds
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            
            if (isMatch) {
                 remoteLog("INFO", "✅ OCR 匹配成功: '${recognizedText}' 包含 '$targetText'")
            } else {
                 remoteLog("DEBUG", "❌ OCR 匹配失敗: 識別出 '${recognizedText}', 預期包含 '$targetText'")
            }

        } catch (e: Exception) {
            Log.e(TAG, "OCR 錯誤", e)
        }

        return Pair(isMatch, recognizedText)
    }

    private fun checkAi(screen: Bitmap, anchor: JSONObject): Pair<Boolean, String?> {
        val prompt = anchor.optString("targetPrompt")
        if (prompt.isEmpty()) return Pair(false, null)

        // 1. Crop Region
        val region = getRegionBitmap(screen, anchor) ?: return Pair(false, null)

        // 2. Base64
        val base64 = bitmapToBase64(region)
        val urlStr = "https://game-auto-editor.vercel.app/api/ai-check" // Should use config or env

        var isMatch = false
        var reason: String? = null

        try {
            val url = java.net.URL(urlStr)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-secret", BuildConfig.AI_API_SECRET) // Authorized App Secret
            conn.connectTimeout = 5000
            conn.readTimeout = 10000

            val jsonBody = JSONObject()
            jsonBody.put("prompt", prompt)
            jsonBody.put("imageBase64", base64)
            // If variableName exists, use 'extract' mode
            if (anchor.optString("variableName").isNotEmpty()) {
                jsonBody.put("mode", "extract") 
            }

            val os = java.io.OutputStreamWriter(conn.outputStream)
            os.write(jsonBody.toString())
            os.flush()
            os.close()

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val br = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                br.close()

                val respJson = JSONObject(sb.toString())

                
                // Flexible parsing: value (extract) or match (check)
                if (respJson.has("value")) {
                     val valObj = respJson.get("value")
                     reason = valObj.toString()
                     isMatch = true // If value returned, assume matched/found
                } else {
                     isMatch = respJson.optBoolean("match", false)
                     reason = respJson.optString("reason", "")
                }
            } else {
                Log.e(TAG, "AI API 錯誤: $responseCode")
            }

        } catch (e: Exception) {
            Log.e(TAG, "AI 檢查錯誤", e)
        }
        
        // Log explanation
        if (reason != null && reason.isNotEmpty()) {
             remoteLog("INFO", "🧠 AI 推理: $reason")
        }

        return Pair(isMatch, reason)
    }

    // Helper: Crop Bitmap based on Anchor definition
    private fun getRegionBitmap(screen: Bitmap, anchor: JSONObject): Bitmap? {
        val xPercent = anchor.optDouble("x", 0.0)
        val yPercent = anchor.optDouble("y", 0.0)
        val wPercent = anchor.optDouble("w", 0.0)
        val hPercent = anchor.optDouble("h", 0.0)

        // Safety: If w/h is 0, use full screen? No, probably a mistake.
        if (wPercent <= 0 || hPercent <= 0) return null

        val x = (xPercent / 100.0 * screen.width).toInt()
        val y = (yPercent / 100.0 * screen.height).toInt()
        val w = (wPercent / 100.0 * screen.width).toInt()
        val h = (hPercent / 100.0 * screen.height).toInt()

        // Boundary Check
        val safeX = x.coerceIn(0, screen.width - 1)
        val safeY = y.coerceIn(0, screen.height - 1)
        val safeW = w.coerceAtMost(screen.width - safeX)
        val safeH = h.coerceAtMost(screen.height - safeY)

        if (safeW <= 0 || safeH <= 0) return null

        return Bitmap.createBitmap(screen, safeX, safeY, safeW, safeH)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = java.io.ByteArrayOutputStream()
        // Quality 70 is good trade-off
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
