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
            put("type", "root")
            put("data", JSONObject().apply {
                put("label", "Start Point (Root)")
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

        // 1. Calculate percentage coordinates
        val percentX = (touchX.toFloat() / screenWidth * 100).toInt()
        val percentY = (touchY.toFloat() / screenHeight * 100).toInt()

        // 2. Crop 100x100 region around the touch point for the Anchor
        val cropSize = 100
        var startX = touchX - cropSize / 2
        var startY = touchY - cropSize / 2

        // Bounds check
        if (startX < 0) startX = 0
        if (startY < 0) startY = 0
        if (startX + cropSize > screenWidth) startX = screenWidth - cropSize
        if (startY + cropSize > screenHeight) startY = screenHeight - cropSize

        val croppedBitmap = try {
            Bitmap.createBitmap(screenshot, startX, startY, cropSize, cropSize)
        } catch (e: Exception) {
            Log.e(TAG, "裁切特徵圖片失敗", e)
            return
        }

        val base64Image = bitmapToBase64(croppedBitmap)
        croppedBitmap.recycle()

        // Percentages for the bounding box
        val regionX = (startX.toFloat() / screenWidth * 100).toInt()
        val regionY = (startY.toFloat() / screenHeight * 100).toInt()
        val regionW = (cropSize.toFloat() / screenWidth * 100).toInt()
        val regionH = (cropSize.toFloat() / screenHeight * 100).toInt()

        // 3. Create Scene Node
        val sceneNodeId = "scene_${UUID.randomUUID().toString().take(8)}"
        val regionId = "region_${UUID.randomUUID().toString().take(8)}"
        val actionId = "action_${UUID.randomUUID().toString().take(8)}"

        val sceneNode = JSONObject().apply {
            put("id", sceneNodeId)
            put("type", "scene")
            put("data", JSONObject().apply {
                put("label", "Scene $sceneNodeId")
                put("regions", JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", regionId)
                        put("name", "Auto Anchor")
                        put("x", regionX)
                        put("y", regionY)
                        put("width", regionW)
                        put("height", regionH)
                        put("imageTemplate", "data:image/jpeg;base64,$base64Image")
                        put("actions", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", actionId)
                                put("type", "click")
                                put("regionId", regionId)
                                put("x", percentX)
                                put("y", percentY)
                                put("delay", delay) // Auto-calculated realistic delay
                            })
                        })
                    })
                })
            })
            // Position for editor (arbitrary, spread them out sequentially)
            put("position", JSONObject().apply {
                put("x", 250 * nodes.length())
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

        // Update tracking
        lastNodeId = sceneNodeId
        Log.i(TAG, "已新增一個錄製步驟: Node=$sceneNodeId, clicked ($percentX%, $percentY%)")
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

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        return android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
    }
}
