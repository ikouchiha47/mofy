package com.mofy.app.playback

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * libVLC-backed [PlayerController] (ADR 0006 — the in-app engine for both
 * solo playback and Watch Together). Media is prepared from a content/file
 * URI string; position and duration are milliseconds. Rendering the video
 * surface is a UI concern (Compose/`VLCVideoLayout`) and intentionally not
 * handled here.
 *
 * The uri passed in is a plain file:// path - ManualEntryScreen/LinkScreen's
 * video/subtitle picking (VideoAndSubtitlePicker) is backed by an in-app
 * java.io.File browser and MANAGE_EXTERNAL_STORAGE, not the SAF document
 * picker, so there's no content:// document uri to resolve here. The
 * content:// branch below is kept only for any content:// uri that reaches
 * this class from elsewhere (e.g. old library rows saved before this).
 */
class VlcPlayerController(
    context: Context,
    mediaUri: String,
    subtitleUri: String? = null,
    subtitle2Uri: String? = null,
    startPositionMs: Long = 0L,
    // Set when mediaUri is a video-only stream (e.g. a resolved YouTube DASH
    // stream via YoutubeStreamResolver) that needs its audio muxed back in -
    // libVLC plays the two as one session via a Slave, no manifest needed.
    audioSlaveUri: String? = null,
) : PlayerController {

    private val libVlc: LibVLC = LibVLC(context.applicationContext)
    private val player: MediaPlayer = MediaPlayer(libVlc)
    private var openFd: android.os.ParcelFileDescriptor? = null

    init {
        // Attached before media/play so no early event (Opening,
        // EncounteredError) is lost to a race - libVLC can start firing
        // events on its own thread as soon as media is assigned, before
        // this constructor even returns to the caller.
        player.setEventListener { event ->
            android.util.Log.e("VlcPlayerController", "event type=${event.type}")
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
        // External subtitle files are LibraryLink.movieUri siblings
        // (subtitleUri/subtitle2Uri, one per RoleRow in LinkScreen's folder
        // picking) that were never wired into actual playback before -
        // stored in the library link, never read back at Play time. libVLC
        // needs each attached as a "slave" on the Media object before
        // playback starts, not toggled later via setSpuTrack (that only
        // switches between tracks already embedded in the video file).
        // Priority descends so the primary subtitle (subtitleUri) is
        // libVLC's preferred default track over the second one.
        subtitleUri?.let { media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 2, Uri.parse(it).toString())) }
        subtitle2Uri?.let { media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 1, Uri.parse(it).toString())) }
        // Video-only DASH stream (e.g. from YoutubeStreamResolver) needs its
        // audio muxed back in - same slave mechanism as subtitles above, just
        // Audio instead of Subtitle. No DASH manifest needed.
        audioSlaveUri?.let { media.addSlave(IMedia.Slave(IMedia.Slave.Type.Audio, 1, it)) }
        // Resume position is set as a media option, applied natively by
        // libVLC as playback starts - not via a seekTo() call after the
        // fact. A setTime() call issued before the native player has
        // actually started playing (media not yet opened/parsed) is
        // unreliable in libVLC and gets silently dropped, confirmed on a
        // real device: construct -> attachViews -> seekTo(resumeMs) ->
        // play() never resumed, always restarted at 0 even though the
        // computed position was correct. :start-time avoids the race
        // entirely instead of working around it.
        if (startPositionMs > 0) {
            media.addOption(":start-time=${startPositionMs / 1000.0}")
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

    /** id -> readable name for every subtitle track libVLC knows about, including its own "Disable" (id=-1) entry. */
    fun subtitleTracks(): List<Pair<Int, String>> =
        player.spuTracks?.map { it.id to it.name }.orEmpty()

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

    override fun setVideoScale(scale: VideoScale) {
        // 16:9/4:3/etc are covered by libVLC's own ScaleType preset enum.
        // IMAX's 1.43:1 and 1.90:1 aren't - those go through the raw
        // aspectRatio string API instead, with scale reset to 0 (fit)
        // since setVideoScale's presets would otherwise overwrite a custom
        // aspect ratio set this way.
        when (scale) {
            VideoScale.FIT -> player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            VideoScale.FILL -> player.videoScale = MediaPlayer.ScaleType.SURFACE_FILL
            VideoScale.ORIGINAL -> player.videoScale = MediaPlayer.ScaleType.SURFACE_ORIGINAL
            VideoScale.RATIO_16_9 -> player.videoScale = MediaPlayer.ScaleType.SURFACE_16_9
            VideoScale.RATIO_4_3 -> player.videoScale = MediaPlayer.ScaleType.SURFACE_4_3
            VideoScale.RATIO_IMAX_143 -> {
                player.aspectRatio = "143:100"
                player.scale = 0f
            }
            VideoScale.RATIO_IMAX_190 -> {
                player.aspectRatio = "19:10"
                player.scale = 0f
            }
        }
    }

    override fun release() {
        player.setMedia(null)
        player.release()
        openFd?.close()
        libVlc.release()
    }

    // libVLC's own attachViews/detachViews are NOT idempotent - calling
    // attachViews while already attached throws IllegalStateException
    // ("Can't set view when already attached"), confirmed crash on a real
    // device: a lifecycle observer's ON_RESUME fired attachViews again
    // right after the initial construction-time attach. Track state here
    // so every caller can call either method freely without knowing
    // whether some other call site already did.
    private var isAttached = false

    /**
     * Attaches the video output to a `VLCVideoLayout`. Needed by callers
     * (Compose `AndroidView`) since libVLC's MediaPlayer is not exposed
     * directly - see class doc, "rendering the video surface is a UI
     * concern".
     */
    fun attachViews(videoLayout: VLCVideoLayout) {
        if (isAttached) return
        player.attachViews(videoLayout, null, false, false)
        isAttached = true
    }

    fun detachViews() {
        if (!isAttached) return
        player.detachViews()
        isAttached = false
    }
}
