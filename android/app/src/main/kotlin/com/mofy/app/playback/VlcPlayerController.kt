package com.mofy.app.playback

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * libVLC-backed [PlayerController] (ADR 0006 — the in-app engine for both
 * solo playback and Watch Together). Media is prepared from a content/file
 * URI string; position and duration are milliseconds. Rendering the video
 * surface is a UI concern (Compose/`VLCVideoLayout`) and intentionally not
 * handled here.
 *
 * The uri passed in is expected to already be stable and playable - either
 * a file:// path or a content://media/... MediaStore uri - resolved once at
 * pick time by [resolvePlayableUri], not a raw SAF document uri. SAF
 * document uris (from ManualEntryScreen/LinkScreen's file/folder pickers)
 * are deliberately not handled here: DownloadStorageProvider's "raw:"
 * documents specifically stop being readable once the picker activity that
 * produced them has finished (confirmed on a real device: SecurityException
 * demanding fresh ACTION_OPEN_DOCUMENT access, regardless of
 * takePersistableUriPermission), so resolving them has to happen while the
 * picker callback's access is still valid - too early for this class to do
 * itself.
 */
class VlcPlayerController(context: Context, mediaUri: String) : PlayerController {

    private val libVlc: LibVLC = LibVLC(context.applicationContext)
    private val player: MediaPlayer = MediaPlayer(libVlc)
    private var openFd: android.os.ParcelFileDescriptor? = null

    init {
        // Attached before media/play so no early event (Opening,
        // EncounteredError) is lost to a race - libVLC can start firing
        // events on its own thread as soon as media is assigned, before
        // this constructor even returns to the caller.
        player.setEventListener { event ->
            android.util.Log.d("VlcPlayerController", "event type=${event.type}")
        }
        val uri = Uri.parse(mediaUri)
        val media = if (uri.scheme == "content") {
            val fd = context.applicationContext.contentResolver.openFileDescriptor(uri, "r")
                ?: error("Could not open file descriptor for $mediaUri")
            openFd = fd
            Media(libVlc, fd.fileDescriptor)
        } else {
            Media(libVlc, uri)
        }
        try {
            player.media = media
        } finally {
            media.release()
        }
    }

    override val positionMs: Long
        get() = player.getTime().coerceAtLeast(0L)

    override val isPlaying: Boolean
        get() = player.isPlaying

    override val durationMs: Long
        get() = player.getLength().coerceAtLeast(0L)

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (positionMs < 0) return
        player.setTime(positionMs)
    }

    override fun setSubtitleTrack(index: Int?) {
        if (index != null) {
            player.setSpuTrack(index)
        } else {
            // libVLC indexes SPU tracks including an internal "disabled"
            // entry; turning subtitles off maps to that pseudo-track.
            player.setSpuTrack(-1)
        }
    }

    override fun setAudioTrack(index: Int?) {
        if (index != null) {
            player.setAudioTrack(index)
        }
    }

    override fun release() {
        player.setMedia(null)
        player.release()
        openFd?.close()
        libVlc.release()
    }

    /**
     * Attaches the video output to a `VLCVideoLayout`. Needed by callers
     * (Compose `AndroidView`) since libVLC's MediaPlayer is not exposed
     * directly - see class doc, "rendering the video surface is a UI
     * concern".
     */
    fun attachViews(videoLayout: VLCVideoLayout) {
        player.attachViews(videoLayout, null, false, false)
    }

    fun detachViews() {
        player.detachViews()
    }
}
