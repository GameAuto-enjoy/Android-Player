package com.gameautoeditor.player

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

class RecordingManager(private val context: Context) {
    private val TAG = "RecordingManager"

    @Volatile
    var isRecording = false
        private set

    // Supports "click" (一般點擊), "condition" (標記條件/OCR), "loop" (標記循環起點/步驟)
    @Volatile
    var currentRecordMode: String = "click"

    private val nodes = JSONArray()
    private val edges = JSONArray()
    private var lastNodeId: String = "root"
    private var firstLoopNodeId: String? = null
    private var recordedStepCount: Int = 0
    private var lastActionTime: Long = 0L
    private var embeddedImageBytes: Int = 0

    // Configs
    private val targetResolution = JSONObject().apply {
        put("width", 720)
        put("height", 1280)
    }

    companion object {
        private const val DEGRADE_QUALITY_THRESHOLD_BYTES = 3_500_000
        private const val MAX_EMBEDDED_SCENE_BYTES = 8_000_000
        private const val DEFAULT_WAIT_AFTER_MS = 1200L
        private const val SWIPE_MIN_DISTANCE_PX = 60.0
    }

    init {
        reset()
    }

    @Synchronized
    fun getStepCount(): Int = recordedStepCount

    @Synchronized
    fun startRecording() {
        Log.i(TAG, "開始錄製腳本")
        isRecording = true
        currentRecordMode = "click"
        reset()
    }

    @Synchronized
    fun stopRecording(): String {
        Log.i(TAG, "停止錄製腳本 (共 $recordedStepCount 步)")
        isRecording = false
        closeRecordingLoop()
        return generateScriptJson()
    }

    @Synchronized
    fun reset() {
        lastActionTime = System.currentTimeMillis()
        embeddedImageBytes = 0
        firstLoopNodeId = null
        recordedStepCount = 0
        // Clear nodes and edges
        while (nodes.length() > 0) nodes.remove(0)
        while (edges.length() > 0) edges.remove(0)

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
        addStep(screenshot, touchX, touchY, null, null, 0L)
    }

    @Synchronized
    fun addStep(
        screenshot: Bitmap,
        touchX: Int,
        touchY: Int,
        endX: Int?,
        endY: Int?,
        gestureDurationMs: Long = 0L
    ) {
        if (!isRecording) return

        val screenWidth = screenshot.width
        val screenHeight = screenshot.height
        if (screenWidth <= 0 || screenHeight <= 0) {
            Log.w(TAG, "截圖尺寸無效 (${screenWidth}x${screenHeight})，略過錄製步驟")
            return
        }

        val currentTime = System.currentTimeMillis()
        val elapsedSincePrev = (currentTime - lastActionTime).coerceIn(500L, 30000L)
        lastActionTime = currentTime

        targetResolution.put("width", screenWidth)
        targetResolution.put("height", screenHeight)
        targetResolution.put("w", screenWidth)
        targetResolution.put("h", screenHeight)

        val activeMode = currentRecordMode
        val isCondition = activeMode == "condition"
        val isLoop = activeMode == "loop"
        val isSwipe = !isCondition &&
            endX != null && endY != null &&
            hypot((endX - touchX).toDouble(), (endY - touchY).toDouble()) >= SWIPE_MIN_DISTANCE_PX

        // Keep sub-percent precision and retain the original touch within a clipped template.
        val recorded = CoordinateGeometry.recordTouch(screenWidth, screenHeight, touchX, touchY)
        val percentX = recorded.touch.x
        val percentY = recorded.touch.y
        val startX = recorded.crop.x.toInt()
        val startY = recorded.crop.y.toInt()
        val actualCropW = recorded.crop.w.toInt()
        val actualCropH = recorded.crop.h.toInt()

        var templateDataUri: String? = null
        if (!isCondition) {
            val croppedBitmap = try {
                Bitmap.createBitmap(screenshot, startX, startY, actualCropW, actualCropH)
            } catch (e: Exception) {
                Log.e(TAG, "裁切特徵圖片失敗", e)
                return
            }

            val base64Template = try {
                bitmapToBase64(croppedBitmap, 85)
            } finally {
                croppedBitmap.recycle()
            }

            val uri = "data:image/jpeg;base64,$base64Template"
            templateDataUri = uri
            // Account for canonical template stored on anchor + region + region.perception[0] (3x; deduplicated to 1 R2 upload on save)
            embeddedImageBytes += uri.length * 3
        }

        // 2b. Compress full scene screenshot at native pixel grid within post-add payload budget
        val dataUriPrefixLen = "data:image/jpeg;base64,".length
        val fullSceneBase64 = if (embeddedImageBytes < MAX_EMBEDDED_SCENE_BYTES) {
            val initialQuality = if (embeddedImageBytes >= DEGRADE_QUALITY_THRESHOLD_BYTES) 45 else 65
            var encoded = compressFullScreenshotToBase64(screenshot, initialQuality)
            var sceneBytes = dataUriPrefixLen + encoded.length
            if (embeddedImageBytes + sceneBytes > MAX_EMBEDDED_SCENE_BYTES && initialQuality > 45) {
                encoded = compressFullScreenshotToBase64(screenshot, 45)
                sceneBytes = dataUriPrefixLen + encoded.length
            }
            if (embeddedImageBytes + sceneBytes <= MAX_EMBEDDED_SCENE_BYTES) {
                embeddedImageBytes += sceneBytes
                encoded
            } else {
                Log.w(TAG, "加入完整截圖將超過總大小上限 (${embeddedImageBytes + sceneBytes} > $MAX_EMBEDDED_SCENE_BYTES bytes)，本步驟僅保留裁切特徵圖")
                null
            }
        } else {
            Log.w(TAG, "完整截圖總大小已達上限 ($embeddedImageBytes bytes)，本步驟僅保留裁切特徵圖")
            null
        }

        // Percentages for the bounding box
        val regionX = recorded.region.x
        val regionY = recorded.region.y
        val regionW = recorded.region.w
        val regionH = recorded.region.h

        // 3. Create Scene Node compatible with both Web Editor and Android SceneGraphEngine
        val stepIndex = nodes.length()
        val sceneNodeId = "scene_${UUID.randomUUID().toString().take(8)}"
        val anchorId = "anchor_${UUID.randomUUID().toString().take(8)}"
        val regionId = "region_${UUID.randomUUID().toString().take(8)}"
        val actionId = "action_${UUID.randomUUID().toString().take(8)}"

        val modePrefix = when {
            isCondition -> "👁️ 條件步驟"
            isLoop -> "🔄 循環步驟"
            isSwipe -> "👆 滑動步驟"
            else -> "步驟"
        }

        val ocrVarName = "錄製數值_$stepIndex"

        val anchorsArray = JSONArray().apply {
            if (isCondition) {
                // Condition mode uses OCR anchor so dynamic numbers/counters don't break static template matching
                put(JSONObject().apply {
                    put("id", anchorId)
                    put("label", "條件區域 #$stepIndex")
                    put("x", regionX)
                    put("y", regionY)
                    put("w", regionW)
                    put("h", regionH)
                    put("matchType", "ocr")
                    put("storeVariable", ocrVarName)
                    put("threshold", 0.8)
                })
            } else {
                put(JSONObject().apply {
                    put("id", anchorId)
                    put("label", "錄製特徵 #$stepIndex")
                    put("x", regionX)
                    put("y", regionY)
                    put("w", regionW)
                    put("h", regionH)
                    put("matchType", "image")
                    put("threshold", 0.8)
                    if (templateDataUri != null) {
                        put("template", templateDataUri)
                    }
                })
            }
        }

        val swipeDir = if (isSwipe && endX != null && endY != null) {
            val dx = endX - touchX
            val dy = endY - touchY
            if (abs(dx) >= abs(dy)) {
                if (dx >= 0) "RIGHT" else "LEFT"
            } else {
                if (dy >= 0) "DOWN" else "UP"
            }
        } else {
            "UP"
        }

        val regionObj = JSONObject().apply {
            put("id", regionId)
            put("name", "錄製動作 #$stepIndex")
            put("label", when {
                isCondition -> "讀取/檢查條件 #$stepIndex"
                isSwipe -> "滑動畫面 #$stepIndex ($swipeDir)"
                isLoop -> "循環點擊 #$stepIndex"
                else -> "點擊按鈕 #$stepIndex"
            })
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
            put("wait_after", DEFAULT_WAIT_AFTER_MS)
            put("delayAfter", DEFAULT_WAIT_AFTER_MS)
            if (templateDataUri != null) {
                put("template", templateDataUri)
            }

            if (isCondition) {
                put("perception", JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "perc_${UUID.randomUUID().toString().take(6)}")
                        put("matchType", "ocr")
                        put("storeVariable", ocrVarName)
                        put("threshold", 0.8)
                    })
                })
                put("action", JSONObject().apply {
                    put("type", "EXTRACT_VAR")
                    put("params", JSONObject().apply {
                        put("variableName", ocrVarName)
                        put("duration", 500)
                    })
                })
            } else {
                // Attach image perception so SceneGraphEngine never blind-clicks during the 3s transition grace period
                // and always acquires a live OpenCV visual lock (_matchedPoint) before dispatching gestures.
                put("perception", JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "perc_${UUID.randomUUID().toString().take(6)}")
                        put("matchType", "image")
                        put("threshold", 0.8)
                        if (templateDataUri != null) {
                            put("template", templateDataUri)
                        }
                    })
                })
                if (isSwipe && endX != null && endY != null) {
                    val safeEndX = endX.coerceIn(0, screenWidth - 1) * 100.0 / screenWidth
                    val safeEndY = endY.coerceIn(0, screenHeight - 1) * 100.0 / screenHeight
                    val swipeDuration = if (gestureDurationMs > 0L) gestureDurationMs.coerceIn(220L, 1200L) else 450L
                    put("action", JSONObject().apply {
                        put("type", "SWIPE")
                        put("params", JSONObject().apply {
                            put("direction", swipeDir)
                            put("endX", safeEndX)
                            put("endY", safeEndY)
                            put("duration", swipeDuration)
                        })
                    })
                } else {
                    put("action", JSONObject().apply {
                        put("type", "CLICK")
                        put("params", JSONObject().apply {
                            put("repeat", 1)
                            put("repeatDelay", 100)
                            put("duration", 500)
                        })
                    })
                }
            }

            // Legacy actions array for backward compatibility
            put("actions", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", actionId)
                    put("type", if (isSwipe) "swipe" else "click")
                    put("regionId", regionId)
                    put("x", percentX)
                    put("y", percentY)
                    put("delay", DEFAULT_WAIT_AFTER_MS)
                })
            })
        }

        // Link previous node's last region (and backfill its actual wait_after) or wire root CHECK_EXIT transition
        var linkedPrevRegionId: String? = null
        var linkedPrevRegionLabel = "下一步"
        if (nodes.length() > 0) {
            val prevNode = nodes.optJSONObject(nodes.length() - 1)
            val prevData = prevNode?.optJSONObject("data")
            val prevRegions = prevData?.optJSONArray("regions")
            if (prevRegions != null && prevRegions.length() > 0) {
                val lastRegion = prevRegions.optJSONObject(prevRegions.length() - 1)
                lastRegion?.put("target", sceneNodeId)
                linkedPrevRegionId = lastRegion?.optString("id")
                linkedPrevRegionLabel = lastRegion?.optString("label")?.ifEmpty { "下一步" } ?: "下一步"
                if (prevNode.optString("id") != "root") {
                    lastRegion?.put("wait_after", elapsedSincePrev)
                    lastRegion?.put("delayAfter", elapsedSincePrev)
                }
            } else if (prevNode?.optString("id") == "root" && prevRegions != null) {
                val rootTransition = JSONObject().apply {
                    put("id", "region_root_start")
                    put("label", "開始錄製流程")
                    put("enabled", true)
                    put("target", sceneNodeId)
                    put("wait_after", 300)
                    put("action", JSONObject().apply {
                        put("type", "CHECK_EXIT")
                        put("params", JSONObject().put("duration", 0))
                    })
                }
                prevRegions.put(rootTransition)
                linkedPrevRegionId = "region_root_start"
                linkedPrevRegionLabel = "開始錄製流程"
            }
        }

        val sceneNode = JSONObject().apply {
            put("id", sceneNodeId)
            put("type", "scene")
            put("data", JSONObject().apply {
                put("label", "$modePrefix #$stepIndex")
                if (fullSceneBase64 != null) {
                    put("image", "data:image/jpeg;base64,$fullSceneBase64")
                }
                // Snapshot per node: later rotation must not rewrite earlier steps.
                put("resolution", JSONObject(targetResolution.toString()))
                put("isLoopStep", isLoop)
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

        // 4. Create Web-Editor-ready styled Edge linking previous node to this node
        val edgeId = if (!linkedPrevRegionId.isNullOrEmpty()) {
            "edge_${lastNodeId}_${sceneNodeId}_${linkedPrevRegionId}"
        } else {
            "edge_${UUID.randomUUID().toString().take(8)}"
        }
        edges.put(buildStyledEdge(edgeId, lastNodeId, sceneNodeId, linkedPrevRegionLabel, linkedPrevRegionId, "#38bdf8", false))

        if (isLoop && firstLoopNodeId == null) {
            firstLoopNodeId = sceneNodeId
        }
        recordedStepCount++
        lastNodeId = sceneNodeId

        // Auto-return to normal click mode after marking a single condition step
        if (isCondition) {
            currentRecordMode = "click"
        }

        Log.i(TAG, "已新增一個錄製步驟 ($activeMode${if (isSwipe) "/swipe" else ""}): Node=$sceneNodeId, touch=($percentX%, $percentY%)")
    }

    private fun buildStyledEdge(
        edgeId: String,
        sourceId: String,
        targetId: String,
        label: String,
        regionId: String?,
        color: String,
        isLoopBack: Boolean
    ): JSONObject {
        return JSONObject().apply {
            put("id", edgeId)
            put("source", sourceId)
            put("target", targetId)
            put("sourceHandle", if (isLoopBack) "bottom" else "right")
            put("targetHandle", if (isLoopBack) "bottom-target" else "left")
            put("label", label)
            put("type", "smoothstep")
            put("animated", true)
            put("style", JSONObject().apply {
                put("strokeWidth", 3)
                put("stroke", color)
            })
            put("labelStyle", JSONObject().apply {
                put("fill", "#e4e4e7")
                put("fontWeight", 600)
                put("fontSize", 11)
            })
            put("labelShowBg", true)
            put("labelBgStyle", JSONObject().apply {
                put("fill", "#18181b")
                put("fillOpacity", 0.95)
                put("rx", 4)
                put("ry", 4)
            })
            put("markerEnd", JSONObject().apply {
                put("type", "arrowclosed")
                put("width", 20)
                put("height", 20)
                put("color", color)
            })
            put("data", JSONObject().apply {
                put("enabled", true)
                if (!regionId.isNullOrEmpty()) {
                    put("regionId", regionId)
                }
            })
        }
    }

    /**
     * Wire the final recorded step back to the first loop-marked step (or Step #1)
     * so recorded scripts form a closed, runnable loop out of the box.
     */
    private fun closeRecordingLoop() {
        if (nodes.length() <= 1) return
        val loopTargetId = firstLoopNodeId ?: nodes.optJSONObject(1)?.optString("id")
        if (loopTargetId.isNullOrEmpty()) return

        val lastNode = nodes.optJSONObject(nodes.length() - 1) ?: return
        val lastId = lastNode.optString("id")
        val lastRegions = lastNode.optJSONObject("data")?.optJSONArray("regions") ?: return
        if (lastRegions.length() == 0) return

        val lastRegion = lastRegions.optJSONObject(lastRegions.length() - 1) ?: return
        lastRegion.put("target", loopTargetId)

        if (lastId.isNotEmpty() && lastId != loopTargetId) {
            val lastRegionId = lastRegion.optString("id").takeIf { it.isNotEmpty() }
            val lastLabel = lastRegion.optString("label").ifEmpty { "循環返回" }
            val edgeId = if (lastRegionId != null) {
                "edge_${lastId}_${loopTargetId}_${lastRegionId}"
            } else {
                "edge_loop_${UUID.randomUUID().toString().take(8)}"
            }
            edges.put(buildStyledEdge(edgeId, lastId, loopTargetId, lastLabel, lastRegionId, "#34d399", true))
        }
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

    private fun compressFullScreenshotToBase64(bitmap: Bitmap, quality: Int = 65): String {
        return try {
            // Keep native dimensions: editor re-crops must use the same pixel grid as templates.
            bitmapToBase64(bitmap, quality)
        } catch (e: Exception) {
            Log.w(TAG, "壓縮完整截圖失敗，退回較低畫質壓縮", e)
            bitmapToBase64(bitmap, 45)
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int = 80): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)
    }
}

