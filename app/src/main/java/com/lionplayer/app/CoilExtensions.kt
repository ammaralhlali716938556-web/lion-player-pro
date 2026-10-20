package com.lionplayer.app.adapter

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.ImageView
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.load
import coil.request.Options
import coil.request.videoFrameMillis
import com.lionplayer.app.R
import com.lionplayer.app.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import java.io.File

data class AudioCover(val uri: Uri, val path: String)

/** جلب غلاف الألبوم المدمج داخل ملفات الصوت عبر MediaMetadataRetriever */
class AudioCoverFetcher(
    private val context: Context,
    private val data: AudioCover
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            if (data.path.isNotEmpty() && File(data.path).exists()) {
                retriever.setDataSource(data.path)
            } else if (data.uri.toString().isNotEmpty()) {
                retriever.setDataSource(context, data.uri)
            } else {
                return@withContext null
            }
            val artBytes = retriever.embeddedPicture
            if (artBytes != null) {
                val buffer = Buffer().write(artBytes)
                SourceResult(
                    source = ImageSource(buffer, context),
                    mimeType = "image/jpeg",
                    dataSource = DataSource.DISK
                )
            } else null
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // تجاهل مقصود
            }
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<AudioCover> {
        override fun create(data: AudioCover, options: Options, imageLoader: ImageLoader): Fetcher {
            return AudioCoverFetcher(context, data)
        }
    }
}

/** تحميل مصغّرة موحّدة للفيديو/الصوت/المجلد */
fun ImageView.loadMediaThumbnail(item: MediaItem) {
    if (item.isFolder) {
        if (item.uri.isEmpty()) {
            setImageResource(R.drawable.ic_folder)
        } else {
            val uri = Uri.parse(item.uri)
            if (item.isVideo) {
                load(uri) {
                    placeholder(R.drawable.ic_folder)
                    error(R.drawable.ic_folder)
                    videoFrameMillis(1000)
                    size(256, 256)
                }
            } else {
                load(AudioCover(uri, item.path)) {
                    placeholder(R.drawable.ic_folder)
                    error(R.drawable.ic_folder)
                    size(256, 256)
                }
            }
        }
        return
    }

    val placeholderRes = if (item.isVideo) R.drawable.ic_video else R.drawable.ic_music_note
    val uri = if (item.uri.isNotEmpty()) Uri.parse(item.uri) else Uri.fromFile(File(item.path))

    if (item.isVideo) {
        load(uri) {
            placeholder(placeholderRes)
            error(placeholderRes)
            videoFrameMillis(1000)
            size(256, 256)
        }
    } else {
        load(AudioCover(uri, item.path)) {
            placeholder(placeholderRes)
            error(placeholderRes)
            size(256, 256)
        }
    }
}
