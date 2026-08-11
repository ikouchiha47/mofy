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
}
