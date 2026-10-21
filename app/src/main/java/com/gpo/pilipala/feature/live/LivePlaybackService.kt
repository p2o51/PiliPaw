package com.gpo.pilipala.feature.live

import com.gpo.pilipala.core.util.ScopeErrors
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.Util
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
import com.gpo.pilipala.core.util.Images
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Hosts the live room's [MediaSession] while background play is on, so media3
 * posts the media notification and keeps the process in the foreground (Android
 * 17 mutes background media audio without one). The session stays owned by
 * [LiveRoomViewModel]; this service only borrows it between [attach] and [detach].
 */
class LivePlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Media3 already catches a foreground start the system refuses (Android 12+, app in the
        // background); a listener makes that explicit and keeps it to a log line.
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                android.util.Log.w("PiliPaw", "media notification: foreground start not allowed")
            }
        })
        hosted?.let { if (!isSessionAdded(it)) addSession(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = hosted
        if (s == null) stopSelf() else if (!isSessionAdded(s)) addSession(s)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = hosted

    override fun onDestroy() {
        getSessions().forEach { removeSession(it) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private var instance: LivePlaybackService? = null
        private var hosted: MediaSession? = null

        /** Starts hosting [session] (main thread, app in the foreground). */
        internal fun attach(context: Context, session: MediaSession) {
            if (hosted === session && instance?.isSessionAdded(session) == true) return
            hosted?.takeIf { it !== session }?.let { old -> instance?.takeIf { it.isSessionAdded(old) }?.removeSession(old) }
            hosted = session
            val service = instance
            if (service != null) {
                if (!service.isSessionAdded(session)) service.addSession(session)
            } else {
                runCatching { context.startService(Intent(context, LivePlaybackService::class.java)) }
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
        }

        /** Brings the task back as it was (the live page is still on top); no deep link, so nothing is pushed. */
        internal fun sessionActivity(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            return PendingIntent.getActivity(
                context,
                1,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}

/** Live has no timeline: play/pause only, no seek bar, no previous/next. */
internal object LiveSessionCallback : MediaSession.Callback {
    private val playerCommands = Player.Commands.Builder()
        .addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_GET_VOLUME,
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_RELEASE,
        )
        .build()

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailablePlayerCommands(playerCommands)
            .setMediaButtonPreferences(emptyList())
            .build()
}

/**
 * What the session sees: play() returns to the live edge (or reloads a dead
 * stream) and both directions mark whether the user paused on purpose.
 */
internal class LiveSessionPlayer(
    player: Player,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
) : ForwardingPlayer(player) {
    override fun play() = onPlay()
    override fun pause() = onPause()
    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) onPlay() else onPause()
}

/** Notification / lock-screen artwork through Coil (and so through BiliClient.http). */
internal class LiveBitmapLoader(private val context: Context) : BitmapLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + ScopeErrors)

    override fun supportsMimeType(mimeType: String): Boolean = Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = future {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IOException("无法解码封面")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = future {
        val request = ImageRequest.Builder(context)
            .data(Images.thumb(uri.toString(), 512))
            .allowHardware(false)
            .build()
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
