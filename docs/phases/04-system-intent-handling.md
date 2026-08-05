# Phase 04: System Intent Handling

**Depends on:** 03

## Goal

Make Mofy a system-level handler for magnet links and torrent files, and let
it show up in the share sheet alongside apps like uTorrent.

## Requirements (EARS)

- The system SHALL register as a handler for `magnet:` URIs at the OS level.
- The system SHALL register as a handler for opening `.torrent` files.
- WHEN the OS presents a share sheet for a magnet link or `.torrent` file,
  the system SHALL appear as one of the available handlers alongside other
  installed torrent apps (e.g. uTorrent).
- WHEN the system is invoked via a magnet URI or `.torrent` file from
  outside the app, the system SHALL route it into the download engine
  (Phase 03) the same way an in-app tapped magnet link would be.
</content>
