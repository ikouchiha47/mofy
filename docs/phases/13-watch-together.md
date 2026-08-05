# Phase 13: Watch Together

**Depends on:** 06, 07

## Goal

Sync playback across two independently-acquired copies of the same movie
(e.g. host + girlfriend in another room), without transferring video bytes.

## Requirements (EARS)

- The system SHALL allow a user to create a watch-together room/session for
  a library item.
- The system SHALL identify the shared item across devices by a
  normalized-title/TMDB-id hash rather than by comparing files.
- The system SHALL allow a second device to join a session via a room
  code/link/QR code.
- The system SHALL require each participant to have their own local copy of
  the item already present in their own library — no video file SHALL be
  transferred between devices.
- WHILE a session is active, the system SHALL sync playback events (play,
  pause, seek position) between participants over a lightweight message
  channel (e.g. websocket).
- WHEN one participant pauses, seeks, or plays, the system SHALL propagate
  that event to the other participant(s).
- IF playback position drifts between participants (e.g. due to buffering),
  THEN the host SHALL periodically broadcast its current position so other
  participants can auto-correct.
- The system SHALL support per-participant subtitle/audio-track preference
  independent of the other participant's settings.
- A joining participant in "guest mode" SHALL only have access to playback
  controls, not the host's library management or download history.
</content>
