package com.lionplayer.app.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lionplayer.app.R
import com.lionplayer.app.databinding.ItemMediaGridBinding
import com.lionplayer.app.databinding.ItemMediaVideoBinding
import com.lionplayer.app.model.MediaItem
import com.lionplayer.app.vault.VaultManager
import java.io.File
import java.util.Locale

/** محول قائمة الوسائط (قائمة / شبكة) مع دعم شارة الخزينة السرية */
class MediaAdapter(
    private var items: List<MediaItem>,
    private var viewMode: Int,
    private var showExtensions: Boolean,
    var isSelectionMode: Boolean = false,
    private val onItemClick: (MediaItem) -> Unit,
    private val onItemLongClick: (MediaItem) -> Unit,
    private val onMoreOptionsClick: (MediaItem, View) -> Unit
) : RecyclerView.Adapter<MediaAdapter.BaseViewHolder>() {

    companion object {
        const val VIEW_MODE_LIST = 0
        const val VIEW_MODE_GRID_LARGE = 1
        const val VIEW_MODE_GRID_SMALL = 2
    }

    fun updateData(
        newItems: List<MediaItem>,
        newViewMode: Int = viewMode,
        newShowExt: Boolean = showExtensions
    ) {
        this.items = newItems
        this.viewMode = newViewMode
        this.showExtensions = newShowExt
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = viewMode

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_MODE_LIST) {
            ListViewHolder(ItemMediaVideoBinding.inflate(inflater, parent, false))
        } else {
            GridViewHolder(ItemMediaGridBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    abstract inner class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        abstract fun bind(item: MediaItem)
    }

    // =========================================================================
    //  عرض القائمة
    // =========================================================================
    inner class ListViewHolder(private val binding: ItemMediaVideoBinding) :
        BaseViewHolder(binding.root) {

        override fun bind(item: MediaItem) {
            binding.tvTitle.text = displayTitle(item)
            applySelectionUi(item, binding.ivCheck, binding.itemRootView)

            val isVault = item.parentFolder == VaultManager.VAULT_FOLDER_LABEL
            binding.ivVaultBadge.visibility = if (isVault) View.VISIBLE else View.GONE

            if (isVault) {
                // لا تُحمَّل أي مصغّرة لملف محمي
                binding.ivThumbnail.setImageResource(R.drawable.ic_lock)
            } else {
                binding.ivThumbnail.loadMediaThumbnail(item)
            }

            if (item.isFolder) {
                binding.tvSubInfo.visibility = View.GONE
                binding.tvDetails.text = "${item.itemCount} عناصر"
                binding.btnMore.visibility = View.GONE
                binding.tvThumbDuration.visibility = View.GONE
                binding.tvNewBadge.visibility = View.GONE
                binding.tvFolderNewCount.visibility = if (item.newCount > 0) View.VISIBLE else View.GONE
                if (item.newCount > 0) {
                    binding.tvFolderNewCount.text = if (item.newCount > 99) "99+" else item.newCount.toString()
                }
            } else {
                binding.tvFolderNewCount.visibility = View.GONE
                binding.tvNewBadge.visibility =
                    if (item.isNew && !(isSelectionMode && item.isSelected)) View.VISIBLE else View.GONE

                if (item.duration > 0) {
                    binding.tvThumbDuration.visibility = View.VISIBLE
                    binding.tvThumbDuration.text = formatDuration(item.duration)
                } else {
                    binding.tvThumbDuration.visibility = View.GONE
                }

                binding.tvSubInfo.visibility = View.VISIBLE
                binding.tvSubInfo.text = if (isVault) {
                    "🔐 ملف داخل الخزينة السرية (مشفّر)"
                } else {
                    "الألبوم: ${item.album.ifEmpty { "ألبوم غير محدد" }} • الفنان: ${item.artist.ifEmpty { "فنان غير معروف" }}"
                }

                val favMark = if (item.isFavorite) "  ❤" else ""
                binding.tvDetails.text =
                    "${formatDuration(item.duration)} | ${formatFileSize(item.size)}$favMark"

                binding.btnMore.visibility = if (isSelectionMode || isVault) View.GONE else View.VISIBLE
                binding.btnMore.setOnClickListener { v -> onMoreOptionsClick(item, v) }
            }

            itemView.setOnClickListener { onItemClick(item) }
            itemView.setOnLongClickListener {
                if (!item.isFolder) {
                    onItemLongClick(item)
                    true
                } else false
            }
        }
    }

    // =========================================================================
    //  عرض الشبكة
    // =========================================================================
    inner class GridViewHolder(private val binding: ItemMediaGridBinding) :
        BaseViewHolder(binding.root) {

        override fun bind(item: MediaItem) {
            binding.tvTitle.text = displayTitle(item)
            applySelectionUi(item, binding.ivCheck, binding.itemRootView)

            val isVault = item.parentFolder == VaultManager.VAULT_FOLDER_LABEL
            binding.ivVaultBadge.visibility = if (isVault) View.VISIBLE else View.GONE

            if (isVault) {
                binding.ivThumbnail.setImageResource(R.drawable.ic_lock)
            } else {
                binding.ivThumbnail.loadMediaThumbnail(item)
            }

            if (item.isFolder) {
                binding.tvSubInfo.visibility = View.GONE
                binding.tvDetails.text = "${item.itemCount} عناصر"
                binding.btnMore.visibility = View.GONE
                binding.tvThumbDuration.visibility = View.GONE
                binding.tvNewBadge.visibility = View.GONE
                binding.tvFolderNewCount.visibility = if (item.newCount > 0) View.VISIBLE else View.GONE
                if (item.newCount > 0) {
                    binding.tvFolderNewCount.text = if (item.newCount > 99) "99+" else item.newCount.toString()
                }
            } else {
                binding.tvFolderNewCount.visibility = View.GONE
                binding.tvNewBadge.visibility =
                    if (item.isNew && !(isSelectionMode && item.isSelected)) View.VISIBLE else View.GONE

                if (item.duration > 0) {
                    binding.tvThumbDuration.visibility = View.VISIBLE
                    binding.tvThumbDuration.text = formatDuration(item.duration)
                } else {
                    binding.tvThumbDuration.visibility = View.GONE
                }

                binding.tvSubInfo.visibility = View.VISIBLE
                binding.tvSubInfo.text = if (isVault) "🔐 مشفّر" else item.album.ifEmpty { "ألبوم غير محدد" }

                val favMark = if (item.isFavorite) "  ❤" else ""
                binding.tvDetails.text = "${formatFileSize(item.size)}$favMark"

                binding.btnMore.visibility = if (isSelectionMode || isVault) View.GONE else View.VISIBLE
                binding.btnMore.setOnClickListener { v -> onMoreOptionsClick(item, v) }
            }

            itemView.setOnClickListener { onItemClick(item) }
            itemView.setOnLongClickListener {
                if (!item.isFolder) {
                    onItemLongClick(item)
                    true
                } else false
            }
        }
    }

    // =========================================================================
    //  أدوات مشتركة
    // =========================================================================
    private fun displayTitle(item: MediaItem): String {
        if (item.isFolder) return item.title
        val ext = if (item.path.isNotEmpty()) File(item.path).extension else ""
        return if (showExtensions) {
            if (item.title.contains(".") || ext.isEmpty()) item.title else "${item.title}.$ext"
        } else {
            item.title.substringBeforeLast('.')
        }
    }

    private fun applySelectionUi(item: MediaItem, ivCheck: ImageView, rootView: View) {
        val isVault = item.parentFolder == VaultManager.VAULT_FOLDER_LABEL
        if (isSelectionMode && !item.isFolder && !isVault) {
            ivCheck.visibility = View.VISIBLE
            if (item.isSelected) {
                ivCheck.setImageResource(R.drawable.ic_check_circle)
                rootView.setBackgroundColor(Color.parseColor("#33FF9800"))
            } else {
                ivCheck.setImageResource(R.drawable.bg_thumb_round)
                rootView.setBackgroundColor(Color.TRANSPARENT)
            }
        } else {
            ivCheck.visibility = View.GONE
            rootView.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 MB"
        val mb = size / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSec = durationMs / 1000
        val sec = totalSec % 60
        val min = (totalSec / 60) % 60
        val hrs = totalSec / 3600
        return if (hrs > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hrs, min, sec)
        } else {
            String.format(Locale.US, "%02d:%02d", min, sec)
        }
    }
}
