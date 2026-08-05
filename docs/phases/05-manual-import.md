# Phase 05: Manual Import

**Depends on:** 01

## Goal

Let content acquired outside the app (external drives, other downloaders)
be pulled into Mofy's library without needing to be re-downloaded — the
"Archivist" persona.

## Requirements (EARS)

- The system SHALL allow the user to select a directory containing movie
  and/or subtitle files via the Storage Access Framework.
- WHEN a directory is imported, the system SHALL pair video files with
  matching subtitle (`.srt`) files where present.
- The system SHALL allow the user to manually enter/confirm a title for an
  imported item.
- WHEN a title is entered, the system SHALL trigger a TMDB search (Phase 01)
  and let the user confirm the correct match from results.
- WHEN a match is confirmed, the system SHALL autofill genres, overview, and
  poster from TMDB into the library entry.
- The system SHALL function fully without requiring the in-app torrent
  downloader (Phase 03) to have been used at all.
</content>
