package com.lingxi.classic.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Xml
import android.view.View
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * 极简 SVG 渲染 View（替代 WebView 显示图形验证码，兼容任意老 WebView/无 WebView 环境）。
 * 支持：rect/circle/ellipse/line/polyline/polygon/path（M L H V C S Q T A Z）；
 * fill/stroke/stroke-width/各 opacity/g 分组属性继承/translate scale rotate matrix 变换。
 * 不支持的元素静默跳过；解析失败显示"点击重试"占位文字。
 */
class SvgCaptchaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Shape(val path: Path, val paint: Paint)

    private val shapes = ArrayList<Shape>(16)
    private var viewBox = RectF(0f, 0f, 100f, 40f)
    private var parseFailed = false

    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9CA3AF.toInt()
        textSize = 14f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }

    /** 设置 SVG 字符串；null/空/解析失败 → 显示占位文字 */
    fun setSvg(svg: String?) {
        shapes.clear()
        parseFailed = true
        if (!svg.isNullOrBlank()) {
            try {
                parse(svg)
                parseFailed = shapes.isEmpty()
            } catch (_: Exception) {
                shapes.clear()
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (parseFailed) {
            canvas.drawText("点击刷新验证码", width / 2f, height / 2f + fallbackPaint.textSize / 3f, fallbackPaint)
            return
        }
        val sx = width / viewBox.width()
        val sy = height / viewBox.height()
        val scale = minOf(sx, sy)
        canvas.save()
        canvas.translate((width - viewBox.width() * scale) / 2f - viewBox.left * scale,
            (height - viewBox.height() * scale) / 2f - viewBox.top * scale)
        canvas.scale(scale, scale)
        for (s in shapes) canvas.drawPath(s.path, s.paint)
        canvas.restore()
    }

    // ==================== 解析 ====================

    private class PaintState(
        var fill: Int? = Color.BLACK,
        var stroke: Int? = null,
        var strokeWidth: Float = 1f,
        var opacity: Float = 1f,
        var fillOpacity: Float = 1f,
        var strokeOpacity: Float = 1f
    ) {
        fun copy() = PaintState(fill, stroke, strokeWidth, opacity, fillOpacity, strokeOpacity)
    }

    private fun parse(svg: String) {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(svg))
        val stateStack = ArrayList<PaintState>()
        val matrixStack = ArrayList<Matrix>()
        var state = PaintState()
        var matrix = Matrix()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    if (name == "g" || name == "svg") {
                        stateStack.add(state)
                        matrixStack.add(Matrix(matrix))
                        state = state.copy()
                        matrix = Matrix(matrix)
                        applyAttrs(parser, state, matrix)
                        if (name == "svg") readViewBox(parser)
                    } else {
                        val st = state.copy()
                        val mx = Matrix(matrix)
                        applyAttrs(parser, st, mx)
                        val path = buildShape(name, parser)
                        if (path != null) {
                            path.transform(mx)
                            emitShapes(path, st)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    if ((name == "g" || name == "svg") && stateStack.isNotEmpty()) {
                        state = stateStack.removeAt(stateStack.size - 1)
                        matrix = matrixStack.removeAt(matrixStack.size - 1)
                    }
                }
            }
            event = parser.next()
        }
    }

    private fun readViewBox(parser: XmlPullParser) {
        val vb = attr(parser, "viewBox")
        if (vb != null) {
            val parts = vb.split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            if (parts.size == 4) viewBox = RectF(parts[0], parts[1], parts[0] + parts[2], parts[1] + parts[3])
        } else {
            val w = parseLen(attr(parser, "width"))
            val h = parseLen(attr(parser, "height"))
            if (w > 0 && h > 0) viewBox = RectF(0f, 0f, w, h)
        }
    }

    private fun applyAttrs(parser: XmlPullParser, st: PaintState, mx: Matrix) {
        attr(parser, "fill")?.let { st.fill = parseColor(it) }
        attr(parser, "stroke")?.let { st.stroke = parseColor(it) }
        attr(parser, "stroke-width")?.let { parseLen(it).let { w -> if (w > 0) st.strokeWidth = w } }
        attr(parser, "opacity")?.toFloatOrNull()?.let { st.opacity = it.coerceIn(0f, 1f) }
        attr(parser, "fill-opacity")?.toFloatOrNull()?.let { st.fillOpacity = it.coerceIn(0f, 1f) }
        attr(parser, "stroke-opacity")?.toFloatOrNull()?.let { st.strokeOpacity = it.coerceIn(0f, 1f) }
        attr(parser, "style")?.let { style ->
            style.split(';').forEach { decl ->
                val kv = decl.split(':')
                if (kv.size == 2) {
                    val v = kv[1].trim()
                    when (kv[0].trim()) {
                        "fill" -> st.fill = parseColor(v)
                        "stroke" -> st.stroke = parseColor(v)
                        "stroke-width" -> parseLen(v).let { if (it > 0) st.strokeWidth = it }
                        "opacity" -> v.toFloatOrNull()?.let { st.opacity = it.coerceIn(0f, 1f) }
                    }
                }
            }
        }
        attr(parser, "transform")?.let { applyTransform(it, mx) }
    }

    private fun applyTransform(t: String, mx: Matrix) {
        val re = Regex("(matrix|translate|scale|rotate)\\(([^)]*)\\)")
        for (m in re.findAll(t)) {
            val nums = m.groupValues[2].split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            when (m.groupValues[1]) {
                "translate" -> if (nums.isNotEmpty()) mx.preTranslate(nums[0], nums.getOrElse(1) { 0f })
                "scale" -> if (nums.isNotEmpty()) mx.preScale(nums[0], nums.getOrElse(1) { nums[0] })
                "rotate" -> if (nums.size >= 3) mx.preRotate(nums[0], nums[1], nums[2])
                    else if (nums.isNotEmpty()) mx.preRotate(nums[0])
                "matrix" -> if (nums.size == 6) {
                    val vals = floatArrayOf(nums[0], nums[2], nums[4], nums[1], nums[3], nums[5], 0f, 0f, 1f)
                    val tm = Matrix(); tm.setValues(vals); mx.preConcat(tm)
                }
            }
        }
    }

    private fun buildShape(name: String, parser: XmlPullParser): Path? {
        val p = Path()
        when (name) {
            "rect" -> {
                val x = parseLen(attr(parser, "x")); val y = parseLen(attr(parser, "y"))
                val w = parseLen(attr(parser, "width")); val h = parseLen(attr(parser, "height"))
                if (w <= 0 || h <= 0) return null
                val rx = parseLen(attr(parser, "rx"))
                if (rx > 0) p.addRoundRect(RectF(x, y, x + w, y + h), rx, rx, Path.Direction.CW)
                else p.addRect(RectF(x, y, x + w, y + h), Path.Direction.CW)
            }
            "circle" -> {
                val r = parseLen(attr(parser, "r")); if (r <= 0) return null
                p.addCircle(parseLen(attr(parser, "cx")), parseLen(attr(parser, "cy")), r, Path.Direction.CW)
            }
            "ellipse" -> {
                val rx = parseLen(attr(parser, "rx")); val ry = parseLen(attr(parser, "ry"))
                if (rx <= 0 || ry <= 0) return null
                val cx = parseLen(attr(parser, "cx")); val cy = parseLen(attr(parser, "cy"))
                p.addOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW)
            }
            "line" -> {
                p.moveTo(parseLen(attr(parser, "x1")), parseLen(attr(parser, "y1")))
                p.lineTo(parseLen(attr(parser, "x2")), parseLen(attr(parser, "y2")))
            }
            "polyline", "polygon" -> {
                val pts = attr(parser, "points")?.split(Regex("[\\s,]+"))
                    ?.mapNotNull { it.toFloatOrNull() } ?: return null
                if (pts.size < 4) return null
                p.moveTo(pts[0], pts[1])
                var i = 2
                while (i + 1 < pts.size) { p.lineTo(pts[i], pts[i + 1]); i += 2 }
                if (name == "polygon") p.close()
            }
            "path" -> {
                val d = attr(parser, "d") ?: return null
                parsePathData(d, p)
            }
            else -> return null
        }
        return p
    }

    private fun emitShapes(path: Path, st: PaintState) {
        st.fill?.let { c ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = withAlpha(c, st.opacity * st.fillOpacity)
            }
            shapes.add(Shape(Path(path), paint))
        }
        st.stroke?.let { c ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = st.strokeWidth
                color = withAlpha(c, st.opacity * st.strokeOpacity)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            shapes.add(Shape(Path(path), paint))
        }
    }

    // ==================== path d 解析 ====================

    private fun parsePathData(d: String, path: Path) {
        val tokens = Regex("[MmLlHhVvCcSsQqTtAaZz]|-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?")
            .findAll(d).map { it.value }.toList()
        var i = 0
        var cmd = ' '
        var cx = 0f; var cy = 0f
        var startX = 0f; var startY = 0f
        var prevCtrlX = 0f; var prevCtrlY = 0f

        fun num(): Float? = tokens.getOrNull(i)?.toFloatOrNull()?.also { i++ }
        fun hasNum(): Boolean = tokens.getOrNull(i)?.toFloatOrNull() != null

        while (i < tokens.size) {
            val t = tokens[i]
            if (t.length == 1 && t[0].isLetter()) { cmd = t[0]; i++ }
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    var x = num() ?: break; var y = num() ?: break
                    if (rel) { x += cx; y += cy }
                    path.moveTo(x, y); cx = x; cy = y; startX = x; startY = y
                    // 隐式后续坐标按 lineTo 处理
                    while (hasNum()) {
                        var lx = num() ?: break; var ly = num() ?: break
                        if (rel) { lx += cx; ly += cy }
                        path.lineTo(lx, ly); cx = lx; cy = ly
                    }
                }
                'L' -> while (hasNum()) {
                    var x = num() ?: break; var y = num() ?: break
                    if (rel) { x += cx; y += cy }
                    path.lineTo(x, y); cx = x; cy = y
                }
                'H' -> while (hasNum()) {
                    var x = num() ?: break
                    if (rel) x += cx
                    path.lineTo(x, cy); cx = x
                }
                'V' -> while (hasNum()) {
                    var y = num() ?: break
                    if (rel) y += cy
                    path.lineTo(cx, y); cy = y
                }
                'C' -> while (hasNum()) {
                    var x1 = num() ?: break; var y1 = num() ?: break
                    var x2 = num() ?: break; var y2 = num() ?: break
                    var x = num() ?: break; var y = num() ?: break
                    if (rel) { x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy }
                    path.cubicTo(x1, y1, x2, y2, x, y)
                    prevCtrlX = x2; prevCtrlY = y2; cx = x; cy = y
                }
                'S' -> while (hasNum()) {
                    var x2 = num() ?: break; var y2 = num() ?: break
                    var x = num() ?: break; var y = num() ?: break
                    if (rel) { x2 += cx; y2 += cy; x += cx; y += cy }
                    path.cubicTo(2 * cx - prevCtrlX, 2 * cy - prevCtrlY, x2, y2, x, y)
                    prevCtrlX = x2; prevCtrlY = y2; cx = x; cy = y
                }
                'Q' -> while (hasNum()) {
                    var x1 = num() ?: break; var y1 = num() ?: break
                    var x = num() ?: break; var y = num() ?: break
                    if (rel) { x1 += cx; y1 += cy; x += cx; y += cy }
                    path.quadTo(x1, y1, x, y)
                    prevCtrlX = x1; prevCtrlY = y1; cx = x; cy = y
                }
                'T' -> while (hasNum()) {
                    var x = num() ?: break; var y = num() ?: break
                    if (rel) { x += cx; y += cy }
                    path.quadTo(2 * cx - prevCtrlX, 2 * cy - prevCtrlY, x, y)
                    prevCtrlX = 2 * cx - prevCtrlX; prevCtrlY = 2 * cy - prevCtrlY
                    cx = x; cy = y
                }
                'A' -> while (hasNum()) {
                    // 弧线完整实现成本高，验证码场景退化为直线到终点
                    num(); num(); num(); num(); num()
                    var x = num() ?: break; var y = num() ?: break
                    if (rel) { x += cx; y += cy }
                    path.lineTo(x, y); cx = x; cy = y
                }
                'Z' -> { path.close(); cx = startX; cy = startY }
                else -> break
            }
        }
    }

    // ==================== 工具 ====================

    private fun attr(parser: XmlPullParser, name: String): String? =
        parser.getAttributeValue(null, name)

    private fun parseLen(s: String?): Float {
        if (s == null) return 0f
        return s.replace(Regex("[a-zA-Z%]+$"), "").toFloatOrNull() ?: 0f
    }

    private fun parseColor(s: String): Int? {
        val v = s.trim().lowercase()
        if (v == "none" || v == "transparent") return null
        if (v.startsWith("hsl")) return parseHsl(v)
        return try {
            when {
                v.startsWith("#") && v.length == 4 -> {
                    val r = v[1]; val g = v[2]; val b = v[3]
                    Color.parseColor("#$r$r$g$g$b$b")
                }
                v.startsWith("rgb") -> {
                    val nums = v.substringAfter('(').substringBefore(')')
                        .split(Regex("[\\s,/]+")).filter { it.isNotBlank() }
                        .mapNotNull { it.removeSuffix("%").toIntOrNull() }
                    if (nums.size >= 3) Color.rgb(nums[0].coerceIn(0, 255), nums[1].coerceIn(0, 255), nums[2].coerceIn(0, 255)) else null
                }
                else -> Color.parseColor(v)
            }
        } catch (_: Exception) {
            when (v) {
                "black" -> Color.BLACK; "white" -> Color.WHITE; "red" -> Color.RED
                "green" -> Color.GREEN; "blue" -> Color.BLUE
                "gray", "grey" -> Color.GRAY; "yellow" -> Color.YELLOW
                else -> null
            }
        }
    }

    /** 解析 hsl(h s% l%) / hsl(h, s%, l%) / hsla(...)（支持空格或逗号分隔、可选 /alpha） */
    private fun parseHsl(v: String): Int? {
        val inner = v.substringAfter('(').substringBefore(')')
        val parts = inner.split(Regex("[\\s,/]+")).filter { it.isNotBlank() }
        if (parts.size < 3) return null
        val h = (parts[0].removeSuffix("deg").toFloatOrNull() ?: return null) / 360f
        val sat = (parts[1].removeSuffix("%").toFloatOrNull() ?: return null) / 100f
        val light = (parts[2].removeSuffix("%").toFloatOrNull() ?: return null) / 100f
        val alpha = parts.getOrNull(3)?.removeSuffix("%")?.toFloatOrNull()?.let {
            if (parts[3].endsWith("%")) it / 100f else it
        } ?: 1f
        val c = (1 - Math.abs(2 * light - 1)) * sat
        val hp = ((h % 1f) + 1f) % 1f * 6f
        val x = c * (1 - Math.abs(hp % 2 - 1))
        val (r1, g1, b1) = when {
            hp < 1 -> Triple(c, x, 0f); hp < 2 -> Triple(x, c, 0f)
            hp < 3 -> Triple(0f, c, x); hp < 4 -> Triple(0f, x, c)
            hp < 5 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = light - c / 2
        return Color.argb(
            (alpha.coerceIn(0f, 1f) * 255).toInt(),
            ((r1 + m).coerceIn(0f, 1f) * 255).toInt(),
            ((g1 + m).coerceIn(0f, 1f) * 255).toInt(),
            ((b1 + m).coerceIn(0f, 1f) * 255).toInt()
        )
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
