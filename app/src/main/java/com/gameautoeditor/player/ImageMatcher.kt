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
    private const val TAG = "ImageMatcher"
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

    /**
     * 在螢幕截圖中尋找目標圖片（支援透明背景 Mask）
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
            // 1. 轉換螢幕截圖為 Mat (RGB)
            val screenMat = Mat()
            Utils.bitmapToMat(screenBitmap, screenMat)
            // 轉為 BGR 或 RGB (OpenCV 使用 BGR，但這裡只要一致即可)
            // 這裡重要的是移除 Alpha 通道，以免影響
            val screenBgr = Mat()
            Imgproc.cvtColor(screenMat, screenBgr, Imgproc.COLOR_RGBA2RGB)

            // 2. 轉換模板圖片為 Mat
            val templateMat = Mat()
            Utils.bitmapToMat(templateBitmap, templateMat)
            
            // 3. 分離模板的 Alpha 通道作為 Mask
            val channels = ArrayList<Mat>()
            Core.split(templateMat, channels)
            
            val templateRgb = Mat()
            Imgproc.cvtColor(templateMat, templateRgb, Imgproc.COLOR_RGBA2RGB)
            
            // 如果有 Alpha 通道 (第4個通道)
            var mask: Mat? = null
            if (channels.size == 4) {
                mask = channels[3] // Alpha channel
                Log.d(TAG, "Using mask for transparent template")
            }

            // 4. 執行模板匹配
            val resultMat = Mat()
            if (mask != null) {
                // 使用支援 Mask 的方法: TM_CCORR_NORMED 或 TM_SQDIFF
                // TM_CCORR_NORMED: 1.0 是完美匹配
                Imgproc.matchTemplate(screenBgr, templateRgb, resultMat, Imgproc.TM_CCORR_NORMED, mask)
            } else {
                // 無透明背景: TM_CCOEFF_NORMED 通常最準確
                Imgproc.matchTemplate(screenBgr, templateRgb, resultMat, Imgproc.TM_CCOEFF_NORMED)
            }

            // 5. 尋找最佳匹配位置
            val minMaxLoc = Core.minMaxLoc(resultMat)
            
            // 根據不同方法，最佳值可能在 min 或 max
            // TM_CCORR_NORMED 和 TM_CCOEFF_NORMED: maxLoc 是最佳
            val matchLoc = minMaxLoc.maxLoc
            val maxVal = minMaxLoc.maxVal

            Log.d(TAG, "Best match score: $maxVal at $matchLoc")

            // 釋放記憶體
            screenMat.release()
            screenBgr.release()
            templateMat.release()
            templateRgb.release()
            resultMat.release()
            channels.forEach { it.release() }

            // 6. 判斷是否超過閾值
            if (maxVal >= threshold) {
                // 計算中心點
                val w = templateBitmap.width
                val h = templateBitmap.height
                val centerX = (matchLoc.x + w / 2).toInt()
                val centerY = (matchLoc.y + h / 2).toInt()
                
                return MatchResult(centerX, centerY, maxVal, w, h)
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
