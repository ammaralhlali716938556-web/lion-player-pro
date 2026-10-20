package com.lionplayer.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.lionplayer.app.R
import com.lionplayer.app.adapter.MediaAdapter
import com.lionplayer.app.adapter.loadMediaThumbnail
import com.lionplayer.app.databinding.ActivityMainBinding
import com.lionplayer.app.model.MediaItem
import com.lionplayer.app.player.LionPlayerManager
import com.lionplayer.app.util.StorageHelper
import com.lionplayer.app.vault.VaultActivity
import com.lionplayer.app.vault.VaultLockActivity
import com.lionplayer.app.vault.VaultManager
import com.lionplayer.app.vault.VaultSecurityManager
import com.lionplayer.app.viewmodel.MediaUiState
import com.lionplayer.app.viewmodel.MediaViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * الشاشة الرئيسية: تصفح الوسائط + التكامل مع "الخزينة السرية".
 * (القائمة الرئيسية نفسها لا تحمل FLAG_SECURE — الحماية محصورة داخل قسم الخزينة
 *  وشاشة المشغل أثناء تشغيل ملف محمي).
 */
class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQ_CODE = 101

    private val viewModel: MediaViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaAdapter: MediaAdapter
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var prefs: SharedPreferences

    enum class ClipAction { NONE, COPY, MOVE }
    private var activeClipAction = ClipAction.NONE
    private val clipBufferList = mutableListOf<MediaItem>()

    private var pendingDeleteItems: List<MediaItem> = emptyList()
    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onItemsDeleted(pendingDeleteItems)
            Toast.makeText(this, "تم الحذف بنجاح", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "تم إلغاء عملية الحذف", Toast.LENGTH_SHORT).show()
        }
        pendingDeleteItems = emptyList()
    }

    private var pendingRenameItem: MediaItem? = null
    private var pendingRenameNewName: String = ""
    private val writeLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingRenameItem?.let { item ->
                lifecycleScope.launch {
                    val success = StorageHelper.renameMediaItem(this@MainActivity, item, pendingRenameNewName)
                    if (success) {
                        viewModel.onItemRenamed(item, pendingRenameNewName)
                        Toast.makeText(this@MainActivity, "تمت إعادة التسمية بنجاح", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "تعذر حفظ الاسم الجديد", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(this, "تم رفض الإذن", Toast.LENGTH_SHORT).show()
        }
        pendingRenameItem = null
        pendingRenameNewName = ""
    }

    private val safTreePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(treeUri, flags)
            } catch (e: Exception) {
                // تجاهل مقصود
            }
            executeSafPaste(treeUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSystemBars()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("lion_player_prefs", Context.MODE_PRIVATE)
        LionPlayerManager.bluetoothControlEnabled = prefs.getBoolean("bluetooth_headset_control", true)
        VaultSecurityManager.getAutoLockMinutes(this)

        setSupportActionBar(binding.topToolbar)
        setupRecyclerView()
        setupBottomNav()
        initSelectionBar()
        initPasteBar()
        initMiniPlayer()
        observeViewModel()
        checkAndRequestPermissions()
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> renderUiState(state) }
            }
        }
    }

    private fun renderUiState(state: MediaUiState) {
        binding.topToolbar.title = state.title

        val lm = when (state.viewStyle) {
            MediaAdapter.VIEW_MODE_LIST -> LinearLayoutManager(this)
            MediaAdapter.VIEW_MODE_GRID_LARGE -> GridLayoutManager(this, 2)
            MediaAdapter.VIEW_MODE_GRID_SMALL -> GridLayoutManager(this, 3)
            else -> LinearLayoutManager(this)
        }
        if (binding.rvMedia.layoutManager?.javaClass != lm.javaClass) {
            binding.rvMedia.layoutManager = lm
        }

        mediaAdapter.isSelectionMode = state.isSelectionMode
        mediaAdapter.updateData(state.displayedItems, state.viewStyle, state.showExtensions)
        binding.tvEmptyState.visibility =
            if (state.displayedItems.isEmpty() && !state.isLoading) View.VISIBLE else View.GONE

        if (state.isSelectionMode) {
            binding.selectionBar.visibility = View.VISIBLE
            binding.tvSelectionCount.text = "تم تحديد ${state.selectedCount}"
        } else {
            binding.selectionBar.visibility = View.GONE
        }

        val targetMenuId = when (state.currentTab) {
            MediaViewModel.TAB_VIDEO -> R.id.nav_local
            MediaViewModel.TAB_AUDIO -> R.id.nav_music
            MediaViewModel.TAB_ALL -> R.id.nav_all
            MediaViewModel.TAB_FAVORITES -> R.id.nav_favorites
            else -> R.id.nav_local
        }
        if (binding.bottomNavigation.selectedItemId != targetMenuId) {
            binding.bottomNavigation.selectedItemId = targetMenuId
        }

        updatePasteBarUI()
    }

    private fun initSelectionBar() {
        binding.btnCancelSelection.setOnClickListener { viewModel.clearSelection() }

        binding.btnFavoriteSelected.setOnClickListener {
            val selected = selectedItems()
            if (selected.isNotEmpty()) viewModel.toggleFavoriteMultiple(selected)
        }

        binding.btnSelectAll.setOnClickListener {
            val allSelected = viewModel.uiState.value.displayedItems.filter { !it.isFolder }
                .all { it.isSelected }
            viewModel.selectAll(!allSelected)
        }

        binding.btnCopySelected.setOnClickListener { startClipboardOperation(ClipAction.COPY) }
        binding.btnMoveSelected.setOnClickListener { startClipboardOperation(ClipAction.MOVE) }

        // 🔐 نقل الملفات المحددة إلى الخزينة السرية المشفّرة
        binding.btnLockSelected.setOnClickListener { confirmLockInVault(selectedItems()) }

        binding.btnDeleteSelected.setOnClickListener {
            val selected = selectedItems()
            if (selected.isNotEmpty()) confirmDeleteMultiple(selected)
        }
    }

    private fun selectedItems(): List<MediaItem> =
        viewModel.uiState.value.displayedItems.filter { it.isSelected && !it.isFolder }

    private fun initPasteBar() {
        binding.btnExecutePaste.setOnClickListener { executeScopedPaste() }
        binding.btnSafPaste.setOnClickListener { safTreePickerLauncher.launch(null) }
        binding.btnCancelPaste.setOnClickListener { cancelPasteOperation() }
    }

    private fun startClipboardOperation(action: ClipAction) {
        val selected = selectedItems()
        if (selected.isEmpty()) {
            Toast.makeText(this, "يرجى تحديد مقطع واحد على الأقل", Toast.LENGTH_SHORT).show()
            return
        }
        clipBufferList.clear()
        clipBufferList.addAll(selected)
        activeClipAction = action

        viewModel.clearSelection()
        updatePasteBarUI()
        val actionName = if (action == ClipAction.COPY) "نسخ" else "نقل"
        Toast.makeText(
            this,
            "تم اختيار $actionName (${clipBufferList.size}) مقاطع. اختر 'لصق هنا' أو 'مجلد SAF'",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun updatePasteBarUI() {
        if (activeClipAction != ClipAction.NONE && clipBufferList.isNotEmpty()) {
            binding.pasteBar.visibility = View.VISIBLE
            val typeStr = if (activeClipAction == ClipAction.COPY) "نسخ" else "نقل"
            val destFolder = viewModel.uiState.value.currentFolder ?: "LionPlayer"
            binding.tvPasteStatus.text = "جاهز لـ $typeStr (${clipBufferList.size}) عناصر إلى: $destFolder"
        } else {
            binding.pasteBar.visibility = View.GONE
        }
    }

    private fun cancelPasteOperation() {
        activeClipAction = ClipAction.NONE
        clipBufferList.clear()
        updatePasteBarUI()
        Toast.makeText(this, "تم إلغاء عملية النسخ/النقل", Toast.LENGTH_SHORT).show()
    }

    private fun executeScopedPaste() {
        if (clipBufferList.isEmpty() || activeClipAction == ClipAction.NONE) return
        val targetFolder = viewModel.uiState.value.currentFolder ?: "LionPlayer"
        val itemsToProcess = ArrayList(clipBufferList)
        val action = activeClipAction
        val actionName = if (action == ClipAction.COPY) "نسخ" else "نقل"

        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "جاري $actionName الملفات عبر Scoped Storage...", Toast.LENGTH_SHORT).show()
            var successCount = 0
            for (item in itemsToProcess) {
                val resultUri = StorageHelper.copyItemToMediaStore(this@MainActivity, item, targetFolder)
                if (resultUri != null) successCount++
            }
            if (action == ClipAction.MOVE && successCount > 0) performDelete(itemsToProcess)
            Toast.makeText(
                this@MainActivity,
                "تم $actionName $successCount ملفات بنجاح إلى: $targetFolder",
                Toast.LENGTH_LONG
            ).show()
            cancelPasteOperation()
            viewModel.loadMedia()
        }
    }

    private fun executeSafPaste(treeUri: Uri) {
        if (clipBufferList.isEmpty() || activeClipAction == ClipAction.NONE) return
        val itemsToProcess = ArrayList(clipBufferList)
        val action = activeClipAction
        val actionName = if (action == ClipAction.COPY) "نسخ" else "نقل"

        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "جاري $actionName الملفات عبر SAF...", Toast.LENGTH_SHORT).show()
            var successCount = 0
            for (item in itemsToProcess) {
                val resultUri = StorageHelper.copyItemToSafTree(this@MainActivity, item, treeUri)
                if (resultUri != null) successCount++
            }
            if (action == ClipAction.MOVE && successCount > 0) performDelete(itemsToProcess)
            Toast.makeText(this@MainActivity, "تم $actionName $successCount ملفات بنجاح عبر SAF", Toast.LENGTH_LONG).show()
            cancelPasteOperation()
            viewModel.loadMedia()
        }
    }

    private fun initMiniPlayer() {
        binding.miniPlayerInfo.setOnClickListener { openPlayerFromMini() }
        binding.ivMiniThumb.setOnClickListener { openPlayerFromMini() }

        binding.btnMiniPrev.setOnClickListener { LionPlayerManager.playPrevious(this) }
        binding.btnMiniPlayPause.setOnClickListener { LionPlayerManager.togglePlayPause(this) }
        binding.btnMiniNext.setOnClickListener { LionPlayerManager.playNext(this) }
        binding.btnMiniClose.setOnClickListener {
            LionPlayerManager.stop(this)
            binding.miniPlayerLayout.visibility = View.GONE
        }
    }

    private fun openPlayerFromMini() {
        LionPlayerManager.getCurrentItem() ?: return
        val playlist = LionPlayerManager.playlist
        val index = LionPlayerManager.currentIndex
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("PLAYLIST", playlist)
            putExtra("CURRENT_INDEX", index)
        }
        startActivity(intent)
    }

    private fun updateMiniPlayerUI() {
        val current = LionPlayerManager.getCurrentItem()
        val player = LionPlayerManager.player
        if (current != null && player != null) {
            binding.miniPlayerLayout.visibility = View.VISIBLE
            binding.tvMiniTitle.text = current.title
            binding.tvMiniSub.text = if (current.isVideo) {
                "فيديو • ${current.parentFolder}"
            } else {
                "صوتيات • ${current.artist}"
            }
            binding.ivMiniThumb.loadMediaThumbnail(current)
            val isPlaying = player.isPlaying
            binding.btnMiniPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        } else {
            binding.miniPlayerLayout.visibility = View.GONE
        }
    }

    private fun setupRecyclerView() {
        mediaAdapter = MediaAdapter(
            viewModel.uiState.value.displayedItems,
            viewModel.uiState.value.viewStyle,
            viewModel.uiState.value.showExtensions,
            false,
            onItemClick = { item ->
                if (mediaAdapter.isSelectionMode) {
                    if (!item.isFolder) viewModel.toggleItemSelection(item)
                } else {
                    if (item.isFolder) {
                        viewModel.selectFolder(item.title)
                    } else {
                        viewModel.markItemAsPlayed(item.id)
                        val playlist = ArrayList(viewModel.uiState.value.displayedItems.filter { !it.isFolder })
                        val index = playlist.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                        val intent = Intent(this, PlayerActivity::class.java).apply {
                            putExtra("PLAYLIST", playlist)
                            putExtra("CURRENT_INDEX", index)
                        }
                        startActivity(intent)
                    }
                }
            },
            onItemLongClick = { item -> viewModel.startSelectionMode(item) },
            onMoreOptionsClick = { item, _ -> showItemActionDialog(item) }
        )
        binding.rvMedia.adapter = mediaAdapter

        var lastScaleTriggerTime = 0L
        scaleGestureDetector = ScaleGestureDetector(this, object :
            ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val now = System.currentTimeMillis()
                if (now - lastScaleTriggerTime < 350) return false

                val factor = detector.scaleFactor
                if (factor > 1.15f) {
                    lastScaleTriggerTime = now
                    zoomIn()
                    return true
                } else if (factor < 0.85f) {
                    lastScaleTriggerTime = now
                    zoomOut()
                    return true
                }
                return false
            }
        })

        binding.rvMedia.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun showItemActionDialog(item: MediaItem) {
        val favoriteTitle = if (item.isFavorite) "إلغاء التفضيل" else "تفضيل"
        val options = arrayOf(
            "تحديد", "نسخ", "نقل", favoriteTitle,
            "🔐 إخفاء في الخزينة السرية",
            "إعادة تسمية", "مشاركة", "خصائص", "حذف"
        )

        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.startSelectionMode(item)
                    1 -> {
                        clipBufferList.clear()
                        clipBufferList.add(item)
                        activeClipAction = ClipAction.COPY
                        updatePasteBarUI()
                        Toast.makeText(this, "تم نسخ المقطع. اضغط 'لصق هنا' أو 'مجلد SAF'", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        clipBufferList.clear()
                        clipBufferList.add(item)
                        activeClipAction = ClipAction.MOVE
                        updatePasteBarUI()
                        Toast.makeText(this, "تم تحديد المقطع للنقل. اضغط 'لصق هنا' أو 'مجلد SAF'", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        val isFav = viewModel.toggleFavorite(item)
                        val msg = if (isFav) "تمت الإضافة إلى المفضلة ❤" else "تمت الإزالة من المفضلة"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                    4 -> confirmLockInVault(listOf(item))
                    5 -> showRenameDialog(item)
                    6 -> shareMediaItem(item)
                    7 -> showPropertiesDialog(item)
                    8 -> confirmDeleteMediaItem(item)
                }
            }
            .show()
    }

    // =========================================================================
    //  🔐 الخزينة السرية
    // =========================================================================

    private fun confirmLockInVault(items: List<MediaItem>) {
        if (items.isEmpty()) {
            Toast.makeText(this, "يرجى تحديد مقطع واحد على الأقل", Toast.LENGTH_SHORT).show()
            return
        }
        if (!VaultSecurityManager.isVaultEnabled(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.vault_name)
                .setMessage("الخزينة السرية غير مفعّلة بعد. سيتم فتح شاشة الإعداد لتعيين رمز PIN وسؤال الأمان.")
                .setPositiveButton("تفعيل الخزينة") { _, _ -> openVault() }
                .setNegativeButton("إلغاء", null)
                .show()
            return
        }

        val names = if (items.size == 1) items[0].title else "${items.size} ملفات"
        AlertDialog.Builder(this)
            .setTitle("🔐 إخفاء في الخزينة السرية")
            .setMessage(
                "سيتم تشفير \"$names\" بـ AES-256-GCM ونقلها إلى مجلد التطبيق المعزول، " +
                    "ثم حذف النسخة الأصلية من ذاكرة الهاتف.\n" +
                    "الملف لن يظهر في المعرض أو أي مشغل آخر (.nomedia)."
            )
            .setPositiveButton("تشفير وإخفاء") { _, _ -> executeLockInVault(items) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun executeLockInVault(items: List<MediaItem>) {
        Toast.makeText(this, "جاري تشفير الملفات داخل الخزينة...", Toast.LENGTH_SHORT).show()
        viewModel.lockItemsInVault(items) { success, error ->
            if (success > 0) {
                Toast.makeText(this, "تم إخفاء وتشفير $success ملف داخل الخزينة 🔐", Toast.LENGTH_LONG).show()
            }
            if (error != null) {
                Toast.makeText(this, "خطأ: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openVault() {
        // البوابة دائماً عبر شاشة القفل (PIN / بصمة)
        startActivity(Intent(this, VaultLockActivity::class.java))
    }

    // =========================================================================

    private fun showRenameDialog(item: MediaItem) {
        val input = EditText(this).apply {
            setText(item.title)
            setSelection(item.title.length)
        }
        AlertDialog.Builder(this)
            .setTitle("إعادة تسمية")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        val success = StorageHelper.renameMediaItem(this@MainActivity, item, newName)
                        if (success) {
                            viewModel.onItemRenamed(item, newName)
                            Toast.makeText(this@MainActivity, "تمت إعادة التسمية بنجاح", Toast.LENGTH_SHORT).show()
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            pendingRenameItem = item
                            pendingRenameNewName = newName
                            val request = StorageHelper.buildWriteIntentSenderRequest(
                                this@MainActivity, listOf(item)
                            )
                            if (request != null) {
                                writeLauncher.launch(request)
                            } else {
                                Toast.makeText(this@MainActivity, "تعذر طلب إذن التعديل", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this@MainActivity, "تعذر إعادة التسمية", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun shareMediaItem(item: MediaItem) {
        if (item.parentFolder == VaultManager.VAULT_FOLDER_LABEL) {
            Toast.makeText(this, "لا يمكن مشاركة ملف محمي مباشرة — استعده من الخزينة أولاً", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent.createChooser(StorageHelper.buildShareIntent(this, item), "مشاركة المقطع"))
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر مشاركة المقطع", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPropertiesDialog(item: MediaItem) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val dateStr = sdf.format(Date(item.dateAdded * 1000L))
        val sizeMb = String.format(Locale.US, "%.2f MB", item.size / (1024.0 * 1024.0))
        val durStr = LionPlayerManager.formatDuration(item.duration)

        val message = """
            الاسم: ${item.title}
            المسار: ${item.path.ifEmpty { item.uri }}
            النوع: ${if (item.isVideo) "فيديو" else "صوتيات"}
            الألبوم: ${item.album}
            الفنان: ${item.artist}
            الحجم: $sizeMb
            المدة: $durStr
            تاريخ الإضافة: $dateStr
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("خصائص المقطع")
            .setMessage(message)
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun confirmDeleteMediaItem(item: MediaItem) {
        AlertDialog.Builder(this)
            .setTitle("حذف المقطع")
            .setMessage("هل أنت متأكد من رغبتك في حذف ${item.title} نهائياً من الجهاز؟")
            .setPositiveButton("حذف") { _, _ -> performDelete(listOf(item)) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun confirmDeleteMultiple(items: List<MediaItem>) {
        AlertDialog.Builder(this)
            .setTitle("حذف المقاطع المحددة")
            .setMessage("هل أنت متأكد من رغبتك في حذف ${items.size} عناصر نهائياً من الذاكرة؟")
            .setPositiveButton("حذف") { _, _ -> performDelete(items) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun performDelete(items: List<MediaItem>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val request = StorageHelper.buildDeleteIntentSenderRequest(this, items)
            if (request != null) {
                pendingDeleteItems = items
                deleteLauncher.launch(request)
                return
            }
        }

        lifecycleScope.launch {
            try {
                for (item in items) {
                    try {
                        contentResolver.delete(Uri.parse(item.uri), null, null)
                    } catch (e: SecurityException) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            e is android.app.RecoverableSecurityException
                        ) {
                            pendingDeleteItems = items
                            deleteLauncher.launch(
                                IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
                            )
                            return@launch
                        }
                    }
                }
                StorageHelper.performLegacyDelete(this@MainActivity, items)
                viewModel.onItemsDeleted(items)
                Toast.makeText(this@MainActivity, "تم الحذف بنجاح", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "حدث خطأ أثناء الحذف", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun zoomIn() {
        val currentStyle = viewModel.uiState.value.viewStyle
        if (currentStyle == MediaAdapter.VIEW_MODE_GRID_SMALL) {
            viewModel.setViewStyle(MediaAdapter.VIEW_MODE_GRID_LARGE)
        } else if (currentStyle == MediaAdapter.VIEW_MODE_GRID_LARGE) {
            viewModel.setViewStyle(MediaAdapter.VIEW_MODE_LIST)
        }
    }

    private fun zoomOut() {
        val currentStyle = viewModel.uiState.value.viewStyle
        if (currentStyle == MediaAdapter.VIEW_MODE_LIST) {
            viewModel.setViewStyle(MediaAdapter.VIEW_MODE_GRID_LARGE)
        } else if (currentStyle == MediaAdapter.VIEW_MODE_GRID_LARGE) {
            viewModel.setViewStyle(MediaAdapter.VIEW_MODE_GRID_SMALL)
        }
    }

    private fun setupBottomNav() {
        binding.bottomNavigation.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_vault -> {
                    // تبويب الخزينة يفتح شاشة القفل ثم الخزينة
                    openVault()
                    // إبقاء التحديد على التبويب السابق
                    val previousTab = when (viewModel.uiState.value.currentTab) {
                        MediaViewModel.TAB_AUDIO -> R.id.nav_music
                        MediaViewModel.TAB_ALL -> R.id.nav_all
                        MediaViewModel.TAB_FAVORITES -> R.id.nav_favorites
                        else -> R.id.nav_local
                    }
                    binding.bottomNavigation.selectedItemId = previousTab
                }
                else -> {
                    val newTab = when (menuItem.itemId) {
                        R.id.nav_local -> MediaViewModel.TAB_VIDEO
                        R.id.nav_music -> MediaViewModel.TAB_AUDIO
                        R.id.nav_all -> MediaViewModel.TAB_ALL
                        R.id.nav_favorites -> MediaViewModel.TAB_FAVORITES
                        else -> MediaViewModel.TAB_VIDEO
                    }
                    viewModel.setTab(newTab)
                }
            }
            true
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), PERMISSION_REQ_CODE)
        } else {
            viewModel.loadMedia()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                viewModel.loadMedia()
            } else {
                Toast.makeText(this, "يرجى منح صلاحيات الوسائط لتشغيل الملفات", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.top_main_menu, menu)
        val searchItem = menu?.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = "ابحث عن أي مقطع..."
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setSearchQuery(newText.orEmpty())
                return true
            }
        })

        val extItem = menu?.findItem(R.id.action_toggle_extension)
        extItem?.isChecked = viewModel.uiState.value.showExtensions
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_vault -> {
                openVault()
                return true
            }
            R.id.action_refresh -> {
                Toast.makeText(this, "جاري تحديث المقاطع...", Toast.LENGTH_SHORT).show()
                viewModel.loadMedia()
                return true
            }
            R.id.action_view_mode -> {
                showViewStyleDialog()
                return true
            }
            R.id.action_display_mode -> {
                showDisplayModeDialog()
                return true
            }
            R.id.action_sort -> {
                showSortByDialog()
                return true
            }
            R.id.action_order -> {
                showOrderDialog()
                return true
            }
            R.id.action_toggle_extension -> {
                val next = !viewModel.uiState.value.showExtensions
                item.isChecked = next
                viewModel.setShowExtensions(next)
                Toast.makeText(
                    this,
                    if (next) "تم تفعيل إظهار الامتدادات" else "تم إخفاء الامتدادات",
                    Toast.LENGTH_SHORT
                ).show()
                return true
            }
            R.id.action_settings -> {
                PlayerSettingsBottomSheet.show(
                    activity = this,
                    onSettingChanged = { viewModel.recomputeDisplay() }
                )
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showViewStyleDialog() {
        val styles = arrayOf("قائمة (List)", "مصغرات كبيرة (Grid 2)", "مصغرات صغيرة (Grid 3)")
        AlertDialog.Builder(this)
            .setTitle("أسلوب العرض")
            .setSingleChoiceItems(styles, viewModel.uiState.value.viewStyle) { dialog, which ->
                viewModel.setViewStyle(which)
                dialog.dismiss()
            }
            .show()
    }

    private fun showDisplayModeDialog() {
        val isFolder = if (viewModel.uiState.value.currentTab == MediaViewModel.TAB_VIDEO) {
            viewModel.uiState.value.isVideoFolderMode
        } else {
            viewModel.uiState.value.isAudioFolderMode
        }
        val options = arrayOf("عرض كمجلدات", "عرض كافة الملفات")
        AlertDialog.Builder(this)
            .setTitle("طريقة العرض")
            .setSingleChoiceItems(options, if (isFolder) 0 else 1) { dialog, which ->
                viewModel.toggleFolderMode(which == 0)
                dialog.dismiss()
            }
            .show()
    }

    private fun showSortByDialog() {
        val options = arrayOf("الاسم", "تاريخ الإضافة", "الحجم", "المدة")
        val current = when (viewModel.uiState.value.sortBy) {
            "name" -> 0
            "date" -> 1
            "size" -> 2
            "duration" -> 3
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("فرز حسب")
            .setSingleChoiceItems(options, current) { dialog, which ->
                val s = when (which) {
                    0 -> "name"
                    1 -> "date"
                    2 -> "size"
                    3 -> "duration"
                    else -> "name"
                }
                viewModel.setSort(s, viewModel.uiState.value.sortAsc)
                dialog.dismiss()
            }
            .show()
    }

    private fun showOrderDialog() {
        val options = arrayOf("تصاعدي", "تنازلي")
        val current = if (viewModel.uiState.value.sortAsc) 0 else 1
        AlertDialog.Builder(this)
            .setTitle("ترتيب")
            .setSingleChoiceItems(options, current) { dialog, which ->
                viewModel.setSort(viewModel.uiState.value.sortBy, which == 0)
                dialog.dismiss()
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        updateMiniPlayerUI()
        LionPlayerManager.onTrackChanged = { runOnUiThread { updateMiniPlayerUI() } }
        LionPlayerManager.onPlaybackStateChanged = { runOnUiThread { updateMiniPlayerUI() } }
    }

    override fun onResume() {
        super.onResume()
        showSystemBars()
        updateMiniPlayerUI()
        viewModel.recomputeDisplay()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            viewModel.uiState.value.isSelectionMode -> viewModel.clearSelection()
            viewModel.uiState.value.currentFolder != null -> viewModel.selectFolder(null)
            else -> super.onBackPressed()
        }
    }
}
