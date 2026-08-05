# Phase 00: Skeleton

**Depends on:** none

## Goal

Stand up the empty Android/Kotlin/Compose project shell that every later
phase attaches to. No feature behavior here — just config, navigation, and
build plumbing.

## Requirements (EARS)

- The system SHALL be a Kotlin project using Jetpack Compose for UI, per
  [[0001-native-kotlin-over-react-native]].
- The system SHALL load `TMDB_API_KEY` from a `.env` file at build/run time.
- The system SHALL load a separate `.env.prod` file for the real
  `TMDB_API_KEY`, which SHALL NOT be committed to version control.
- WHEN the app is launched, the system SHALL display a navigable shell (even
  if individual screens are placeholders).
- IF `TMDB_API_KEY` is missing or empty, THEN the system SHALL surface a
  clear startup error rather than failing silently later during an API
  call.
</content>
