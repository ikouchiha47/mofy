# Phase 03: Torrent Download Engine

**Depends on:** 02

## Goal

Actual torrent downloading inside the app, driven by magnet links captured
in Phase 02, surviving backgrounding.

## Requirements (EARS)

- The system SHALL download torrent content given a magnet URI, using a
  native torrent engine (e.g. libtorrent4j).
- WHEN a download is active, the system SHALL run it inside a foreground
  service so the OS does not kill it when the app is backgrounded.
- WHILE a download is active, the system SHALL display progress (percent,
  speed, ETA) to the user.
- The system SHALL allow the user to pause, resume, and cancel an active
  download.
- WHEN a download completes, the system SHALL notify the local library
  layer (Phase 06) with the resulting file path(s).
- IF a download stalls (no peers, no progress for an extended period), THEN
  the system SHALL surface that state to the user rather than showing an
  indefinitely spinning progress indicator.
</content>
