package com.gameautoeditor.player

/** Pure pixel geometry shared by recording and action dispatch. No Android dependencies. */
object CoordinateGeometry {
    data class Point(val x: Double, val y: Double)
    data class Rect(val x: Double, val y: Double, val w: Double, val h: Double)
    data class RecordedTouch(val crop: Rect, val region: Rect, val touch: Point, val offset: Point)

    fun mapRegion(r: Rect, screenW: Double, screenH: Double,
                  nodeW: Double = 0.0, nodeH: Double = 0.0, visualLock: Boolean = false): Rect {
        require(screenW > 0 && screenH > 0)
        if (visualLock || nodeW <= 0 || nodeH <= 0) {
            return Rect(r.x / 100 * screenW, r.y / 100 * screenH,
                r.w / 100 * screenW, r.h / 100 * screenH)
        }
        // Compatibility: retain legacy width scale and top/center/bottom anchoring.
        val scale = screenW / nodeW
        val srcY = r.y / 100 * nodeH
        val top = when {
            r.y < 33 -> srcY * scale
            r.y > 66 -> screenH - (nodeH - srcY) * scale
            else -> screenH / 2 + (srcY - nodeH / 2) * scale
        }
        return Rect(r.x / 100 * screenW, top, r.w / 100 * screenW, r.h / 100 * nodeH * scale)
    }

    fun percentRect(x: Double, y: Double, w: Double, h: Double, screenW: Double, screenH: Double): Rect {
        require(screenW > 0 && screenH > 0)
        return Rect(x * 100 / screenW, y * 100 / screenH, w * 100 / screenW, h * 100 / screenH)
    }

    fun crosshair(r: Rect, x: Double = 50.0, y: Double = 50.0) =
        Point(r.x + r.w * x.coerceIn(0.0, 100.0) / 100,
              r.y + r.h * y.coerceIn(0.0, 100.0) / 100)

    // Existing one/two-pixel display safety margin is part of the script contract.
    fun clampPoint(x: Double, y: Double, w: Double, h: Double): Point {
        require(w >= 3 && h >= 3)
        return Point(x.coerceIn(1.0, w - 2), y.coerceIn(1.0, h - 2))
    }

    fun recordTouch(w: Int, h: Int, touchX: Int, touchY: Int): RecordedTouch {
        require(w > 0 && h > 0)
        val x = touchX.coerceIn(0, w - 1)
        val y = touchY.coerceIn(0, h - 1)
        val left = (x - 60).coerceAtLeast(0)
        val top = (y - 60).coerceAtLeast(0)
        val cw = minOf(120, w - left)
        val ch = minOf(120, h - top)
        return RecordedTouch(Rect(left.toDouble(), top.toDouble(), cw.toDouble(), ch.toDouble()),
            Rect(left * 100.0 / w, top * 100.0 / h, cw * 100.0 / w, ch * 100.0 / h),
            Point(x * 100.0 / w, y * 100.0 / h),
            Point((x - left) * 100.0 / cw, (y - top) * 100.0 / ch))
    }

    /** Current display wins after rotation/resolution changes; capture is only a fallback. */
    fun screenSize(current: Pair<Int, Int>?, captured: Pair<Int, Int>?, fallback: Pair<Int, Int>): Pair<Int, Int> =
        listOfNotNull(current, captured, fallback).first { it.first > 0 && it.second > 0 }
}
