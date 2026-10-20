package com.lionplayer.app.vault

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.lionplayer.app.model.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * ============================================================================
 *  إدارة ملفات الخزينة: التشفير / فك التشفير / الفهرس / ملف .nomedia
 * ----------------------------------------------------------------------------
 *  • المجلد المعزول : context.filesDir/lion_vault  (لا يصله أي تطبيق آخر)
 *  • التشفير        : EncryptedFile = AES-256-GCM-HKDF-4KB (مكتبة Tink)
 *  • الفهرس         : index.json داخل EncryptedSharedPreferences (مشفّر بالكامل)
 *  • الإخفاء        : ملف .nomedia داخل مجلدات التطبيق حتى لا تفهرسه MediaStore
 *  • الاستعادة      : فك التشفير ثم الإدراج في MediaStore (Movies/LionVault أو
 *                     Music/LionVault) أو إلى مجلد SAF يختاره المستخدم.
 * ============================================================================
 */
object VaultManager {

    private const val VAULT_DIR_NAME = "lion_vault"
    private const val DECRYPT_CACHE_DIR = "lion_vault_cache"
    private const val PREF_INDEX_KEY = "vault_index_v1"
    private const val BUFFER_SIZE = 64 * 1024

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** اسم المجلد الظاهر للعناصر القادمة من الخزينة داخل قائمة المشغل */
    const val VAULT_FOLDER_LABEL = "الخزينة السرية"

    // =========================================================================
    //  المسارات المعزولة
    // =========================================================================

    /** المجلد الداخلي الخاص بالتطبيق الذي تُحفظ فيه الملفات المشفّرة */
    fun vaultDir(context: Context): File {
        val dir = File(context.filesDir, VAULT_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        ensureNoMedia(dir)
        return dir
    }

    /** مجلد فك التشفير المؤقت (داخل cacheDir ويُمسح عند إعادة القفل) */
    fun decryptCacheDir(context: Context): File {
        val dir = File(context.cacheDir, DECRYPT_CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        ensureNoMedia(dir)
        return dir
    }

    /**
     * إنشاء ملف ‎.nomedia داخل المجلد: يضمن عدم فهرسة أي ملف (حتى لو سُرب المسار)
     * في معرض الوسائط أو أي مشغل آخر على الهاتف.
     */
    fun ensureNoMedia(dir: File): File {
        val noMedia = File(dir, ".nomedia")
        try {
            if (!noMedia.exists()) {
                noMedia.createNewFile()
                noMedia.writeText("Lion Player Secure Vault - do not index")
            }
        } catch (e: Exception) {
            // لا يفشل التدفق بسبب .nomedia
        }
        return noMedia
    }

    // =========================================================================
    //  ملف EncryptedFile
    // =========================================================================

    /**
     * بناء EncryptedFile (AES-256-GCM-HKDF-4KB عبر Tink):
     *  • API 23+  -> Builder(Context, File, MasterKey, scheme)
     *  • API 21/22 -> Builder(File, Context, keyAlias, scheme)
     */
    private fun buildEncryptedFile(context: Context, file: File): EncryptedFile {
        val appContext = context.applicationContext
        val keyMaterial = VaultSecurityManager.masterKeyMaterial(appContext)
        val builder = if (keyMaterial is MasterKey) {
            EncryptedFile.Builder(
                appContext,
                file,
                keyMaterial,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            )
        } else {
            @Suppress("DEPRECATION")
            EncryptedFile.Builder(
                file,
                appContext,
                keyMaterial as String,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            )
        }
        return builder.build()
    }

    // =========================================================================
    //  الفهرس المشفّر
    // =========================================================================

    fun listVaultFiles(context: Context): List<VaultFileItem> {
        return try {
            val raw = VaultSecurityManager.securePrefs(context)
                .getString(PREF_INDEX_KEY, null) ?: return emptyList()
            val array = JSONArray(raw)
            val list = ArrayList<VaultFileItem>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val name = o.optString("name")
                val fileName = o.optString("file")
                if (name.isEmpty() || fileName.isEmpty()) continue
                // تنظيف الملفات اليتيمة من الفهرس (محذوفة يدوياً من المجلد)
                if (!File(vaultDir(context), fileName).exists()) continue
                list.add(
                    VaultFileItem(
                        id = o.optString("id"),
                        displayName = name,
                        encryptedFileName = fileName,
                        sizeBytes = o.optLong("size"),
                        isVideo = o.optBoolean("video"),
                        originalFolder = o.optString("folder"),
                        addedAt = o.optLong("addedAt"),
                        originalUri = o.optString("uri")
                    )
                )
            }
            list.sortedByDescending { it.addedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(context: Context, items: List<VaultFileItem>) {
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("name", item.displayName)
                    put("file", item.encryptedFileName)
                    put("size", item.sizeBytes)
                    put("video", item.isVideo)
                    put("folder", item.originalFolder)
                    put("addedAt", item.addedAt)
                    put("uri", item.originalUri)
                }
            )
        }
        VaultSecurityManager.securePrefs(context)
            .edit()
            .putString(PREF_INDEX_KEY, array.toString())
            .apply()
    }

    private fun findItem(context: Context, id: String): VaultFileItem? =
        listVaultFiles(context).firstOrNull { it.id == id }

    // =========================================================================
    //  1) الإخفاء: نقل + تشفير ملف من الوسائط العامة إلى الخزينة
    // =========================================================================

    /**
     * يشفّر ملف وسائط (من MediaStore) داخل الخزينة.
     * @param deleteOriginal حذف الملف الأصلي من الهاتف بعد نجاح التشفير (نقل فعلي).
     */
    suspend fun lockMediaItem(
        context: Context,
        item: MediaItem,
        deleteOriginal: Boolean = true
    ): VaultResult<VaultFileItem> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val sourceUri = Uri.parse(item.uri)
            val id = UUID.randomUUID().toString()
            val safeName = sanitizeName(item.title.ifEmpty { "media_file" })
            val target = File(vaultDir(context), "${id.take(8)}.lv")
            if (target.exists()) target.delete()

            val encryptedFile = buildEncryptedFile(context, target)
            var written = 0L

            resolver.openInputStream(sourceUri)?.use { input ->
                encryptedFile.openFileOutput().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                    }
                    output.flush()
                }
            } ?: run {
                VaultSecurityManager.secureDelete(target)
                return@withContext VaultResult.Failure("تعذّر قراءة الملف الأصلي")
            }

            val vaultItem = VaultFileItem(
                id = id,
                displayName = safeName,
                encryptedFileName = target.name,
                sizeBytes = if (written > 0) written else item.size,
                isVideo = item.isVideo,
                originalFolder = item.parentFolder.ifEmpty { "الرئيسي" },
                addedAt = System.currentTimeMillis(),
                originalUri = item.uri
            )

            val all = listVaultFiles(context).toMutableList()
            all.add(0, vaultItem)
            saveIndex(context, all)

            if (deleteOriginal) {
                runCatching { resolver.delete(sourceUri, null, null) }
                runCatching {
                    if (item.path.isNotEmpty()) File(item.path).delete()
                }
            }
            VaultResult.Success(vaultItem)
        } catch (e: Exception) {
            VaultResult.Failure(e.localizedMessage ?: "فشل تشفير الملف")
        }
    }

    /**
     * يشفّر ملفاً من مزود مستندات عام (SAF / ACTION_OPEN_DOCUMENT) داخل الخزينة.
     */
    suspend fun lockFromUri(
        context: Context,
        uri: Uri,
        isVideo: Boolean,
        deleteOriginal: Boolean = false
    ): VaultResult<VaultFileItem> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val displayName = queryDisplayName(context, uri) ?: "vault_file_${System.currentTimeMillis()}"
            val id = UUID.randomUUID().toString()
            val target = File(vaultDir(context), "${id.take(8)}.lv")
            if (target.exists()) target.delete()

            val encryptedFile = buildEncryptedFile(context, target)
            var written = 0L

            resolver.openInputStream(uri)?.use { input ->
                encryptedFile.openFileOutput().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                    }
                    output.flush()
                }
            } ?: run {
                VaultSecurityManager.secureDelete(target)
                return@withContext VaultResult.Failure("تعذّر قراءة الملف المحدد")
            }

            val vaultItem = VaultFileItem(
                id = id,
                displayName = sanitizeName(displayName),
                encryptedFileName = target.name,
                sizeBytes = written,
                isVideo = isVideo,
                originalFolder = "مستند خارجي",
                addedAt = System.currentTimeMillis(),
                originalUri = uri.toString()
            )
            val all = listVaultFiles(context).toMutableList()
            all.add(0, vaultItem)
            saveIndex(context, all)

            if (deleteOriginal) runCatching { resolver.delete(uri, null, null) }
            VaultResult.Success(vaultItem)
        } catch (e: Exception) {
            VaultResult.Failure(e.localizedMessage ?: "فشل تشفير الملف")
        }
    }

    // =========================================================================
    //  2) فك التشفير المؤقت للتشغيل داخل التطبيق
    // =========================================================================

    /**
     * يفك تشفير الملف إلى مجلد cache المعزول (.nomedia) حتى يستطيع ExoPlayer تشغيله.
     * الملف المؤقت يُمسح عند الخروج من الخزينة أو إعادة قفلها.
     */
    suspend fun decryptToCache(context: Context, id: String): VaultResult<File> =
        withContext(Dispatchers.IO) {
            val item = findItem(context, id)
                ?: return@withContext VaultResult.Failure("الملف غير موجود في الخزينة")
            val encrypted = File(vaultDir(context), item.encryptedFileName)
            if (!encrypted.exists()) {
                return@withContext VaultResult.Failure("الملف المشفّر مفقود من المجلد")
            }
            val outDir = decryptCacheDir(context)
            val out = File(outDir, item.decryptedFileName)
            val tmp = File(outDir, "${item.decryptedFileName}.part")
            try {
                if (tmp.exists()) tmp.delete()
                buildEncryptedFile(context, encrypted).openFileInput().use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) {
                    tmp.inputStream().use { i -> FileOutputStream(out).use { i.copyTo(it) } }
                    tmp.delete()
                }
                VaultSession.touch()
                VaultResult.Success(out)
            } catch (e: Exception) {
                VaultSecurityManager.secureDelete(tmp)
                VaultResult.Failure(e.localizedMessage ?: "فشل فك التشفير")
            }
        }

    // =========================================================================
    //  3) إلغاء القفل: استعادة الملف إلى مجلد عام في الهاتف
    // =========================================================================

    /**
     * يفك التشفير ثم يُدرج الملف في MediaStore العام:
     * Movies/LionVault للفيديو أو Music/LionVault للصوت.
     * @return مسار/URI الملف المستعاد.
     */
    suspend fun restoreToPublicStorage(
        context: Context,
        id: String,
        removeFromVault: Boolean = true
    ): VaultResult<String> = withContext(Dispatchers.IO) {
        val decrypted = decryptToCache(context, id)
        if (decrypted is VaultResult.Failure) {
            return@withContext VaultResult.Failure(decrypted.message)
        }
        val file = (decrypted as VaultResult.Success).value
        try {
            val saved = com.lionplayer.app.util.StorageHelper.insertFileToMediaStore(
                context = context,
                sourceFile = file,
                displayName = findItem(context, id)?.displayName ?: file.name,
                isVideo = findItem(context, id)?.isVideo ?: true
            )
            if (saved != null) {
                if (removeFromVault) deleteVaultFile(context, id, stopPlayback = false)
                VaultResult.Success(saved.toString())
            } else {
                VaultResult.Failure("تعذّر حفظ الملف في ذاكرة الهاتف")
            }
        } catch (e: Exception) {
            VaultResult.Failure(e.localizedMessage ?: "فشل الاستعادة")
        }
    }

    /** فك التشفير إلى مجلد SAF يختاره المستخدم (مفيد لبطاقات SD) */
    suspend fun restoreToSafTree(
        context: Context,
        id: String,
        treeUri: Uri,
        removeFromVault: Boolean = true
    ): VaultResult<String> = withContext(Dispatchers.IO) {
        val decrypted = decryptToCache(context, id)
        if (decrypted is VaultResult.Failure) {
            return@withContext VaultResult.Failure(decrypted.message)
        }
        try {
            val saved = com.lionplayer.app.util.StorageHelper.copyFileToSafTree(
                context = context,
                sourceFile = (decrypted as VaultResult.Success).value,
                treeUri = treeUri,
                isVideo = findItem(context, id)?.isVideo ?: true
            )
            if (saved != null) {
                if (removeFromVault) deleteVaultFile(context, id, stopPlayback = false)
                VaultResult.Success(saved.toString())
            } else {
                VaultResult.Failure("تعذّر الحفظ في المجلد المحدد")
            }
        } catch (e: Exception) {
            VaultResult.Failure(e.localizedMessage ?: "فشل الاستعادة إلى SAF")
        }
    }

    // =========================================================================
    //  الحذف / التنظيف / القفل
    // =========================================================================

    fun deleteVaultFile(context: Context, id: String, stopPlayback: Boolean = true) {
        findItem(context, id)?.let { item ->
            if (stopPlayback) stopPlaybackIfVaultItem(context, id)
            VaultSecurityManager.secureDelete(File(vaultDir(context), item.encryptedFileName))
            VaultSecurityManager.secureDelete(File(decryptCacheDir(context), item.decryptedFileName))
            val all = listVaultFiles(context).toMutableList()
            all.removeAll { it.id == id }
            saveIndex(context, all)
        }
    }

    fun clearAllVaultFiles(context: Context) {
        stopVaultPlayback(context)
        try {
            vaultDir(context).listFiles()?.forEach { it.delete() }
            decryptCacheDir(context).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            // تجاهل مقصود
        }
        saveIndex(context, emptyList())
    }

    /**
     * إعادة قفل الخزينة: إنهاء أي تشغيل قادم من الخزينة + مسح النسخ المفكوكة
     * من الذاكرة المؤقتة + مسح ذاكرة Coil المصغّرات من القرص.
     */
    fun lockVault(context: Context) {
        stopVaultPlayback(context)
        wipeDecryptedCache(context)
        VaultSession.lock()
    }

    fun wipeDecryptedCache(context: Context) {
        try {
            decryptCacheDir(context).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            // تجاهل مقصود
        }
        // مسح المصغّرات المخزّنة (Coil) حتى لا تبقى آثار مرئية لملفات الخزينة
        ioScope.launch {
            runCatching { coil.Coil.imageLoader(context).diskCache?.clear() }
            runCatching { coil.Coil.imageLoader(context).memoryCache?.clear() }
        }
    }

    private fun stopPlaybackIfVaultItem(context: Context, id: String) {
        val current = com.lionplayer.app.player.LionPlayerManager.getCurrentItem()
        if (current != null && current.uri.contains("vault_") && current.id == vaultIdToMediaId(id)) {
            stopVaultPlayback(context)
        }
    }

    private fun stopVaultPlayback(context: Context) {
        val current = com.lionplayer.app.player.LionPlayerManager.getCurrentItem()
        if (current != null && current.parentFolder == VAULT_FOLDER_LABEL) {
            com.lionplayer.app.player.LionPlayerManager.stop(context)
        }
    }

    // =========================================================================
    //  أدوات مساعدة
    // =========================================================================

    /** معرّف MediaItem المستخدم لعناصر الخزينة داخل المشغل */
    fun vaultIdToMediaId(id: String): Long {
        var hash = 1125899906842597L // عدد أولي
        for (c in id) hash = 31 * hash + c.code
        return -kotlin.math.abs(hash)
    }

    fun sanitizeName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifEmpty { "vault_file" }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        }
    }

    /** حجم الخزينة الكلي على القرص */
    fun totalVaultSize(context: Context): Long {
        return try {
            vaultDir(context).listFiles()?.sumOf { it.length() } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** مسار المجلد المعزول (لعرضه للمستخدم كتأكيد على العزل) */
    fun vaultPath(context: Context): String = vaultDir(context).absolutePath

    /** فحص سريع لقابلية فك التشفير (يُستخدم لعرض حالة التشفير) */
    fun isFileIntact(context: Context, item: VaultFileItem): Boolean {
        val f = File(vaultDir(context), item.encryptedFileName)
        return f.exists() && f.length() > 0
    }

    /** نسخة FileInputStream مساعدة للاختبارات/التشخيص */
    fun openRawEncrypted(context: Context, item: VaultFileItem): FileInputStream? {
        val f = File(vaultDir(context), item.encryptedFileName)
        return if (f.exists()) FileInputStream(f) else null
    }
}
