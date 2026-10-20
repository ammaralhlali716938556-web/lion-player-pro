package com.lionplayer.app.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackGroup
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.videoFrameMillis
import com.lionplayer.app.R
import com.lionplayer.app.databinding.ActivityPlayerBinding
import com.lionplayer.app.databinding.DialogOpensubtitlesSearchBinding
import com.lionplayer.app.databinding.ItemOpensubtitleResultBinding
import com.lionplayer.app.model.MediaItem as LionMediaItem
import com.lionplayer.app.player.LionPlayerManager
import com.lionplayer.app.subtitle.OpenSubtitlesManager
import com.lionplayer.app.subtitle.SubtitleResult
import com.lionplayer.app.vault.VaultManager
import com.lionplayer.app.vault.VaultSecurityManager
import java.io.File
import java.util.Locale

/**
 * شاشة المشغل (Media3) — تُفعّل FLAG_SECURE تلقائياً عند تشغيل ملف قادم من
 * "الخزينة السرية" لمنع لقطة الشاشة/تسجيل الشاشة للمحتوى المحمي.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FROM_VAULT = "extra_from_vault"
        private const val SUBTITLE_PICK_REQ = 2001
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefs: SharedPreferences

    private var isLocked = false
    private var isKidsLocked = false
    private var kidsLockStep = 0
    private var controlsVisible = true
    private var rotationState = 0
    private var isSystemNavHidden = true
    private var isFromVault = false

    // A-B Repeat
    private var abPointA: Long = -1L
    private var abPointB: Long = -1L
    private var isAbRepeatActive: Boolean = false

    // Sleep Timer
    private var isSleepTimerActive = false
    private var sleepTimerEndTimeMs = 0L
    private var sleepTimerInitialVolumePercent = 100f

    // Audio effects
    private var audioManager: AudioManager? = null
    private var maxSystemVolume: Int = 15
    private var currentTotalVolumePercent = 100f
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var presetReverb: PresetReverb? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    // Gestures & zoom
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var videoScale = 1.0f
    private var videoTranslationX = 0f
    private var videoTranslationY = 0f
    private var isPinching = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val hideZoomHudRunnable = Runnable { binding.hudZoom.visibility = View.GONE }

    private var doubleTapForwardCount = 0
    private var doubleTapRewindCount = 0

    private val hideDoubleTapRightRunnable = Runnable {
        binding.hudDoubleTapRight.animate()
            .alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(250)
            .withEndAction {
                binding.hudDoubleTapRight.visibility = View.GONE
                doubleTapForwardCount = 0
            }
            .start()
    }

    private val hideDoubleTapLeftRunnable = Runnable {
        binding.hudDoubleTapLeft.animate()
            .alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(250)
            .withEndAction {
                binding.hudDoubleTapLeft.visibility = View.GONE
                doubleTapRewindCount = 0
            }
            .start()
    }

    private val hideKidsLockHudRunnable = Runnable { binding.hudKidsLockHint.visibility = View.GONE }

    private var resizeModeIndex = -1
    private val resizeModes = arrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "ملاءمة للشاشة (Fit)",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "ملء الشاشة (Crop)",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "تمديد (Stretch)"
    )

    private val autoHideLandscapeRunnable = Runnable {
        val currentItem = LionPlayerManager.getCurrentItem()
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (currentItem?.isVideo == true && isLandscape && !isKidsLocked) {
            hideAllControls()
            hideSystemBars()
        }
    }

    private fun scheduleAutoHideLandscape() {
        handler.removeCallbacks(autoHideLandscapeRunnable)
        val currentItem = LionPlayerManager.getCurrentItem()
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (currentItem?.isVideo == true && isLandscape && !isKidsLocked) {
            handler.postDelayed(autoHideLandscapeRunnable, 2500L)
        }
    }

    // Long press fast-forward
    private var isLongPressSpeeding = false
    private var originalSpeedBeforeLongPress = 1.0f
    private val longPressRunnable = Runnable {
        if (!isDraggingVertical && !isDraggingHorizontal && !isPinching &&
            videoScale <= 1.05f && !isKidsLocked && !isLocked
        ) {
            val p = LionPlayerManager.player
            if (p?.isPlaying == true) {
                isLongPressSpeeding = true
                originalSpeedBeforeLongPress = p.playbackParameters.speed
                val targetSpeed = prefs.getFloat("long_press_speed", 2.0f)
                val speedLabel = prefs.getString("long_press_speed_str", "2x") ?: "${targetSpeed}x"
                p.playbackParameters = PlaybackParameters(targetSpeed)

                binding.hudFastForward.visibility = View.VISIBLE
                binding.tvFastForwardValue.text = "$speedLabel ⏩ تسريع"
                triggerHapticFeedback()
            }
        }
    }

    private var lastCheckedResumeItemId: Long = -1L

    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            LionPlayerManager.player?.let { p ->
                if (p.isPlaying) {
                    val current = p.currentPosition
                    val total = p.duration.coerceAtLeast(0L)
                    binding.playerSeekBar.max = total.toInt()
                    binding.playerSeekBar.progress = current.toInt()
                    binding.tvCurrentTime.text = formatDuration(current)
                    binding.tvTotalTime.text = formatDuration(total)

                    // A-B Repeat
                    if (isAbRepeatActive && abPointB > abPointA) {
                        if (current >= abPointB || current < abPointA - 500L) {
                            p.seekTo(abPointA)
                        }
                    }

                    // Sleep Timer
                    if (isSleepTimerActive) {
                        val remainingMs = sleepTimerEndTimeMs - SystemClock.elapsedRealtime()
                        if (remainingMs <= 0L) {
                            isSleepTimerActive = false
                            binding.btnSleepTimer.setColorFilter(Color.WHITE)
                            LionPlayerManager.stop(this@PlayerActivity)
                            Toast.makeText(
                                this@PlayerActivity,
                                "انتهى مؤقت النوم: تم إيقاف التشغيل",
                                Toast.LENGTH_LONG
                            ).show()
                            finish()
                            return
                        } else if (remainingMs <= 45000L) {
                            val ratio = (remainingMs.toFloat() / 45000f).coerceIn(0f, 1f)
                            applyVolumePercent(sleepTimerInitialVolumePercent * ratio)
                        }
                    }
                }
            }
            handler.postDelayed(this, 250)
        }
    }

    private var gestureStartX = 0f
    private var initialGestureY = 0f
    private var isDraggingVertical = false
    private var isDraggingHorizontal = false
    private var gestureMode = 0
    private val GESTURE_VOLUME = 1
    private val GESTURE_BRIGHTNESS = 2
    private var initialVolume = 0
    private var initialBrightness = 0.5f
    private var initialSeekPosition = 0L
    private var targetSeekPosition = 0L
    private var lastSeekDispatchTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("lion_player_prefs", Context.MODE_PRIVATE)
        isFromVault = intent.getBooleanExtra(EXTRA_FROM_VAULT, false)

        applySecureWindowFlag()
        applySystemBarsVisibility()

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        initAudioManager()
        setupListeners()
        setupGestureDetection()

        val p = LionPlayerManager.getOrCreatePlayer(this)
        binding.playerView.player = p

        handleIntent(intent)
    }

    /**
     * حماية الشاشة للمحتوى المحمي:
     * يُفعَّل FLAG_SECURE عند تشغيل ملف من الخزينة السرية (أو دائماً داخل الخزينة).
     */
    private fun applySecureWindowFlag() {
        val vaultContent = isFromVault || LionPlayerManager.isCurrentItemFromVault()
        if (vaultContent && VaultSecurityManager.isSecureWindowEnabled(this)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBarsVisibility()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBarsVisibility()
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            scheduleAutoHideLandscape()
        }
    }

    private fun applySystemBarsVisibility() {
        if (isInFloatingWindow()) return
        if (isKidsLocked) {
            hideSystemBars()
            return
        }
        val currentItem = LionPlayerManager.getCurrentItem()
        val isVideo = currentItem?.isVideo ?: true
        val orientation = resources.configuration.orientation

        if (isVideo && orientation == Configuration.ORIENTATION_LANDSCAPE) {
            hideSystemBars()
        } else {
            showSystemNavBars()
        }
    }

    private fun hideSystemBars() {
        isSystemNavHidden = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun showSystemNavBars() {
        isSystemNavHidden = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_VISIBLE or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    private fun isInFloatingWindow(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val pip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) isInPictureInPictureMode else false
            pip || isInMultiWindowMode
        } else false
    }

    fun hideAllControls() {
        controlsVisible = false
        binding.topControls.visibility = View.GONE
        binding.bottomControls.visibility = View.GONE
        binding.layoutLyrics.visibility = View.GONE
        binding.btnUnlock.visibility = View.GONE
        binding.hudSeek.visibility = View.GONE
        binding.hudVolume.visibility = View.GONE
        binding.hudBrightness.visibility = View.GONE
        binding.hudFastForward.visibility = View.GONE
        binding.hudZoom.visibility = View.GONE
        binding.layoutThumbnailPreview.visibility = View.GONE
    }

    fun showAllControls() {
        if (isKidsLocked) return
        controlsVisible = true
        binding.topControls.visibility = View.VISIBLE
        binding.bottomControls.visibility = View.VISIBLE
        val currentItem = LionPlayerManager.getCurrentItem()
        if (currentItem?.isVideo == false) {
            checkAndLoadLyrics(currentItem)
        }
        scheduleAutoHideLandscape()
    }

    @Suppress("DEPRECATION")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            hideAllControls()
            binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
            showAllControls()
            applySystemBarsVisibility()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val currentItem = LionPlayerManager.getCurrentItem()
        // ملفات الخزينة لا تُنقل إلى الوضع العائم (حماية إضافية)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            currentItem?.isVideo == true &&
            LionPlayerManager.player?.isPlaying == true &&
            !isKidsLocked &&
            !LionPlayerManager.isVaultItem(currentItem)
        ) {
            enterPipMode()
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            hideAllControls()
            val builder = PictureInPictureParams.Builder()

            val format = LionPlayerManager.player?.videoFormat
            if (format != null && format.width > 0 && format.height > 0) {
                val ratio = format.width.toFloat() / format.height.toFloat()
                if (ratio in 0.42f..2.38f) {
                    builder.setAspectRatio(Rational(format.width, format.height))
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setSeamlessResizeEnabled(true)
                builder.setAutoEnterEnabled(true)
            }

            val rect = Rect()
            binding.playerView.getGlobalVisibleRect(rect)
            builder.setSourceRectHint(rect)

            try {
                enterPictureInPictureMode(builder.build())
            } catch (e: Exception) {
                @Suppress("DEPRECATION")
                enterPictureInPictureMode()
            }
        } else {
            Toast.makeText(this, "خاصية النافذة العائمة غير مدعومة", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIntent(it) }
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun handleIntent(intent: Intent) {
        isFromVault = intent.getBooleanExtra(EXTRA_FROM_VAULT, isFromVault)

        val incomingList = intent.getSerializableExtra("PLAYLIST") as? ArrayList<*>
        val list = incomingList?.filterIsInstance<LionMediaItem>()?.let { ArrayList(it) }
        val index = intent.getIntExtra("CURRENT_INDEX", -1)

        if (list != null && index >= 0) {
            LionPlayerManager.playPlaylist(this, list, index)
        } else {
            LionPlayerManager.getCurrentItem()?.let { updateUIForItem(it) }
        }
        applySecureWindowFlag()
        updatePlayPauseButton(LionPlayerManager.player?.isPlaying == true)
        updateRepeatButtonUI()
        updateAbRepeatButtonUI()
        updateScreenLock()
        scheduleAutoHideLandscape()
    }

    private fun initViews() {
        binding.btnDecoder.text = LionPlayerManager.currentDecoderMode
        updateDecoderBadge(LionPlayerManager.currentDecoderMode)
        updateRepeatButtonUI()
        updateAbRepeatButtonUI()
    }

    private fun initAudioManager() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxSystemVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        val currVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: (maxSystemVolume / 2)
        currentTotalVolumePercent = (currVol.toFloat() / maxSystemVolume.toFloat()) * 100f
    }

    private fun initAudioEffects() {
        try {
            val sessionId = LionPlayerManager.player?.audioSessionId ?: 0
            if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != 0) {
                if (equalizer == null) equalizer = Equalizer(0, sessionId).apply { enabled = true }
                if (bassBoost == null) bassBoost = BassBoost(0, sessionId).apply { enabled = true }
                if (presetReverb == null) presetReverb = PresetReverb(0, sessionId).apply { enabled = true }
                if (loudnessEnhancer == null) {
                    loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                        enabled = currentTotalVolumePercent > 100f
                        if (currentTotalVolumePercent > 100f) {
                            val boostRatio = (currentTotalVolumePercent - 100f) / 100f
                            setTargetGain((boostRatio * 1000f).toInt())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // تجاهل مقصود
        }
    }

    private fun applyVolumePercent(targetPercent: Float) {
        val volumeBoostEnabled = prefs.getBoolean("volume_boost_enabled", true)
        val maxPercent = if (volumeBoostEnabled) 200f else 100f
        val clampedPercent = targetPercent.coerceIn(0f, maxPercent)
        currentTotalVolumePercent = clampedPercent

        audioManager?.let { am ->
            if (clampedPercent <= 100f) {
                val targetSysVol = kotlin.math.round((clampedPercent / 100f) * maxSystemVolume)
                    .toInt().coerceIn(0, maxSystemVolume)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, targetSysVol, 0)
                try {
                    loudnessEnhancer?.setTargetGain(0)
                    loudnessEnhancer?.enabled = false
                } catch (e: Exception) {
                    // تجاهل مقصود
                }
            } else {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, maxSystemVolume, 0)
                val boostRatio = (clampedPercent - 100f) / 100f
                initAudioEffects()
                try {
                    loudnessEnhancer?.apply {
                        setTargetGain((boostRatio * 1000f).toInt())
                        enabled = true
                    }
                } catch (e: Exception) {
                    // تجاهل مقصود
                }
            }
        }

        binding.hudVolume.visibility = View.VISIBLE
        binding.volumeProgressBar.max = if (volumeBoostEnabled) 200 else 100
        binding.volumeProgressBar.progress = clampedPercent.toInt()
        if (clampedPercent > 100f) {
            binding.tvVolumeValue.text = "${clampedPercent.toInt()}% 🔊 مضاعف"
            binding.tvVolumeValue.setTextColor(Color.parseColor("#FF9800"))
        } else {
            binding.tvVolumeValue.text = "${clampedPercent.toInt()}%"
            binding.tvVolumeValue.setTextColor(Color.WHITE)
        }
    }

    private fun updateBrightnessByPercent(deltaPercent: Float) {
        val initialPercent = initialBrightness * 100f
        val targetPercent = (initialPercent + deltaPercent).coerceIn(1f, 100f)
        val lp = window.attributes
        lp.screenBrightness = (targetPercent / 100f).coerceIn(0.01f, 1.0f)
        window.attributes = lp

        binding.hudBrightness.visibility = View.VISIBLE
        binding.brightnessProgressBar.progress = targetPercent.toInt()
        binding.tvBrightnessValue.text = "${targetPercent.toInt()}%"
    }

    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                binding.playerView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        } catch (e: Exception) {
            // تجاهل مقصود
        }
    }

    private fun applyVideoTransform() {
        val targetView: View = binding.playerView.videoSurfaceView ?: binding.playerView
        targetView.pivotX = targetView.width / 2f
        targetView.pivotY = targetView.height / 2f
        targetView.scaleX = videoScale
        targetView.scaleY = videoScale

        if (!isInFloatingWindow() && videoScale <= 1.05f && videoScale >= 0.95f) {
            videoScale = 1.0f
            targetView.scaleX = 1.0f
            targetView.scaleY = 1.0f
            videoTranslationX = 0f
            videoTranslationY = 0f
            targetView.translationX = 0f
            targetView.translationY = 0f
        } else {
            val maxTransX = (targetView.width * kotlin.math.abs(videoScale - 1f)) / 2f
            val maxTransY = (targetView.height * kotlin.math.abs(videoScale - 1f)) / 2f
            videoTranslationX = videoTranslationX.coerceIn(-maxTransX, maxTransX)
            videoTranslationY = videoTranslationY.coerceIn(-maxTransY, maxTransY)
            targetView.translationX = videoTranslationX
            targetView.translationY = videoTranslationY
        }

        if (!isInFloatingWindow()) {
            if (videoScale > 1.0f) {
                binding.hudZoom.visibility = View.VISIBLE
                binding.tvZoomValue.text = "تكبير ${(videoScale * 100).toInt()}%"
            } else {
                binding.hudZoom.visibility = View.GONE
            }
        }
    }

    private fun checkResumePositionForTrack(item: LionMediaItem) {
        if (!item.isVideo || item.id == lastCheckedResumeItemId) return
        if (LionPlayerManager.isVaultItem(item)) return // لا نُسجّل/نستأنف ملفات الخزينة
        lastCheckedResumeItemId = item.id

        val savedPos = prefs.getLong("video_resume_pos_${item.id}", 0L)
        if (savedPos > 3000L) {
            val resumeMode = prefs.getInt("resume_playback_mode", 0)
            when (resumeMode) {
                0 -> {
                    LionPlayerManager.player?.seekTo(savedPos)
                    Toast.makeText(this, "تم استئناف المقطع من ${formatDuration(savedPos)}", Toast.LENGTH_SHORT).show()
                }
                1 -> LionPlayerManager.player?.seekTo(0L)
                2 -> {
                    val p = LionPlayerManager.player
                    p?.pause()
                    AlertDialog.Builder(this)
                        .setTitle("استئناف المشاهدة")
                        .setMessage("هل ترغب في متابعة المقطع من حيث توقفت (${formatDuration(savedPos)})؟")
                        .setPositiveButton("استئناف") { _, _ ->
                            p?.seekTo(savedPos)
                            p?.play()
                        }
                        .setNegativeButton("من البداية") { _, _ ->
                            p?.seekTo(0L)
                            p?.play()
                        }
                        .setCancelable(false)
                        .show()
                }
            }
        }
    }

    private fun saveCurrentVideoPosition() {
        val currentItem = LionPlayerManager.getCurrentItem()
        if (currentItem?.isVideo == true && !LionPlayerManager.isVaultItem(currentItem)) {
            val p = LionPlayerManager.player
            val pos = p?.currentPosition ?: 0L
            val dur = p?.duration ?: 0L
            if (dur > 0 && pos > 3000L && pos < dur - 5000L) {
                prefs.edit().putLong("video_resume_pos_${currentItem.id}", pos).apply()
            } else if (pos >= dur - 5000L) {
                prefs.edit().remove("video_resume_pos_${currentItem.id}").apply()
            }
        }
    }

    private fun updateScreenLock() {
        val isVideo = LionPlayerManager.getCurrentItem()?.isVideo ?: true
        if (isVideo) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.playerView.keepScreenOn = true
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            binding.playerView.keepScreenOn = false
        }
    }

    private fun updateFavoriteIcon(isFav: Boolean) {
        if (isFav) {
            binding.btnFavorite.setImageResource(R.drawable.ic_favorite)
            binding.btnFavorite.setColorFilter(Color.parseColor("#FF9800"))
        } else {
            binding.btnFavorite.setImageResource(R.drawable.ic_favorite_border)
            binding.btnFavorite.setColorFilter(Color.WHITE)
        }
    }

    private fun updateRepeatButtonUI() {
        binding.btnRepeat.setColorFilter(
            if (LionPlayerManager.isRepeatMode) Color.parseColor("#FF9800") else Color.WHITE
        )
    }

    private fun updateAbRepeatButtonUI() {
        when {
            isAbRepeatActive -> binding.btnAbRepeat.setColorFilter(Color.parseColor("#FF9800"))
            abPointA != -1L -> binding.btnAbRepeat.setColorFilter(Color.parseColor("#FFD54F"))
            else -> binding.btnAbRepeat.setColorFilter(Color.WHITE)
        }
    }

    private fun clearAbRepeat() {
        abPointA = -1L
        abPointB = -1L
        isAbRepeatActive = false
        updateAbRepeatButtonUI()
    }

    private fun updateDecoderBadge(mode: String) {
        val bg = binding.btnDecoder.background as? GradientDrawable
        when (mode) {
            "HW" -> bg?.setColor(Color.parseColor("#FF9800"))
            "HW+" -> bg?.setColor(Color.parseColor("#E65100"))
            "SW" -> bg?.setColor(Color.parseColor("#0288D1"))
            else -> bg?.setColor(Color.parseColor("#FF9800"))
        }
    }

    private fun updateUIForItem(item: LionMediaItem) {
        val isVaultItem = LionPlayerManager.isVaultItem(item)
        applySecureWindowFlag()

        binding.tvVideoTitle.text = item.title
        val dur = item.duration.coerceAtLeast(0L)
        binding.tvTotalTime.text = formatDuration(dur)
        binding.playerSeekBar.max = dur.toInt()
        val curr = LionPlayerManager.player?.currentPosition ?: 0L
        binding.playerSeekBar.progress = curr.toInt()
        binding.tvCurrentTime.text = formatDuration(curr)

        val favSet = prefs.getStringSet("favorite_ids", setOf()) ?: setOf()
        item.isFavorite = favSet.contains(item.id.toString())
        updateFavoriteIcon(item.isFavorite)

        val videoControlsVis = if (item.isVideo) View.VISIBLE else View.GONE
        binding.btnRotate.visibility = videoControlsVis
        // ملفات الخزينة: لا نافذة عائمة ولا مشاركة
        binding.btnPip.visibility = if (item.isVideo && !isVaultItem) View.VISIBLE else View.GONE
        binding.btnResize.visibility = videoControlsVis
        binding.btnToggleNav.visibility = videoControlsVis
        binding.btnSubtitles.visibility = videoControlsVis
        binding.btnFavorite.visibility = if (isVaultItem) View.GONE else View.VISIBLE
        binding.btnLyricsToggle.visibility = if (!item.isVideo) View.VISIBLE else View.GONE

        videoScale = 1.0f
        videoTranslationX = 0f
        videoTranslationY = 0f
        applyVideoTransform()

        clearAbRepeat()

        if (item.isVideo) {
            applySystemBarsVisibility()
            binding.layoutLyrics.visibility = View.GONE
            checkResumePositionForTrack(item)
            scheduleAutoHideLandscape()
        } else {
            showSystemNavBars()
            checkAndLoadLyrics(item)
        }
        updateScreenLock()
    }

    private fun checkAndLoadLyrics(item: LionMediaItem) {
        val showLyricsPref = prefs.getBoolean("show_lyrics", true)
        if (!showLyricsPref || item.isVideo) {
            binding.layoutLyrics.visibility = View.GONE
            return
        }
        val lyricsFound = extractLyrics(item)
        if (lyricsFound.isNotEmpty()) {
            binding.tvLyrics.text = lyricsFound
            binding.layoutLyrics.visibility = View.VISIBLE
        } else {
            binding.layoutLyrics.visibility = View.GONE
        }
    }

    private fun extractLyrics(item: LionMediaItem): String {
        try {
            if (item.path.isNotEmpty()) {
                val lrcFile = File(item.path.substringBeforeLast('.') + ".lrc")
                if (lrcFile.exists()) return lrcFile.readText()
                val txtFile = File(item.path.substringBeforeLast('.') + ".txt")
                if (txtFile.exists()) return txtFile.readText()
            }
            val retriever = MediaMetadataRetriever()
            if (item.path.isNotEmpty() && File(item.path).exists()) {
                retriever.setDataSource(item.path)
            } else {
                retriever.setDataSource(this, Uri.parse(item.uri))
            }
            val comp = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPILATION)
            retriever.release()
            if (!comp.isNullOrEmpty()) return comp
        } catch (e: Exception) {
            // تجاهل مقصود
        }
        return ""
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        binding.btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        updateScreenLock()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { handleBackAction() }
        binding.btnSubtitles.setOnClickListener { showSubtitleSettingsSheet() }
        binding.btnAudioTracks.setOnClickListener { showAudioTracksDialog() }

        binding.btnMore.setOnClickListener {
            PlayerSettingsBottomSheet.show(
                activity = this,
                onEqualizerClick = { showAudioSettingsDialog() },
                onVolumeBoostChanged = { isBoosted ->
                    if (!isBoosted && currentTotalVolumePercent > 100f) applyVolumePercent(100f)
                },
                onSettingChanged = {
                    LionPlayerManager.getCurrentItem()?.let { updateUIForItem(it) }
                }
            )
        }

        binding.btnDecoder.setOnClickListener {
            val nextMode = when (LionPlayerManager.currentDecoderMode) {
                "HW" -> "HW+"
                "HW+" -> "SW"
                else -> "HW"
            }
            val newPlayer = LionPlayerManager.switchDecoder(this, nextMode)
            binding.playerView.player = newPlayer
            binding.btnDecoder.text = nextMode
            updateDecoderBadge(nextMode)
            Toast.makeText(this, "تم التبديل إلى ترميز: $nextMode", Toast.LENGTH_SHORT).show()
        }

        binding.btnAbRepeat.setOnClickListener {
            val p = LionPlayerManager.player ?: return@setOnClickListener
            when {
                abPointA == -1L -> {
                    abPointA = p.currentPosition
                    updateAbRepeatButtonUI()
                    Toast.makeText(
                        this,
                        "تم تحديد النقطة A: ${formatDuration(abPointA)}\nاضغط مجدداً لتحديد النقطة B",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                abPointB == -1L -> {
                    val currentPos = p.currentPosition
                    if (currentPos <= abPointA + 500L) {
                        Toast.makeText(this, "النقطة B يجب أن تكون بعد النقطة A بمقدار ثانية على الأقل", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    abPointB = currentPos
                    isAbRepeatActive = true
                    p.seekTo(abPointA)
                    updateAbRepeatButtonUI()
                    Toast.makeText(
                        this,
                        "تم تفعيل تكرار A-B: من ${formatDuration(abPointA)} إلى ${formatDuration(abPointB)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                else -> {
                    clearAbRepeat()
                    Toast.makeText(this, "تم إلغاء تكرار A-B", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSleepTimer.setOnClickListener { showSleepTimerDialog() }

        binding.btnKidsLock.setOnClickListener {
            isKidsLocked = true
            kidsLockStep = 0
            hideAllControls()
            hideSystemBars()
            showKidsLockHud(0)
            triggerHapticFeedback()
            Toast.makeText(this, "تم تفعيل قفل الأطفال الذكي", Toast.LENGTH_SHORT).show()
        }

        binding.btnRepeat.setOnClickListener {
            val isRepeating = LionPlayerManager.toggleRepeat()
            updateRepeatButtonUI()
            Toast.makeText(this, if (isRepeating) "تم تفعيل تكرار المقطع" else "تم إيقاف التكرار", Toast.LENGTH_SHORT).show()
        }

        binding.btnFavorite.setOnClickListener {
            val currentItem = LionPlayerManager.getCurrentItem() ?: return@setOnClickListener
            val favSet = prefs.getStringSet("favorite_ids", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            val idStr = currentItem.id.toString()
            if (currentItem.isFavorite) {
                favSet.remove(idStr)
                currentItem.isFavorite = false
                Toast.makeText(this, "تمت الإزالة من المفضلة", Toast.LENGTH_SHORT).show()
            } else {
                favSet.add(idStr)
                currentItem.isFavorite = true
                Toast.makeText(this, "تمت الإضافة إلى المفضلة", Toast.LENGTH_SHORT).show()
            }
            prefs.edit().putStringSet("favorite_ids", favSet).apply()
            updateFavoriteIcon(currentItem.isFavorite)
        }

        binding.btnRotate.setOnClickListener {
            rotationState = (rotationState + 1) % 3
            when (rotationState) {
                0 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                1 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                2 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            applySystemBarsVisibility()
        }

        binding.btnToggleNav.setOnClickListener {
            if (isSystemNavHidden) showSystemNavBars() else hideSystemBars()
        }

        binding.btnLyricsToggle.setOnClickListener {
            val current = LionPlayerManager.getCurrentItem() ?: return@setOnClickListener
            if (binding.layoutLyrics.visibility == View.VISIBLE) {
                binding.layoutLyrics.visibility = View.GONE
            } else {
                checkAndLoadLyrics(current)
                if (binding.layoutLyrics.visibility != View.VISIBLE) {
                    Toast.makeText(this, "لا توجد كلمات لهذا المقطع", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnPlayPause.setOnClickListener { LionPlayerManager.togglePlayPause(this) }
        binding.btnNext.setOnClickListener { LionPlayerManager.playNext(this) }
        binding.btnPrev.setOnClickListener { LionPlayerManager.playPrevious(this) }

        binding.btnRewind10.setOnClickListener { triggerDoubleTapRewind() }
        binding.btnForward10.setOnClickListener { triggerDoubleTapForward() }

        binding.btnEqualizer.setOnClickListener { showAudioSettingsDialog() }

        binding.btnLock.setOnClickListener {
            isLocked = true
            hideAllControls()
            binding.btnUnlock.visibility = View.VISIBLE
            updateScreenLock()
        }

        binding.btnUnlock.setOnClickListener {
            isLocked = false
            binding.btnUnlock.visibility = View.GONE
            showAllControls()
            updateScreenLock()
        }

        binding.btnPip.setOnClickListener { enterPipMode() }

        binding.btnResize.setOnClickListener {
            resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
            val (mode, modeName) = resizeModes[resizeModeIndex]
            binding.playerView.resizeMode = mode
            Toast.makeText(this, "أبعاد الفيديو: $modeName", Toast.LENGTH_SHORT).show()
        }

        binding.btnSpeed.setOnClickListener {
            val speeds = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
            AlertDialog.Builder(this)
                .setTitle("سرعة التشغيل")
                .setItems(speeds) { _, which ->
                    val speedVal = when (which) {
                        0 -> 0.5f
                        1 -> 0.75f
                        2 -> 1.0f
                        3 -> 1.25f
                        4 -> 1.5f
                        5 -> 2.0f
                        else -> 1.0f
                    }
                    LionPlayerManager.player?.playbackParameters = PlaybackParameters(speedVal)
                    binding.btnSpeed.text = speeds[which]
                }
                .show()
        }

        // Timeline thumbnail scrubbing
        binding.playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val p = LionPlayerManager.player
                    p?.seekTo(progress.toLong())
                    binding.tvCurrentTime.text = formatDuration(progress.toLong())

                    val currentItem = LionPlayerManager.getCurrentItem()
                    if (currentItem?.isVideo == true) {
                        val seekBar = binding.playerSeekBar
                        val availableWidth =
                            (seekBar.width - seekBar.paddingStart - seekBar.paddingEnd).coerceAtLeast(1)
                        val progressRatio =
                            if (seekBar.max > 0) progress.toFloat() / seekBar.max else 0f
                        val thumbCenter =
                            seekBar.left + seekBar.paddingStart + (availableWidth * progressRatio)

                        val previewWidth = if (binding.layoutThumbnailPreview.width > 0) {
                            binding.layoutThumbnailPreview.width
                        } else {
                            (144 * resources.displayMetrics.density).toInt()
                        }
                        val minX = 16f
                        val maxX = (binding.playerContainer.width - previewWidth - 16).coerceAtLeast(16).toFloat()
                        binding.layoutThumbnailPreview.x = (thumbCenter - previewWidth / 2f).coerceIn(minX, maxX)
                        binding.tvThumbnailTime.text = formatDuration(progress.toLong())

                        binding.ivThumbnailPreview.load(Uri.parse(currentItem.uri)) {
                            videoFrameMillis(progress.toLong())
                            size(288, 192)
                            placeholder(R.drawable.bg_thumb_round)
                            error(R.drawable.bg_thumb_round)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                val currentItem = LionPlayerManager.getCurrentItem()
                if (currentItem?.isVideo == true) {
                    binding.layoutThumbnailPreview.visibility = View.VISIBLE
                    binding.layoutThumbnailPreview.alpha = 0f
                    binding.layoutThumbnailPreview.animate().alpha(1f).setDuration(150).start()
                }
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                LionPlayerManager.updateNotification(this@PlayerActivity)
                binding.layoutThumbnailPreview.animate()
                    .alpha(0f).setDuration(200)
                    .withEndAction { binding.layoutThumbnailPreview.visibility = View.GONE }
                    .start()
            }
        })
    }

    // ============================ Sleep Timer ============================
    private fun showSleepTimerDialog() {
        val options = if (isSleepTimerActive) {
            arrayOf("15 دقيقة", "30 دقيقة", "45 دقيقة", "60 دقيقة", "إيقاف مؤقت النوم")
        } else {
            arrayOf("15 دقيقة", "30 دقيقة", "45 دقيقة", "60 دقيقة")
        }

        val title = if (isSleepTimerActive) {
            val remainingMins = ((sleepTimerEndTimeMs - SystemClock.elapsedRealtime()) / 60000L).coerceAtLeast(1)
            "مؤقت النوم (متبقي $remainingMins دقيقة)"
        } else {
            "ضبط مؤقت النوم"
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                if (isSleepTimerActive && which == 4) {
                    cancelSleepTimer()
                    Toast.makeText(this, "تم إلغاء مؤقت النوم", Toast.LENGTH_SHORT).show()
                } else {
                    val minutes = when (which) {
                        0 -> 15
                        1 -> 30
                        2 -> 45
                        3 -> 60
                        else -> 15
                    }
                    startSleepTimer(minutes)
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun startSleepTimer(minutes: Int) {
        isSleepTimerActive = true
        sleepTimerInitialVolumePercent = currentTotalVolumePercent
        val durationMs = minutes * 60 * 1000L
        sleepTimerEndTimeMs = SystemClock.elapsedRealtime() + durationMs
        binding.btnSleepTimer.setColorFilter(Color.parseColor("#FF9800"))
        Toast.makeText(this, "تم تفعيل مؤقت النوم: $minutes دقيقة", Toast.LENGTH_SHORT).show()
    }

    private fun cancelSleepTimer() {
        isSleepTimerActive = false
        binding.btnSleepTimer.setColorFilter(Color.WHITE)
        applyVolumePercent(sleepTimerInitialVolumePercent)
    }

    // ============================ Kids Lock ============================
    private fun showKidsLockHud(step: Int) {
        handler.removeCallbacks(hideKidsLockHudRunnable)
        binding.hudKidsLockHint.visibility = View.VISIBLE
        binding.hudKidsLockHint.alpha = 1f
        val progressText = when (step) {
            1 -> "● ○ ○ ○"
            2 -> "● ● ○ ○"
            3 -> "● ● ● ○"
            else -> "○ ○ ○ ○"
        }
        binding.tvKidsLockProgress.text = progressText
        handler.postDelayed(hideKidsLockHudRunnable, 3000)
    }

    private fun handleKidsLockCornerTouch(x: Float, y: Float) {
        val w = binding.playerView.width.toFloat().coerceAtLeast(1f)
        val h = binding.playerView.height.toFloat().coerceAtLeast(1f)

        val inCorner1 = x < w * 0.28f && y < h * 0.28f
        val inCorner2 = x > w * 0.72f && y < h * 0.28f
        val inCorner3 = x > w * 0.72f && y > h * 0.72f
        val inCorner4 = x < w * 0.28f && y > h * 0.72f

        when (kidsLockStep) {
            0 -> {
                if (inCorner1) {
                    kidsLockStep = 1
                    triggerHapticFeedback()
                    showKidsLockHud(1)
                } else {
                    showKidsLockHud(0)
                }
            }
            1 -> {
                if (inCorner2) {
                    kidsLockStep = 2
                    triggerHapticFeedback()
                    showKidsLockHud(2)
                } else if (!inCorner1) {
                    kidsLockStep = 0
                    showKidsLockHud(0)
                }
            }
            2 -> {
                if (inCorner3) {
                    kidsLockStep = 3
                    triggerHapticFeedback()
                    showKidsLockHud(3)
                } else {
                    kidsLockStep = 0
                    showKidsLockHud(0)
                }
            }
            3 -> {
                if (inCorner4) {
                    isKidsLocked = false
                    kidsLockStep = 0
                    binding.hudKidsLockHint.visibility = View.GONE
                    triggerHapticFeedback()
                    showAllControls()
                    applySystemBarsVisibility()
                    Toast.makeText(this, "تم إلغاء قفل الأطفال بنجاح", Toast.LENGTH_SHORT).show()
                } else {
                    kidsLockStep = 0
                    showKidsLockHud(0)
                }
            }
        }
    }

    // ============================ Double tap ============================
    private fun triggerDoubleTapForward() {
        val p = LionPlayerManager.player ?: return
        handler.removeCallbacks(hideDoubleTapRightRunnable)
        doubleTapForwardCount++
        val totalSec = doubleTapForwardCount * 10
        p.seekTo((p.currentPosition + 10000L).coerceAtMost(p.duration))
        LionPlayerManager.updateNotification(this)

        binding.tvDoubleTapRightText.text = "+$totalSec ثوانٍ"
        binding.hudDoubleTapRight.visibility = View.VISIBLE
        binding.hudDoubleTapRight.alpha = 0f
        binding.hudDoubleTapRight.scaleX = 0.7f
        binding.hudDoubleTapRight.scaleY = 0.7f
        binding.hudDoubleTapRight.animate()
            .alpha(1f).scaleX(1.08f).scaleY(1.08f).setDuration(160)
            .withEndAction {
                binding.hudDoubleTapRight.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
            }
            .start()

        handler.postDelayed(hideDoubleTapRightRunnable, 750)
    }

    private fun triggerDoubleTapRewind() {
        val p = LionPlayerManager.player ?: return
        handler.removeCallbacks(hideDoubleTapLeftRunnable)
        doubleTapRewindCount++
        val totalSec = doubleTapRewindCount * 10
        p.seekTo((p.currentPosition - 10000L).coerceAtLeast(0L))
        LionPlayerManager.updateNotification(this)

        binding.tvDoubleTapLeftText.text = "-$totalSec ثوانٍ"
        binding.hudDoubleTapLeft.visibility = View.VISIBLE
        binding.hudDoubleTapLeft.alpha = 0f
        binding.hudDoubleTapLeft.scaleX = 0.7f
        binding.hudDoubleTapLeft.scaleY = 0.7f
        binding.hudDoubleTapLeft.animate()
            .alpha(1f).scaleX(1.08f).scaleY(1.08f).setDuration(160)
            .withEndAction {
                binding.hudDoubleTapLeft.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
            }
            .start()

        handler.postDelayed(hideDoubleTapLeftRunnable, 750)
    }

    // ============================ Subtitles ============================
    private fun showSubtitleSettingsSheet() {
        SubtitleSettingsBottomSheet.show(
            activity = this,
            onPickLocalClick = {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                @Suppress("DEPRECATION")
                startActivityForResult(Intent.createChooser(intent, "اختر ملف الترجمة"), SUBTITLE_PICK_REQ)
            },
            onOpenSubtitlesClick = { showOpenSubtitlesSearchDialog() },
            onSelectTracksClick = { showSubtitleTracksDialog() },
            onStyleChanged = { style, textSizeSp ->
                binding.playerView.subtitleView?.setStyle(style)
                binding.playerView.subtitleView?.setFixedTextSize(
                    TypedValue.COMPLEX_UNIT_SP, textSizeSp
                )
            }
        )
    }

    private fun showSubtitleTracksDialog() {
        val player = LionPlayerManager.player ?: return
        val tracks = player.currentTracks
        val subtitleGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

        val trackLabels = mutableListOf<String>()
        val groupAndIndices = mutableListOf<Pair<TrackGroup, Int>>()
        trackLabels.add("تعطيل الترجمة (إيقاف)")
        groupAndIndices.add(Pair(TrackGroup("disabled", androidx.media3.common.Format.Builder().build()), -1))

        var selectedIdx = 0
        var counter = 1

        for (group in subtitleGroups) {
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val isSelected = group.isTrackSelected(i)
                val label = format.label ?: format.language ?: "مسار ترجمة #$counter"
                trackLabels.add("$label${if (isSelected) " ✓" else ""}")
                groupAndIndices.add(Pair(group.mediaTrackGroup, i))
                if (isSelected) selectedIdx = trackLabels.size - 1
                counter++
            }
        }

        AlertDialog.Builder(this)
            .setTitle("مسارات الترجمة المتاحة")
            .setSingleChoiceItems(trackLabels.toTypedArray(), selectedIdx) { dialog, which ->
                if (which == 0) {
                    LionPlayerManager.disableSubtitles()
                } else {
                    val (group, trackIdx) = groupAndIndices[which]
                    LionPlayerManager.selectSubtitleTrack(group, trackIdx)
                }
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showAudioTracksDialog() {
        val player = LionPlayerManager.player ?: return
        val tracks = player.currentTracks
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

        val trackLabels = mutableListOf<String>()
        val groupAndIndices = mutableListOf<Pair<TrackGroup, Int>>()
        var selectedIdx = 0
        var counter = 1

        for (group in audioGroups) {
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val isSelected = group.isTrackSelected(i)
                val lang = format.language ?: "غير محدد"
                val label = format.label ?: "صوت #$counter"
                trackLabels.add("$label [$lang]${if (isSelected) " ✓" else ""}")
                groupAndIndices.add(Pair(group.mediaTrackGroup, i))
                if (isSelected) selectedIdx = trackLabels.size - 1
                counter++
            }
        }

        if (trackLabels.isEmpty()) {
            Toast.makeText(this, "لا توجد مسارات صوتية بديلة", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("اختيار المسار الصوتي")
            .setSingleChoiceItems(trackLabels.toTypedArray(), selectedIdx) { dialog, which ->
                val (group, trackIdx) = groupAndIndices[which]
                LionPlayerManager.selectAudioTrack(group, trackIdx)
                dialog.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showOpenSubtitlesSearchDialog() {
        val searchBinding = DialogOpensubtitlesSearchBinding.inflate(layoutInflater)
        searchBinding.rvSubtitlesResults.layoutManager = LinearLayoutManager(this)

        val curItem = LionPlayerManager.getCurrentItem()
        searchBinding.etSearchQuery.setText(curItem?.title?.substringBeforeLast('.') ?: "")

        val dialog = AlertDialog.Builder(this)
            .setTitle("البحث في OpenSubtitles")
            .setView(searchBinding.root)
            .setNegativeButton("إلغاء", null)
            .create()

        val resultsList = mutableListOf<SubtitleResult>()
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val itemBinding = ItemOpensubtitleResultBinding.inflate(
                    android.view.LayoutInflater.from(parent.context), parent, false
                )
                return object : RecyclerView.ViewHolder(itemBinding.root) {}
            }

            override fun getItemCount(): Int = resultsList.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = resultsList[position]
                val itemBinding = ItemOpensubtitleResultBinding.bind(holder.itemView)
                itemBinding.tvSubFileName.text = item.fileName
                itemBinding.tvSubLanguage.text = "اللغة: ${item.language.uppercase(Locale.ROOT)}"

                val clickListener = View.OnClickListener {
                    Toast.makeText(this@PlayerActivity, "جاري تنزيل الترجمة...", Toast.LENGTH_SHORT).show()
                    OpenSubtitlesManager.downloadSubtitle(
                        context = this@PlayerActivity,
                        result = item,
                        onSuccess = { uri ->
                            runOnUiThread {
                                LionPlayerManager.attachCustomSubtitle(this@PlayerActivity, uri)
                                Toast.makeText(this@PlayerActivity, "تم تطبيق الترجمة بنجاح", Toast.LENGTH_LONG).show()
                                dialog.dismiss()
                            }
                        },
                        onError = { err ->
                            runOnUiThread {
                                Toast.makeText(this@PlayerActivity, "خطأ: $err", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
                itemBinding.root.setOnClickListener(clickListener)
                itemBinding.btnDownloadSub.setOnClickListener(clickListener)
            }
        }
        searchBinding.rvSubtitlesResults.adapter = adapter

        fun executeSearch() {
            val q = searchBinding.etSearchQuery.text.toString().trim()
            searchBinding.pbLoadingSubtitles.visibility = View.VISIBLE
            OpenSubtitlesManager.searchSubtitles(
                context = this,
                query = q,
                movieHash = null,
                onSuccess = { res ->
                    runOnUiThread {
                        searchBinding.pbLoadingSubtitles.visibility = View.GONE
                        resultsList.clear()
                        resultsList.addAll(res)
                        adapter.notifyDataSetChanged()
                    }
                },
                onError = { msg ->
                    runOnUiThread {
                        searchBinding.pbLoadingSubtitles.visibility = View.GONE
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        searchBinding.btnStartSearch.setOnClickListener { executeSearch() }
        dialog.show()
        executeSearch()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SUBTITLE_PICK_REQ && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                LionPlayerManager.attachCustomSubtitle(this, uri)
                Toast.makeText(this, "تم تحميل ملف الترجمة بنجاح", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ============================ Audio effects dialog ============================
    private fun showAudioSettingsDialog() {
        initAudioEffects()
        val eq = equalizer
        if (eq == null) {
            Toast.makeText(this, "معادل الصوت غير مدعوم أو لم يبدأ التشغيل بعد", Toast.LENGTH_SHORT).show()
            return
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("معادل الصوت المتقدم ومضاعفة الصوت")

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        val switchBoost = Switch(this).apply {
            text = "مضاعفة الصوت حتى 200% (تعزيز نقي ومتقدم)"
            isChecked = prefs.getBoolean("volume_boost_enabled", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("volume_boost_enabled", isChecked).apply()
                if (!isChecked && currentTotalVolumePercent > 100f) applyVolumePercent(100f)
            }
        }
        layout.addView(switchBoost)

        val tvBass = TextView(this).apply {
            val strength = bassBoost?.roundedStrength ?: 0
            text = "تضخيم الباس (Bass Boost): ${(strength / 10)}%"
            setTextColor(Color.parseColor("#FF9800"))
            textSize = 13f
            paint.isFakeBoldText = true
            setPadding(0, 16, 0, 4)
        }
        layout.addView(tvBass)

        val sbBass = SeekBar(this).apply {
            max = 1000
            progress = (bassBoost?.roundedStrength ?: 0).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        try {
                            bassBoost?.enabled = progress > 0
                            bassBoost?.setStrength(progress.toShort())
                            tvBass.text = "تضخيم الباس (Bass Boost): ${(progress / 10)}%"
                        } catch (e: Exception) {
                            // تجاهل مقصود
                        }
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        layout.addView(sbBass)

        val switchEnable = Switch(this).apply {
            text = "تفعيل المعادل الصوتي (Equalizer)"
            isChecked = eq.enabled
            setPadding(0, 16, 0, 0)
            setOnCheckedChangeListener { _, isChecked ->
                eq.enabled = isChecked
                bassBoost?.enabled = isChecked
            }
        }
        layout.addView(switchEnable)

        val numPresets = eq.numberOfPresets.toInt()
        if (numPresets > 0) {
            val presetNames = (0 until numPresets).map { eq.getPresetName(it.toShort()) }
            val tvPresets = TextView(this).apply {
                text = "الأنماط الجاهزة:"
                setTextColor(Color.parseColor("#FF9800"))
                textSize = 13f
                paint.isFakeBoldText = true
                setPadding(0, 16, 0, 8)
            }
            layout.addView(tvPresets)

            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presetNames)
            try {
                spinner.setSelection(eq.currentPreset.toInt().coerceIn(0, numPresets - 1))
            } catch (e: Exception) {
                // تجاهل مقصود
            }
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                    try {
                        eq.usePreset(position.toShort())
                    } catch (e: Exception) {
                        // تجاهل مقصود
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
            layout.addView(spinner)
        }

        val numBands = eq.numberOfBands.toInt()
        val range = eq.bandLevelRange
        val minLevel = range[0].toInt()
        val maxLevel = range[1].toInt()
        val totalRange = (maxLevel - minLevel).coerceAtLeast(1)

        val tvBandsTitle = TextView(this).apply {
            text = "النطاقات الترددية ($numBands نطاقات):"
            setTextColor(Color.parseColor("#FF9800"))
            textSize = 13f
            paint.isFakeBoldText = true
            setPadding(0, 16, 0, 8)
        }
        layout.addView(tvBandsTitle)

        for (band in 0 until numBands) {
            val centerFreq = eq.getCenterFreq(band.toShort())
            val freqText = if (centerFreq >= 1000000) {
                "${centerFreq / 1000000.0} kHz"
            } else {
                "${centerFreq / 1000} Hz"
            }

            val currentLevel = eq.getBandLevel(band.toShort()).toInt()
            val tvBandLabel = TextView(this).apply {
                val db = currentLevel / 100
                text = "$freqText (${if (db > 0) "+$db" else "$db"} dB)"
                textSize = 12f
                setPadding(0, 8, 0, 2)
            }
            layout.addView(tvBandLabel)

            val sbBand = SeekBar(this).apply {
                max = totalRange
                progress = currentLevel - minLevel
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val level = (progress + minLevel).toShort()
                            try {
                                eq.setBandLevel(band.toShort(), level)
                                val db = level / 100
                                tvBandLabel.text = "$freqText (${if (db > 0) "+$db" else "$db"} dB)"
                            } catch (e: Exception) {
                                // تجاهل مقصود
                            }
                        }
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            layout.addView(sbBand)
        }

        scroll.addView(layout)
        builder.setView(scroll)
        builder.setPositiveButton("تم", null)
        builder.show()
    }

    // ============================ Gestures ============================
    private fun setupGestureDetection() {
        scaleGestureDetector = ScaleGestureDetector(this, object :
            ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isPinching = true
                handler.removeCallbacks(longPressRunnable)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val currentItem = LionPlayerManager.getCurrentItem()
                if (currentItem?.isVideo == true && !isKidsLocked) {
                    videoScale = (videoScale * factor).coerceIn(1.0f, 5.0f)
                    applyVideoTransform()
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (videoScale <= 1.05f) {
                    videoScale = 1.0f
                    applyVideoTransform()
                }
                handler.postDelayed({ isPinching = false }, 200)
                handler.removeCallbacks(hideZoomHudRunnable)
                handler.postDelayed(hideZoomHudRunnable, 1500)
            }
        })

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isLocked && !isKidsLocked && !isLongPressSpeeding) {
                    if (controlsVisible) hideAllControls() else showAllControls()
                    applySystemBarsVisibility()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked || isKidsLocked) return true
                val screenWidth = resources.displayMetrics.widthPixels
                if (e.x > screenWidth / 2) {
                    triggerDoubleTapForward()
                } else {
                    triggerDoubleTapRewind()
                }
                return true
            }
        })

        binding.playerView.setOnTouchListener { _, event ->
            if (isKidsLocked) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    handleKidsLockCornerTouch(event.x, event.y)
                }
                return@setOnTouchListener true
            }

            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            if (isLocked) return@setOnTouchListener true

            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val currentItem = LionPlayerManager.getCurrentItem()
            val isVideo = currentItem?.isVideo == true

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartX = event.x
                    initialGestureY = event.y
                    lastTouchX = event.x
                    lastTouchY = event.y
                    isDraggingVertical = false
                    isDraggingHorizontal = false
                    gestureMode = 0
                    initialSeekPosition = LionPlayerManager.player?.currentPosition ?: 0L

                    audioManager?.let { am ->
                        initialVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    }
                    val lp = window.attributes
                    initialBrightness = if (lp.screenBrightness >= 0f) lp.screenBrightness else 0.5f

                    if (event.pointerCount == 1 && videoScale <= 1.05f &&
                        LionPlayerManager.player?.isPlaying == true
                    ) {
                        handler.postDelayed(longPressRunnable, 350)
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    handler.removeCallbacks(longPressRunnable)
                    binding.hudVolume.visibility = View.GONE
                    binding.hudBrightness.visibility = View.GONE
                    binding.hudSeek.visibility = View.GONE
                    isDraggingVertical = false
                    isDraggingHorizontal = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - gestureStartX
                    val dy = event.y - initialGestureY

                    if (kotlin.math.abs(dx) > 20 || kotlin.math.abs(dy) > 20) {
                        handler.removeCallbacks(longPressRunnable)
                    }

                    if (event.pointerCount >= 2 && videoScale > 1.05f && isVideo) {
                        videoTranslationX += (event.x - lastTouchX)
                        videoTranslationY += (event.y - lastTouchY)
                        applyVideoTransform()
                    } else if (event.pointerCount == 1 && !isPinching && !isLongPressSpeeding) {
                        if (videoScale > 1.05f && isVideo) {
                            videoTranslationX += (event.x - lastTouchX)
                            videoTranslationY += (event.y - lastTouchY)
                            applyVideoTransform()
                        } else {
                            if (!isDraggingVertical && !isDraggingHorizontal) {
                                if (kotlin.math.abs(dy) > 25 && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                                    isDraggingVertical = true
                                    gestureMode =
                                        if (gestureStartX > screenWidth / 2) GESTURE_VOLUME else GESTURE_BRIGHTNESS
                                } else if (kotlin.math.abs(dx) > 25 && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                                    isDraggingHorizontal = true
                                }
                            }

                            if (isDraggingVertical) {
                                val deltaY = initialGestureY - event.y
                                val maxPerc =
                                    if (prefs.getBoolean("volume_boost_enabled", true)) 200f else 100f
                                val deltaPercent = (deltaY / (screenHeight * 0.6f)) * maxPerc

                                if (gestureMode == GESTURE_VOLUME) {
                                    val initPerc = (initialVolume.toFloat() / maxSystemVolume.toFloat()) * 100f
                                    applyVolumePercent(initPerc + deltaPercent)
                                } else if (gestureMode == GESTURE_BRIGHTNESS) {
                                    updateBrightnessByPercent((deltaY / (screenHeight * 0.6f)) * 100f)
                                }
                            } else if (isDraggingHorizontal) {
                                val deltaX = gestureStartX - event.x
                                val maxDur = LionPlayerManager.player?.duration?.coerceAtLeast(0L) ?: 0L
                                if (maxDur > 0) {
                                    val seekOffsetMs = ((deltaX / screenWidth) * 60000.0).toLong()
                                    targetSeekPosition =
                                        (initialSeekPosition + seekOffsetMs).coerceIn(0L, maxDur)
                                    binding.playerSeekBar.progress = targetSeekPosition.toInt()
                                    binding.tvCurrentTime.text = formatDuration(targetSeekPosition)

                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastSeekDispatchTime >= 16L) {
                                        lastSeekDispatchTime = now
                                        LionPlayerManager.player?.seekTo(targetSeekPosition)
                                    }
                                    binding.hudSeek.visibility = View.VISIBLE
                                    val diffSec = (targetSeekPosition - initialSeekPosition) / 1000.0
                                    binding.tvSeekTarget.text = String.format(
                                        Locale.US, "%s%.1fs\n%s / %s",
                                        if (diffSec > 0) "+" else "", diffSec,
                                        formatDuration(targetSeekPosition), formatDuration(maxDur)
                                    )
                                }
                            }
                        }
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (isLongPressSpeeding) {
                        isLongPressSpeeding = false
                        binding.hudFastForward.visibility = View.GONE
                        LionPlayerManager.player?.playbackParameters =
                            PlaybackParameters(originalSpeedBeforeLongPress)
                    }
                    if (isDraggingHorizontal) {
                        LionPlayerManager.player?.seekTo(targetSeekPosition)
                        LionPlayerManager.updateNotification(this)
                        binding.hudSeek.visibility = View.GONE
                    }
                    binding.hudVolume.visibility = View.GONE
                    binding.hudBrightness.visibility = View.GONE
                    isDraggingVertical = false
                    isDraggingHorizontal = false
                }
            }
            true
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSec = durationMs.coerceAtLeast(0L) / 1000
        val sec = totalSec % 60
        val min = (totalSec / 60) % 60
        val hrs = totalSec / 3600
        return if (hrs > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hrs, min, sec)
        } else {
            String.format(Locale.US, "%02d:%02d", min, sec)
        }
    }

    private fun handleBackAction() {
        if (isKidsLocked) {
            Toast.makeText(this, "قفل الأطفال مفعّل! انقر على الزوايا الأربع لإلغائه", Toast.LENGTH_SHORT).show()
            showKidsLockHud(kidsLockStep)
            return
        }
        if (isLocked) {
            Toast.makeText(this, "الشاشة مقفلة! اضغط على زر القفل أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        val currentItem = LionPlayerManager.getCurrentItem()
        val isVaultItem = LionPlayerManager.isVaultItem(currentItem)

        // 🔐 ملف محمي: إنهاء التشغيل + مسح النسخة المفكوكة من الذاكرة المؤقتة
        if (isVaultItem) {
            saveCurrentVideoPosition()
            LionPlayerManager.stop(this)
            VaultManager.wipeDecryptedCache(this)
            finish()
            return
        }

        saveCurrentVideoPosition()
        val isVideo = currentItem?.isVideo ?: true
        if (isVideo) {
            AlertDialog.Builder(this)
                .setTitle("تشغيل الفيديو")
                .setMessage("هل تريد استمرار تشغيل الفيديو في الخلفية أم الإغلاق؟")
                .setPositiveButton("استمرار بالعمل") { _, _ ->
                    LionPlayerManager.allowBackgroundPlayback = true
                    LionPlayerManager.updateNotification(this)
                    finish()
                }
                .setNegativeButton("إغلاق") { _, _ ->
                    LionPlayerManager.stop(this)
                    finish()
                }
                .setNeutralButton("إلغاء", null)
                .show()
        } else {
            LionPlayerManager.allowBackgroundPlayback = true
            LionPlayerManager.updateNotification(this)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackAction()
    }

    override fun onStart() {
        super.onStart()
        LionPlayerManager.onTrackChanged = { item ->
            runOnUiThread {
                updateUIForItem(item)
                scheduleAutoHideLandscape()
            }
        }
        LionPlayerManager.onPlaybackStateChanged = { isPlaying ->
            runOnUiThread {
                updatePlayPauseButton(isPlaying)
                if (isPlaying) scheduleAutoHideLandscape()
            }
        }
        LionPlayerManager.getCurrentItem()?.let { updateUIForItem(it) }
        binding.playerView.player = LionPlayerManager.player
        updatePlayPauseButton(LionPlayerManager.player?.isPlaying == true)
        updateRepeatButtonUI()
        updateAbRepeatButtonUI()
        updateScreenLock()
        handler.post(updateProgressRunnable)
        scheduleAutoHideLandscape()
    }

    override fun onResume() {
        super.onResume()
        applySecureWindowFlag()
        applySystemBarsVisibility()
        updateScreenLock()
        scheduleAutoHideLandscape()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentVideoPosition()
    }

    override fun onStop() {
        super.onStop()
        saveCurrentVideoPosition()
        LionPlayerManager.onTrackChanged = null
        LionPlayerManager.onPlaybackStateChanged = null
        updateScreenLock()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentVideoPosition()
        handler.removeCallbacks(updateProgressRunnable)
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(hideZoomHudRunnable)
        handler.removeCallbacks(autoHideLandscapeRunnable)
        handler.removeCallbacks(hideDoubleTapRightRunnable)
        handler.removeCallbacks(hideDoubleTapLeftRunnable)
        handler.removeCallbacks(hideKidsLockHudRunnable)
        binding.playerView.player = null

        // 🔐 مسح النسخة المفكوكة عند مغادرة مشغل الخزينة
        if (isFromVault && LionPlayerManager.player?.isPlaying != true) {
            VaultManager.wipeDecryptedCache(this)
        }

        try {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
            loudnessEnhancer = null
        } catch (e: Exception) {
            // تجاهل مقصود
        }

        if (!LionPlayerManager.allowBackgroundPlayback &&
            LionPlayerManager.player?.isPlaying != true
        ) {
            LionPlayerManager.dismissNotification(this)
        }
    }
}
