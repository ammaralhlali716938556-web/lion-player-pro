package com.lionplayer.app.viewmodel

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lionplayer.app.adapter.MediaAdapter
import com.lionplayer.app.model.MediaItem
import com.lionplayer.app.vault.VaultManager
import com.lionplayer.app.vault.VaultResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class MediaUiState(
    val isLoading: Boolean = false,
    val displayedItems: List<MediaItem> = emptyList(),
    val currentTab: Int = 0,
    val currentFolder: String? = null,
    val isVideoFolderMode: Boolean = true,
    val isAudioFolderMode: Boolean = true,
    val isSelectionMode: Boolean = false,
    val selectedCount: Int = 0,
    val viewStyle: Int = 0,
    val showExtensions: Boolean = false,
    val sortBy: String = "name",
    val sortAsc: Boolean = true,
    val title: String = "Lion Player",
    val searchQuery: String = "",
    val isVaultProcessing: Boolean = false
)

class MediaViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val TAB_VIDEO = 0
        const val TAB_AUDIO = 1
        const val TAB_ALL = 2
        const val TAB_FAVORITES = 3
        const val TAB_VAULT = 4
    }

    private val prefs: SharedPreferences =
        application.getSharedPreferences("lion_player_prefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        MediaUiState(
            currentTab = prefs.getInt("last_tab", TAB_VIDEO),
            currentFolder = prefs.getString("last_folder", null),
            isVideoFolderMode = prefs.getBoolean("is_video_folder_mode", true),
            isAudioFolderMode = prefs.getBoolean("is_audio_folder_mode", true),
            viewStyle = prefs.getInt("view_style", MediaAdapter.VIEW_MODE_LIST),
            showExtensions = prefs.getBoolean("show_extensions", false),
            sortBy = prefs.getString("sort_by", "name") ?: "name",
            sortAsc = prefs.getBoolean("sort_asc", true)
        )
    )
    val uiState: StateFlow<MediaUiState> = _uiState.asStateFlow()

    private var rawVideosList = mutableListOf<MediaItem>()
    private var rawAudioList = mutableListOf<MediaItem>()

    fun loadMedia() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val (videos, audios) = withContext(Dispatchers.IO) {
                Pair(fetchVideos(), fetchAudio())
            }
            rawVideosList = videos.toMutableList()
            rawAudioList = audios.toMutableList()
            recomputeDisplay()
        }
    }

    private fun fetchVideos(): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        val favSet = prefs.getStringSet("favorite_ids", emptySet()) ?: emptySet()
        val playedSet = prefs.getStringSet("played_media_ids", emptySet()) ?: emptySet()
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.ARTIST,
            MediaStore.Video.Media.ALBUM
        )

        try {
            getApplication<Application>().contentResolver
                .query(uri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                    val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                    val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                    val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                    val artistCol = cursor.getColumnIndex(MediaStore.Video.Media.ARTIST)
                    val albumCol = cursor.getColumnIndex(MediaStore.Video.Media.ALBUM)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                        val dispName = if (nameCol != -1) cursor.getString(nameCol) ?: "" else ""
                        val title = dispName.ifEmpty {
                            File(path).name.ifEmpty { "فيديو" }
                        }
                        val duration = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                        val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        val dateAdded = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                        val artist = if (artistCol != -1) cursor.getString(artistCol) ?: "فيديو" else "فيديو"
                        val album = if (albumCol != -1) cursor.getString(albumCol) ?: "فيديو" else "فيديو"
                        val contentUri = ContentUris.withAppendedId(uri, id).toString()
                        val folderName = try {
                            val parent = File(path).parentFile?.name
                            if (!parent.isNullOrEmpty()) parent else "الرئيسي"
                        } catch (e: Exception) {
                            "الرئيسي"
                        }

                        list.add(
                            MediaItem(
                                id = id,
                                title = title,
                                uri = contentUri,
                                duration = duration,
                                size = size,
                                dateAdded = dateAdded,
                                isVideo = true,
                                parentFolder = folderName,
                                artist = artist,
                                album = album,
                                isFavorite = favSet.contains(id.toString()),
                                path = path,
                                isNew = !playedSet.contains(id.toString())
                            )
                        )
                    }
                }
        } catch (e: Exception) {
            // تجاهل مقصود
        }
        return list
    }

    private fun fetchAudio(): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        val favSet = prefs.getStringSet("favorite_ids", emptySet()) ?: emptySet()
        val playedSet = prefs.getStringSet("played_media_ids", emptySet()) ?: emptySet()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM
        )

        try {
            getApplication<Application>().contentResolver
                .query(uri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                    val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                    val dateCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                    val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val path = if (dataCol >= 0) cursor.getString(dataCol) ?: "" else ""
                        val dispName = if (nameCol != -1) cursor.getString(nameCol) ?: "" else ""
                        val title = dispName.ifEmpty {
                            File(path).name.ifEmpty { "صوتيات" }
                        }
                        val duration = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                        val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        val dateAdded = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                        val artist = if (artistCol != -1) cursor.getString(artistCol) ?: "فنان غير معروف" else "فنان غير معروف"
                        val album = if (albumCol != -1) cursor.getString(albumCol) ?: "ألبوم غير محدد" else "ألبوم غير محدد"
                        val contentUri = ContentUris.withAppendedId(uri, id).toString()
                        val folderName = try {
                            val parent = File(path).parentFile?.name
                            if (!parent.isNullOrEmpty()) parent else "الرئيسي"
                        } catch (e: Exception) {
                            "الرئيسي"
                        }

                        list.add(
                            MediaItem(
                                id = id,
                                title = title,
                                uri = contentUri,
                                duration = duration,
                                size = size,
                                dateAdded = dateAdded,
                                isVideo = false,
                                parentFolder = folderName,
                                artist = artist,
                                album = album,
                                isFavorite = favSet.contains(id.toString()),
                                path = path,
                                isNew = !playedSet.contains(id.toString())
                            )
                        )
                    }
                }
        } catch (e: Exception) {
            // تجاهل مقصود
        }
        return list
    }

    fun recomputeDisplay() {
        val state = _uiState.value
        val displayed = mutableListOf<MediaItem>()
        var screenTitle = "Lion Player"

        val query = state.searchQuery.trim()

        if (query.isNotEmpty()) {
            val baseList = when (state.currentTab) {
                TAB_VIDEO -> rawVideosList
                TAB_AUDIO -> rawAudioList
                TAB_ALL -> rawVideosList + rawAudioList
                TAB_FAVORITES -> (rawVideosList + rawAudioList).filter { it.isFavorite }
                else -> rawVideosList
            }
            displayed.addAll(baseList.filter { it.title.contains(query, ignoreCase = true) })
            screenTitle = "نتائج البحث: $query"
        } else {
            when (state.currentTab) {
                TAB_VIDEO -> {
                    if (state.isVideoFolderMode && state.currentFolder == null) {
                        screenTitle = "مجلدات الفيديو"
                        val grouped = rawVideosList.groupBy { it.parentFolder }
                        for ((folderName, items) in grouped) {
                            val firstItem = items.firstOrNull { it.path.isNotEmpty() || it.uri.isNotEmpty() }
                                ?: items.firstOrNull()
                            displayed.add(
                                MediaItem(
                                    id = firstItem?.id ?: folderName.hashCode().toLong(),
                                    title = folderName,
                                    uri = firstItem?.uri ?: "",
                                    duration = 0,
                                    size = items.sumOf { it.size },
                                    dateAdded = items.maxOfOrNull { it.dateAdded } ?: 0,
                                    isVideo = true,
                                    parentFolder = folderName,
                                    isFolder = true,
                                    itemCount = items.size,
                                    path = firstItem?.path ?: "",
                                    newCount = items.count { it.isNew }
                                )
                            )
                        }
                    } else if (state.isVideoFolderMode && state.currentFolder != null) {
                        screenTitle = state.currentFolder
                        displayed.addAll(rawVideosList.filter { it.parentFolder == state.currentFolder })
                    } else {
                        screenTitle = "كافة الفيديوهات"
                        displayed.addAll(rawVideosList)
                    }
                }

                TAB_AUDIO -> {
                    if (state.isAudioFolderMode && state.currentFolder == null) {
                        screenTitle = "مجلدات الصوت"
                        val grouped = rawAudioList.groupBy { it.parentFolder }
                        for ((folderName, items) in grouped) {
                            val firstItem = items.firstOrNull { it.path.isNotEmpty() || it.uri.isNotEmpty() }
                                ?: items.firstOrNull()
                            displayed.add(
                                MediaItem(
                                    id = firstItem?.id ?: folderName.hashCode().toLong(),
                                    title = folderName,
                                    uri = firstItem?.uri ?: "",
                                    duration = 0,
                                    size = items.sumOf { it.size },
                                    dateAdded = items.maxOfOrNull { it.dateAdded } ?: 0,
                                    isVideo = false,
                                    parentFolder = folderName,
                                    isFolder = true,
                                    itemCount = items.size,
                                    path = firstItem?.path ?: "",
                                    newCount = items.count { it.isNew }
                                )
                            )
                        }
                    } else if (state.isAudioFolderMode && state.currentFolder != null) {
                        screenTitle = state.currentFolder
                        displayed.addAll(rawAudioList.filter { it.parentFolder == state.currentFolder })
                    } else {
                        screenTitle = "كافة الصوتيات"
                        displayed.addAll(rawAudioList)
                    }
                }

                TAB_ALL -> {
                    screenTitle = "جميع الملفات"
                    displayed.addAll(rawVideosList)
                    displayed.addAll(rawAudioList)
                }

                TAB_FAVORITES -> {
                    screenTitle = "المفضلة"
                    displayed.addAll(rawVideosList.filter { it.isFavorite })
                    displayed.addAll(rawAudioList.filter { it.isFavorite })
                }

                TAB_VAULT -> {
                    screenTitle = VaultManager.VAULT_FOLDER_LABEL
                }
            }
        }

        val comparator: Comparator<MediaItem> = when (state.sortBy) {
            "name" -> compareBy { it.title.lowercase() }
            "date" -> compareBy { it.dateAdded }
            "size" -> compareBy { it.size }
            "duration" -> compareBy { it.duration }
            else -> compareBy { it.title.lowercase() }
        }
        if (state.sortAsc) {
            displayed.sortWith(comparator)
        } else {
            displayed.sortWith(comparator.reversed())
        }

        val selectedCount = displayed.count { it.isSelected && !it.isFolder }
        val isSelection = state.isSelectionMode && selectedCount > 0

        _uiState.update {
            it.copy(
                isLoading = false,
                displayedItems = displayed,
                title = screenTitle,
                isSelectionMode = isSelection,
                selectedCount = selectedCount
            )
        }
    }

    fun setTab(tab: Int) {
        if (_uiState.value.currentTab != tab) {
            prefs.edit().putInt("last_tab", tab).putString("last_folder", null).apply()
            _uiState.update { it.copy(currentTab = tab, currentFolder = null, searchQuery = "") }
            recomputeDisplay()
        }
    }

    fun selectFolder(folderName: String?) {
        prefs.edit().putString("last_folder", folderName).apply()
        _uiState.update { it.copy(currentFolder = folderName) }
        recomputeDisplay()
    }

    fun toggleFolderMode(isFolder: Boolean) {
        val tab = _uiState.value.currentTab
        if (tab == TAB_VIDEO) {
            prefs.edit().putBoolean("is_video_folder_mode", isFolder).apply()
            _uiState.update { it.copy(isVideoFolderMode = isFolder, currentFolder = null) }
        } else if (tab == TAB_AUDIO) {
            prefs.edit().putBoolean("is_audio_folder_mode", isFolder).apply()
            _uiState.update { it.copy(isAudioFolderMode = isFolder, currentFolder = null) }
        }
        recomputeDisplay()
    }

    fun setSort(sortBy: String, sortAsc: Boolean) {
        prefs.edit().putString("sort_by", sortBy).putBoolean("sort_asc", sortAsc).apply()
        _uiState.update { it.copy(sortBy = sortBy, sortAsc = sortAsc) }
        recomputeDisplay()
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        recomputeDisplay()
    }

    fun setViewStyle(style: Int) {
        prefs.edit().putInt("view_style", style).apply()
        _uiState.update { it.copy(viewStyle = style) }
    }

    fun setShowExtensions(show: Boolean) {
        prefs.edit().putBoolean("show_extensions", show).apply()
        _uiState.update { it.copy(showExtensions = show) }
    }

    fun startSelectionMode(initialItem: MediaItem? = null) {
        initialItem?.isSelected = true
        _uiState.update { it.copy(isSelectionMode = true) }
        recomputeDisplay()
    }

    fun toggleItemSelection(item: MediaItem) {
        item.isSelected = !item.isSelected
        val count = _uiState.value.displayedItems.count { it.isSelected && !it.isFolder }
        _uiState.update { it.copy(isSelectionMode = count > 0, selectedCount = count) }
        recomputeDisplay()
    }

    fun selectAll(select: Boolean) {
        _uiState.value.displayedItems.forEach { if (!it.isFolder) it.isSelected = select }
        val count = if (select) _uiState.value.displayedItems.count { !it.isFolder } else 0
        _uiState.update { it.copy(isSelectionMode = count > 0, selectedCount = count) }
        recomputeDisplay()
    }

    fun clearSelection() {
        _uiState.value.displayedItems.forEach { it.isSelected = false }
        _uiState.update { it.copy(isSelectionMode = false, selectedCount = 0) }
        recomputeDisplay()
    }

    fun markItemAsPlayed(id: Long) {
        val playedSet = prefs.getStringSet("played_media_ids", emptySet())?.toMutableSet()
            ?: mutableSetOf()
        if (playedSet.add(id.toString())) {
            prefs.edit().putStringSet("played_media_ids", playedSet).apply()
            rawVideosList.find { it.id == id }?.isNew = false
            rawAudioList.find { it.id == id }?.isNew = false
            recomputeDisplay()
        }
    }

    fun toggleFavorite(item: MediaItem): Boolean {
        val favSet = prefs.getStringSet("favorite_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        val idStr = item.id.toString()
        val newFav = !item.isFavorite
        if (newFav) favSet.add(idStr) else favSet.remove(idStr)
        item.isFavorite = newFav
        rawVideosList.find { it.id == item.id }?.isFavorite = newFav
        rawAudioList.find { it.id == item.id }?.isFavorite = newFav
        prefs.edit().putStringSet("favorite_ids", favSet).apply()
        recomputeDisplay()
        return newFav
    }

    fun toggleFavoriteMultiple(items: List<MediaItem>): Boolean {
        val favSet = prefs.getStringSet("favorite_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        val allFav = items.all { it.isFavorite }
        val targetFav = !allFav

        for (item in items) {
            val idStr = item.id.toString()
            item.isFavorite = targetFav
            rawVideosList.find { it.id == item.id }?.isFavorite = targetFav
            rawAudioList.find { it.id == item.id }?.isFavorite = targetFav
            if (targetFav) favSet.add(idStr) else favSet.remove(idStr)
        }
        prefs.edit().putStringSet("favorite_ids", favSet).apply()
        clearSelection()
        recomputeDisplay()
        return targetFav
    }

    fun onItemsDeleted(deleted: List<MediaItem>) {
        val deletedIds = deleted.map { it.id }.toSet()
        rawVideosList.removeAll { it.id in deletedIds }
        rawAudioList.removeAll { it.id in deletedIds }
        clearSelection()
        recomputeDisplay()
    }

    fun onItemRenamed(item: MediaItem, newName: String) {
        item.title = newName
        rawVideosList.find { it.id == item.id }?.title = newName
        rawAudioList.find { it.id == item.id }?.title = newName
        recomputeDisplay()
    }

    fun getAllRawMedia(): List<MediaItem> = rawVideosList + rawAudioList

    // =========================================================================
    //  الخزينة السرية: نقل الملفات المحددة إلى الخزينة المشفّرة
    // =========================================================================

    /**
     * يشفّر العناصر المحددة داخل الخزينة ثم يحذف الأصل من الهاتف.
     * @return عدد الملفات التي نُقلت بنجاح.
     */
    fun lockItemsInVault(items: List<MediaItem>, onDone: (Int, String?) -> Unit) {
        if (items.isEmpty()) {
            onDone(0, "يرجى تحديد مقطع واحد على الأقل")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isVaultProcessing = true) }
            var success = 0
            var firstError: String? = null
            val lockedIds = mutableSetOf<Long>()

            for (item in items) {
                when (val result = VaultManager.lockMediaItem(getApplication(), item, deleteOriginal = true)) {
                    is VaultResult.Success -> {
                        success++
                        lockedIds.add(item.id)
                    }
                    is VaultResult.Failure -> {
                        if (firstError == null) firstError = result.message
                    }
                }
            }

            if (lockedIds.isNotEmpty()) {
                rawVideosList.removeAll { it.id in lockedIds }
                rawAudioList.removeAll { it.id in lockedIds }
            }
            clearSelection()
            _uiState.update { it.copy(isVaultProcessing = false) }
            recomputeDisplay()
            onDone(success, firstError)
        }
    }

    /** عدد الملفات داخل الخزينة (لعرضه في الواجهة) */
    fun vaultFileCount(): Int = VaultManager.listVaultFiles(getApplication()).size
}
