# Phase 10: Notifications

**Depends on:** 07, 08

## Goal

Resume-watching notifications timed to the user's actual habits, not a
fixed clock.

## Requirements (EARS)

- WHEN the user leaves a video midway (per Phase 07 resume position), the
  system SHALL track that item as resumable.
- The system SHALL track the local time at which the user typically starts
  watching.
- The system SHALL send a resume notification 4-5 minutes before the user's
  typical watch time, if a resumable item exists.
- The system SHALL track which days of the week the user watches, and
  SHALL adjust notification timing/days to match observed patterns rather
  than firing every day at a fixed time.
- IF no resumable item exists, THEN the system SHALL NOT send a resume
  notification.
</content>
