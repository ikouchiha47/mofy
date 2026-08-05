# Phase 08: Home UI + Signals

**Depends on:** 06, 07

## Goal

The Netflix-like home surface, continue-watching, and the like/dislike
feedback signals that later drive recommendations.

## Requirements (EARS)

- The system SHALL display a home screen showing library items the user was
  watching or has watched.
- The system SHALL display a "continue watching" row for items with a saved
  resume position (from Phase 07).
- The system SHALL support like, double-like, and not-interested actions per
  item.
- WHEN a like/double-like/not-interested/removed/watched-midway-never-again
  action is taken, the system SHALL record it as a feedback signal
  associated with that item's TMDB id/genres.
- The system SHALL allow the user to copy an item's title/identifier via a
  copy icon shown per item.
</content>
