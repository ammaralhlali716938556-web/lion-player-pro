package com.lionplayer.app.vault

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.lionplayer.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * قائمة الملفات المشفّرة داخل الخزينة.
 * لا تُعرض أي مصغّرة حقيقية هنا (لا يوجد فك تشفير للتصفح) — فقط أيقونة نوع الملف.
 */
class VaultAdapter(
    private var items: List<VaultFileItem>,
    private val onPlay: (VaultFileItem) -> Unit,
    private val onRestore: (VaultFileItem) -> Unit,
    private val onDelete: (VaultFileItem) -> Unit,
    private val onMore: (VaultFileItem, View) -> Unit
) : RecyclerView.Adapter<VaultAdapter.VaultViewHolder>() {

    fun submit(newItems: List<VaultFileItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VaultViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_vault_file, parent, false)
        return VaultViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VaultViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class VaultViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivType: ImageView = itemView.findViewById(R.id.ivVaultItemType)
        private val tvName: TextView = itemView.findViewById(R.id.tvVaultItemName)
        private val tvInfo: TextView = itemView.findViewById(R.id.tvVaultItemInfo)
        private val btnPlay: TextView = itemView.findViewById(R.id.btnVaultItemPlay)
        private val btnRestore: TextView = itemView.findViewById(R.id.btnVaultItemRestore)
        private val btnDelete: TextView = itemView.findViewById(R.id.btnVaultItemDelete)
        private val btnMore: ImageButton = itemView.findViewById(R.id.btnVaultItemMore)

        private val dateFormat = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault())

        fun bind(item: VaultFileItem) {
            ivType.setImageResource(if (item.isVideo) R.drawable.ic_video else R.drawable.ic_music_note)
            tvName.text = item.displayName

            val date = if (item.addedAt > 0) dateFormat.format(Date(item.addedAt)) else "-"
            tvInfo.text = "🔐 ${VaultManager.formatSize(item.sizeBytes)}  •  $date  •  من: ${item.originalFolder}"

            btnPlay.setOnClickListener { onPlay(item) }
            btnRestore.setOnClickListener { onRestore(item) }
            btnDelete.setOnClickListener { onDelete(item) }
            btnMore.setOnClickListener { v -> onMore(item, v) }
            itemView.setOnClickListener { onPlay(item) }
        }
    }
}
