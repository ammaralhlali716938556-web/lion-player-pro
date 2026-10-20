package com.lionplayer.app.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.MediaSession
import com.lionplayer.app.R
import com.lionplayer.app.model.MediaItem as LionMediaItem
import com.lionplayer.app.receiver.NotificationReceiver
import com.lionplayer.app.subtitle.SubtitleUtils
import com.lionplayer.app.ui.PlayerActivity
import com.lionplayer.app.vault.VaultManager
import java.util.Locale

/**
 * مدير المشغل المركزي (Media3 ExoPlayer + MediaSession + الإشعارات).
 * يدعم عناصر "الخزينة السرية" عبر المسار file:// المفكوك تشفيره مؤقتاً.
 */
object LionPlayerManager {

    const val CHANNEL_ID = "lion_playback_channel"
    const val NOTIFICATION_ID = 2024

    var player: ExoPlayer? = null
        private set

    private var mediaSession: MediaSession? = null

    var playlist: ArrayList<LionMediaItem> = arrayListOf()
    var currentIndex: Int = 0
    var allowBackgroundPlayback: Boolean = false
    var bluetoothControlEnabled: Boolean = true

    var currentDecoderMode: String = "HW"
    var isRepeatMode: Boolean = false

    var currentCustomSubtitleUri: Uri? = null
    var currentSubtitleOffsetMs: Long = 0L

    var onTrackChanged: ((LionMediaItem) -> Unit)? = null
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null
    var onTracksChangedListener: ((Tracks) -> Unit)? = null

    private val tickerHandler = Handler(Looper.getMainLooper())
    private var appContextRef: Context? = null

    private val progressTickerRunnable = object : Runnable {
        override fun run() {
            appContextRef?.let { ctx ->
                if (player?.isPlaying == true) {
                    updateNotification(ctx)
                    tickerHandler.postDelayed(this, 1000)
                }
            }
        }
    }

    /** هل العنصر الحالي قادم من الخزينة السرية؟ */
    fun isCurrentItemFromVault(): Boolean =
        getCurrentItem()?.parentFolder == VaultManager.VAULT_FOLDER_LABEL

    fun isVaultItem(item: LionMediaItem?): Boolean =
        item?.parentFolder == VaultManager.VAULT_FOLDER_LABEL

    fun markItemAsPlayed(context: Context, id: Long) {
        // لا نُسجّل عناصر الخزينة في "تمت المشاهدة"
        if (isVaultItem(getCurrentItem())) return
        try {
            val prefs = context.getSharedPreferences("lion_player_prefs", Context.MODE_PRIVATE)
            val played = prefs.getStringSet("played_media_ids", mutableSetOf())?.toMutableSet()
                ?: mutableSetOf()
            if (played.add(id.toString())) {
                prefs.edit().putStringSet("played_media_ids", played).apply()
            }
        } catch (e: Exception) {
            // تجاهل مقصود
        }
    }

    fun createRenderersFactory(context: Context, mode: String): RenderersFactory {
        val factory = DefaultRenderersFactory(context.applicationContext)
        factory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        factory.setEnableDecoderFallback(true)

        when (mode) {
            "SW" -> {
                factory.setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    val allDecoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder
                    )
                    val swDecoders = allDecoders.filter {
                        it.softwareOnly ||
                            it.name.startsWith("OMX.google.", ignoreCase = true) ||
                            it.name.startsWith("c2.android.", ignoreCase = true)
                    }
                    if (swDecoders.isNotEmpty()) swDecoders else allDecoders
                }
            }
            "HW+" -> {
                factory.setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    val allDecoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder
                    )
                    val hwDecoders = allDecoders.filter {
                        !it.softwareOnly &&
                            !it.name.startsWith("OMX.google.", ignoreCase = true) &&
                            !it.name.startsWith("c2.android.", ignoreCase = true)
                    }
                    if (hwDecoders.isNotEmpty()) hwDecoders else allDecoders
                }
            }
            else -> { // "HW"
                factory.setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    val allDecoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder
                    )
                    val hwDecoders = allDecoders.filter { !it.softwareOnly }
                    if (hwDecoders.isNotEmpty()) hwDecoders else allDecoders
                }
            }
        }
        return factory
    }

    fun getOrCreatePlayer(context: Context, mode: String = currentDecoderMode): ExoPlayer {
        val appContext = context.applicationContext
        appContextRef = appContext

        if (player == null) {
            currentDecoderMode = mode
            player = ExoPlayer.Builder(appContext)
                .setRenderersFactory(createRenderersFactory(appContext, mode))
                .setSeekParameters(SeekParameters.EXACT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setHandleAudioBecomingNoisy(true)
                .build()
                .apply {
                    repeatMode = if (isRepeatMode) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            onPlaybackStateChanged?.invoke(isPlaying)
                            updateNotification(appContext)
                            tickerHandler.removeCallbacks(progressTickerRunnable)
                            if (isPlaying) {
                                tickerHandler.postDelayed(progressTickerRunnable, 1000)
                            }
                        }

                        override fun onTracksChanged(tracks: Tracks) {
                            onTracksChangedListener?.invoke(tracks)
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                if (isRepeatMode || repeatMode == Player.REPEAT_MODE_ONE) {
                                    seekTo(0L)
                                    play()
                                    return
                                }
                                val finishedItem = playlist.getOrNull(currentIndex)
                                if (finishedItem != null && !isVaultItem(finishedItem)) {
                                    val prefs = appContext.getSharedPreferences(
                                        "lion_player_prefs", Context.MODE_PRIVATE
                                    )
                                    prefs.edit()
                                        .remove("video_resume_pos_${finishedItem.id}")
                                        .apply()
                                }
                                if (currentIndex < playlist.size - 1) {
                                    playNext(appContext)
                                } else {
                                    onPlaybackStateChanged?.invoke(false)
                                    updateNotification(appContext)
                                }
                            }
                        }
                    })
                }

            mediaSession = MediaSession.Builder(appContext, player!!)
                .setId("LionMediaSession")
                .setCallback(object : MediaSession.Callback {
                    override fun onMediaButtonEvent(
                        session: MediaSession,
                        controllerInfo: MediaSession.ControllerInfo,
                        intent: Intent
                    ): Boolean {
                        if (!bluetoothControlEnabled) return true
                        return super.onMediaButtonEvent(session, controllerInfo, intent)
                    }
                })
                .build()

            createNotificationChannel(appContext)
        }
        return player!!
    }

    fun switchDecoder(context: Context, mode: String): ExoPlayer {
        currentDecoderMode = mode
        val p = player
        val currentPos = p?.currentPosition ?: 0L
        val wasPlaying = p?.isPlaying == true
        val speed = p?.playbackParameters?.speed ?: 1.0f

        tickerHandler.removeCallbacks(progressTickerRunnable)
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null

        val newPlayer = getOrCreatePlayer(context, mode)
        newPlayer.playbackParameters = PlaybackParameters(speed)

        val curItem = playlist.getOrNull(currentIndex)
        if (curItem != null) {
            val mediaItem = buildExoMediaItem(context, curItem)
            newPlayer.setMediaItem(mediaItem)
            newPlayer.prepare()
            newPlayer.seekTo(currentPos)
            if (wasPlaying) newPlayer.play()
        }
        return newPlayer
    }

    fun attachCustomSubtitle(context: Context, subtitleUri: Uri) {
        currentCustomSubtitleUri = subtitleUri
        val curItem = playlist.getOrNull(currentIndex) ?: return
        val p = player ?: return
        val currentPos = p.currentPosition
        val wasPlaying = p.isPlaying

        val mediaItem = buildExoMediaItem(context, curItem)
        p.setMediaItem(mediaItem, false)
        p.seekTo(currentPos)
        if (wasPlaying) p.play()
    }

    fun applySubtitleOffset(context: Context, offsetMs: Long) {
        currentSubtitleOffsetMs = offsetMs
        val subUri = currentCustomSubtitleUri ?: return
        val shiftedUri = SubtitleUtils.shiftSubtitleFile(context, subUri, offsetMs)
        val curItem = playlist.getOrNull(currentIndex) ?: return
        val p = player ?: return
        val currentPos = p.currentPosition
        val wasPlaying = p.isPlaying

        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(curItem.uri))
        val subConfig = SubtitleUtils.buildSubtitleConfiguration(shiftedUri)
        mediaItemBuilder.setSubtitleConfigurations(listOf(subConfig))

        p.setMediaItem(mediaItemBuilder.build(), false)
        p.seekTo(currentPos)
        if (wasPlaying) p.play()
    }

    private fun buildExoMediaItem(context: Context, item: LionMediaItem): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(item.uri))
        val subUri = currentCustomSubtitleUri
        if (subUri != null) {
            val effectiveUri = if (currentSubtitleOffsetMs != 0L) {
                SubtitleUtils.shiftSubtitleFile(context, subUri, currentSubtitleOffsetMs)
            } else {
                subUri
            }
            builder.setSubtitleConfigurations(
                listOf(SubtitleUtils.buildSubtitleConfiguration(effectiveUri))
            )
        }
        return builder.build()
    }

    fun selectAudioTrack(trackGroup: TrackGroup, trackIndex: Int) {
        player?.let { p ->
            val override = TrackSelectionOverride(trackGroup, trackIndex)
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(override)
                .build()
        }
    }

    fun selectSubtitleTrack(trackGroup: TrackGroup, trackIndex: Int) {
        player?.let { p ->
            val override = TrackSelectionOverride(trackGroup, trackIndex)
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(override)
                .build()
        }
    }

    fun disableSubtitles() {
        player?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
    }

    fun toggleRepeat(): Boolean {
        isRepeatMode = !isRepeatMode
        player?.repeatMode = if (isRepeatMode) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        return isRepeatMode
    }

    fun playPlaylist(context: Context, list: ArrayList<LionMediaItem>, index: Int) {
        val appContext = context.applicationContext
        val p = getOrCreatePlayer(appContext)

        val currentItem = playlist.getOrNull(currentIndex)
        val targetItem = list.getOrNull(index)

        if (currentItem != null && targetItem != null &&
            currentItem.id == targetItem.id && p.isPlaying
        ) {
            playlist = list
            currentIndex = index
            onTrackChanged?.invoke(targetItem)
            return
        }

        playlist = list
        currentIndex = index
        currentCustomSubtitleUri = null
        currentSubtitleOffsetMs = 0L
        playCurrentItem(appContext)
    }

    fun playCurrentItem(context: Context) {
        val item = playlist.getOrNull(currentIndex) ?: return
        val appContext = context.applicationContext
        val p = getOrCreatePlayer(appContext)

        markItemAsPlayed(appContext, item.id)
        item.isNew = false

        if (!item.isVideo) allowBackgroundPlayback = true

        val mediaItem = buildExoMediaItem(appContext, item)
        p.setMediaItem(mediaItem)
        p.prepare()
        p.play()

        onTrackChanged?.invoke(item)
        onPlaybackStateChanged?.invoke(true)
        updateNotification(appContext)
    }

    fun playNext(context: Context) {
        if (currentIndex < playlist.size - 1) {
            currentIndex++
            currentCustomSubtitleUri = null
            currentSubtitleOffsetMs = 0L
            playCurrentItem(context)
        } else {
            Toast.makeText(context.applicationContext, "هذا هو العنصر الأخير في القائمة", Toast.LENGTH_SHORT).show()
        }
    }

    fun playPrevious(context: Context) {
        if (currentIndex > 0) {
            currentIndex--
            currentCustomSubtitleUri = null
            currentSubtitleOffsetMs = 0L
            playCurrentItem(context)
        } else {
            Toast.makeText(context.applicationContext, "هذا هو العنصر الأول في القائمة", Toast.LENGTH_SHORT).show()
        }
    }

    fun togglePlayPause(context: Context) {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        updateNotification(context)
    }

    fun stop(context: Context) {
        allowBackgroundPlayback = false
        tickerHandler.removeCallbacks(progressTickerRunnable)
        player?.stop()
        dismissNotification(context)
        onPlaybackStateChanged?.invoke(false)
    }

    fun release() {
        allowBackgroundPlayback = false
        tickerHandler.removeCallbacks(progressTickerRunnable)
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
    }

    fun getCurrentItem(): LionMediaItem? = playlist.getOrNull(currentIndex)

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Lion Playback"
            val desc = "Lion Player Media Playback Controls"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = desc
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun formatDuration(durationMs: Long): String {
        val safeMs = durationMs.coerceAtLeast(0L)
        val totalSec = safeMs / 1000
        val sec = totalSec % 60
        val min = (totalSec / 60) % 60
        val hrs = totalSec / 3600
        return if (hrs > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hrs, min, sec)
        } else {
            String.format(Locale.US, "%02d:%02d", min, sec)
        }
    }

    fun updateNotification(context: Context) {
        val currentItem = playlist.getOrNull(currentIndex) ?: return
        val p = player
        val isPlaying = p?.isPlaying == true
        val appContext = context.applicationContext

        val currentPos = p?.currentPosition ?: 0L
        val totalDur = (if (p != null && p.duration > 0) p.duration else currentItem.duration).coerceAtLeast(0L)
        val timeString = "${formatDuration(currentPos)} / ${formatDuration(totalDur)}"

        // عناصر الخزينة تُعرض بتسمية محايدة دون أي تفاصيل
        val mediaTypeString = when {
            isVaultItem(currentItem) -> "🔐 ملف محمي"
            currentItem.isVideo -> "فيديو"
            else -> "صوت"
        }

        val notifTitle: CharSequence =
            if (!isVaultItem(currentItem) && currentItem.artist.isNotEmpty() &&
                currentItem.artist != "غير معروف" && currentItem.artist != "فنان غير معروف"
            ) {
                val fullText = "${currentItem.title}  ${currentItem.artist}"
                val span = SpannableString(fullText)
                val start = currentItem.title.length
                span.setSpan(RelativeSizeSpan(0.8f), start, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(
                    ForegroundColorSpan(Color.parseColor("#B0BEC5")),
                    start, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                span
            } else {
                currentItem.title
            }

        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val contentIntent = Intent(appContext, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(appContext, 0, contentIntent, pendingFlags)

        val prevIntent = Intent(NotificationReceiver.ACTION_NOTIF_PREV).setPackage(appContext.packageName)
        val prevPending = PendingIntent.getBroadcast(appContext, 1, prevIntent, pendingFlags)

        val playPauseIntent = Intent(NotificationReceiver.ACTION_NOTIF_PLAY_PAUSE).setPackage(appContext.packageName)
        val playPausePending = PendingIntent.getBroadcast(appContext, 2, playPauseIntent, pendingFlags)

        val nextIntent = Intent(NotificationReceiver.ACTION_NOTIF_NEXT).setPackage(appContext.packageName)
        val nextPending = PendingIntent.getBroadcast(appContext, 3, nextIntent, pendingFlags)

        val closeIntent = Intent(NotificationReceiver.ACTION_NOTIF_CLOSE).setPackage(appContext.packageName)
        val closePending = PendingIntent.getBroadcast(appContext, 4, closeIntent, pendingFlags)

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseTitle = if (isPlaying) "إيقاف مؤقت" else "تشغيل"

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lion_logo)
            .setContentTitle(notifTitle)
            .setContentText("$timeString • $mediaTypeString")
            .setSubText(timeString)
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(R.drawable.ic_skip_previous, "السابق", prevPending)
            .addAction(playPauseIcon, playPauseTitle, playPausePending)
            .addAction(R.drawable.ic_skip_next, "التالي", nextPending)
            .addAction(R.drawable.ic_close, "إنهاء", closePending)

        try {
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )
        } catch (e: Exception) {
            // تجاهل مقصود
        }

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // تجاهل مقصود
        }
    }

    fun dismissNotification(context: Context) {
        tickerHandler.removeCallbacks(progressTickerRunnable)
        val manager = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }
}
