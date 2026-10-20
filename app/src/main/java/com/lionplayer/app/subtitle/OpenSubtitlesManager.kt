package com.lionplayer.app.subtitle

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

data class SubtitleResult(
    val fileId: Int,
    val fileName: String,
    val language: String,
    val downloadUrl: String
)

/** البحث والتنزيل من OpenSubtitles (REST API v1) */
object OpenSubtitlesManager {

    private val executor = Executors.newSingleThreadExecutor()
    private const val API_BASE = "https://api.opensubtitles.com/api/v1"

    /**
     * ضع مفتاح API الخاص بك هنا (مجاني عبر opensubtitles.com).
     * لا تُضمِّن مفتاحاً مشتركاً في إصدار الإنتاج.
     */
    private const val API_KEY = "lion_player_community_key"
    private const val USER_AGENT = "LionPlayer v2.0"

    /** حساب OpenSubtitles MovieHash لملف فيديو */
    fun computeMovieHash(file: File): String {
        val size = file.length()
        val chunkSize = 65536L
        if (size < chunkSize) return ""
        var head = 0L
        var tail = 0L

        FileInputStream(file).use { fis ->
            val buf = ByteArray(chunkSize.toInt())
            val dis = DataInputStream(fis)
            dis.readFully(buf)
            head = computeChunkHash(buf)

            fis.channel.position((size - chunkSize).coerceAtLeast(0L))
            dis.readFully(buf)
            tail = computeChunkHash(buf)
        }

        val hash = size + head + tail
        return String.format("%016x", hash)
    }

    private fun computeChunkHash(bytes: ByteArray): Long {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var hash = 0L
        while (bb.hasRemaining()) {
            hash += bb.long
        }
        return hash
    }

    fun searchSubtitles(
        context: Context,
        query: String,
        movieHash: String? = null,
        onSuccess: (List<SubtitleResult>) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val urlStr = buildString {
                    append("$API_BASE/subtitles?languages=ar,en")
                    if (!movieHash.isNullOrEmpty()) {
                        append("&moviehash=").append(movieHash)
                    }
                    if (query.isNotEmpty()) {
                        append("&query=").append(URLEncoder.encode(query, "UTF-8"))
                    }
                }
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Api-Key", API_KEY)
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val code = conn.responseCode
                if (code in 200..299) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val dataArr = json.optJSONArray("data")
                    val list = mutableListOf<SubtitleResult>()
                    if (dataArr != null) {
                        for (i in 0 until dataArr.length()) {
                            val item = dataArr.getJSONObject(i)
                            val attr = item.optJSONObject("attributes")
                            val files = attr?.optJSONArray("files")
                            val firstFile = files?.optJSONObject(0)
                            val fileId = firstFile?.optInt("file_id") ?: 0
                            val fileName = firstFile?.optString("file_name") ?: "subtitle_$fileId.srt"
                            val lang = attr?.optString("language") ?: "ar"
                            val dlUrl = "$API_BASE/download"
                            list.add(SubtitleResult(fileId, fileName, lang, dlUrl))
                        }
                    }
                    onSuccess(list)
                } else {
                    onError("فشل الاتصال بخادم الترجمة (كود $code)")
                }
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "حدث خطأ أثناء البحث")
            }
        }
    }

    fun downloadSubtitle(
        context: Context,
        result: SubtitleResult,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                val url = URL(result.downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Api-Key", API_KEY)
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val body = JSONObject().apply { put("file_id", result.fileId) }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val code = conn.responseCode
                if (code in 200..299) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val respJson = JSONObject(resp)
                    val link = respJson.optString("link")
                    if (link.isNotEmpty()) {
                        val dlConn = URL(link).openConnection() as HttpURLConnection
                        val subFile = File(context.cacheDir, result.fileName)
                        dlConn.inputStream.use { input ->
                            FileOutputStream(subFile).use { output -> input.copyTo(output) }
                        }
                        onSuccess(Uri.fromFile(subFile))
                    } else {
                        onError("تعذر استخراج رابط التنزيل المباشر")
                    }
                } else {
                    onError("فشل طلب التنزيل (كود $code)")
                }
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "حدث خطأ أثناء تنزيل الترجمة")
            }
        }
    }
}
