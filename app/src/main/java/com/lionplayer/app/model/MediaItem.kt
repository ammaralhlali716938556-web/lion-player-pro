package com.lionplayer.app.model

import java.io.Serializable

/**
 * عنصر وسائط موحّد (فيديو / صوتيات / مجلد).
 * ملاحظة: العناصر القادمة من "الخزينة السرية" تحمل
 * parentFolder = VaultManager.VAULT_FOLDER_LABEL ومعرّفاً سالباً.
 */
data class MediaItem(
    val id: Long,
    var title: String,
    val uri: String,
    val duration: Long,
    val size: Long,
    val dateAdded: Long,
    val isVideo: Boolean,
    val parentFolder: String = "الرئيسي",
    var isFolder: Boolean = false,
    var itemCount: Int = 0,
    val artist: String = "غير معروف",
    val album: String = "غير معروف",
    var isFavorite: Boolean = false,
    val path: String = "",
    var isSelected: Boolean = false,
    var isNew: Boolean = false,
    var newCount: Int = 0
) : Serializable
