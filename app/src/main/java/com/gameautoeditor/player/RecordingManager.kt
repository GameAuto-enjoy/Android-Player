package com.gameautoeditor.player

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

class RecordingManager(private val context: Context) {
    private val TAG = "RecordingManager"
    
    var isRecording = false
        private set

    // Supports "click" (一般點擊), "condition" (標記條件/OCR), "loop" (標記循環步驟)
    var currentRecordMode: String = "click"
        
    private val nodes = JSONArray()
    private val edges = JSONArray()
    private var lastNodeId: String = "root"
    private var lastActionTime: Long = 0L
    
    // Configs
    private val targetResolution = JSONObject().apply {
        put("width", 720)
        put("height", 1280)
    }

    init {
        reset()
    }

    fun startRecording() {
        Log.i(TAG, "開始錄製腳本")
        isRecording = true
        currentRecordMode = "click"
        reset()
    }

    fun stopRecording(): String {
        Log.i(TAG, "停止錄製腳本")
        isRecording = false
        return generateScriptJson()
    }

    fun reset() {
        lastActionTime = System.currentTimeMillis()
        // Clear nodes and edges
        while(nodes.length() > 0) nodes.remove(0)
        while(edges.length() > 0) edges.remove(0)
        
        // Add root node
        val rootNode = JSONObject().apply {
            put("id", "root")
            put("type", "scene")
            put("position", JSONObject().apply {
                put("x", 100)
                put("y", 100)
            })
            put("data", JSONObject().apply {
                put("label", "起始畫面 (Root)")
                put("isRoot", true)
                put("anchors", JSONArray())
                put("regions", JSONArray())
            })
        }
        nodes.put(rootNode)
        lastNodeId = "root"
    }

    fun addStep(screenshot: Bitmap, touchX: Int, touchY: Int) {
        if (!isRecording) return

        val currentTime = System.currentTimeMillis()
        var delay = currentTime - lastActionTime
        
        // Safety bounds for realistic delay
        if (delay < 500) delay = 500
        if (delay > 30000) delay = 30000 // Cap at 30 seconds
        
        lastActionTime = currentTime

        val screenWidth = screenshot.width
        val screenHeight = screenshot.height
        if (screenWidth > 0 && screenHeight > 0) {
            targetResolution.put("width", screenWidth)
            targetResolution.put("height", screenHeight)
            targetResolution.put("w", screenWidth)
            targetResolution.put("h", screenHeight)
        }

        // Keep sub-percent precision and retain the original touch within a clipped template.
        val recorded = CoordinateGeometry.recordTouch(screenWidth, screenHeight, touchX, touchY)
        val percentX = recorded.touch.x
        val percentY = recorded.touch.y
        val startX = recorded.crop.x.toInt()
        val startY = recorded.crop.y.toInt()
        val actualCropW = recorded.crop.w.toInt()
        val actualCropH = recorded.crop.h.toInt()

        val croppedBitmap = try {
            Bitmap.createBitmap(screenshot, startX, startY, actualCropW, actualCropH)
        } catch (e: Exception) {
            Log.e(TAG, "裁切特徵圖片失敗", e)
            return
        }

        val base64Template = bitmapToBase64(croppedBitmap, 90)
        croppedBitmap.recycle()

        // 2b. Compress full scene screenshot so Web Editor Storyboard always has the full image!
        val fullSceneBase64 = compressFullScreenshotToBase64(screenshot)

        // Percentages for the bounding box
        val regionX = recorded.region.x
        val regionY = recorded.region.y
        val regionW = recorded.region.w
        val regionH = recorded.region.h

        // 3. Create Scene Node compatible with both Web Editor (anchors + regions w/h) and Android Engine
        val stepIndex = nodes.length()
        val sceneNodeId = "scene_${UUID.randomUUID().toString().take(8)}"
        val anchorId = "anchor_${UUID.randomUUID().toString().take(8)}"
        val regionId = "region_${UUID.randomUUID().toString().take(8)}"
        val actionId = "action_${UUID.randomUUID().toString().take(8)}"

        val modePrefix = when (currentRecordMode) {
            "condition" -> "👁️ 條件步驟"
            "loop" -> "🔄 循環步驟"
            else -> "步驟"
        }

        val templateDataUri = "data:image/jpeg;base64,$base64Template"
        val anchorsArray = JSONArray().apply {
            put(JSONObject().apply {
                put("id", anchorId)
                put("label", "錄製特徵 #$stepIndex")
                put("x", regionX)
                put("y", regionY)
                put("w", regionW)
                put("h", regionH)
                put("matchType", "image")
                put("threshold", 0.8)
                put("template", templateDataUri)
                put("imageTemplate", templateDataUri)
                put("customTemplate", templateDataUri)
            })
        }

        val regionObj = JSONObject().apply {
            put("id", regionId)
            put("name", "錄製動作 #$stepIndex")
            put("label", if (currentRecordMode == "condition") "讀取/檢查條件 #$stepIndex" else "點擊按鈕 #$stepIndex")
            put("enabled", true)
            put("clickOffsetX", recorded.offset.x)
            put("clickOffsetY", recorded.offset.y)
            put("exactClick", true)
            put("x", regionX)
            put("y", regionY)
            put("w", regionW)
            put("h", regionH)
            put("width", regionW)
            put("height", regionH)
            put("template", templateDataUri)
            put("imageTemplate", templateDataUri)
            put("customTemplate", templateDataUri)

            if (currentRecordMode == "condition") {
                put("perception", JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "perc_${UUID.randomUUID().toString().take(6)}")
                        put("matchType", "ocr")
                        put("storeVariable", "錄製數值_$stepIndex")
                        put("threshold", 0.8)
                    })
                })
            }

            put("action", JSONObject().apply {
                put("type", if (currentRecordMode == "condition") "WAIT" else "CLICK")
                put("params", JSONObject().apply {
                    put("repeat", 1)
                    put("repeatDelay", delay)
                    put("waitTime", 500)
                })
            })

            // Legacy actions array for backward compatibility
            put("actions", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", actionId)
                    put("type", "click")
                    put("regionId", regionId)
                    put("x", percentX)
                    put("y", percentY)
                    put("delay", delay)
                })
            })
        }

        // Link previous node's last region to this new sceneNodeId for state machine transitions
        if (nodes.length() > 0) {
            val prevNode = nodes.optJSONObject(nodes.length() - 1)
            val prevData = prevNode?.optJSONObject("data")
            val prevRegions = prevData?.optJSONArray("regions")
            if (prevRegions != null && prevRegions.length() > 0) {
                val lastRegion = prevRegions.optJSONObject(prevRegions.length() - 1)
                lastRegion?.put("target", sceneNodeId)
            }
        }

        val sceneNode = JSONObject().apply {
            put("id", sceneNodeId)
            put("type", "scene")
            put("data", JSONObject().apply {
                put("label", "$modePrefix #$stepIndex")
                put("image", "data:image/jpeg;base64,$fullSceneBase64")
                // Snapshot per node: later rotation must not rewrite earlier steps.
                put("resolution", JSONObject(targetResolution.toString()))
                put("isLoopStep", currentRecordMode == "loop")
                put("anchors", anchorsArray)
                put("regions", JSONArray().apply {
                    put(regionObj)
                })
            })
            put("position", JSONObject().apply {
                put("x", 280 * stepIndex)
                put("y", 100)
            })
        }
        nodes.put(sceneNode)

        // 4. Create Edge linking previous node to this node
        val edgeId = "edge_${UUID.randomUUID().toString().take(8)}"
        val edge = JSONObject().apply {
            put("id", edgeId)
            put("source", lastNodeId)
            put("target", sceneNodeId)
        }
        edges.put(edge)

        // Update tracking and reset mode to default click if it was condition
        lastNodeId = sceneNodeId
        Log.i(TAG, "已新增一個錄製步驟 ($currentRecordMode): Node=$sceneNodeId, clicked ($percentX%, $percentY%)")
    }

    private fun generateScriptJson(): String {
        val root = JSONObject()
        
        val settings = JSONObject().apply {
            put("resolution", targetResolution)
            put("humanization", JSONObject().apply {
                put("enabled", true)
                put("clickOffset", 5)
                put("delayOffset", 300)
            })
        }

        root.put("settings", settings)
        root.put("nodes", nodes)
        root.put("edges", edges)

        return root.toString()
    }

    private fun compressFullScreenshotToBase64(bitmap: Bitmap): String {
        return try {
            // Keep native dimensions: editor re-crops must use the same pixel grid as templates.
            bitmapToBase64(bitmap, 85)
        } catch (e: Exception) {
            Log.w(TAG, "壓縮完整截圖失敗，退回原圖壓縮", e)
            bitmapToBase64(bitmap, 55)
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int = 80): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
    }
}

