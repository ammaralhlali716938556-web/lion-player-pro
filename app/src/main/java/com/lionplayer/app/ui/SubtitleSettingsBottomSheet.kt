package com.lionplayer.app.ui

import android.app.Activity
import android.graphics.Color
import android.view.LayoutInflater
import android.widget.SeekBar
import androidx.media3.ui.CaptionStyleCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lionplayer.app.R
import com.lionplayer.app.databinding.BottomSheetSubtitlesBinding
import com.lionplayer.app.player.LionPlayerManager

/** نافذة إعدادات الترجمة (اختيار ملف / بحث OpenSubtitles / مسارات / تأخير / نمط النص) */
object SubtitleSettingsBottomSheet {

    fun show(
        activity: Activity,
        onPickLocalClick: () -> Unit,
        onOpenSubtitlesClick: () -> Unit,
        onSelectTracksClick: () -> Unit,
        onStyleChanged: (CaptionStyleCompat, Float) -> Unit
    ) {
        val dialog = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
        val binding = BottomSheetSubtitlesBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(binding.root)

        binding.btnPickLocalSubtitle.setOnClickListener {
            dialog.dismiss()
            onPickLocalClick()
        }
        binding.btnOpenSubtitlesSearch.setOnClickListener {
            dialog.dismiss()
            onOpenSubtitlesClick()
        }
        binding.btnSelectSubtitleTrack.setOnClickListener {
            dialog.dismiss()
            onSelectTracksClick()
        }

        var currentDelay = LionPlayerManager.currentSubtitleOffsetMs
        binding.sbSubtitleDelay.progress = (currentDelay + 5000L).coerceIn(0L, 10000L).toInt()
        binding.tvDelayValue.text = "التأخير: $currentDelay ms"

        fun applyDelay(newDelay: Long) {
            currentDelay = newDelay.coerceIn(-5000L, 5000L)
            binding.sbSubtitleDelay.progress = (currentDelay + 5000L).toInt()
            binding.tvDelayValue.text = "التأخير: $currentDelay ms"
            LionPlayerManager.applySubtitleOffset(activity, currentDelay)
        }

        binding.btnDelayMinus.setOnClickListener { applyDelay(currentDelay - 500L) }
        binding.btnDelayPlus.setOnClickListener { applyDelay(currentDelay + 500L) }

        binding.sbSubtitleDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentDelay = progress.toLong() - 5000L
                    binding.tvDelayValue.text = "التأخير: $currentDelay ms"
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                LionPlayerManager.applySubtitleOffset(activity, currentDelay)
            }
        })

        var currentTextColor = Color.WHITE
        var currentBgColor = Color.TRANSPARENT
        var currentTextSize = 18f

        fun notifyStyle() {
            val style = CaptionStyleCompat(
                currentTextColor,
                currentBgColor,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                null
            )
            onStyleChanged(style, currentTextSize)
        }

        binding.btnFontSmall.setOnClickListener { currentTextSize = 14f; notifyStyle() }
        binding.btnFontNormal.setOnClickListener { currentTextSize = 18f; notifyStyle() }
        binding.btnFontLarge.setOnClickListener { currentTextSize = 24f; notifyStyle() }

        binding.btnColorWhite.setOnClickListener { currentTextColor = Color.WHITE; notifyStyle() }
        binding.btnColorYellow.setOnClickListener { currentTextColor = Color.parseColor("#FFEB3B"); notifyStyle() }
        binding.btnColorCyan.setOnClickListener { currentTextColor = Color.parseColor("#00E5FF"); notifyStyle() }

        binding.switchSubtitleBackground.setOnCheckedChangeListener { _, isChecked ->
            currentBgColor = if (isChecked) Color.parseColor("#80000000") else Color.TRANSPARENT
            notifyStyle()
        }

        dialog.show()
    }
}
