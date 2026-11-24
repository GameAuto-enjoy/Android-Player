package com.gameautoeditor.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

object ImageMatcher {
    private const val TAG = "GameAuto"
    private const val MAX_MULTI_MATCHES = 50
    private const val HTTP_CONNECT_TIMEOUT_MS = 10_000
    private const val HTTP_READ_TIMEOUT_MS = 15_000

    @Volatile
    private var isInitialized = false

    init {
        initOpenCV()
    }

    @Synchronized
    private fun initOpenCV() {
        if (!isInitialized) {
            try {
                if (OpenCVLoader.initDebug()) {
                    Log.i(TAG, "✅ OpenCV 初始化成功")
                    isInitialized = true
                } else {
                    Log.e(TAG, "❌ OpenCV 初始化失敗")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "❌ OpenCV 原生庫加載異常: ${t.message}", t)
            }
        }
    }

    data class MatchResult(
        val x: Int,
        val y: Int,
        val score: Double,
        val width: Int,
        val height: Int
    )

    // Internal Helper for single match
    private fun matchAtScale(screenBgr: Mat, templateRgb: Mat, mask: Mat?): Pair<MatchResult?, Double> {
        if (mask != null && Core.countNonZero(mask) == 0) {
            return Pair(null, -1.0)
        }
        val resultMat = Mat()
        return try {
            if (mask != null) {
                Imgproc.matchTemplate(screenBgr, templateRgb, resultMat, Imgproc.TM_CCORR_NORMED, mask)
            } else {
                Imgproc.matchTemplate(screenBgr, templateRgb, resultMat, Imgproc.TM_CCOEFF_NORMED)
            }

            val minMaxLoc = Core.minMaxLoc(resultMat)
            val matchLoc = minMaxLoc.maxLoc
            val maxVal = minMaxLoc.maxVal
            if (!maxVal.isFinite()) {
                Pair(null, -1.0)
            } else {
                val w = templateRgb.cols()
                val h = templateRgb.rows()
                val centerX = (matchLoc.x + w / 2).toInt()
                val centerY = (matchLoc.y + h / 2).toInt()
                Pair(MatchResult(centerX, centerY, maxVal, w, h), maxVal)
            }
        } finally {
            resultMat.release()
        }
    }

    /**
     * 在螢幕截圖中尋找目標圖片（支援透明背景 Mask 與多尺度匹配）
     * @param screenBitmap 螢幕截圖
     * @param templateBitmap 目標圖片
     * @param threshold 相似度閾值 (0.0 - 1.0)
     */
    fun findTemplate(
        screenBitmap: Bitmap,
        templateBitmap: Bitmap,
        threshold: Double = 0.8,
        expectedScale: Double? = null
    ): MatchResult? {
        if (screenBitmap.isRecycled || templateBitmap.isRecycled ||
            screenBitmap.width <= 0 || screenBitmap.height <= 0 ||
            templateBitmap.width <= 0 || templateBitmap.height <= 0
        ) {
            return null
        }
        if (!isInitialized) initOpenCV()
        if (!isInitialized) return null

        var screenMat: Mat? = null
        var screenBgr: Mat? = null
        var templateMat: Mat? = null
        var templateRgb: Mat? = null
        val channels = ArrayList<Mat>()

        return try {
            screenMat = Mat()
            Utils.bitmapToMat(screenBitmap, screenMat)
            screenBgr = Mat()
            Imgproc.cvtColor(screenMat, screenBgr, Imgproc.COLOR_RGBA2RGB)

            templateMat = Mat()
            Utils.bitmapToMat(templateBitmap, templateMat)

            // Mask handling (Alpha channel)
            Core.split(templateMat, channels)
            val mask: Mat? = if (channels.size == 4) channels[3] else null

            templateRgb = Mat()
            Imgproc.cvtColor(templateMat, templateRgb, Imgproc.COLOR_RGBA2RGB)

            val effectiveThreshold = if (threshold.isNaN()) 0.8 else threshold.coerceIn(0.01, 1.0)
            var bestResult: MatchResult? = null
            var bestScore = -1.0

            // Determine scales to check
            val scalesToCheck = mutableListOf<Double>()

            if (expectedScale != null && expectedScale.isFinite() && expectedScale > 0) {
                scalesToCheck.add(expectedScale)
                scalesToCheck.add(expectedScale * 0.9)
                scalesToCheck.add(expectedScale * 1.1)
                val standardScales = listOf(0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5)
                for (s in standardScales) {
                    if (kotlin.math.abs(s - expectedScale) > 0.15) {
                        scalesToCheck.add(s)
                    }
                }
            } else {
                scalesToCheck.add(1.0)
                scalesToCheck.addAll(listOf(0.5, 0.6, 0.7, 0.8, 0.9, 1.1, 1.2, 1.3, 1.4, 1.5))
            }

            for (scale in scalesToCheck) {
                val newW = (templateRgb.cols() * scale).toInt()
                val newH = (templateRgb.rows() * scale).toInt()

                if (newW < 10 || newH < 10 || newW > screenBgr.cols() || newH > screenBgr.rows()) continue

                val scaledTemplate = Mat()
                val scaledMask = if (mask != null) Mat() else null
                try {
                    if (scale == 1.0) {
                        templateRgb.copyTo(scaledTemplate)
                        if (mask != null && scaledMask != null) mask.copyTo(scaledMask)
                    } else {
                        val targetSize = org.opencv.core.Size(newW.toDouble(), newH.toDouble())
                        Imgproc.resize(templateRgb, scaledTemplate, targetSize)
                        if (mask != null && scaledMask != null) {
                            Imgproc.resize(mask, scaledMask, targetSize)
                        }
                    }

                    val res = matchAtScale(screenBgr, scaledTemplate, scaledMask)

                    // Early Exit on Good Match
                    if (res.first != null && res.second.isFinite() && res.second >= effectiveThreshold) {
                        Log.d(TAG, "✅ 圖片匹配成功 (縮放: ${scale}x, 分數: ${res.second})")
                        return res.first
                    }

                    if (res.first != null && res.second.isFinite() && res.second > bestScore) {
                        bestScore = res.second
                        bestResult = res.first
                    }
                } finally {
                    scaledMask?.release()
                    scaledTemplate.release()
                }
            }

            if (bestResult != null && bestScore.isFinite() && bestScore >= effectiveThreshold) {
                Log.i(TAG, "✅ 多重縮放匹配成功! 分數: $bestScore")
                bestResult
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV Error: ${e.message}", e)
            null
        } finally {
            channels.forEach { it.release() }
            templateRgb?.release()
            templateMat?.release()
            screenBgr?.release()
            screenMat?.release()
        }
    }

    private fun collectMatchesAtScale(
        screenBgr: Mat,
        scaledTemplate: Mat,
        scaledMask: Mat?,
        threshold: Double,
        scale: Double,
        results: MutableList<MatchResult>
    ) {
        if (scaledMask != null && Core.countNonZero(scaledMask) == 0) return
        val newW = scaledTemplate.cols()
        val newH = scaledTemplate.rows()
        val resultMat = Mat()
        try {
            if (scaledMask != null) {
                Imgproc.matchTemplate(screenBgr, scaledTemplate, resultMat, Imgproc.TM_CCORR_NORMED, scaledMask)
            } else {
                Imgproc.matchTemplate(screenBgr, scaledTemplate, resultMat, Imgproc.TM_CCOEFF_NORMED)
            }

            val effectiveThreshold = if (threshold.isNaN()) 0.8 else threshold.coerceIn(0.01, 1.0)
            val suppressHalfW = max(1, newW / 2)
            val suppressHalfH = max(1, newH / 2)
            while (results.size < MAX_MULTI_MATCHES) {
                val minMaxLoc = Core.minMaxLoc(resultMat)
                val matchLoc = minMaxLoc.maxLoc
                val maxVal = minMaxLoc.maxVal
                if (!maxVal.isFinite() || !(maxVal >= effectiveThreshold)) break

                val centerX = (matchLoc.x + newW / 2).toInt()
                val centerY = (matchLoc.y + newH / 2).toInt()
                results.add(MatchResult(centerX, centerY, maxVal, newW, newH))
                Log.d(TAG, "✅ [多目標] 找到目標 (x:$centerX, y:$centerY, scale:${scale}x, 分數: $maxVal)")

                val x1 = max(0.0, matchLoc.x - suppressHalfW)
                val y1 = max(0.0, matchLoc.y - suppressHalfH)
                val x2 = min((resultMat.cols() - 1).toDouble(), matchLoc.x + suppressHalfW)
                val y2 = min((resultMat.rows() - 1).toDouble(), matchLoc.y + suppressHalfH)
                Imgproc.rectangle(resultMat, Point(x1, y1), Point(x2, y2), Scalar(-2.0), -1)
            }
        } finally {
            resultMat.release()
        }
    }

    /**
     * 在螢幕截圖中尋找所有符合目標圖片的位置
     * @param screenBitmap 螢幕截圖
     * @param templateBitmap 目標圖片
     * @param threshold 相似度閾值 (0.0 - 1.0)
     */
    fun findAllTemplates(
        screenBitmap: Bitmap,
        templateBitmap: Bitmap,
        threshold: Double = 0.8,
        expectedScale: Double? = null
    ): List<MatchResult> {
        val results = mutableListOf<MatchResult>()
        if (screenBitmap.isRecycled || templateBitmap.isRecycled ||
            screenBitmap.width <= 0 || screenBitmap.height <= 0 ||
            templateBitmap.width <= 0 || templateBitmap.height <= 0
        ) return results
        if (!isInitialized) initOpenCV()
        if (!isInitialized) return results

        var screenMat: Mat? = null
        var screenBgr: Mat? = null
        var templateMat: Mat? = null
        var templateRgb: Mat? = null
        val channels = ArrayList<Mat>()

        try {
            val sMat = Mat(); screenMat = sMat; Utils.bitmapToMat(screenBitmap, sMat)
            val sBgr = Mat(); screenBgr = sBgr; Imgproc.cvtColor(sMat, sBgr, Imgproc.COLOR_RGBA2RGB)
            val tMat = Mat(); templateMat = tMat; Utils.bitmapToMat(templateBitmap, tMat)
            Core.split(tMat, channels)
            val mask: Mat? = if (channels.size == 4) channels[3] else null
            val tRgb = Mat(); templateRgb = tRgb; Imgproc.cvtColor(tMat, tRgb, Imgproc.COLOR_RGBA2RGB)

            val scalesToCheck = if (expectedScale != null && expectedScale.isFinite() && expectedScale > 0) {
                listOf(expectedScale)
            } else {
                listOf(1.0, 0.8, 0.9, 1.1, 1.2)
            }

            for (scale in scalesToCheck) {
                val newW = (tRgb.cols() * scale).toInt()
                val newH = (tRgb.rows() * scale).toInt()
                if (newW < 10 || newH < 10 || newW > sBgr.cols() || newH > sBgr.rows()) continue

                val scaledTemplate = Mat()
                val scaledMask = if (mask != null) Mat() else null
                try {
                    if (scale == 1.0) {
                        tRgb.copyTo(scaledTemplate)
                        if (mask != null && scaledMask != null) mask.copyTo(scaledMask)
                    } else {
                        val targetSize = org.opencv.core.Size(newW.toDouble(), newH.toDouble())
                        Imgproc.resize(tRgb, scaledTemplate, targetSize)
                        if (mask != null && scaledMask != null) Imgproc.resize(mask, scaledMask, targetSize)
                    }
                    collectMatchesAtScale(sBgr, scaledTemplate, scaledMask, threshold, scale, results)
                } finally {
                    scaledMask?.release()
                    scaledTemplate.release()
                }
                if (results.isNotEmpty()) break
            }
            return results
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV Error (findAll): ${e.message}", e)
            return results
        } finally {
            channels.forEach { it.release() }
            templateRgb?.release()
            templateMat?.release()
            screenBgr?.release()
            screenMat?.release()
        }
    }

    /**
     * 從 URL 下載圖片
     */
    fun downloadBitmap(url: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                readTimeout = HTTP_READ_TIMEOUT_MS
            }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } else {
                Log.w(TAG, "❌ 圖片下載失敗 (HTTP $code): $url")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 圖片下載失敗: $url", e)
            null
        } finally {
            conn?.disconnect()
        }
    }
}
