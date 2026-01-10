package com.gameautoeditor.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.InputStream
import java.net.URL
import kotlin.math.max

object ImageMatcher {
    private const val TAG = "GameAuto"
    private var isInitialized = false

    init {
        initOpenCV()
    }

    private fun initOpenCV() {
        if (!isInitialized) {
            if (OpenCVLoader.initDebug()) {
                Log.i(TAG, "✅ OpenCV initialized successfully")
                isInitialized = true
            } else {
                Log.e(TAG, "❌ OpenCV initialization failed")
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
        val resultMat = Mat()
        if (mask != null) {
            Imgproc.matchTemplate(screenBgr, templateRgb, resultMat, Imgproc.TM_CCORR_NORMED, mask)
        } else {
            Imgproc.matchTemplate(screenBgr, templateRgb, resultMat, Imgproc.TM_CCOEFF_NORMED)
        }

        val minMaxLoc = Core.minMaxLoc(resultMat)
        val matchLoc = minMaxLoc.maxLoc
        val maxVal = minMaxLoc.maxVal
        
        resultMat.release()
        
        val w = templateRgb.cols()
        val h = templateRgb.rows()
        val centerX = (matchLoc.x + w / 2).toInt()
        val centerY = (matchLoc.y + h / 2).toInt()
        
        return Pair(MatchResult(centerX, centerY, maxVal, w, h), maxVal)
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
        threshold: Double = 0.8
    ): MatchResult? {
        if (!isInitialized) initOpenCV()

        try {
            val screenMat = Mat()
            Utils.bitmapToMat(screenBitmap, screenMat)
            val screenBgr = Mat()
            Imgproc.cvtColor(screenMat, screenBgr, Imgproc.COLOR_RGBA2RGB)

            val templateMat = Mat()
            Utils.bitmapToMat(templateBitmap, templateMat)
            
            // Mask handling (Alpha channel)
            val channels = ArrayList<Mat>()
            Core.split(templateMat, channels)
            var mask: Mat? = null
            if (channels.size == 4) mask = channels[3]
            
            val templateRgb = Mat()
            Imgproc.cvtColor(templateMat, templateRgb, Imgproc.COLOR_RGBA2RGB)

            // 1. Try Original Scale (1.0x)
            var bestResult: MatchResult? = null
            var bestScore = -1.0
            
            val pass1 = matchAtScale(screenBgr, templateRgb, mask)
            if (pass1.second >= threshold) {
                // Perfect match found early
                screenMat.release(); screenBgr.release(); templateMat.release(); templateRgb.release(); channels.forEach{it.release()}
                return pass1.first
            }
            
            bestResult = pass1.first
            bestScore = pass1.second
            
            // 2. Multi-Scale Search (Fallback)
            // Scales to check: 0.5 to 1.5
            val scales = listOf(0.5, 0.6, 0.7, 0.8, 0.9, 1.1, 1.2, 1.3, 1.4, 1.5)
            
            for (scale in scales) {
                val newW = (templateRgb.cols() * scale).toInt()
                val newH = (templateRgb.rows() * scale).toInt()
                
                if (newW < 10 || newH < 10 || newW > screenBgr.cols() || newH > screenBgr.rows()) continue
                
                val scaledTemplate = Mat()
                val scaledMask = if (mask != null) Mat() else null
                
                Imgproc.resize(templateRgb, scaledTemplate, org.opencv.core.Size(newW.toDouble(), newH.toDouble()))
                if (mask != null) {
                    Imgproc.resize(mask, scaledMask, org.opencv.core.Size(newW.toDouble(), newH.toDouble()))
                }
                
                val res = matchAtScale(screenBgr, scaledTemplate, scaledMask)
                if (res.second > bestScore) {
                    bestScore = res.second
                    bestResult = res.first
                    Log.v(TAG, "Best Score Updated (Scale ${scale}x): $bestScore")
                }
                
                scaledTemplate.release()
                scaledMask?.release()
                
                // Optimistic Exit
                if (bestScore >= threshold) break
            }
            
            // Cleanup
            screenMat.release()
            screenBgr.release()
            templateMat.release()
            templateRgb.release()
            channels.forEach { it.release() }
            
            if (bestScore >= threshold) {
                Log.i(TAG, "✅ Multi-Scale Match Found! Score: $bestScore")
                return bestResult
            }

            return null

        } catch (e: Exception) {
            Log.e(TAG, "OpenCV Error: ${e.message}", e)
            return null
        }
    }

    /**
     * 從 URL 下載圖片
     */
    fun downloadBitmap(url: String): Bitmap? {
        return try {
            val inputStream: InputStream = URL(url).openStream()
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Image download failed: $url", e)
            null
        }
    }
}
