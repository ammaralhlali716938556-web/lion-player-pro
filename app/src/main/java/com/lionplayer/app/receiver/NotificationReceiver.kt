package com.lionplayer.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lionplayer.app.player.LionPlayerManager

class NotificationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_NOTIF_PREV = "com.lionplayer.app.ACTION_NOTIF_PREV"
        const val ACTION_NOTIF_PLAY_PAUSE = "com.lionplayer.app.ACTION_NOTIF_PLAY_PAUSE"
        const val ACTION_NOTIF_NEXT = "com.lionplayer.app.ACTION_NOTIF_NEXT"
        const val ACTION_NOTIF_CLOSE = "com.lionplayer.app.ACTION_NOTIF_CLOSE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_NOTIF_PREV -> LionPlayerManager.playPrevious(context)
            ACTION_NOTIF_PLAY_PAUSE -> LionPlayerManager.togglePlayPause(context)
            ACTION_NOTIF_NEXT -> LionPlayerManager.playNext(context)
            ACTION_NOTIF_CLOSE -> LionPlayerManager.stop(context)
        }
    }
}
