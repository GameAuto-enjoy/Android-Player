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
                Log.i(TAG, "✅ OpenCV 初始化成功")
                isInitialized = true
            } else {
                Log.e(TAG, "❌ OpenCV 初始化失敗")
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
        threshold: Double = 0.8,
        expectedScale: Double? = null
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

            var bestResult: MatchResult? = null
            var bestScore = -1.0
            
            // Determine scales to check
            val scalesToCheck = mutableListOf<Double>()
            
            if (expectedScale != null && expectedScale > 0) {
                // Priority: Expected Scale +/- 10%
                scalesToCheck.add(expectedScale)
                scalesToCheck.add(expectedScale * 0.9)
                scalesToCheck.add(expectedScale * 1.1)
                // Add backup scales if strict check fails? 
                // Let's add standard scales but filtered to avoid duplicates
                val standardScales = listOf(0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5)
                for (s in standardScales) {
                    if (kotlin.math.abs(s - expectedScale) > 0.15) { // Only add if significantly different
                        scalesToCheck.add(s)
                    }
                }
            } else {
                // Standard Strategy: 1.0 then broad search
                scalesToCheck.add(1.0)
                scalesToCheck.addAll(listOf(0.5, 0.6, 0.7, 0.8, 0.9, 1.1, 1.2, 1.3, 1.4, 1.5))
            }
            
            for (scale in scalesToCheck) {
                val newW = (templateRgb.cols() * scale).toInt()
                val newH = (templateRgb.rows() * scale).toInt()
                
                if (newW < 10 || newH < 10 || newW > screenBgr.cols() || newH > screenBgr.rows()) continue
                
                val scaledTemplate = Mat()
                val scaledMask = if (mask != null) Mat() else null
                
                if (scale == 1.0) {
                     templateRgb.copyTo(scaledTemplate)
                     mask?.copyTo(scaledMask)
                } else {
                    Imgproc.resize(templateRgb, scaledTemplate, org.opencv.core.Size(newW.toDouble(), newH.toDouble()))
                    if (mask != null) {
                        Imgproc.resize(mask, scaledMask, org.opencv.core.Size(newW.toDouble(), newH.toDouble()))
                    }
                }
                
                val res = matchAtScale(screenBgr, scaledTemplate, scaledMask)
                
                // Early Exit on Good Match
                if (res.second >= threshold) {
                    // Cleanup Loop
                    scaledTemplate.release()
                    scaledMask?.release()
                    screenMat.release(); screenBgr.release(); templateMat.release(); templateRgb.release(); channels.forEach{it.release()}
                    
                    Log.d(TAG, "✅ 圖片匹配成功 (縮放: ${scale}x, 分數: ${res.second})")
                    return res.first
                }

                if (res.second > bestScore) {
                    bestScore = res.second
                    bestResult = res.first
                }
                
                scaledTemplate.release()
                scaledMask?.release()
            }
            
            // Cleanup
            screenMat.release()
            screenBgr.release()
            templateMat.release()
            templateRgb.release()
            channels.forEach { it.release() }
            
            if (bestScore >= threshold) {
                Log.i(TAG, "✅ 多重縮放匹配成功! 分數: $bestScore")
                return bestResult
            }

            return null

        } catch (e: Exception) {
            Log.e(TAG, "OpenCV Error: ${e.message}", e)
            return null
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
        if (!isInitialized) initOpenCV()
        val results = mutableListOf<MatchResult>()

        try {
            val screenMat = Mat()
            Utils.bitmapToMat(screenBitmap, screenMat)
            val screenBgr = Mat()
            Imgproc.cvtColor(screenMat, screenBgr, Imgproc.COLOR_RGBA2RGB)

            val templateMat = Mat()
            Utils.bitmapToMat(templateBitmap, templateMat)
            
            val channels = ArrayList<Mat>()
            Core.split(templateMat, channels)
            var mask: Mat? = null
            if (channels.size == 4) mask = channels[3]
            
            val templateRgb = Mat()
            Imgproc.cvtColor(templateMat, templateRgb, Imgproc.COLOR_RGBA2RGB)

            val scalesToCheck = mutableListOf<Double>()
            if (expectedScale != null && expectedScale > 0) {
                scalesToCheck.add(expectedScale)
                // For findAll, checking multiple scales extensively is very expensive.
                // We trust expectedScale if provided, otherwise standard 1.0.
            } else {
                scalesToCheck.add(1.0)
                scalesToCheck.addAll(listOf(0.8, 0.9, 1.1, 1.2)) // Reduced scales for performance
            }
            
            for (scale in scalesToCheck) {
                val newW = (templateRgb.cols() * scale).toInt()
                val newH = (templateRgb.rows() * scale).toInt()
                
                if (newW < 10 || newH < 10 || newW > screenBgr.cols() || newH > screenBgr.rows()) continue
                
                val scaledTemplate = Mat()
                val scaledMask = if (mask != null) Mat() else null
                
                if (scale == 1.0) {
                     templateRgb.copyTo(scaledTemplate)
                     mask?.copyTo(scaledMask)
                } else {
                    Imgproc.resize(templateRgb, scaledTemplate, org.opencv.core.Size(newW.toDouble(), newH.toDouble()))
                    if (mask != null) {
                        Imgproc.resize(mask, scaledMask, org.opencv.core.Size(newW.toDouble(), newH.toDouble()))
                    }
                }

                // Create a clone of screen for this scale to mask found results
                val workingScreen = screenBgr.clone()
                val resultMat = Mat()

                while (true) {
                    if (scaledMask != null) {
                        Imgproc.matchTemplate(workingScreen, scaledTemplate, resultMat, Imgproc.TM_CCORR_NORMED, scaledMask)
                    } else {
                        Imgproc.matchTemplate(workingScreen, scaledTemplate, resultMat, Imgproc.TM_CCOEFF_NORMED)
                    }

                    val minMaxLoc = Core.minMaxLoc(resultMat)
                    val matchLoc = minMaxLoc.maxLoc
                    val maxVal = minMaxLoc.maxVal
                    
                    if (maxVal >= threshold) {
                        val centerX = (matchLoc.x + newW / 2).toInt()
                        val centerY = (matchLoc.y + newH / 2).toInt()
                        results.add(MatchResult(centerX, centerY, maxVal, newW, newH))
                        Log.d(TAG, "✅ [多目標] 找到目標 (x:$centerX, y:$centerY, scale:${scale}x, 分數: $maxVal)")

                        // Mask out the found region in workingScreen so it won't be found again
                        Imgproc.rectangle(
                            workingScreen, 
                            matchLoc, 
                            Point(matchLoc.x + newW, matchLoc.y + newH), 
                            Scalar(0.0, 0.0, 0.0), 
                            -1 // filled
                        )
                    } else {
                        break // No more matches above threshold at this scale
                    }
                }
                
                resultMat.release()
                workingScreen.release()
                scaledTemplate.release()
                scaledMask?.release()
                
                if (results.isNotEmpty()) {
                    // Once we find matches at a specific scale, we stop checking other scales
                    // to prevent finding the same elements slightly off-scale.
                    break 
                }
            }
            
            screenMat.release()
            screenBgr.release()
            templateMat.release()
            templateRgb.release()
            channels.forEach { it.release() }
            
            return results

        } catch (e: Exception) {
            Log.e(TAG, "OpenCV Error (findAll): ${e.message}", e)
            return results
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
            Log.e(TAG, "❌ 圖片下載失敗: $url", e)
            null
        }
    }
}
