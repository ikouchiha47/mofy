package com.mofy.app.watchtogether.sync

object SyncEngineConfig {
    /** Host position heartbeat interval. */
    const val POSITION_HEARTBEAT_MS = 3_000L

    /**
     * If |remote - local| exceeds this, apply seek.
     * 1500ms: below typical scene-cut notice, above normal jitter on Wi‑Fi.
     */
    const val DRIFT_THRESHOLD_MS = 1_500L

    /** Guest transport peer id for the host hub (star topology). */
    const val HOST_PEER_ID = "host"

    /**
     * Control (play/pause/seek) emission debounce. Rapid scrubs coalesce to
     * a single emit within this window - never per-frame (flows doc §4.1).
     */
    const val CONTROL_DEBOUNCE_MS = 250L

    /**
     * After applying a remote seek, local control events within this window
     * are ignored so the local slider and the echo cannot ping-pong
     * (flows doc §4.4).
     */
    const val CONTROL_IGNORE_WINDOW_MS = 500L

    /**
     * Guest-side grace before a host disconnect is treated as host-dead.
     * Inside the window we stay in Reconnecting; only after it elapses do we
     * emit HostLost so the caller can demote to solo (flows doc §5).
     */
    const val HOST_DISCONNECT_GRACE_MS = 10_000L

    /** Max allowed difference between host and guest local-file runtimes at
     *  join, before the join is refused as a different cut (flows doc §14). */
    const val DURATION_MISMATCH_MS = 10 * 60 * 1000L
}
