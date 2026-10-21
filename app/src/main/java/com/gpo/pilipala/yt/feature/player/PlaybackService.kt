package com.gpo.pilipala.yt.feature.player

import com.gpo.pilipala.feature.player.*

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.Util
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.gpo.pilipala.MainActivity
import com.gpo.pilipala.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Hosts [Player]'s [MediaSession] while background play is on, so media3 posts the media
 * notification (also the lock screen, the quick-settings media card and Bluetooth / car
 * keys) and keeps the process in the foreground. The session stays owned by [Player]; this
 * service only borrows it between [attach] and [detach]. (PiliPaw's PlaybackService, without
 * the queue and its extra buttons.)
 */
class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_notification) },
        )
        hosted?.let { if (!isSessionAdded(it)) addSession(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A start that lands on an instance already told to stop brings it back into use.
        instance = this
        val s = hosted
        if (s == null) {
            stopSelf()
        } else if (!isSessionAdded(s)) {
            addSession(s)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = hosted

    override fun onDestroy() {
        // Player releases nothing here; only let go of the session.
        getSessions().forEach { removeSession(it) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /** Set on the notification's tap intent: bring the player back up instead of opening a link. */
        const val EXTRA_RESUME = "com.gpo.pilipala.yt.extra.RESUME_PLAYER"

        private var instance: PlaybackService? = null
        private var hosted: MediaSession? = null

        /** Starts hosting [session] (main thread). Must be called while the app is in the foreground. */
        internal fun attach(context: Context, session: MediaSession) {
            if (hosted === session && instance?.isSessionAdded(session) == true) return
            hosted = session
            val service = instance
            if (service != null) {
                if (!service.isSessionAdded(session)) service.addSession(session)
            } else {
                runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
            }
        }

        /** Stops hosting [session]; the notification goes away and the service stops. */
        internal fun detach(session: MediaSession) {
            if (hosted !== session) return
            hosted = null
            instance?.let { service ->
                if (service.isSessionAdded(session)) service.removeSession(session)
                service.stopSelf()
            }
            // The stopped instance lives on until onDestroy; the next attach starts the service again.
            instance = null
        }

        /** Tapping the notification brings the app forward with the player expanded. */
        internal fun sessionActivity(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_RESUME, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** The media controls' side slots: ±10s (no queue yet, so never 上一个 / 下一个). */
        internal val buttons: List<CommandButton> = listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                .setPlayerCommand(Media3Player.COMMAND_SEEK_BACK)
                .setDisplayName("后退10秒")
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                .setPlayerCommand(Media3Player.COMMAND_SEEK_FORWARD)
                .setDisplayName("快进10秒")
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
        )
    }
}

/** Notification / lock-screen artwork through Coil (and so through the app's one OkHttp). */
internal class CoilBitmapLoader(private val context: Context) : BitmapLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun supportsMimeType(mimeType: String): Boolean = Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = future {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IOException("无法解码封面")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = future {
        val request = ImageRequest.Builder(context).data(uri.toString()).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request)
        (result as? SuccessResult)?.image?.toBitmap() ?: throw IOException("封面加载失败")
    }

    private fun future(block: suspend () -> Bitmap): ListenableFuture<Bitmap> {
        val f = SettableFuture.create<Bitmap>()
        scope.launch {
            runCatching { block() }.onSuccess { f.set(it) }.onFailure { f.setException(it) }
        }
        return f
    }
}
