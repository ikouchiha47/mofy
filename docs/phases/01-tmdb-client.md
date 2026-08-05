# Phase 01: TMDB Client

**Depends on:** 00

## Goal

A data-layer client wrapping the TMDB APIs described in `ideate.md`: search,
genre listing, discover. No UI consumes it yet beyond debug/inspection —
this is pure plumbing needed by phases 05, 06, 09, 11.

## Requirements (EARS)

- The system SHALL provide a search-movie function wrapping
  `GET /search/movie?query=`, authenticated via Bearer token.
- The system SHALL provide a search-tv function wrapping `GET /search/tv?query=`.
- The system SHALL provide a genre-listing function wrapping
  `GET /genre/movie/list` and `GET /genre/tv/list`.
- The system SHALL cache genre listings locally so they are not re-fetched
  on every app launch.
- The system SHALL provide a discover function wrapping
  `GET /discover/movie` and `GET /discover/tv`, supporting `with_genres` as
  a comma (AND) or pipe (OR) separated parameter.
- WHEN a TMDB request fails (network error, non-2xx response), the system
  SHALL surface a typed error to the caller rather than crashing or
  returning a stale/empty result silently.
- IF the device is offline, THEN the system SHALL fail fast on TMDB calls
  rather than hanging/retrying indefinitely.
</content>
