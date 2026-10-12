package com.gameautoeditor.player

import android.graphics.Bitmap
import android.util.Log
import org.json.JSONObject
import android.graphics.BitmapFactory
import android.util.Base64
import com.gameautoeditor.player.solver.Match3Solver

class PerceptionSystem(private val service: AutomationService, private val logger: ((String, String) -> Unit)? = null) {
    private val TAG = "GameAuto"
    
    // Cache for Decoded Template Bitmaps (Config Layer asset)
    private val templateCache = mutableMapOf<String, Bitmap>()
    
    // API Throttling & Caching for Slow Anchors
    private val anchorThrottleCache = mutableMapOf<String, Pair<Long, Boolean>>()
    private val API_COOLDOWN_MS = 3000L

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

        // Prioritize Fast Anchors (Image, Color, Matrix) over Slow Anchors (OCR, AI)
        val fastAnchors = mutableListOf<JSONObject>()
        val slowAnchors = mutableListOf<JSONObject>()
        
        for (i in 0 until totalAnchors) {
            val anchor = anchors.getJSONObject(i)
            val type = anchor.optString("matchType", "image").lowercase()
            if (type == "ocr" || type == "text" || type == "ai") {
                slowAnchors.add(anchor)
            } else {
                fastAnchors.add(anchor)
            }
        }
        
        val sortedAnchors = fastAnchors + slowAnchors

        val stateId = stateNode.optString("id", sceneName)
        for (i in 0 until totalAnchors) {
            val anchor = sortedAnchors[i]
            val type = anchor.optString("matchType", "image").lowercase()
            val isPrerequisite = anchor.optBoolean("isPrerequisite", false)

            val anchorId = anchor.optString("id", "${stateId}_$i")

            // API Throttling & Caching for Slow Anchors
            var cachedResult: Boolean? = null
            if (type == "ocr" || type == "text" || type == "ai") {
                val cacheEntry = anchorThrottleCache[anchorId]
                val lastCallTime = cacheEntry?.first ?: 0L
                val now = System.currentTimeMillis()
                
                if (now - lastCallTime < API_COOLDOWN_MS) {
                    if (verbose) {
                        remoteLog("DEBUG", "⏳ [$sceneName] Api Throttled ($type). 等待冷卻中...")
                    }
                    // Use cached result instead of skipping and treating as false
                    cachedResult = cacheEntry?.second ?: false
                }
            }

            // Pass verbose flag and index
            val anchorMatched = if (cachedResult != null) {
                cachedResult
            } else {
                val matched = checkAnchor(screen, anchor, variables, sceneName, expectedScale, nodeRes, verbose, i + 1)
                if (type == "ocr" || type == "text" || type == "ai") {
                    anchorThrottleCache[anchorId] = Pair(System.currentTimeMillis(), matched)
                }
                matched
            }
            
            if (anchorMatched) {
                matchCount++
            } else if (isPrerequisite) {
                // Prerequisite Short-Circuit (Fail Fast)
                if (verbose) {
                    remoteLog("DEBUG", "❌ [$sceneName] 條件不符: 前置條件 (Prerequisite) 檢查失敗，提早放棄。")
                }
                return false
            }
            
            // Short-Circuit Optimization (Quick Fail)
            // If the remaining anchors are not enough to reach 'minMatches', abort early.
            // This prevents expensive checks (AI) if a simple check (Image) has already failed.
            val remainingAnchors = totalAnchors - (i + 1)
            if (matchCount + remainingAnchors < minMatches) {
                // Only log if we are actually saving time (skipping future checks)
                if (verbose && remainingAnchors > 0) {
                    remoteLog("DEBUG", "⚡ 條件不夠，提早放棄 (已符合 $matchCount, 剩餘 $remainingAnchors, 目標 $minMatches)")
                }
                break
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
        var variableName = anchor.optString("variableName")
        if (variableName.isEmpty()) {
            variableName = anchor.optString("storeVariable")
        }
        
        val result = when (matchType.lowercase()) {
            "color" -> Pair(checkColor(screen, anchor, sceneName), null)
            "text" -> checkText(screen, anchor, sceneName, verbose)
            "ai" -> checkAi(screen, anchor, sceneName, verbose)
            "matrix" -> checkMatrix(screen, anchor, sceneName, variables)
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

        val multiMatch = anchor.optBoolean("multiMatch", false)
        if (multiMatch) {
            val results = ImageMatcher.findAllTemplates(screen, template, 0.7, scale)
            if (results.isEmpty()) return false
            
            val pointsArray = org.json.JSONArray()
            val metrics = service.resources.displayMetrics
            
            for (res in results) {
                val point = JSONObject()
                point.put("x", (res.x.toDouble() / metrics.widthPixels.toDouble()) * 100.0)
                point.put("y", (res.y.toDouble() / metrics.heightPixels.toDouble()) * 100.0)
                point.put("w", (res.width.toDouble() / metrics.widthPixels.toDouble()) * 100.0)
                point.put("h", (res.height.toDouble() / metrics.heightPixels.toDouble()) * 100.0)
                pointsArray.put(point)
            }
            anchor.put("_dynamicPoints", pointsArray)
            
            if (verbose) remoteLog("INFO", "✅ [$sceneName] 找到 ${results.size} 個匹配目標 (Multi-Match)")
            return true
        }

        // Use ImageMatcher (OpenCV)
        val result = ImageMatcher.findTemplate(screen, template, 0.7, scale)
        if (result == null) {
            return false
        }
        
        if (result.score >= 0.9) {
             if (verbose) remoteLog("INFO", "✅ [$sceneName] 圖案超像! (相似度 ${String.format("%.0f", result.score*100)}%) 直接通過")
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
                         Log.w(TAG, "⚠️ [$sceneName] 圖案找到了，但位置不對! 預期在 (${targetDevX.toInt()}, ${targetDevY.toInt()}) 附近")
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
                 Log.w(TAG, "⚠️ [$sceneName] 圖案位置偏太遠了! 實際在 (${(foundXPercent*100).toInt()}%, ${(foundYPercent*100).toInt()}%)")
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

    private fun checkColor(screen: Bitmap, anchor: JSONObject, sceneName: String): Boolean {
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
                remoteLog("INFO", "✅ [$sceneName] 顏色匹配成功: ${anchor.optString("label")} Dist:${String.format("%.1f", dist)}")
                return true
            } else {
                 // Log.v(TAG, "❌ 顏色匹配失敗: ${anchor.optString("label")} Dist:$dist Target:$targetColor Found:RGB($r,$g,$b)")
                return false
            }
        } catch (e: Exception) {
            return false
        }
    }

    private fun checkText(screen: Bitmap, anchor: JSONObject, sceneName: String, verbose: Boolean): Pair<Boolean, String?> {
        var targetText = anchor.optString("targetText")
        if (targetText.isEmpty()) {
            targetText = anchor.optString("textPattern")
        }
        if (targetText.isEmpty()) {
            if (verbose) remoteLog("WARN", "⚠️ [$sceneName] OCR 檢查略過: 未設定 targetText")
            return Pair(false, null)
        }

        // 1. Crop Region
        var region = getRegionBitmap(screen, anchor) 
        if (region == null) {
            if (verbose) remoteLog("WARN", "⚠️ [$sceneName] OCR 截圖失敗: 區域無效 (Region invalid)")
            return Pair(false, null)
        }

        // --- Optimization 1: Upscale (v1.9.10) ---
        if (region.height < 50) {
             val newW = region.width * 2
             val newH = region.height * 2
             try {
                 val scaled = Bitmap.createScaledBitmap(region, newW, newH, true)
                 if (scaled != region) {
                     region.recycle() 
                     region = scaled
                 }
             } catch (e: OutOfMemoryError) {
                 Log.w(TAG, "OCR Upscale OOM", e)
             }
        }

        // --- NEW: Smart Visual Filters (v1.9.15) ---
        // Dealing with Rendered Game Text (Glowing, text on texture, bad contrast)
        // We try "Multi-Pass" recognition with different visual filters.
        
        val filters = listOf(
            "original",         // 1. Raw Image
            "binary",           // 2. High Contrast (Thresholding) - Best for general game text
            "invert_binary"     // 3. Inverted High Contrast - Best for "White text on Dark"
        )
        
        var finalMatch = false
        var finalText = ""
        
        for (filterType in filters) {
            // Apply filter (Clone bitmap to avoid messing up next pass if using same source? createBitmap does copy)
            // region is a var (can be reassigned), so smart cast doesn't work perfectly. Force !!.
            val processedBitmap = if (filterType == "original") region!! else applyFilter(region!!, filterType)
            
            // Perform OCR
            val (match, text) = performOcr(processedBitmap, targetText)
            
            // Clean up processed bitmap if made
            if (processedBitmap != region) processedBitmap.recycle()
            
            if (match) {
                finalMatch = true
                finalText = text
                if (verbose) remoteLog("INFO", "✅ [$sceneName] OCR 匹配成功 ($filterType): '$text'")
                break // Success!
            } else {
                // Keep the last text just for logging info
                finalText = text 
                // Don't log failure yet, try next filter
            }
        }
        
        if (!finalMatch) {
             if (verbose) remoteLog("DEBUG", "❌ [$sceneName] OCR失敗 (嘗試 ${filters.size} 種濾鏡): 畫面讀取為 '$finalText', 但需要 '$targetText'")
        }

        // Clean up
        region!!.recycle()

        return Pair(finalMatch, finalText)
    }

    // Helper: Perform actual OCR on a provided bitmap
    private fun performOcr(bitmap: Bitmap, target: String): Pair<Boolean, String> {
        val latch = java.util.concurrent.CountDownLatch(1)
        var isMatch = false
        var recognizedText = ""

        try {
            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build()
            )

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val raw = visionText.text
                    // Robust Cleaning: Remove all whitespace
                    val cleanFound = raw.replace("\\s".toRegex(), "")
                    val cleanTarget = target.replace("\\s".toRegex(), "")
                    
                    recognizedText = cleanFound
                    if (cleanFound.contains(cleanTarget, ignoreCase = true)) {
                        isMatch = true
                    }
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR SDK Error", e)
                    latch.countDown()
                }

            latch.await(2, java.util.concurrent.TimeUnit.SECONDS) // Fast timeout for multi-pass
        } catch (e: Exception) {
            Log.e(TAG, "OCR Error", e)
        }
        return Pair(isMatch, recognizedText)
    }

    // Helper: Apply Visual Filters using Android Canvas/Paint
    private fun applyFilter(src: Bitmap, type: String): Bitmap {
        val dest = Bitmap.createBitmap(src.width, src.height, src.config)
        val canvas = android.graphics.Canvas(dest)
        val paint = android.graphics.Paint()
        val matrix = android.graphics.ColorMatrix()

        when (type) {
            "binary" -> {
                // 1. Grayscale
                matrix.setSaturation(0f)
                // 2. High Contrast (Scale intensity)
                // [ 2  0  0  0 -160 ]
                // [ 0  2  0  0 -160 ]
                // [ 0  0  2  0 -160 ]
                // [ 0  0  0  1    0 ]
                val m = matrix.array
                val contrast = 3.0f 
                val offset = -140f
                val scale = android.graphics.ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, offset,
                    0f, contrast, 0f, 0f, offset,
                    0f, 0f, contrast, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                ))
                matrix.postConcat(scale)
            }
            "invert_binary" -> {
                // 1. Grayscale
                matrix.setSaturation(0f)
                // 2. Invert: [ -1  0  0  0  255 ] ...
                val invert = android.graphics.ColorMatrix(floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                ))
                matrix.postConcat(invert)
                
                // 3. Contrast to Binary
                val contrast = 3.0f 
                val offset = -140f 
                val scale = android.graphics.ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, offset,
                    0f, contrast, 0f, 0f, offset,
                    0f, 0f, contrast, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                ))
                matrix.postConcat(scale)
            }
        }
        
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dest
    }

    /**
     * Recursive search for text in Accessibility Node Tree
     * Returns the found text if matched, null otherwise.
     */
    private fun searchAccessibilityTree(node: android.view.accessibility.AccessibilityNodeInfo?, target: String, anchor: JSONObject): String? {
        if (node == null) return null
        
        // 1. Check Node Content
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val viewId = node.viewIdResourceName

        // Unified clean check
        val cleanTarget = target.replace("\\s".toRegex(), "")

        if (!text.isNullOrEmpty()) {
             if (text.replace("\\s".toRegex(), "").contains(cleanTarget, true)) return text
        }
        if (!desc.isNullOrEmpty()) {
             if (desc.replace("\\s".toRegex(), "").contains(cleanTarget, true)) return desc
        }
        // Also support ID matching if target looks like an ID? (Optional)
        
        // 2. Spatial Check (Optional: Only check nodes roughly inside the anchor region)
        // For now, global search is safer because Accessibility Nodes often have weird bounds.
        // IF we want to restrict, we need rect logic.
        
        // 3. Recurse Children
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i)
            val result = searchAccessibilityTree(child, target, anchor)
            if (result != null) return result
        }
        
        return null
    }

    private fun checkAi(screen: Bitmap, anchor: JSONObject, sceneName: String, verbose: Boolean): Pair<Boolean, String?> {
        val prompt = if (anchor.has("prompt")) anchor.getString("prompt") else anchor.optString("targetPrompt")
        if (prompt.isEmpty()) {
            if (verbose) remoteLog("WARN", "⚠️ [$sceneName] AI 檢查失敗: 未設定 prompt (Prompt is empty)")
            return Pair(false, "Prompt 未設置")
        }

        // 1. Crop Region
        val region = getRegionBitmap(screen, anchor)
        if (region == null) {
            if (verbose) remoteLog("WARN", "⚠️ [$sceneName] AI 檢查失敗: 截圖區域無效 (Region invalid)")
            return Pair(false, "截圖區域無效")
        }

        // 2. Base64
        val base64 = bitmapToBase64(region)
        val urlStr = "https://game-auto-ai.vercel.app/api/ai-check" // Should use config or env

        var isMatch = false
        var reason: String? = null
        var retryAfterRefresh: Boolean

        do {
            retryAfterRefresh = false
            try {
                val url = java.net.URL(urlStr)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-api-secret", BuildConfig.AI_API_SECRET) // Authorized App Secret
                
                // Dual Auth: Priority User Token > License Key
                val userToken = service.getUserToken()
                val licenseKey = service.getLicenseKey()
                val deviceId = service.getAppDeviceId()

                if (!userToken.isNullOrEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer $userToken")
                    // Enforce Device Binding for User Token
                    if (!deviceId.isNullOrEmpty()) {
                        conn.setRequestProperty("x-device-id", deviceId)
                    }
                } else if (!licenseKey.isNullOrEmpty()) {
                    conn.setRequestProperty("x-license-key", licenseKey)
                }
                conn.connectTimeout = 5000
                conn.readTimeout = 10000

                val jsonBody = JSONObject()
                jsonBody.put("prompt", prompt)
                jsonBody.put("imageBase64", base64)
                // If variableName exists, use 'extract' mode
                val varName = if (anchor.has("variableName")) anchor.optString("variableName") else anchor.optString("storeVariable")
                if (varName.isNotEmpty()) {
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
                    val errorMsg = try {
                        conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "未知錯誤"
                    } catch (e: Exception) { "讀取錯誤: ${e.message}" }
                    
                    Log.e(TAG, "AI API 錯誤: $responseCode - $errorMsg")
                    
                    if (responseCode == 401 && errorMsg.contains("expired", ignoreCase = true)) {
                        Log.i(TAG, "AI API 憑證過期，嘗試自動刷新...")
                        if (service.refreshTokenSync()) {
                            remoteLog("INFO", "♻️ 憑證自動刷新成功，即將重試 AI 感知...")
                            retryAfterRefresh = true
                        } else {
                            remoteLog("ERROR", "🛑 驗證過期且自動刷新失敗！腳本已自動中斷，請返回 App 重新登入。")
                            service.forceStop("驗證已過期且刷新失敗，請重新登入")
                        }
                    } else {
                        if (verbose) remoteLog("ERROR", "❌ [$sceneName] AI API 請求失敗: HTTP $responseCode\n\n$errorMsg")
                    }
                }

            } catch (e: java.net.SocketTimeoutException) {
                 Log.e(TAG, "AI 連線逾時", e)
                 if (verbose) remoteLog("ERROR", "❌ [$sceneName] AI 連線逾時 (Timeout): 請檢查網路狀況")
            } catch (e: Exception) {
                Log.e(TAG, "AI 檢查錯誤", e)
                if (verbose) remoteLog("ERROR", "❌ [$sceneName] AI 執行錯誤: ${e.message}")
            }
        } while (retryAfterRefresh)
        
        // Log explanation
        if (reason != null && reason.isNotEmpty()) {
             if (verbose) remoteLog("INFO", "🧠 [$sceneName] AI 推理: $reason")
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

    private fun checkMatrix(screen: Bitmap, anchor: JSONObject, sceneName: String, variables: MutableMap<String, Int>): Pair<Boolean, String?> {
        remoteLog("INFO", "🎲 [$sceneName] 網格分析 (Grid Scanner) 啟動...")
        
        val region = getRegionBitmap(screen, anchor)
        if (region == null) {
            remoteLog("WARN", "⚠️ [$sceneName] 網格分析失敗: 截圖區域無效")
            return Pair(false, "截圖區域無效")
        }

        try {
            // 1. Cut the `getRegionBitmap(screen, anchor)` into 6x5 blocks and classify
            val grid = Match3Solver.analyzeGrid(region)
            
            // Format grid for remoteLog (Web UI Visualizer)
            val sb = StringBuilder("盤面解析結果:\n")
            for (row in 0 until Match3Solver.ROWS) {
                for (col in 0 until Match3Solver.COLUMNS) {
                    val icon = when (grid[row][col]) {
                        Match3Solver.OrbType.WATER -> "💧"
                        Match3Solver.OrbType.FIRE -> "🔥"
                        Match3Solver.OrbType.WOOD -> "🌿"
                        Match3Solver.OrbType.LIGHT -> "☀️"
                        Match3Solver.OrbType.DARK -> "🌑"
                        Match3Solver.OrbType.HEART -> "💖"
                        Match3Solver.OrbType.UNKNOWN -> "❓"
                    }
                    sb.append("$icon ")
                }
                sb.append("\n")
            }
            remoteLog("INFO", sb.toString())
            
            // 2. Run pathfinding on the matrix
            val path = Match3Solver.calculateBestPath(grid)
            
            // 3. Inject the resulting points into the current Action via variables?
            val pointsArray = Match3Solver.convertPathToSwipeConfig(path, anchor)
            remoteLog("INFO", "✅ [$sceneName] 轉珠路徑計算完成! 步數: ${pointsArray.length()}")
            
            // Pass the extracted path to the ActionSystem via the anchor context
            anchor.put("_dynamicPoints", pointsArray)
            
            return Pair(true, "找到 3-Match 轉珠路徑 (${pointsArray.length()} 步)")
        } catch (e: Exception) {
            Log.e(TAG, "Matrix 解算錯誤", e)
            remoteLog("ERROR", "❌ [$sceneName] 網格分析錯誤: ${e.message}")
            return Pair(false, "分析異常")
        } finally {
            region.recycle()
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = java.io.ByteArrayOutputStream()
        // Quality 70 is good trade-off
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
