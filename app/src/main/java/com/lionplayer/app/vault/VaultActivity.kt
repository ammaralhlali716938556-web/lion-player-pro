package com.lionplayer.app.vault

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.lionplayer.app.R
import com.lionplayer.app.model.MediaItem
import com.lionplayer.app.player.LionPlayerManager
import com.lionplayer.app.ui.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ============================================================================
 *  شاشة الخزينة السرية (داخل القفل)
 * ----------------------------------------------------------------------------
 *  • FLAG_SECURE: منع لقطات الشاشة وتسجيل الشاشة.
 *  • عرض الملفات المشفّرة + تشغيلها بعد فك تشفير مؤقت داخل الذاكرة المعزولة.
 *  • زر "استعادة إلى الذاكرة": يفك التشفير ويعيد الملف إلى مجلد عام في الهاتف
 *    (Movies/LionVault أو Music/LionVault) أو إلى مجلد SAF يختاره المستخدم.
 *  • إعادة القفل تمسح كل النسخ المفكوكة وتُنهي أي تشغيل.
 * ============================================================================
 */
class VaultActivity : AppCompatActivity() {

    private lateinit var rvFiles: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvCount: TextView
    private lateinit var tvStorage: TextView
    private lateinit var toolbar: MaterialToolbar

    private lateinit var adapter: VaultAdapter

    private var pendingRestoreItem: VaultFileItem? = null

    /** منتقي ملفات عام (SAF) لإضافة ملفات من خارج MediaStore */
    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) encryptPickedUris(uris)
    }

    /** منتقي مجلد SAF للاستعادة */
    private val safTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { contentResolver.takePersistableUriPermission(treeUri, flags) }
            pendingRestoreItem?.let { restoreToSaf(it, treeUri) }
            pendingRestoreItem = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ===== حماية الشاشة داخل قسم الخزينة =====
        applySecureFlag()

        setContentView(R.layout.activity_vault)

        toolbar = findViewById(R.id.vaultToolbar)
        rvFiles = findViewById(R.id.rvVaultFiles)
        emptyState = findViewById(R.id.vaultEmptyState)
        progressBar = findViewById(R.id.vaultProgress)
        tvCount = findViewById(R.id.tvVaultFilesCount)
        tvStorage = findViewById(R.id.tvVaultStorageInfo)

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { onMenuItem(it.itemId) }

        adapter = VaultAdapter(
            items = emptyList(),
            onPlay = { playVaultItem(it) },
            onRestore = { askRestoreDestination(it) },
            onDelete = { confirmDelete(it) },
            onMore = { item, anchor -> showItemMenu(item, anchor) }
        )
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = adapter

        findViewById<ExtendedFloatingActionButton>(R.id.fabAddToVault)
            .setOnClickListener { openFilePicker() }

        VaultSecurityManager.getAutoLockMinutes(this)

        if (intent.getBooleanExtra(EXTRA_OPEN_PICKER, false)) {
            intent.removeExtra(EXTRA_OPEN_PICKER)
            rvFiles.post { openFilePicker() }
        }
    }

    private fun applySecureFlag() {
        if (VaultSecurityManager.isSecureWindowEnabled(this)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onStart() {
        super.onStart()
        // أي وصول بدون جلسة مفتوحة يُرجع المستخدم إلى شاشة القفل
        if (!VaultSession.isUnlocked) {
            startActivity(Intent(this, VaultLockActivity::class.java))
            finish()
            return
        }
        VaultSession.touch()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        VaultSession.touch()
    }

    // =========================================================================
    //  القائمة
    // =========================================================================

    private fun refreshList() {
        val items = VaultManager.listVaultFiles(this)
        adapter.submit(items)
        emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rvFiles.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        tvCount.text = "${getString(R.string.vault_files_count)}: ${items.size}"
        tvStorage.text = "الحجم المشفّر: ${VaultManager.formatSize(VaultManager.totalVaultSize(this))}  •  " +
            "قفل تلقائي بعد ${VaultSession.autoLockMinutes} دقيقة"
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        rvFiles.alpha = if (loading) 0.4f else 1f
    }

    // =========================================================================
    //  إضافة ملفات جديدة إلى الخزينة
    // =========================================================================

    private fun openFilePicker() {
        runCatching {
            pickFilesLauncher.launch(arrayOf("video/*", "audio/*"))
        }.onFailure {
            Toast.makeText(this, "تعذّر فتح منتقي الملفات", Toast.LENGTH_SHORT).show()
        }
    }

    private fun encryptPickedUris(uris: List<Uri>) {
        setLoading(true)
        lifecycleScope.launch {
            var success = 0
            var failure = 0
            for (uri in uris) {
                val isVideo = guessIsVideo(uri)
                when (val result = VaultManager.lockFromUri(this@VaultActivity, uri, isVideo)) {
                    is VaultResult.Success -> success++
                    is VaultResult.Failure -> {
                        failure++
                        if (failure == 1) toast(result.message)
                    }
                }
            }
            setLoading(false)
            refreshList()
            toast("تم تشفير $success ملف داخل الخزينة" + if (failure > 0) " — فشل $failure" else "")
        }
    }

    private fun guessIsVideo(uri: Uri): Boolean {
        val type = runCatching { contentResolver.getType(uri) }.getOrNull()
        if (type != null) return type.startsWith("video", ignoreCase = true)
        val name = uri.lastPathSegment.orEmpty().lowercase()
        return VIDEO_EXTENSIONS.any { name.endsWith(".$it") }
    }

    // =========================================================================
    //  التشغيل الآمن داخل التطبيق
    // =========================================================================

    private fun playVaultItem(item: VaultFileItem) {
        VaultSession.touch()
        setLoading(true)
        lifecycleScope.launch {
            when (val result = VaultManager.decryptToCache(this@VaultActivity, item.id)) {
                is VaultResult.Success -> {
                    setLoading(false)
                    val file = result.value
                    val mediaItem = MediaItem(
                        id = VaultManager.vaultIdToMediaId(item.id),
                        title = item.displayName,
                        uri = Uri.fromFile(file).toString(),
                        duration = 0L,
                        size = item.sizeBytes,
                        dateAdded = item.addedAt / 1000L,
                        isVideo = item.isVideo,
                        parentFolder = VaultManager.VAULT_FOLDER_LABEL,
                        artist = "الخزينة السرية",
                        album = "ملف مشفّر",
                        path = file.absolutePath
                    )
                    val playlist = ArrayList(listOf(mediaItem))
                    val intent = Intent(this@VaultActivity, PlayerActivity::class.java).apply {
                        putExtra("PLAYLIST", playlist)
                        putExtra("CURRENT_INDEX", 0)
                        putExtra(PlayerActivity.EXTRA_FROM_VAULT, true)
                    }
                    startActivity(intent)
                }
                is VaultResult.Failure -> {
                    setLoading(false)
                    toast(result.message)
                }
            }
        }
    }

    // =========================================================================
    //  إلغاء القفل / الاستعادة إلى مجلد عام
    // =========================================================================

    private fun askRestoreDestination(item: VaultFileItem) {
        val options = arrayOf(
            if (item.isVideo) "استعادة إلى Movies/LionVault" else "استعادة إلى Music/LionVault",
            "استعادة إلى مجلد أختاره (SAF / بطاقة SD)"
        )
        AlertDialog.Builder(this, R.style.VaultTheme)
            .setTitle(R.string.vault_restore)
            .setMessage("سيُفك تشفير \"${item.displayName}\" ويُحفظ في ذاكرة الهاتف العامة.\n" +
                "هل تريد إبقاء نسخة داخل الخزينة؟")
            .setItems(options) { _, which ->
                if (which == 0) restoreToMediaStore(item)
                else {
                    pendingRestoreItem = item
                    runCatching { safTreeLauncher.launch(null) }
                        .onFailure { toast("تعذّر فتح منتقي المجلدات") }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun restoreToMediaStore(item: VaultFileItem) {
        setLoading(true)
        lifecycleScope.launch {
            when (val result = VaultManager.restoreToPublicStorage(
                context = this@VaultActivity,
                id = item.id,
                removeFromVault = true
            )) {
                is VaultResult.Success -> {
                    setLoading(false)
                    refreshList()
                    toast("تمت استعادة \"${item.displayName}\" إلى ذاكرة الهاتف ✔")
                }
                is VaultResult.Failure -> {
                    setLoading(false)
                    toast(result.message)
                }
            }
        }
    }

    private fun restoreToSaf(item: VaultFileItem, treeUri: Uri) {
        setLoading(true)
        lifecycleScope.launch {
            when (val result = VaultManager.restoreToSafTree(
                context = this@VaultActivity,
                id = item.id,
                treeUri = treeUri,
                removeFromVault = true
            )) {
                is VaultResult.Success -> {
                    setLoading(false)
                    refreshList()
                    toast("تمت الاستعادة إلى المجلد المحدد ✔")
                }
                is VaultResult.Failure -> {
                    setLoading(false)
                    toast(result.message)
                }
            }
        }
    }

    private fun restoreAll() {
        val items = VaultManager.listVaultFiles(this)
        if (items.isEmpty()) {
            toast("لا توجد ملفات داخل الخزينة")
            return
        }
        AlertDialog.Builder(this, R.style.VaultTheme)
            .setTitle(R.string.vault_restore)
            .setMessage("سيتم فك تشفير ${items.size} ملف وإعادتها إلى ذاكرة الهاتف ثم حذفها من الخزينة. المتابعة؟")
            .setPositiveButton("استعادة الكل") { _, _ ->
                setLoading(true)
                lifecycleScope.launch {
                    var ok = 0
                    for (item in items) {
                        val result = VaultManager.restoreToPublicStorage(
                            this@VaultActivity, item.id, true
                        )
                        if (result is VaultResult.Success) ok++
                    }
                    setLoading(false)
                    refreshList()
                    toast("تمت استعادة $ok ملف بنجاح")
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // =========================================================================
    //  الحذف وإعادة القفل وإدارة الرمز
    // =========================================================================

    private fun confirmDelete(item: VaultFileItem) {
        AlertDialog.Builder(this, R.style.VaultTheme)
            .setTitle(R.string.vault_delete)
            .setMessage("سيُحذف \"${item.displayName}\" نهائياً من الخزينة (الملف مشفّر ولا يمكن استعادته).")
            .setPositiveButton("حذف نهائي") { _, _ ->
                VaultManager.deleteVaultFile(this, item.id)
                refreshList()
                toast("تم الحذف من الخزينة")
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showItemMenu(item: VaultFileItem, anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "تشغيل داخل التطبيق")
        menu.menu.add(0, 2, 1, getString(R.string.vault_restore))
        menu.menu.add(0, 3, 2, "خصائص الملف")
        menu.menu.add(0, 4, 3, getString(R.string.vault_delete))
        menu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> playVaultItem(item)
                2 -> askRestoreDestination(item)
                3 -> showProperties(item)
                4 -> confirmDelete(item)
            }
            true
        }
        menu.show()
    }

    private fun showProperties(item: VaultFileItem) {
        val message = """
            الاسم: ${item.displayName}
            النوع: ${if (item.isVideo) "فيديو" else "صوتيات"}
            الحجم: ${VaultManager.formatSize(item.sizeBytes)}
            الملف المشفّر: ${item.encryptedFileName}
            المجلد الأصلي: ${item.originalFolder}
            التشفير: AES-256-GCM-HKDF-4KB (Tink)
            المجلد المعزول: ${VaultManager.vaultPath(this)}
            .nomedia: ${if (java.io.File(VaultManager.vaultDir(this), ".nomedia").exists()) "موجود ✔" else "غير موجود"}
        """.trimIndent()

        AlertDialog.Builder(this, R.style.VaultTheme)
            .setTitle("خصائص الملف المشفّر")
            .setMessage(message)
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun onMenuItem(itemId: Int): Boolean {
        when (itemId) {
            R.id.action_vault_add -> openFilePicker()
            R.id.action_vault_relock -> relock()
            R.id.action_vault_change_pin -> showChangePinDialog()
            R.id.action_vault_auto_lock -> showAutoLockDialog()
            R.id.action_vault_restore_all -> restoreAll()
            else -> return false
        }
        return true
    }

    private fun showChangePinDialog() {
        val oldPin = android.widget.EditText(this).apply {
            hint = "رمز PIN الحالي"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }
        val newPin = android.widget.EditText(this).apply {
            hint = getString(R.string.vault_new_pin)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }
        val confirm = android.widget.EditText(this).apply {
            hint = getString(R.string.vault_confirm_pin)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
            addView(oldPin); addView(newPin); addView(confirm)
        }

        AlertDialog.Builder(this, R.style.VaultTheme)
            .setTitle(R.string.vault_change_pin)
            .setView(container)
            .setPositiveButton("حفظ") { _, _ ->
                val o = oldPin.text?.toString().orEmpty()
                val n = newPin.text?.toString().orEmpty()
                val c = confirm.text?.toString().orEmpty()
                if (n.length !in VaultSecurityManager.MIN_PIN_LENGTH..VaultSecurityManager.MAX_PIN_LENGTH) {
                    toast("رمز PIN يجب أن يكون من ${VaultSecurityManager.MIN_PIN_LENGTH} إلى ${VaultSecurityManager.MAX_PIN_LENGTH} أرقام")
                    return@setPositiveButton
                }
                if (n != c) {
                    toast("رمزا PIN غير متطابقين")
                    return@setPositiveButton
                }
                when (val result = VaultSecurityManager.changePin(this, o, n)) {
                    is VaultResult.Success -> toast("تم تغيير رمز PIN بنجاح ✔")
                    is VaultResult.Failure -> toast(result.message)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showAutoLockDialog() {
        val labels = arrayOf("دقيقة واحدة", "5 دقائق", "10 دقائق", "30 دقيقة", "60 دقيقة")
        val values = intArrayOf(1, 5, 10, 30, 60)
        val current = values.indexOf(VaultSession.autoLockMinutes).coerceAtLeast(1)
        AlertDialog.Builder(this, R.style.VaultTheme)
            .setTitle(R.string.vault_auto_lock)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                VaultSecurityManager.setAutoLockMinutes(this, values[which])
                refreshList()
                toast("القفل التلقائي بعد ${labels[which]}")
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun relock() {
        VaultManager.lockVault(this)
        toast("تم إعادة قفل الخزينة 🔒")
        startActivity(Intent(this, VaultLockActivity::class.java))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // الخروج من الخزينة = إعادة قفلها ومسح النسخ المفكوكة
        relock()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_OPEN_PICKER = "extra_open_picker"

        private val VIDEO_EXTENSIONS = listOf(
            "mp4", "mkv", "webm", "avi", "mov", "3gp", "ts", "m4v", "flv", "wmv"
        )
    }
}
