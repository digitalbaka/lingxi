package com.lingxi.classic.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object FormatUtils {

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "00:00"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, secs)
        else String.format(Locale.getDefault(), "%02d:%02d", minutes, secs)
    }

    fun formatDurationMs(ms: Long): String = formatDuration(TimeUnit.MILLISECONDS.toSeconds(ms))

    fun formatCount(count: Long): String = when {
        count >= 10000 -> String.format(Locale.getDefault(), "%.1f万", count / 10000f)
        else -> count.toString()
    }

    fun formatCount(count: Int): String = formatCount(count.toLong())

    fun formatDate(timestampMs: Long): String {
        if (timestampMs <= 0) return ""
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestampMs))
    }

    /** 相对时间：刚刚 / n分钟前 / n小时前 / n天前 / 日期 */
    fun formatRelative(timestampMs: Long): String {
        if (timestampMs <= 0) return ""
        val diff = System.currentTimeMillis() - timestampMs
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
            diff < 30L * 86_400_000 -> "${diff / 86_400_000} 天前"
            else -> formatDate(timestampMs)
        }
    }

    /** 服务端可能返回毫秒/秒时间戳或已格式化字符串，统一成 yyyy-MM-dd */
    fun formatDateString(raw: String): String {
        val ts = raw.trim().toLongOrNull() ?: return raw
        val ms = if (raw.trim().length <= 10) ts * 1000 else ts
        return formatDate(ms)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1L shl 10 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
