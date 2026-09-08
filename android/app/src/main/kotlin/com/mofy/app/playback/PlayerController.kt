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

    /** Local-only, never synced across a Watch Together room - each device picks its own fit. */
    fun setVideoScale(scale: VideoScale)
}

enum class VideoScale(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    ORIGINAL("Original"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
    RATIO_IMAX_143("IMAX 1.43:1"),
    RATIO_IMAX_190("IMAX 1.90:1"),
}