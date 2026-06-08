package com.lyne.adas.l1.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.fusion.FusionResult
import com.lyne.adas.l1.fusion.Severity
import com.lyne.adas.l1.inference.BBox
import com.lyne.adas.l1.inference.Detection
import com.lyne.adas.l1.inference.ObjectClass
import com.lyne.adas.l1.ui.theme.LynePalette
import kotlin.math.abs

/**
 * Automotive HUD overlay: a 3D-perspective green lane "carpet" with forward-scrolling chevrons,
 * corner-bracket detection boxes with class+distance labels, and a lane-departure steer arrow.
 * Normalized detector coords are mapped through the PreviewView FILL_CENTER crop.
 */
@Composable
fun HudOverlay(
    result: FusionResult?,
    frameAspect: Float,
    palette: LynePalette,
    phase: Float,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val map = fillCenterMapper(size.width, size.height, frameAspect)
        result ?: return@Canvas
        if (result.lane.confidence >= 0.40f) {
            if (result.lane.mask != null) drawMask(result, map, palette, phase)
            else drawCarpet(result, map, palette, phase)
        }
        for (d in result.detections) drawDetection(d, result, map, palette, measurer)
        drawDepartureArrow(result, palette)
    }
}

private fun DrawScope.drawCarpet(result: FusionResult, map: Mapper, p: LynePalette, phase: Float) {
    val lane = result.lane
    val left = lane.leftLaneX ?: return
    val right = lane.rightLaneX ?: return
    val sev = result.ldw
    val near = when (sev) { Severity.CRITICAL -> p.critical; Severity.CAUTION -> p.caution; else -> p.laneNear }
    val far = when (sev) { Severity.CRITICAL -> p.critical; Severity.CAUTION -> p.caution; else -> p.laneFar }

    val path = Path(); var started = false
    var topY = size.height; var botY = 0f
    for (i in lane.rows.indices) {
        val x = left.getOrNull(i) ?: continue; if (x.isNaN()) continue
        val px = map.x(x); val py = map.y(lane.rows[i])
        if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
        if (py < topY) topY = py; if (py > botY) botY = py
    }
    for (i in lane.rows.indices.reversed()) {
        val x = right.getOrNull(i) ?: continue; if (x.isNaN()) continue
        path.lineTo(map.x(x), map.y(lane.rows[i]))
    }
    if (!started) return
    path.close()
    // Perspective gradient: brighter near (bottom), fading toward the vanishing point (top).
    val brush = Brush.verticalGradient(
        0f to far.copy(alpha = 0.05f),
        1f to near.copy(alpha = 0.34f),
        startY = topY, endY = botY,
    )
    drawPath(path, brush)

    // Edge lines.
    val edge = near.copy(alpha = 0.95f)
    drawPolyline(lane.rows, left, edge, map, 4f * density)
    drawPolyline(lane.rows, right, edge, map, 4f * density)

    // Forward-scrolling chevrons along the lane centre.
    drawChevrons(lane.rows, left, right, map, near, phase)
}

/** Render the actual drivable-area segmentation mask as a smoothed translucent green overlay. */
private fun DrawScope.drawMask(result: FusionResult, map: Mapper, p: LynePalette, phase: Float) {
    val lane = result.lane
    val mask = lane.mask ?: return
    val mw = lane.maskW; val mh = lane.maskH
    if (mw <= 0 || mh <= 0) return
    val base = when (result.ldw) { Severity.CRITICAL -> p.critical; Severity.CAUTION -> p.caution; else -> p.laneNear }

    for (my in 0 until mh) {
        val nyT = my.toFloat() / mh
        val nyB = (my + 1).toFloat() / mh
        val yT = map.y(nyT); val yB = map.y(nyB) + 1f // +1 to avoid row seams
        val near = my / (mh - 1f) // 0 far(top) -> 1 near(bottom)
        val alpha = 0.07f + 0.30f * near
        val color = base.copy(alpha = alpha)
        val row = my * mw
        var x = 0
        while (x < mw) {
            if (mask[row + x].toInt() == 1) {
                var x2 = x
                while (x2 + 1 < mw && mask[row + x2 + 1].toInt() == 1) x2++
                val xl = map.x(x.toFloat() / mw); val xr = map.x((x2 + 1).toFloat() / mw)
                drawRect(color, Offset(xl, yT), Size((xr - xl).coerceAtLeast(0f), (yB - yT).coerceAtLeast(0f)))
                x = x2 + 1
            } else x++
        }
    }

    // Forward-scrolling chevrons along the region centre (reuses per-row extent).
    val left = lane.leftLaneX; val right = lane.rightLaneX
    if (left != null && right != null) drawChevrons(lane.rows, left, right, map, base, phase)
}

private fun DrawScope.drawChevrons(
    rows: FloatArray, left: FloatArray, right: FloatArray, map: Mapper, color: Color, phase: Float,
) {
    val n = 5
    for (k in 0 until n) {
        val t = ((k.toFloat() / n) + phase) % 1f   // 0=near(bottom) -> 1=far(top)
        val rowF = (rows.size - 1) * (1f - t)
        val i = rowF.toInt().coerceIn(0, rows.size - 1)
        val lx = left.getOrNull(i); val rx = right.getOrNull(i)
        if (lx == null || rx == null || lx.isNaN() || rx.isNaN()) continue
        val cx = map.x((lx + rx) / 2f); val cy = map.y(rows[i])
        val half = (map.x(rx) - map.x(lx)) * 0.16f
        val hgt = half * 0.9f
        val a = (1f - t) * 0.9f
        val c = color.copy(alpha = a)
        val sw = (2.5f + (1f - t) * 3f) * density
        // forward-pointing (apex up)
        drawLine(c, Offset(cx - half, cy + hgt), Offset(cx, cy - hgt), sw, cap = StrokeCap.Round)
        drawLine(c, Offset(cx + half, cy + hgt), Offset(cx, cy - hgt), sw, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawDetection(d: Detection, result: FusionResult, map: Mapper, p: LynePalette, measurer: TextMeasurer) {
    val isLead = result.leadBox != null && d.box == result.leadBox
    val color = when {
        isLead && result.fcw == Severity.CRITICAL -> p.critical
        isLead && result.fcw == Severity.CAUTION -> p.caution
        d.cls.isVru && result.vru == Severity.CRITICAL -> p.critical
        d.cls.isVru -> p.caution
        d.cls == ObjectClass.STOP_SIGN -> p.critical
        d.cls.isVehicle -> p.text
        else -> p.textDim
    }
    val l = map.x(d.box.left); val t = map.y(d.box.top)
    val r = map.x(d.box.right); val b = map.y(d.box.bottom)
    if (d.cls == ObjectClass.UNKNOWN && !isLead) return // declutter: only show relevant classes
    drawCornerBox(l, t, r, b, color, (if (isLead) 3f else 2f) * density)

    val label = if (isLead && result.leadDistanceM != null) "${classTag(d.cls)} ${result.leadDistanceM.toInt()}m"
    else classTag(d.cls)
    drawLabel(measurer, label, l, t, color, p.chip)
}

private fun DrawScope.drawCornerBox(l: Float, t: Float, r: Float, b: Float, color: Color, sw: Float) {
    val w = (r - l); val h = (b - t)
    val len = minOf(w, h) * 0.28f
    if (len <= 0f) return
    // four L-shaped corners
    drawLine(color, Offset(l, t), Offset(l + len, t), sw); drawLine(color, Offset(l, t), Offset(l, t + len), sw)
    drawLine(color, Offset(r, t), Offset(r - len, t), sw); drawLine(color, Offset(r, t), Offset(r, t + len), sw)
    drawLine(color, Offset(l, b), Offset(l + len, b), sw); drawLine(color, Offset(l, b), Offset(l, b - len), sw)
    drawLine(color, Offset(r, b), Offset(r - len, b), sw); drawLine(color, Offset(r, b), Offset(r, b - len), sw)
}

private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, x: Float, y: Float, color: Color, bg: Color) {
    val style = TextStyle(color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    val layout = measurer.measure(text, style)
    val pad = 3f * density
    val top = (y - layout.size.height - 2 * pad).coerceAtLeast(0f)
    drawRect(bg, Offset(x, top), Size(layout.size.width + 2 * pad, layout.size.height + 2 * pad))
    drawText(layout, topLeft = Offset(x + pad, top + pad))
}

private fun DrawScope.drawDepartureArrow(result: FusionResult, p: LynePalette) {
    if (result.ldw == Severity.NONE) return
    val offset = result.laneOffset ?: return
    if (abs(offset) < 0.6f) return
    val color = if (result.ldw == Severity.CRITICAL) p.critical else p.caution
    val cx = size.width / 2f; val cy = size.height * 0.34f
    val dir = if (offset > 0f) -1f else 1f // drift right -> steer left
    val s = 26f * density
    val sw = 6f * density
    val tip = Offset(cx + dir * s, cy)
    drawLine(color, Offset(cx - dir * s, cy), tip, sw, cap = StrokeCap.Round)
    drawLine(color, tip, Offset(cx + dir * (s * 0.4f), cy - s * 0.5f), sw, cap = StrokeCap.Round)
    drawLine(color, tip, Offset(cx + dir * (s * 0.4f), cy + s * 0.5f), sw, cap = StrokeCap.Round)
}

private fun DrawScope.drawPolyline(rows: FloatArray, xs: FloatArray?, color: Color, map: Mapper, strokePx: Float) {
    xs ?: return
    var prev: Offset? = null
    for (i in rows.indices) {
        val x = xs.getOrNull(i) ?: continue
        if (x.isNaN()) { prev = null; continue }
        val pnt = Offset(map.x(x), map.y(rows[i]))
        prev?.let { drawLine(color, it, pnt, strokeWidth = strokePx) }
        prev = pnt
    }
}

private fun classTag(c: ObjectClass): String = when (c) {
    ObjectClass.CAR -> "CAR"; ObjectClass.TRUCK -> "TRUCK"; ObjectClass.BUS -> "BUS"
    ObjectClass.AUTORICKSHAW -> "AUTO"; ObjectClass.MOTORCYCLE -> "MOTO"; ObjectClass.BICYCLE -> "CYCLE"
    ObjectClass.PEDESTRIAN -> "PED"; ObjectClass.STOP_SIGN -> "STOP"; ObjectClass.UNKNOWN -> "OBJ"
}

/** Maps normalized [0,1] display-image coords to view pixels under a FILL_CENTER crop. */
private class Mapper(
    private val viewW: Float, private val viewH: Float,
    private val cropX: Float, private val cropY: Float,
) {
    fun x(nx: Float): Float = ((nx - cropX) / (1f - 2f * cropX)).coerceIn(-0.1f, 1.1f) * viewW
    fun y(ny: Float): Float = ((ny - cropY) / (1f - 2f * cropY)).coerceIn(-0.1f, 1.1f) * viewH
}

private fun fillCenterMapper(viewW: Float, viewH: Float, imageAspect: Float): Mapper {
    if (viewW <= 0f || viewH <= 0f || imageAspect <= 0f) return Mapper(viewW, viewH, 0f, 0f)
    val viewAspect = viewW / viewH
    return if (imageAspect > viewAspect) {
        Mapper(viewW, viewH, (1f - viewAspect / imageAspect) / 2f, 0f)
    } else {
        Mapper(viewW, viewH, 0f, (1f - imageAspect / viewAspect) / 2f)
    }
}
