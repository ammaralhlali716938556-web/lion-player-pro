package com.lionplayer.app.subtitle

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Locale
import java.util.regex.Pattern

/** أدوات الترجمة: تحديد النوع + إزاحة زمنية (SRT / VTT / ASS) */
object SubtitleUtils {

    fun getSubtitleMimeType(filePathOrUri: String): String {
        val lower = filePathOrUri.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            lower.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    fun buildSubtitleConfiguration(uri: Uri, label: String = "ترجمة مخصصة"): MediaItem.SubtitleConfiguration {
        val mime = getSubtitleMimeType(uri.toString())
        return MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage("ar")
            .setLabel(label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    /** إعادة كتابة ملف الترجمة بإزاحة زمنية وإرجاعه كملف مؤقت */
    fun shiftSubtitleFile(context: Context, sourceUri: Uri, offsetMs: Long): Uri {
        if (offsetMs == 0L) return sourceUri
        return try {
            val mime = getSubtitleMimeType(sourceUri.toString())
            val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return sourceUri
            val reader = BufferedReader(InputStreamReader(inputStream))
            val lines = reader.readLines()
            reader.close()

            val shiftedLines = when (mime) {
                MimeTypes.APPLICATION_SUBRIP -> shiftSrt(lines, offsetMs)
                MimeTypes.TEXT_VTT -> shiftVtt(lines, offsetMs)
                MimeTypes.TEXT_SSA -> shiftAss(lines, offsetMs)
                else -> shiftSrt(lines, offsetMs)
            }

            val ext = when (mime) {
                MimeTypes.TEXT_SSA -> ".ass"
                MimeTypes.TEXT_VTT -> ".vtt"
                else -> ".srt"
            }
            val cacheFile = File(context.cacheDir, "shifted_sub_${System.currentTimeMillis()}$ext")
            cacheFile.writeText(shiftedLines.joinToString("\n"))
            Uri.fromFile(cacheFile)
        } catch (e: Exception) {
            sourceUri
        }
    }

    private fun shiftSrt(lines: List<String>, offsetMs: Long): List<String> {
        val p = Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})")
        return lines.map { line ->
            val m = p.matcher(line)
            if (m.find()) {
                val startMs = parseTime(m.group(1), m.group(2), m.group(3), m.group(4))
                val endMs = parseTime(m.group(5), m.group(6), m.group(7), m.group(8))
                val newStart = (startMs + offsetMs).coerceAtLeast(0L)
                val newEnd = (endMs + offsetMs).coerceAtLeast(0L)
                "${formatSrtTime(newStart)} --> ${formatSrtTime(newEnd)}"
            } else {
                line
            }
        }
    }

    private fun shiftVtt(lines: List<String>, offsetMs: Long): List<String> {
        val p = Pattern.compile("(\\d{2}):(\\d{2}):(\\d{2}).(\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2}).(\\d{3})")
        return lines.map { line ->
            val m = p.matcher(line)
            if (m.find()) {
                val startMs = parseTime(m.group(1), m.group(2), m.group(3), m.group(4))
                val endMs = parseTime(m.group(5), m.group(6), m.group(7), m.group(8))
                val newStart = (startMs + offsetMs).coerceAtLeast(0L)
                val newEnd = (endMs + offsetMs).coerceAtLeast(0L)
                "${formatVttTime(newStart)} --> ${formatVttTime(newEnd)}"
            } else {
                line
            }
        }
    }

    private fun shiftAss(lines: List<String>, offsetMs: Long): List<String> {
        val p = Pattern.compile("Dialogue:\\s*(\\d+),(\\d+:\\d{2}:\\d{2}.\\d{2}),(\\d+:\\d{2}:\\d{2}.\\d{2}),(.*)")
        return lines.map { line ->
            val m = p.matcher(line)
            if (m.find()) {
                val layer = m.group(1) ?: "0"
                val startMs = parseAssTime(m.group(2))
                val endMs = parseAssTime(m.group(3))
                val rest = m.group(4)
                val newStart = (startMs + offsetMs).coerceAtLeast(0L)
                val newEnd = (endMs + offsetMs).coerceAtLeast(0L)
                "Dialogue: $layer,${formatAssTime(newStart)},${formatAssTime(newEnd)},$rest"
            } else {
                line
            }
        }
    }

    private fun parseTime(h: String?, m: String?, s: String?, ms: String?): Long {
        val hh = h?.toLongOrNull() ?: 0L
        val mm = m?.toLongOrNull() ?: 0L
        val ss = s?.toLongOrNull() ?: 0L
        val mss = ms?.toLongOrNull() ?: 0L
        return (hh * 3600000L) + (mm * 60000L) + (ss * 1000L) + mss
    }

    private fun parseAssTime(t: String?): Long {
        val parts = (t ?: "0:00:00.00").split(":")
        if (parts.size < 3) return 0L
        val h = parts[0].toLongOrNull() ?: 0L
        val m = parts[1].toLongOrNull() ?: 0L
        val secParts = parts[2].split(".")
        val s = secParts[0].toLongOrNull() ?: 0L
        val cs = if (secParts.size > 1) (secParts[1].toLongOrNull() ?: 0L) * 10L else 0L
        return (h * 3600000L) + (m * 60000L) + (s * 1000L) + cs
    }

    private fun formatSrtTime(ms: Long): String {
        val h = ms / 3600000L
        val rem = ms % 3600000L
        val m = rem / 60000L
        val rem2 = rem % 60000L
        val s = rem2 / 1000L
        val msec = rem2 % 1000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, msec)
    }

    private fun formatVttTime(ms: Long): String {
        val h = ms / 3600000L
        val rem = ms % 3600000L
        val m = rem / 60000L
        val rem2 = rem % 60000L
        val s = rem2 / 1000L
        val msec = rem2 % 1000L
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, msec)
    }

    private fun formatAssTime(ms: Long): String {
        val h = ms / 3600000L
        val rem = ms % 3600000L
        val m = rem / 60000L
        val rem2 = rem % 60000L
        val s = rem2 / 1000L
        val cs = (rem2 % 1000L) / 10L
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
    }
}
