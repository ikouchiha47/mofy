# ADR 0003: App Navigation and Screen Flow

**Status:** accepted

## Context

Early planning conflated "pick movie vs. TV" with app launch, and treated
Watch Together as an undefined feature with no home in the navigation. Both
were resolved by mocking up the full flow in
`design/browse-flow-mockup.html` (published as an Artifact) and iterating on
it directly with the user. This ADR records the resulting navigation
structure and per-screen contents so the phase docs can be updated to match
exactly, rather than the earlier, looser phase descriptions.

## Decision

### Shell: bottom navigation, 4 tabs

`Home` · `Browse` · `Library` · `Settings`

Only these four are tabs. Every other screen in this document is a pushed
screen (has a back arrow, no bottom nav) or a sheet, reached from within a
tab — never a fifth tab, never a decision screen shown before the tabs.

### Home (tab)

Top bar: app title, a **join-session icon (👥)**, and a **search icon (🔍)**.
These are the entry points to Watch Together's join flow and to Search,
respectively — neither gets its own tab.

Body is Netflix-style horizontal rows, in this order:
1. **Continue Watching** — poster cards with a thin progress bar burned into
   the bottom edge of the artwork, subtitle shows episode/time remaining.
2. **Recommended for You** — poster cards, subtitle shows genre + year.
3. **Recently Added** — poster cards, subtitle shows genre + year.

Tapping any poster card goes to **Title Detail** (pushed, not a tab).

### Browse (tab)

Top bar: "Browse" / "Torrent sites" subtitle. No category decision screen —
category is a **segmented control** (`Movies` / `TV Shows`) directly under
the top bar, defaulting to whichever was last selected.

Below the segmented control: a list of sites filtered to the selected
category. Each row: favicon-letter badge, site name, site URL, then on the
**trailing edge together**: an edit pencil icon and a `›` chevron, in that
order, with visible gap between them (not adjacent to the name). Tapping the
row body (not the pencil) opens the **WebView** for that site. Tapping the
pencil opens **Edit Site** for that site. A final "Add a site…" row opens
the same **Edit Site** form empty.

### WebView (pushed, no bottom nav)

Chrome: back arrow, a shield icon indicating ad-blocking is active, and a
URL bar showing the current page path (truncated).

Ad overlays are fully blocked — not hidden with CSS, never rendered at all.
On page load, the configured CSS selector silently extracts the title and
surfaces it as a small non-blocking toast at the bottom of the page
("Detected: *Title*" / "auto-extracted via CSS selector on page load") —
browsing continues uninterrupted. Tapping a magnet link on the page is what
triggers navigation to **Confirm Match**, carrying the last-extracted title,
the magnet URI, and the category (already known from Browse's segmented
control — never re-asked or guessed).

### Confirm Match (pushed, no bottom nav)

Top bar: "Confirm Match" / "Magnet link captured" subtitle, back arrow.

Body: a banner showing the extracted title in quotes and a pill reading
"searching movies ▸ category locked" (or the TV equivalent) — communicating
that the category was never in question. Below it, TMDB search results for
that title (poster, title, year, overview snippet), selectable. A single
primary action button: "Confirm & Start Download".

### Title Detail (pushed, no bottom nav)

A backdrop hero image with a floating circular back button over it, no
title bar. Below the backdrop: title, `year · runtime · rating` meta line,
a row of genre chips, then the overview paragraph.

Action row (two buttons, primary + secondary, side by side):
- **▶ Play** (primary, filled)
- **👥 Watch Together** (secondary, outlined) — creates a room for this
  title; see below.

Below the actions: a feedback row (👍 like / 🔥 double-like / 👎
not-interested) plus a copy icon pushed to the far right for copying the
title/identifier.

### Watch Together — Create Room (pushed from Title Detail's "Watch
Together" button, no bottom nav)

Top bar: "Watch Together" / the title's name as subtitle, back arrow.

Body, centered: "Room code" label over a large monospaced code (e.g. `7F K9
Q2`), a QR code below it, a "Waiting for someone to join…" status row with a
pulsing indicator dot, and a "🔗 Share Link" button at the bottom.

### Watch Together — Join Session (sheet, triggered only from Home's 👥 icon)

A bottom sheet (not a full pushed screen) with a drag handle, "Join Watch
Together" heading, "Enter the code you were sent" subtitle, a row of
individual character boxes for code entry, a primary "Join" button, an "or"
divider, and a "▢ Scan QR Code" alternative action.

This is deliberately the *only* entry point to joining — the guest never
browses the library or picks a category; the fastest possible path is the
point.

### Player (pushed, no bottom nav, full-bleed video)

Top overlay (floating over the video, not a solid bar): back button on the
left; on the right, a **"● Watching with [name]" pill** — shown only when a
Watch Together session is active for this playback, absent otherwise.

Bottom overlay: a seek row (elapsed time / progress bar with filled portion
/ total time), then a transport row with rewind/pause/forward controls on
the left and a circular **invite icon (👥)** on the right — tapping it
promotes a solo playback into a hosted Watch Together session without
needing to back out to Title Detail first.

### Edit Site (pushed from Browse's pencil icon or "Add a site…", no bottom
nav)

Top bar: "Edit Site", back arrow. Form fields, in order: Name (text), Base
URL (monospaced text), Category (segmented control, `Movies` / `TV Shows`),
Title CSS selector (monospaced text). Bottom action row: "Delete"
(destructive, outlined) and "Save" (primary, filled, wider).

### Search (pushed from Home's 🔍 icon, no bottom nav)

Top bar: "Search", back arrow. Body: a free-text input ("*feeling
nostalgic*" or describe a plot…") at the top, a row of genre filter chips
directly beneath it, then a "Results" list (poster, title, `year · genre`,
overview snippet) fed by whichever signal(s) produced them — text query,
genre chips, or both. There is no separate genre-filter screen; genre
filtering and free-text mood/plot search live in this one screen together,
feeding the same result list.

## Consequences

- `docs/phases/02-torrent-site-browsing.md` needs its extraction/hand-off
  requirements rewritten to match the WebView → Confirm Match flow above
  (silent toast, magnet-tap-as-trigger, category carried from Browse) —
  currently underspecified relative to this ADR.
- `docs/phases/08-home-ui-signals.md` needs the top-bar icons (join-session,
  search) and the exact three-row order added.
- `docs/phases/13-watch-together.md` needs the Create Room / Join Session /
  Player-invite screens added as concrete UI requirements, not just the
  sync-protocol requirements it currently has.
- A new phase doc is needed for **Title Detail** (currently has no home —
  Phase 08 only covers the Home rows themselves) and for **Edit Site**
  (currently implied by Phase 02 but not specified as its own screen).
- Site configuration (Phase 02's site list) is no longer read-only/seed-only
  — it must support create/edit/delete through the Edit Site screen, which
  changes Phase 02 from "static configured list" to "user-editable list
  with persistence."
</content>
