package com.lingxi.classic.util

import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * 极简 Markdown 渲染器（零第三方依赖）。
 * 支持：标题、粗体、斜体、行内代码、代码块、列表、引用、分割线、链接、独占行图片块。
 */
object MarkdownRenderer {

    sealed class Block {
        data class Text(val text: CharSequence) : Block()
        data class Image(val url: String, val alt: String) : Block()
    }

    private val COLOR_CODE_BG = Color.parseColor("#1A000000")
    private val COLOR_QUOTE = Color.parseColor("#FF9E9E9E")
    private val COLOR_LINK = Color.parseColor("#FF10B981")

    fun render(markdown: String): CharSequence = parseBlocks(markdown)
        .filterIsInstance<Block.Text>()
        .fold(SpannableStringBuilder()) { acc, b -> acc.append(b.text) }

    /** 解析为块序列；独占一行的图片抽为 Image 块（交给 Glide 加载，RGB_565 省内存） */
    fun parseBlocks(markdown: String, imageBaseUrl: String = ""): List<Block> {
        val blocks = mutableListOf<Block>()
        var textBuf = SpannableStringBuilder()
        var inCodeBlock = false

        fun flushText() {
            if (textBuf.isNotBlank()) blocks.add(Block.Text(textBuf))
            textBuf = SpannableStringBuilder()
        }

        val lines = markdown.replace("\r\n", "\n").split("\n")
        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            if (line.trimStart().startsWith("```")) {
                inCodeBlock = !inCodeBlock
                continue
            }
            if (!inCodeBlock) {
                val img = Regex("^\\s*!\\[([^]]*)]\\(([^)\\s]+)[^)]*\\)\\s*$").find(line)
                if (img != null) {
                    flushText()
                    var url = img.groupValues[2]
                    if (url.startsWith("/")) url = imageBaseUrl.trimEnd('/') + url
                    blocks.add(Block.Image(url, img.groupValues[1]))
                    continue
                }
            }
            processLine(textBuf, line, inCodeBlock)
        }
        flushText()
        return blocks
    }

    private fun processLine(out: SpannableStringBuilder, line: String, inCodeBlock: Boolean) {
        if (inCodeBlock) {
            appendCode(out, line.ifEmpty { " " })
            out.append('\n')
            return
        }
        when {
            line.matches(Regex("^\\s*([-*_])\\1{2,}\\s*$")) -> {
                out.append("────────────────")
                out.append('\n')
            }
            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                val text = line.drop(level).trim()
                val start = out.length
                appendInline(out, text)
                val size = when (level) {
                    1 -> 1.6f; 2 -> 1.45f; 3 -> 1.3f; else -> 1.15f
                }
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.append('\n')
            }
            line.trimStart().startsWith(">") -> {
                val text = line.trimStart().removePrefix(">").trim()
                val start = out.length
                appendInline(out, text)
                // QuoteSpan(color, stripeWidth, gapWidth) 自 API 28 才有；
                // 低版本调用会 NoSuchMethodError（Android 7 上含引用块的文章直接闪退）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    out.setSpan(QuoteSpan(COLOR_QUOTE, 12, 24), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    out.setSpan(QuoteSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.append('\n')
            }
            line.trimStart().matches(Regex("^[-*+]\\s+.*")) -> {
                out.append("  • ")
                appendInline(out, line.trimStart().drop(2))
                out.append('\n')
            }
            line.trimStart().matches(Regex("^\\d+[.、]\\s+.*")) -> {
                val t = line.trimStart()
                val idx = t.indexOfFirst { it == '.' || it == '、' }
                out.append("  " + t.substring(0, idx + 1) + " ")
                appendInline(out, t.substring(idx + 1).trimStart())
                out.append('\n')
            }
            line.isEmpty() -> out.append('\n')
            else -> {
                appendInline(out, line)
                out.append('\n')
            }
        }
    }

    private fun appendInline(out: SpannableStringBuilder, text: String) {
        var s = text
        // 内联图片（非独占行）→ 占位符，真正图片块已在外层抽出
        s = s.replace(Regex("!\\[([^]]*)]\\([^)]*\\)")) { m ->
            val alt = m.groupValues[1].ifEmpty { "图片" }
            "🖼 $alt"
        }
        // 链接 → 文字（绿色）
        val linkMatches = Regex("\\[([^]]+)]\\(([^)]*)\\)").findAll(s).toList()
        if (linkMatches.isNotEmpty()) {
            val sb = StringBuilder()
            var last = 0
            for (m in linkMatches) {
                sb.append(s.substring(last, m.range.first))
                sb.append(m.groupValues[1])
                last = m.range.last + 1
            }
            sb.append(s.substring(last))
            s = sb.toString()
        }

        val pattern = Regex("(`[^`]+`)|(\\*\\*[^*]+\\*\\*)|(\\*[^*]+\\*)")
        var last = 0
        for (m in pattern.findAll(s)) {
            if (m.range.first > last) out.append(s.substring(last, m.range.first))
            val token = m.value
            val start = out.length
            when {
                token.startsWith("**") -> {
                    out.append(token.removeSurrounding("**"))
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                token.startsWith("`") -> {
                    out.append(token.removeSurrounding("`"))
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(COLOR_CODE_BG), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                token.startsWith("*") -> {
                    out.append(token.removeSurrounding("*"))
                    out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            last = m.range.last + 1
        }
        if (last < s.length) out.append(s.substring(last))
    }

    private fun appendCode(out: SpannableStringBuilder, code: String) {
        val start = out.length
        out.append(code)
        out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(BackgroundColorSpan(COLOR_CODE_BG), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(RelativeSizeSpan(0.9f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
