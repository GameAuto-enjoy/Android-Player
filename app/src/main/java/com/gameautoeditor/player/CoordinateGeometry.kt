package com.gameautoeditor.player

/** Pure pixel geometry shared by recording and action dispatch. No Android dependencies. */
object CoordinateGeometry {
    data class Point(val x: Double, val y: Double)
    data class Rect(val x: Double, val y: Double, val w: Double, val h: Double)
    data class RecordedTouch(val crop: Rect, val region: Rect, val touch: Point, val offset: Point)

    fun mapRegion(r: Rect, screenW: Double, screenH: Double,
                  nodeW: Double = 0.0, nodeH: Double = 0.0, visualLock: Boolean = false): Rect {
        require(screenW.isFinite() && screenH.isFinite() && screenW > 0 && screenH > 0)
        val rx = if (r.x.isFinite()) r.x else 0.0
        val ry = if (r.y.isFinite()) r.y else 0.0
        val rw = if (r.w.isFinite() && r.w >= 0.0) r.w else 0.0
        val rh = if (r.h.isFinite() && r.h >= 0.0) r.h else 0.0
        val safeNodeW = if (nodeW.isFinite()) nodeW else 0.0
        val safeNodeH = if (nodeH.isFinite()) nodeH else 0.0
        if (visualLock || safeNodeW <= 0 || safeNodeH <= 0) {
            return Rect(rx / 100 * screenW, ry / 100 * screenH,
                rw / 100 * screenW, rh / 100 * screenH)
        }
        // Compatibility: retain legacy width scale and top/center/bottom anchoring.
        val scale = screenW / safeNodeW
        val srcY = ry / 100 * safeNodeH
        val top = when {
            ry < 33 -> srcY * scale
            ry > 66 -> screenH - (safeNodeH - srcY) * scale
            else -> screenH / 2 + (srcY - safeNodeH / 2) * scale
        }
        return Rect(rx / 100 * screenW, top, rw / 100 * screenW, rh / 100 * safeNodeH * scale)
    }

    fun percentRect(x: Double, y: Double, w: Double, h: Double, screenW: Double, screenH: Double): Rect {
        require(screenW.isFinite() && screenH.isFinite() && screenW > 0 && screenH > 0)
        val sx = if (x.isFinite()) x else 0.0
        val sy = if (y.isFinite()) y else 0.0
        val sw = if (w.isFinite() && w >= 0.0) w else 0.0
        val sh = if (h.isFinite() && h >= 0.0) h else 0.0
        return Rect(sx * 100 / screenW, sy * 100 / screenH, sw * 100 / screenW, sh * 100 / screenH)
    }

    /** Converts a center-anchored pixel match box (e.g. ImageMatcher.MatchResult) to top-left screen percentages. */
    fun percentRectFromCenter(
        centerX: Double,
        centerY: Double,
        w: Double,
        h: Double,
        screenW: Double,
        screenH: Double
    ): Rect {
        if (!screenW.isFinite() || !screenH.isFinite() || screenW <= 0.0 || screenH <= 0.0 ||
            !centerX.isFinite() || !centerY.isFinite() ||
            !w.isFinite() || !h.isFinite() || w < 0.0 || h < 0.0
        ) {
            return Rect(0.0, 0.0, 0.0, 0.0)
        }
        val left = (centerX - w / 2.0).coerceIn(0.0, screenW)
        val top = (centerY - h / 2.0).coerceIn(0.0, screenH)
        return percentRect(left, top, w, h, screenW, screenH)
    }

    fun crosshair(r: Rect, x: Double = 50.0, y: Double = 50.0): Point {
        val rx = if (r.x.isFinite()) r.x else 0.0
        val ry = if (r.y.isFinite()) r.y else 0.0
        val rw = if (r.w.isFinite() && r.w >= 0.0) r.w else 0.0
        val rh = if (r.h.isFinite() && r.h >= 0.0) r.h else 0.0
        val ox = (if (x.isFinite()) x else 50.0).coerceIn(0.0, 100.0)
        val oy = (if (y.isFinite()) y else 50.0).coerceIn(0.0, 100.0)
        return Point(rx + rw * ox / 100.0, ry + rh * oy / 100.0)
    }

    // Existing one/two-pixel display safety margin is part of the script contract.
    fun clampPoint(x: Double, y: Double, w: Double, h: Double): Point {
        require(w.isFinite() && h.isFinite() && w >= 3 && h >= 3)
        val safeX = if (x.isFinite()) x else (w / 2.0)
        val safeY = if (y.isFinite()) y else (h / 2.0)
        return Point(safeX.coerceIn(1.0, w - 2), safeY.coerceIn(1.0, h - 2))
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
