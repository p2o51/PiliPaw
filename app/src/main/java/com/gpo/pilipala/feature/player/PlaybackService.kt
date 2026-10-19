package com.gpo.pilipala.feature.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.Util
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.gpo.pilipala.MainActivity
import com.gpo.pilipala.R
import com.gpo.pilipala.core.util.Images
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Hosts the current video's [MediaSession] while background play is on, so
 * media3 posts the media notification and keeps the process in the foreground.
 * The session (and its ExoPlayer) stays owned by [PiliPlayerController]; this
 * service only borrows it between [attach] and [detach].
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
        // The controller releases the session; only let go of it here.
        getSessions().forEach { removeSession(it) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        /** Set on the notification's tap intent: bring the open video page back instead of pushing a new one. */
        const val EXTRA_RESUME = "com.gpo.pilipala.extra.RESUME_PLAYER"

        private var instance: PlaybackService? = null
        private var hosted: MediaSession? = null

        /** Starts hosting [session] (main thread). Must be called while the app is in the foreground. */
        internal fun attach(context: Context, session: MediaSession) {
            if (hosted === session && instance?.isSessionAdded(session) == true) return
            hosted?.takeIf { it !== session }?.let { old -> instance?.takeIf { it.isSessionAdded(old) }?.removeSession(old) }
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
            // The stopped instance lives on until onDestroy; a session added to it now would be
            // dropped there with no service left. The next attach starts the service again.
            instance = null
        }

        /** Tapping the notification returns to the page that is playing [t]. */
        internal fun sessionActivity(context: Context, t: PlayTarget): PendingIntent {
            val link = when {
                t.isPgc && t.epId > 0 -> "https://www.bilibili.com/bangumi/play/ep${t.epId}"
                t.bvid.isNotBlank() -> "https://www.bilibili.com/video/${t.bvid}"
                else -> "https://www.bilibili.com/video/av${t.aid}"
            }
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(link))
                .putExtra(EXTRA_RESUME, true)
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /** Custom session commands behind the media controls' extra buttons. */
        internal const val ACTION_PLAY_MODE = "com.gpo.pilipala.session.PLAY_MODE"
        internal const val ACTION_LIKE = "com.gpo.pilipala.session.LIKE"
        internal const val ACTION_AUDIO_ONLY = "com.gpo.pilipala.session.AUDIO_ONLY"
        private val playModeCommand = SessionCommand(ACTION_PLAY_MODE, Bundle.EMPTY)
        private val likeCommand = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
        private val audioOnlyCommand = SessionCommand(ACTION_AUDIO_ONLY, Bundle.EMPTY)
        internal val customCommands = listOf(playModeCommand, likeCommand, audioOnlyCommand)

        /**
         * The media controls (notification, lock screen, the quick-settings media card,
         * Bluetooth / car controllers). The back / forward slots are always filled:
         * 上一个 / 下一个 when there is an item to go to (queue, 分P, 合集), else ±10s.
         * The overflow carries the play mode (queue only), 点赞 (signed in, [liked] not
         * null) and 仅听声音; the system card shows the first two of them.
         */
        internal fun buttons(
            hasPrevious: Boolean,
            hasNext: Boolean,
            mode: PlayMode?,
            liked: Boolean?,
            audioOnly: Boolean,
        ): List<CommandButton> = buildList {
            add(
                if (hasPrevious) {
                    CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .setDisplayName("上一个")
                } else {
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .setDisplayName("后退10秒")
                }.setSlots(CommandButton.SLOT_BACK).build(),
            )
            add(
                if (hasNext) {
                    CommandButton.Builder(CommandButton.ICON_NEXT)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                        .setDisplayName("下一个")
                } else {
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .setDisplayName("快进10秒")
                }.setSlots(CommandButton.SLOT_FORWARD).build(),
            )
            if (mode != null) {
                add(
                    CommandButton.Builder(
                        when (mode) {
                            PlayMode.ListLoop -> CommandButton.ICON_REPEAT_ALL
                            PlayMode.Shuffle -> CommandButton.ICON_SHUFFLE_ON
                            PlayMode.RepeatOne -> CommandButton.ICON_REPEAT_ONE
                        },
                    )
                        .setSessionCommand(playModeCommand)
                        .setDisplayName(mode.label)
                        .setSlots(CommandButton.SLOT_OVERFLOW)
                        .build(),
                )
            }
            if (liked != null) {
                add(
                    CommandButton.Builder(if (liked) CommandButton.ICON_THUMB_UP_FILLED else CommandButton.ICON_THUMB_UP_UNFILLED)
                        .setSessionCommand(likeCommand)
                        .setDisplayName(if (liked) "取消点赞" else "点赞")
                        .setSlots(CommandButton.SLOT_OVERFLOW)
                        .build(),
                )
            }
            add(
                CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                    .setCustomIconResId(if (audioOnly) R.drawable.ic_media_audio_only_on else R.drawable.ic_media_audio_only)
                    .setSessionCommand(audioOnlyCommand)
                    .setDisplayName(if (audioOnly) "显示画面" else "仅听声音")
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .build(),
            )
        }
    }
}

/**
 * Session callback. Which transport commands exist is decided by the controller's
 * session player (previous / next only when there is an item to go to, so otherwise
 * the slots keep ±10s); the buttons come from [buttons], custom taps go to [onCommand].
 */
internal class PiliSessionCallback(
    private val buttons: () -> List<CommandButton>,
    private val onCommand: (String) -> Boolean,
) : MediaSession.Callback {
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .apply { PlaybackService.customCommands.forEach { add(it) } }
                    .build(),
            )
            .setMediaButtonPreferences(buttons())
            .build()

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (onCommand(customCommand.customAction)) {
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        return super.onCustomCommand(session, controller, customCommand, args)
    }
}

/** Notification / lock-screen artwork through Coil (and so through BiliClient.http). */
internal class CoilBitmapLoader(private val context: Context) : BitmapLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
