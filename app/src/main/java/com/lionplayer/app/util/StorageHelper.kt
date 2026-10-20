package com.lionplayer.app.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.documentfile.provider.DocumentFile
import com.lionplayer.app.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * أدوات Scoped Storage (Android 10–15) و SAF DocumentFile.
 * أُضيفت دالتا [insertFileToMediaStore] و[copyFileToSafTree] لخدمة
 * "استعادة الملفات من الخزينة السرية إلى مجلد عام في الهاتف".
 */
object StorageHelper {

    /** مجلد الاستعادة العام الذي تُرجَع إليه ملفات الخزينة */
    const val VAULT_RESTORE_FOLDER = "LionVault"

    fun buildDeleteIntentSenderRequest(context: Context, items: List<MediaItem>): IntentSenderRequest? {
        if (items.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uris = items.map { Uri.parse(it.uri) }
            val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
            return IntentSenderRequest.Builder(pi.intentSender).build()
        }
        return null
    }

    fun buildWriteIntentSenderRequest(context: Context, items: List<MediaItem>): IntentSenderRequest? {
        if (items.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uris = items.map { Uri.parse(it.uri) }
            val pi = MediaStore.createWriteRequest(context.contentResolver, uris)
            return IntentSenderRequest.Builder(pi.intentSender).build()
        }
        return null
    }

    suspend fun renameMediaItem(context: Context, item: MediaItem, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                }
                val rows = context.contentResolver.update(Uri.parse(item.uri), values, null, null)
                if (rows > 0) return@withContext true

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && item.path.isNotEmpty()) {
                    val oldFile = File(item.path)
                    val newFile = File(oldFile.parentFile, newName)
                    if (oldFile.renameTo(newFile)) return@withContext true
                }
                false
            } catch (e: Exception) {
                false
            }
        }

    suspend fun performLegacyDelete(context: Context, items: List<MediaItem>): Int =
        withContext(Dispatchers.IO) {
            var count = 0
            for (item in items) {
                try {
                    if (item.path.isNotEmpty()) File(item.path).delete()
                    context.contentResolver.delete(Uri.parse(item.uri), null, null)
                    count++
                } catch (e: Exception) {
                    // تجاهل مقصود
                }
            }
            count
        }

    suspend fun copyItemToMediaStore(
        context: Context,
        item: MediaItem,
        targetRelativeFolder: String
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val isVideo = item.isVideo
            val collectionUri = collectionFor(context, isVideo)

            val fileName = if (item.path.isNotEmpty()) File(item.path).name else item.title
            val mimeType = if (isVideo) "video/*" else "audio/*"
            val relativeDir = if (isVideo) "Movies/$targetRelativeFolder" else "Music/$targetRelativeFolder"

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val newUri = resolver.insert(collectionUri, values) ?: return@withContext null

            resolver.openInputStream(Uri.parse(item.uri))?.use { input ->
                resolver.openOutputStream(newUri)?.use { output -> input.copyTo(output) }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(newUri, values, null, null)
            }
            newUri
        } catch (e: Exception) {
            null
        }
    }

    suspend fun copyItemToSafTree(
        context: Context,
        item: MediaItem,
        treeUri: Uri
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val pickedDir = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext null
            val mime = if (item.isVideo) "video/*" else "audio/*"
            val name = if (item.path.isNotEmpty()) File(item.path).name else item.title
            val newFileDoc = pickedDir.createFile(mime, name) ?: return@withContext null

            context.contentResolver.openInputStream(Uri.parse(item.uri))?.use { input ->
                context.contentResolver.openOutputStream(newFileDoc.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            newFileDoc.uri
        } catch (e: Exception) {
            null
        }
    }

    // =========================================================================
    //  أدوات استعادة ملفات "الخزينة السرية"
    // =========================================================================

    /**
     * إدخال ملف مفكوك التشفير إلى MediaStore العام:
     * Movies/LionVault للفيديو أو Music/LionVault للصوت.
     * يدعم Android 10+ عبر RELATIVE_PATH، والأجهزة الأقدم عبر DATA.
     */
    fun insertFileToMediaStore(
        context: Context,
        sourceFile: File,
        displayName: String,
        isVideo: Boolean
    ): Uri? {
        if (!sourceFile.exists()) return null
        return try {
            val resolver = context.contentResolver
            val collectionUri = collectionFor(context, isVideo)
            val mimeType = guessMimeType(displayName, isVideo)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, sanitize(displayName))
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val dir = if (isVideo) "Movies/$VAULT_RESTORE_FOLDER" else "Music/$VAULT_RESTORE_FOLDER"
                    put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val newUri = resolver.insert(collectionUri, values) ?: return null

            resolver.openOutputStream(newUri)?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: run {
                resolver.delete(newUri, null, null)
                return null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(newUri, values, null, null)
            } else {
                // Android 9 وأقل: نسخ مباشر إلى المسار العام
                val publicDir = if (isVideo) {
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_MOVIES
                    )
                } else {
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_MUSIC
                    )
                }
                val target = File(publicDir, VAULT_RESTORE_FOLDER)
                if (!target.exists()) target.mkdirs()
                val outFile = File(target, sanitize(displayName))
                sourceFile.inputStream().use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                val legacyValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, outFile.absolutePath)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sanitize(displayName))
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                }
                resolver.update(newUri, legacyValues, null, null)
            }
            newUri
        } catch (e: Exception) {
            null
        }
    }

    /** نسخ ملف مفكوك التشفير إلى مجلد SAF اختاره المستخدم (بطاقة SD مثلاً) */
    fun copyFileToSafTree(
        context: Context,
        sourceFile: File,
        treeUri: Uri,
        isVideo: Boolean
    ): Uri? {
        if (!sourceFile.exists()) return null
        return try {
            val pickedDir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val mime = guessMimeType(sourceFile.name, isVideo)
            val newFileDoc = pickedDir.createFile(mime, sanitize(sourceFile.name)) ?: return null
            context.contentResolver.openOutputStream(newFileDoc.uri)?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: return null
            newFileDoc.uri
        } catch (e: Exception) {
            null
        }
    }

    /** Intent مشاركة آمن (لا يُستخدم مع ملفات الخزينة) */
    fun buildShareIntent(context: Context, item: MediaItem): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = if (item.isVideo) "video/*" else "audio/*"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun collectionFor(context: Context, isVideo: Boolean): Uri {
        return if (isVideo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
        }
    }

    private fun guessMimeType(name: String, isVideo: Boolean): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val map = mapOf(
            "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
            "avi" to "video/x-msvideo", "mov" to "video/quicktime", "3gp" to "video/3gpp",
            "ts" to "video/mp2t", "m4v" to "video/x-m4v",
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac",
            "flac" to "audio/flac", "wav" to "audio/wav", "ogg" to "audio/ogg",
            "opus" to "audio/opus", "wma" to "audio/x-ms-wma"
        )
        return map[ext] ?: if (isVideo) "video/*" else "audio/*"
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "media_file" }
}
