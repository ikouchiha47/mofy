package com.mofy.app.ui.watchtogether

import android.content.Context
import android.content.Intent
import com.mofy.app.watchtogether.RoomCode
import com.mofy.app.watchtogether.WatchTogetherSession

/** Android share sheet for a room invite - shared by CreateRoomScreen and PlayerScreen's invite button. */
fun shareWatchTogetherInvite(context: Context, session: WatchTogetherSession) {
    val shareLabel = RoomCode.formatForDisplay(session.roomKey)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Join me: $shareLabel\n${session.deepLink}")
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share invite"))
}
