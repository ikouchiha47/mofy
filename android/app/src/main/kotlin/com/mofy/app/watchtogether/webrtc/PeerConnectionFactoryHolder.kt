package com.mofy.app.watchtogether.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.PeerConnectionFactory

/**
 * Process-wide [PeerConnectionFactory]. Init once from Application.
 * DataChannel-only — no audio/video encoder factories required for media.
 */
object PeerConnectionFactoryHolder {
    private const val TAG = "WtWebRtc"

    @Volatile
    private var factory: PeerConnectionFactory? = null

    fun init(context: Context) {
        if (factory != null) return
        synchronized(this) {
            if (factory != null) return
            val appContext = context.applicationContext
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions(),
            )
            factory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .createPeerConnectionFactory()
            Log.i(TAG, "PeerConnectionFactory ready")
        }
    }

    fun get(): PeerConnectionFactory =
        factory ?: error("PeerConnectionFactoryHolder.init() not called")

    /** Test-only: drop the holder so the next init runs again. */
    internal fun resetForTests() {
        synchronized(this) {
            factory?.dispose()
            factory = null
        }
    }
}
