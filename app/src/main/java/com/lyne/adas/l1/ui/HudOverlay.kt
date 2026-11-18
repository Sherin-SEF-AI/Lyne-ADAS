package com.lyne.adas.l1.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.lyne.adas.l1.fusion.FusionResult
import com.lyne.adas.l1.fusion.LaneSource
import com.lyne.adas.l1.fusion.Severity
import com.lyne.adas.l1.inference.ObjectClass
import com.lyne.adas.l1.ui.theme.LynePalette
import kotlin.math.abs

/**
 * Automotive HUD overlay drawn over the camera preview:
 *  - the drivable road ahead (segmentation, cleaned up) as one smooth green shape fading with
 *    distance, cut off at the vehicle ahead;
 *  - detections as rounded brackets that glide between model updates and fade in/out; the lead
 *    vehicle gets a lit ground "footprint" (where range is measured from) and a distance tag;
 *  - painted lane lines and the lane-departure steer arrow.
 * Severity is the only thing that turns the overlay amber or red.
 * Normalized detector coords are mapped through the PreviewView FILL_CENTER crop.
 */
@Composable
fun HudOverlay(
    result: FusionResult?,
    frameAspect: Float,
    palette: LynePalette,
    phase: Float,
    modifier: Modifier = Modifier,
    showHorizon: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    val boxes = remember { BoxAnimator() }
    val corridorSmoother = remember { CorridorSmoother() }
    val corridorCache = remember { arrayOfNulls<Any>(2) } // [frameId, Corridor?]
    Canvas(modifier = modifier) {
        val now = System.nanoTime()
        @Suppress("UNUSED_VARIABLE") val tick = phase // read so the canvas redraws every animation frame
        val map = fillCenterMapper(size.width, size.height, frameAspect)

        // Corridor: rebuilt only when a new fusion result arrives, eased every frame.
        if (result != null && corridorCache[0] != result.frameId) {
            corridorCache[0] = result.frameId
            corridorCache[1] = buildCorridor(result)
        }
        val corridor = corridorSmoother.update(corridorCache[1] as Corridor?, now)
        if (result != null && corridor != null) drawCorridor(corridor, result, map, palette, corridorSmoother.alpha)
        if (result != null) {
            drawLaneLines(result, map, palette)
            if (showHorizon) drawHorizon(result, map, palette)
        }
        val anims = boxes.update(result?.objects.orEmpty(), now)
        // Far/small first, lead last, so the important box is on top.
        val ordered = anims.sortedWith(compareBy({ it.obj.isLead }, { it.b - it.t }))
        for (a in ordered) if (result != null) drawObject(a, result, map, palette, measurer)
        if (result != null) drawDepartureArrow(result, palette)
    }
}

// ---------------------------------------------------------------- corridor

private const val MIN_LANE_CONF = 0.40f

private fun buildCorridor(r: FusionResult): Corridor? {
    val lane = r.lane
    if (lane.confidence < MIN_LANE_CONF) return null
    // The IMU horizon bounds how far the road can reach; when it's implausible (odd mount, phone
    // being handled) let the segmentation decide where the road ends.
    val hz = if (!r.horizonLeftY.isNaN() && !r.horizonRightY.isNaN()) (r.horizonLeftY + r.horizonRightY) / 2f else Float.NaN
    var top = if (hz.isNaN() || hz !in 0.15f..0.8f) 0.2f else hz + 0.015f
    // Stop at the vehicle ahead: the road beyond it isn't "yours" yet.
    val lead = r.objects.firstOrNull { it.isLead }
    if (lead != null) top = maxOf(top, lead.box.bottom - 0.005f)
    val mask = lane.mask
    return if (mask != null) Corridor.fromMask(mask, lane.maskW, lane.maskH, top)
    else {
        val l = lane.leftLaneX; val rr = lane.rightLaneX
        if (l != null && rr != null) Corridor.fromEdges(lane.rows, l, rr, top) else null
    }
}

private fun DrawScope.drawCorridor(c: Corridor, r: FusionResult, map: Mapper, p: LynePalette, vis: Float) {
    val n = c.length
    if (n < 3) return
    val warn = when (r.ldw) { Severity.CRITICAL -> p.critical; Severity.CAUTION -> p.caution; else -> null }
    val lx = FloatArray(n) { map.x(c.left[it]) }; val rx = FloatArray(n) { map.x(c.right[it]) }
    val ys = FloatArray(n) { map.y(c.rows[it]) }
    val bottomY = ys[0]; val topY = ys[n - 1]
    if (bottomY - topY < 8f * density) return

    // Body: one smooth closed shape.
    val body = Path()
    body.moveTo(lx[0], ys[0])
    smoothThrough(body, lx, ys, 0 until n)
    body.lineTo(rx[n - 1], ys[n - 1])
    smoothThrough(body, rx, ys, (n - 1) downTo 0)
    body.close()
    // The original Lyne look: green, brighter near the car and fading toward the distance.
    val near = warn ?: p.laneNear
    val far = warn ?: p.laneFar
    drawPath(
        body,
        Brush.verticalGradient(
            0f to far.copy(alpha = 0.07f * vis),
            1f to near.copy(alpha = 0.40f * vis),
            startY = topY, endY = bottomY,
        ),
    )
    // Soft edge so the road boundary reads clearly without a hard outline.
    val edge = Brush.verticalGradient(0f to far.copy(alpha = 0.15f * vis), 1f to near.copy(alpha = 0.75f * vis), startY = topY, endY = bottomY)
    drawPath(body, edge, style = Stroke(1.6f * density, join = StrokeJoin.Round))
}


/** Quadratic smoothing through points (midpoint method): no kinks from the row sampling. */
private fun smoothThrough(path: Path, xs: FloatArray, ys: FloatArray, idx: IntProgression) {
    val list = idx.toList()
    if (list.size < 2) return
    for (j in 1 until list.size - 1) {
        val i = list[j]; val nx = list[j + 1]
        path.quadraticBezierTo(xs[i], ys[i], (xs[i] + xs[nx]) / 2f, (ys[i] + ys[nx]) / 2f)
    }
    val last = list.last()
    path.lineTo(xs[last], ys[last])
}

/** Detected painted lane lines: thin white strokes fading with distance (only while confident). */
private fun DrawScope.drawLaneLines(result: FusionResult, map: Mapper, p: LynePalette) {
    val lines = result.laneLines
    if (lines.confidence < LANE_LINE_MIN_CONF) return
    val sev = if (result.laneSource == LaneSource.LINES) result.ldw else Severity.NONE
    val color = when (sev) { Severity.CRITICAL -> p.critical; Severity.CAUTION -> p.caution; else -> Color.White }
    for (xs in arrayOf(lines.leftX, lines.rightX)) {
        xs ?: continue
        val path = Path(); var started = false; var top = Float.MAX_VALUE; var bot = 0f
        for (i in lines.rows.indices) {
            val x = xs.getOrNull(i) ?: continue
            if (x.isNaN()) continue
            val px = map.x(x); val py = map.y(lines.rows[i])
            if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
            top = minOf(top, py); bot = maxOf(bot, py)
        }
        if (!started || bot - top < 4f) continue
        val brush = Brush.verticalGradient(0f to color.copy(alpha = 0f), 1f to color.copy(alpha = if (sev == Severity.NONE) 0.7f else 0.95f), startY = top, endY = bot)
        drawPath(path, brush, style = Stroke((if (sev == Severity.NONE) 2.5f else 4f) * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private const val LANE_LINE_MIN_CONF = 0.40f

/** Height of the top strip used by the status, lead and alert cards. */
private const val TOP_UI_BAND_DP = 84f

// ---------------------------------------------------------------- objects

private fun DrawScope.drawObject(a: BoxAnimator.Anim, result: FusionResult, map: Mapper, p: LynePalette, measurer: TextMeasurer) {
    val o = a.obj
    if (o.cls == ObjectClass.UNKNOWN) return
    val alert = when {
        o.isLead && result.fcw == Severity.CRITICAL -> p.critical
        o.isLead && result.fcw == Severity.CAUTION -> p.caution
        o.isLead && result.leadBraking -> p.critical
        o.cls.isVru && o.inPath && result.vru == Severity.CRITICAL -> p.critical
        o.cls.isVru && o.inPath -> p.caution
        o.cls == ObjectClass.ANIMAL && result.animal == Severity.CRITICAL -> p.critical
        o.cls == ObjectClass.ANIMAL && (o.inPath || result.animal == Severity.CAUTION) -> p.caution
        o.cls == ObjectClass.STOP_SIGN -> p.critical
        else -> null
    }
    val l = map.x(a.l); val t = map.y(a.t); val r = map.x(a.r); val b = map.y(a.b)
    val w = r - l; val h = b - t
    if (w < 4f || h < 4f) return
    val fade = a.alpha

    if (o.isLead) {
        val c = alert ?: p.accent
        // Lit ground footprint under the vehicle: where its range is measured from.
        val fy = b + 1.5f * density
        val fw = w * 1.08f; val fx = l - (fw - w) / 2f
        val foot = Brush.horizontalGradient(0f to c.copy(alpha = 0f), 0.5f to c.copy(alpha = 0.85f * fade), 1f to c.copy(alpha = 0f), startX = fx, endX = fx + fw)
        drawRoundRect(foot, Offset(fx, fy - 8f * density), Size(fw, 16f * density), CornerRadius(8f * density), alpha = 0.35f)
        drawRoundRect(foot, Offset(fx, fy - 2f * density), Size(fw, 4f * density), CornerRadius(2f * density))
        // Faint body tint + brackets.
        drawRoundRect(c.copy(alpha = 0.07f * fade), Offset(l, t), Size(w, h), CornerRadius(10f * density))
        drawBrackets(l, t, r, b, c.copy(alpha = 0.95f * fade), 2.6f * density)
        // Distance tag centred above.
        val d = o.distanceM
        if (d != null) {
            val s = o.sigmaM
            val main = "${d.toInt()} m"
            val sub = if (s != null && s >= 1f) "±${s.toInt()}" else null
            // Above the vehicle normally; below its footprint when the top of the screen is taken by
            // the status/lead cards.
            val above = t - 6f * density > TOP_UI_BAND_DP * density
            val tagY = if (above) t - 6f * density else fy + 36f * density
            drawTag(measurer, main, sub, (l + r) / 2f, tagY, c, p, fade, big = true, centered = true)
            if (result.leadBraking) {
                val by = if (above) fy + 26f * density else tagY + 26f * density
                drawTag(measurer, "BRAKING", null, (l + r) / 2f, by, p.critical, p, fade, big = false, centered = true, solid = true)
            }
        } else if (result.leadBraking) {
            drawTag(measurer, "BRAKING", null, (l + r) / 2f, fy + 26f * density, p.critical, p, fade, big = false, centered = true, solid = true)
        }
        return
    }

    val important = alert != null || o.inPath
    // Don't clutter the view with every far-away box: small, out-of-path objects get a whisper.
    val small = h < 28f * density
    val c = alert ?: Color.White
    val baseAlpha = when {
        alert != null -> 0.95f
        o.inPath -> 0.75f
        small -> 0.30f
        else -> 0.45f
    }
    drawBrackets(l, t, r, b, c.copy(alpha = baseAlpha * fade), (if (important) 2f else 1.4f) * density)
    val d = o.distanceM
    val showLabel = alert != null || (!small && (o.inPath || (d != null && d < 40f)))
    if (showLabel) {
        val main = classTag(o.cls)
        val sub = if (d != null && d < 80f) "${d.toInt()} m" else null
        drawTag(measurer, main, sub, l, t - 4f * density, c, p, fade * (if (important) 1f else 0.85f), big = false, centered = false)
    }
}

/** Rounded corner brackets: four short arcs, a lighter look than a full rectangle. */
private fun DrawScope.drawBrackets(l: Float, t: Float, r: Float, b: Float, color: Color, sw: Float) {
    val w = r - l; val h = b - t
    val len = minOf(w, h) * 0.26f
    if (len <= 1f) return
    val rad = minOf(len * 0.55f, 9f * density)
    val path = Path().apply {
        moveTo(l, t + len); lineTo(l, t + rad); quadraticBezierTo(l, t, l + rad, t); lineTo(l + len, t)
        moveTo(r - len, t); lineTo(r - rad, t); quadraticBezierTo(r, t, r, t + rad); lineTo(r, t + len)
        moveTo(r, b - len); lineTo(r, b - rad); quadraticBezierTo(r, b, r - rad, b); lineTo(r - len, b)
        moveTo(l + len, b); lineTo(l + rad, b); quadraticBezierTo(l, b, l, b - rad); lineTo(l, b - len)
    }
    drawPath(path, Color.Black.copy(alpha = color.alpha * 0.35f), style = Stroke(sw + 2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * Pill label: dark glass background, a colour dot, bold main text and an optional dim sub-text.
 * [y] is the pill's bottom edge; it is kept on screen.
 */
private fun DrawScope.drawTag(
    measurer: TextMeasurer, main: String, sub: String?, x: Float, y: Float, color: Color, p: LynePalette,
    alpha: Float, big: Boolean, centered: Boolean, solid: Boolean = false,
) {
    if (alpha < 0.05f) return
    val mainStyle = TextStyle(color = if (solid) p.background else Color.White, fontSize = if (big) 17.sp else 12.sp, fontWeight = FontWeight.Bold)
    val subStyle = TextStyle(color = Color.White.copy(alpha = 0.65f), fontSize = if (big) 12.sp else 11.sp, fontWeight = FontWeight.Medium)
    val m = measurer.measure(main, mainStyle)
    val s = sub?.let { measurer.measure(it, subStyle) }
    val padH = (if (big) 10f else 7f) * density
    val padV = (if (big) 4f else 2.5f) * density
    val dot = if (solid) 0f else (if (big) 7f else 6f) * density
    val gap = 5f * density
    val w = padH * 2 + dot + (if (dot > 0) gap else 0f) + m.size.width + (s?.let { gap + it.size.width } ?: 0f)
    val h = padV * 2 + m.size.height
    var left = if (centered) x - w / 2f else x
    left = left.coerceIn(2f, size.width - w - 2f)
    val top = (y - h).coerceIn(2f, size.height - h - 2f)
    val bg = if (solid) color else Color(0xFF0A0E12).copy(alpha = 0.72f)
    drawRoundRect(bg.copy(alpha = bg.alpha * alpha), Offset(left, top), Size(w, h), CornerRadius(h / 2f))
    if (!solid) drawRoundRect(color.copy(alpha = 0.55f * alpha), Offset(left, top), Size(w, h), CornerRadius(h / 2f), style = Stroke(1f * density))
    var cx = left + padH
    if (dot > 0) {
        drawCircle(color.copy(alpha = alpha), dot / 2f, Offset(cx + dot / 2f, top + h / 2f))
        cx += dot + gap
    }
    drawText(m, topLeft = Offset(cx, top + padV), alpha = alpha)
    if (s != null) drawText(s, topLeft = Offset(cx + m.size.width + gap, top + padV + (m.size.height - s.size.height) * 0.6f), alpha = alpha)
}

private fun DrawScope.drawHorizon(result: FusionResult, map: Mapper, p: LynePalette) {
    val yl = result.horizonLeftY; val yr = result.horizonRightY
    if (yl.isNaN() || yr.isNaN()) return
    drawLine(
        p.accent.copy(alpha = 0.22f), Offset(map.x(0f), map.y(yl)), Offset(map.x(1f), map.y(yr)),
        strokeWidth = 1f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * density, 10f * density)),
    )
}

private fun DrawScope.drawDepartureArrow(result: FusionResult, p: LynePalette) {
    if (result.ldw == Severity.NONE) return
    val offset = result.laneOffset ?: return
    if (abs(offset) < 0.6f) return
    val color = if (result.ldw == Severity.CRITICAL) p.critical else p.caution
    val cx = size.width / 2f; val cy = size.height * 0.36f
    val dir = if (offset > 0f) -1f else 1f // drift right -> steer left
    val s = 30f * density
    drawCircle(Color(0xFF0A0E12).copy(alpha = 0.6f), s * 1.25f, Offset(cx, cy))
    drawCircle(color, s * 1.25f, Offset(cx, cy), style = Stroke(2.5f * density))
    val sw = 6f * density
    val tip = Offset(cx + dir * s * 0.7f, cy)
    val path = Path().apply {
        moveTo(cx - dir * s * 0.7f, cy); lineTo(tip.x, tip.y)
        moveTo(cx + dir * (s * 0.1f), cy - s * 0.5f); lineTo(tip.x, tip.y); lineTo(cx + dir * (s * 0.1f), cy + s * 0.5f)
    }
    drawPath(path, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun classTag(c: ObjectClass): String = when (c) {
    ObjectClass.CAR -> "Car"; ObjectClass.TRUCK -> "Truck"; ObjectClass.BUS -> "Bus"
    ObjectClass.AUTORICKSHAW -> "Auto"; ObjectClass.MOTORCYCLE -> "Bike"; ObjectClass.BICYCLE -> "Cycle"
    ObjectClass.PEDESTRIAN -> "Person"; ObjectClass.STOP_SIGN -> "Stop"
    ObjectClass.ANIMAL -> "Animal"; ObjectClass.UNKNOWN -> "Object"
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
