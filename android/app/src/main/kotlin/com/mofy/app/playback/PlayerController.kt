package com.mofy.app.playback

/**
 * Controllable playback surface shared by solo playback (Phase 07) and
 * Watch Together (Phase 13). Deliberately minimal - no Android framework
 * types, so it is testable in a plain JVM unit test. Concrete backends
 * (libVLC for the real player, a fake for unit tests) implement this.
 *
 * Position/duration are always milliseconds.
 */
interface PlayerController {
    val positionMs: Long
    val isPlaying: Boolean
    val durationMs: Long

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)

    /** null turns subtitles off; an index selects that track. */
    fun setSubtitleTrack(index: Int?)

    /** null keeps default audio; an index selects that track. */
    fun setAudioTrack(index: Int?)

    fun release()
}