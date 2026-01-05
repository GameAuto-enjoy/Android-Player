package com.gameautoeditor.player

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.URL
import org.json.JSONArray

class ScriptEngine(private val service: AutomationService) {
    
    private val TAG = "ScriptEngine"
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val variables = mutableMapOf<String, String>()
    private val imageCache = mutableMapOf<String, Bitmap>()
    
    fun executeScript(scriptJson: String) {
        try {
            val root = JSONObject(scriptJson)
            val metadata = root.optJSONObject("metadata")
            val steps = root.getJSONArray("steps")
            
            Log.i(TAG, "📜 載入腳本: ${metadata?.optString("project_name", "Unknown")}")
            Log.i(TAG, "📊 總步驟數: ${steps.length()}")
            
            isRunning = true
            executeStep(steps, findStepById(steps, findStartNode(steps)))
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 腳本執行錯誤: ${e.message}", e)
            service.showToast("腳本執行失敗: ${e.message}")
        }
    }
    
    private fun findStartNode(steps: JSONArray): String? {
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            if (step.getString("type") == "START") {
                return step.getString("id")
            }
        }
        return null
    }
    
    private fun findStepById(steps: JSONArray, id: String?): JSONObject? {
        if (id == null) return null
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            if (step.getString("id") == id) {
                return step
            }
        }
        return null
    }
    
    private fun executeStep(steps: JSONArray, step: JSONObject?) {
        if (step == null || !isRunning) {
            Log.i(TAG, "✅ 腳本執行完畢")
            service.showToast("腳本執行完畢")
            return
        }
        
        val type = step.getString("type")
        val id = step.getString("id")
        
        Log.d(TAG, "▶️ 執行步驟: $id ($type)")
        
        when (type) {
            "START" -> {
                val next = step.optString("next", null)
                executeStep(steps, findStepById(steps, next))
            }
            
            "CLICK" -> {
                val params = step.optJSONObject("params")
                if (params != null) {
                    val xPercent = params.optDouble("x_percent", 0.5)
                    val yPercent = params.optDouble("y_percent", 0.5)
                    
                    val metrics = service.resources.displayMetrics
                    val x = (metrics.widthPixels * xPercent).toFloat()
                    val y = (metrics.heightPixels * yPercent).toFloat()
                    
                    performClick(x, y)
                    
                    handler.postDelayed({
                        val next = step.optString("next", null)
                        executeStep(steps, findStepById(steps, next))
                    }, 500)
                } else {
                    val next = step.optString("next", null)
                    executeStep(steps, findStepById(steps, next))
                }
            }
            
            "WAIT" -> {
                val params = step.optJSONObject("params")
                val duration = params?.optInt("duration", 1000) ?: 1000
                
                handler.postDelayed({
                    val next = step.optString("next", null)
                    executeStep(steps, findStepById(steps, next))
                }, duration.toLong())
            }
            
            "LOOP" -> {
                val params = step.optJSONObject("params")
                val mode = params?.optString("mode", "count") ?: "count"
                val count = params?.optInt("count", 1) ?: 1
                
                // 簡化版：只支援固定次數迴圈
                val loopVar = "loop_${id}_count"
                val currentCount = variables[loopVar]?.toIntOrNull() ?: 0
                
                if (currentCount < count) {
                    variables[loopVar] = (currentCount + 1).toString()
                    val next = step.optString("next", null) // Do path
                    executeStep(steps, findStepById(steps, next))
                } else {
                    variables.remove(loopVar)
                    val branches = step.optJSONObject("branches")
                    val done = branches?.optString("done", null)
                    executeStep(steps, findStepById(steps, done))
                }
            }
            
            "LOOP_END" -> {
                // Loop End 應該跳回 Loop 節點
                // 這裡需要反向查找，簡化版暫時跳過
                service.showToast("Loop End (返回上層)")
                val next = step.optString("next", null)
                executeStep(steps, findStepById(steps, next))
            }
            
            "CONDITION" -> {
                val params = step.optJSONObject("params")
                val condType = params?.optString("type", "prev_status") ?: "prev_status"
                
                var result = false
                when (condType) {
                    "prev_status" -> result = true // 簡化：總是 true
                    "variable" -> {
                        val varKey = params?.optString("var_key", "")
                        val varOp = params?.optString("var_op", "==")
                        val varVal = params?.optString("var_val", "")
                        
                        val actualVal = variables[varKey] ?: "0"
                        result = when (varOp) {
                            "==" -> actualVal == varVal
                            "!=" -> actualVal != varVal
                            ">" -> actualVal.toIntOrNull()?.let { it > (varVal?.toIntOrNull() ?: 0) } ?: false
                            "<" -> actualVal.toIntOrNull()?.let { it < (varVal?.toIntOrNull() ?: 0) } ?: false
                            else -> false
                        }
                    }
                }
                
                val nextId = if (result) {
                    step.optString("next", null)
                } else {
                    val branches = step.optJSONObject("branches")
                    branches?.optString("false", null)
                }
                
                executeStep(steps, findStepById(steps, nextId))
            }
            
            "VARIABLE" -> {
                val params = step.optJSONObject("params")
                if (params != null) {
                    val key = params.optString("key", "")
                    val op = params.optString("op", "set")
                    val value = params.optString("value", "0")
                    
                    when (op) {
                        "set" -> variables[key] = value
                        "add" -> {
                            val current = variables[key]?.toIntOrNull() ?: 0
                            variables[key] = (current + (value.toIntOrNull() ?: 0)).toString()
                        }
                        "sub" -> {
                            val current = variables[key]?.toIntOrNull() ?: 0
                            variables[key] = (current - (value.toIntOrNull() ?: 0)).toString()
                        }
                    }
                    
                    Log.d(TAG, "📝 變數更新: $key = ${variables[key]}")
                }
                
                val next = step.optString("next", null)
                executeStep(steps, findStepById(steps, next))
            }
            
            "TOAST" -> {
                val params = step.optJSONObject("params")
                val message = params?.optString("message", "Toast") ?: "Toast"
                service.showToast(message)
                
                handler.postDelayed({
                    val next = step.optString("next", null)
                    executeStep(steps, findStepById(steps, next))
                }, 1000)
            }
            

            
            "MATCH_IMAGE" -> {
                val params = step.optJSONObject("params")
                val imageUrl = params?.optString("image_url")
                val threshold = params?.optDouble("threshold", 0.8) ?: 0.8
                val action = params?.optString("action", "click") ?: "click"

                if (imageUrl.isNullOrEmpty()) {
                    Log.e(TAG, "❌ MATCH_IMAGE: No image URL provided")
                    val branches = step.optJSONObject("branches")
                    executeStep(steps, findStepById(steps, branches?.optString("fail")))
                    return
                }

                service.captureScreen { screenBitmap ->
                    if (screenBitmap == null) {
                        Log.e(TAG, "❌ Screen capture failed")
                        val branches = step.optJSONObject("branches")
                        executeStep(steps, findStepById(steps, branches?.optString("fail")))
                        return@captureScreen
                    }

                    Thread {
                        try {
                            var templateBitmap = imageCache[imageUrl]
                            if (templateBitmap == null) {
                                Log.d(TAG, "📥 Downloading template: $imageUrl")
                                val url = URL(imageUrl)
                                templateBitmap = BitmapFactory.decodeStream(url.openStream())
                                if (templateBitmap != null) {
                                    imageCache[imageUrl] = templateBitmap
                                }
                            }

                            if (templateBitmap == null) {
                                handler.post {
                                    Log.e(TAG, "❌ Failed to load template image")
                                    val branches = step.optJSONObject("branches")
                                    executeStep(steps, findStepById(steps, branches?.optString("fail")))
                                }
                                return@Thread
                            }

                            Log.d(TAG, "🔍 Matching image...")
                            val result = ImageMatcher.findTemplate(screenBitmap, templateBitmap, threshold)

                            handler.post {
                                if (result != null) {
                                    Log.i(TAG, "✅ Image Found at: ${result.x}, ${result.y}")

                                    if (action == "click") {
                                        performClick(result.x.toFloat(), result.y.toFloat())
                                    }

                                    val delay = if (action == "click") 500L else 0L
                                    handler.postDelayed({
                                        val next = step.optString("next", null)
                                        executeStep(steps, findStepById(steps, next))
                                    }, delay)

                                } else {
                                    Log.i(TAG, "❌ Image Not Found")
                                    val branches = step.optJSONObject("branches")
                                    val failStep = branches?.optString("fail", null)
                                    executeStep(steps, findStepById(steps, failStep))
                                }
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "Error in Match Image: ${e.message}")
                            handler.post {
                                val branches = step.optJSONObject("branches")
                                executeStep(steps, findStepById(steps, branches?.optString("fail")))
                            }
                        }
                    }.start()
                }
            }
            
            "SYSTEM" -> {
                val params = step.optJSONObject("params")
                val command = params?.optString("command", "home") ?: "home"
                
                when (command) {
                    "home" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    "recent" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                }
                
                handler.postDelayed({
                    val next = step.optString("next", null)
                    executeStep(steps, findStepById(steps, next))
                }, 500)
            }
            
            "STOP" -> {
                Log.i(TAG, "🛑 腳本停止")
                service.showToast("腳本已停止")
                isRunning = false
            }
            
            else -> {
                Log.w(TAG, "⚠️ 未知節點類型: $type")
                val next = step.optString("next", null)
                executeStep(steps, findStepById(steps, next))
            }
        }
    }
    
    private fun performClick(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        
        service.dispatchGesture(gesture, null, null)
        Log.d(TAG, "👆 點擊座標: ($x, $y)")
    }
    
    fun stop() {
        isRunning = false
        Log.i(TAG, "⏹️ 腳本引擎停止")
    }
}
