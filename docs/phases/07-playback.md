# Phase 07: Playback

**Depends on:** 06

## Goal

Play library items using an mpv-like engine, tracking watch position, and
critically: never getting backgrounded/killed mid-playback.

## Requirements (EARS)

- The system SHALL play local video files using an mpv-based player.
- WHILE a video is playing, the system SHALL run in a foreground service and
  hold a wakelock so the OS does not background or kill the app.
- WHILE a video is playing, the system SHALL keep the screen on.
- The system SHALL support standard playback controls: play, pause, seek,
  forward, backward.
- WHILE a video is playing, the system SHALL periodically persist the
  current watch position for that library item.
- WHEN a video is stopped/exited before completion, the system SHALL retain
  the last watch position for resume.
- WHEN a video reaches its end, the system SHALL mark the item as watched
  and clear its resume position.
</content>
