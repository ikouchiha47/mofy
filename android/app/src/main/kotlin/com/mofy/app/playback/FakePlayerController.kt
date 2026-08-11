package com.mofy.app.playback

import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [PlayerController] for unit tests. Tracks position/playback
 * state directly and records the most recent seek/subtitle/audio calls so
 * tests can assert what Watch Together actually applied to the player.
 */
class FakePlayerController(initialDurationMs: Long = 100_000L) : PlayerController {

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Listeners notified before each PlayerController call mutates state. */
    fun interface Listener {
        fun onCall(method: String, argMs: Long?)
    }

    var durationMsImpl = initialDurationMs
        private set

    var positionMsImpl = 0L
        private set

    var isPlayingImpl = false
        private set

    var subtitleTrackImpl: Int? = null
        private set

    var audioTrackImpl: Int? = null
        private set

    /** Last seek position applied via [seekTo], or null if never sought. */
    var lastSeekMs: Long? = null
        private set

    val isReleased: Boolean
        get() = released

    private var released = false

    override val positionMs: Long get() = positionMsImpl
    override val isPlaying: Boolean get() = isPlayingImpl
    override val durationMs: Long get() = durationMsImpl

    override fun play() {
        notify("play", null)
        if (released) return
        isPlayingImpl = true
    }

    override fun pause() {
        notify("pause", null)
        if (released) return
        isPlayingImpl = false
    }

    override fun seekTo(positionMs: Long) {
        notify("seekTo", positionMs)
        if (released) return
        lastSeekMs = positionMs
        positionMsImpl = positionMs
    }

    override fun setSubtitleTrack(index: Int?) {
        notify("setSubtitleTrack", index?.toLong())
        if (released) return
        subtitleTrackImpl = index
    }

    override fun setAudioTrack(index: Int?) {
        notify("setAudioTrack", index?.toLong())
        if (released) return
        audioTrackImpl = index
    }

    override fun release() {
        released = true
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    private fun notify(method: String, argMs: Long?) {
        listeners.forEach { it.onCall(method, argMs) }
    }
}