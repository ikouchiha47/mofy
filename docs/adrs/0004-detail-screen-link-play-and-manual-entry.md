# ADR 0004: Detail Screen Actions (Link/Play/Watch Together), Manual Entry, and TMDB Field Sync

**Status:** accepted

## Context

Three related gaps were identified after Detail existed only as a minimal
poster+overview+downloads view (see ADR 0003's Title Detail section, built
ahead of this ADR with fewer specifics):

1. Detail had no way to point at a file the user already downloaded with
   another app, and no Play action - "download inside Mofy" was explicitly
   parked earlier in the project, but nothing replaced it.
2. Media type (Movie/TV) is usually known from Browse's category context at
   save time, but not always (manual adds, some imports) - there was no way
   to set or correct it from Detail.
3. Some titles aren't on TMDB at all, and the magnet-paste fallback
   (mentioned early in the project, never built) had no home.

Per standing project practice (see `CLAUDE.md` "Working conventions"), the
full flow was mocked up in `design/detail-link-play-mockup.html` (published
as an Artifact) and iterated on directly with the user across several
rounds before any code was written. This ADR records the resulting design
**and an explicit style guide** so implementation matches the approved
mockup exactly - a prior round of UI work drifted from what was designed
because the style wasn't written down anywhere code-facing; this is the
fix for that.

## Decision

### Detail screen: action row (Link / Play / Watch Together)

Three actions, left to right, sized `flex: 0.8 / 1 / 1.1` respectively:

- **Link** (`btn-link`) - outlined, `surfaceVariant` background, 1px
  `border` outline. Opens the linking flow (below). Once something is
  linked, this button's state flips: label becomes "✅ Linked", text color
  `good` (#3ECF8E), border tinted to match - it stays tappable to re-link.
- **Play** (`btn-play`) - filled, `text` color background (`#F2F2F2`) with
  near-black (`#101012`) label - the one visually "loudest" action, matches
  the existing Confirm Match / Save button treatment of using the brightest
  fill for the primary action.
- **Watch Together** (`btn-watch`) - filled, `surfaceContainer` background.
  Present but inert until Phase 13 (Watch Together) actually exists -
  **not hidden**, so users know it's coming, but tapping it before Phase 13
  ships should show a "coming soon" toast rather than nothing.

No in-app torrent download engine backs any of this - **Mofy never
downloads anything itself** (reaffirming the Phase 03 park decision from
earlier in the project, now explicit in an ADR rather than only a code
comment). Linking always points at a file the user already has.

### Media type: always-editable chips, not a one-time prompt

Below the Genres line (not in a separate banner, not conditionally
rendered): a compact row with two small chips, "Movie" and "TV", plus the
Sync info button on the same row's trailing edge:

```
[Movie] [TV]                    [↻ Sync info]
```

- If `mediaType` is already known (the common case - Browse's category
  locks it before Confirm Match even runs), the matching chip renders
  `selected` (accent-filled); the other stays neutral.
- If unset (manual add with no clear source), **neither** chip is
  `selected` - this is a real, valid, visible state, not an error.
- Tapping either chip **always** works, whether or not one is already
  selected - this lets the user correct a wrong classification later, not
  just fill in a missing one. Tapping saves immediately (no separate
  "confirm" step) and updates the chip's active state.

### Linking a file (Link → picker → optional role assignment)

1. Tap **Link** → two options: "Pick a single video file" or "Pick a
   folder". Both go through Android's Storage Access Framework (matches
   the existing Library import picker - no broad storage permission).
2. If a **single file** is picked, that's the link - done, no further step.
3. If a **folder** is picked, list its contents, then an "Assign roles"
   section with one row per role: **Movie file** (required), **Subtitles**
   (optional), **Subtitles 2** (optional, second track). Each row shows the
   currently-assigned filename (base name ellipsis-truncated, extension
   always visible - e.g. `Jack.Reacher.2012.1080p…mkv`) and a `Change`/
   `Pick` button. "Save link" is disabled until a movie file is assigned.
4. After linking, Detail's Link button flips to the "✅ Linked" state and
   the corresponding download row shows the local path instead of a
   magnet/URL, tagged `Linked` instead of `Not linked`.

### Manual magnet/link entry (Downloads section)

A `+ Add magnet or link manually` row at the bottom of the Downloads list
(dashed border, matches empty-state affordances elsewhere in the app)
opens a two-field form: **Name** and **Link**, both free text - no
resolution/quality parsing at this stage, stored exactly as typed. Tapping
an unlinked download row (magnet or manual link alike) opens the same
Android share sheet as Confirm & Download; tapping **Link** afterward is
how the user tells Mofy the file now exists locally.

### Adding a title with no TMDB match

Reached from Library (alongside the existing "Import from device" entry):
**"Add manually"**.

1. First, search TMDB by title (reuses the existing search infrastructure -
   same as Confirm Match). This is explicitly the *first* search source,
   not the only one - **more search sources can be added later** without
   changing this flow's shape.
2. If TMDB has no match, or the user just wants to skip straight to it:
   **"Can't find it? Enter details manually →"** opens a plain form:
   - **Poster** - a placeholder box by default, with two actions above/below
     it: **"🔍 Search TMDB"** (re-run the poster-only search inline) and
     **"📁 Upload image"** (SAF file picker for a local image). Neither is
     required - an unset poster stays as the placeholder box, it is never a
     blocking field.
   - **Title** (required, the only required field), **Year**, **Movie/TV**
     chips (same component as Detail's), **Genres** (free-text,
     comma-separated - no genre picker UI for manual entries), **Overview**
     (optional, multi-line).
   - Actions: `Cancel` / `Save to library`.

### TMDB field sync (config-driven required/optional, Sync info button)

Detail already stores whatever TMDB search-result fields were available at
save time (title, overview, poster, year, genre ids, rating - see
`LibraryItem`). This ADR adds:

- A **field requirement config** distinguishing **required** fields (must
  be populated before the item is considered "complete" - title, poster,
  year, overview) from **optional** ones (richer detail-endpoint-only
  fields like runtime, cast, tagline - nice to have, never block anything).
- On Detail open, if any **required** field is missing and the item has a
  `tmdbId` (i.e. it's not a fully-manual entry), silently fetch and fill
  the gap from TMDB's detail endpoint (`/movie/{id}` / `/tv/{id}`) - no
  user action needed, matches the existing genre-sync self-healing pattern
  (`GenreRepository.resolveNames`).
- **Optional** fields are never fetched automatically - the **"↻ Sync
  info"** button (in the type/sync row described above) is what triggers a
  full re-fetch, for when the user wants the richer fields or suspects the
  cached data is stale. This keeps Detail's default load fast (no forced
  network round-trip) while still making a manual refresh one tap away.

## Style guide

**This section is binding, not descriptive** - every value below was
extracted directly from the approved `design/detail-link-play-mockup.html`
(itself built from `CLAUDE.md`'s existing design tokens, which do not
change here). Implementation must use these exact tokens - if a new
surface needs a value not listed here, add it to `CLAUDE.md`'s Design guide
section and this table together, don't invent a one-off.

### Colors (unchanged from `CLAUDE.md` / `ui/theme/Color.kt`)

| Token | Hex |
|---|---|
| Background | `#0E0E10` |
| Surface | `#1A1A1D` |
| Surface Variant | `#232327` |
| Surface Container | `#2B2B30` |
| Border | `#2E2E33` |
| Text | `#F2F2F2` |
| Text Dim | `#9A9AA2` |
| Accent (primary) | `#E94560` |
| Accent Blue (secondary/links/sync) | `#4EA1FF` |
| Good (linked/success state) | `#3ECF8E` |

### Corner radius (`ui/theme/Shape.kt` scale - never pill/stadium)

| Element | Radius |
|---|---|
| Chips (Movie/TV, genre) | 7-8dp (`extraSmall`/`small`) |
| Buttons (Link/Play/Watch Together, Cancel/Save, Sync info) | `small` (8dp) - pass `shape=` explicitly, Material3's default ignores the theme |
| Poster thumbnails, folder-picker rows | `large`/`extraLarge` (12-16dp) |
| Download rows, form text fields, dashed "Add manually" row | `medium` (10dp) |

### New component-specific values (not yet in `CLAUDE.md` - add on implementation)

| Component | Spec |
|---|---|
| Type/sync row | `justify-content: space-between`, 8dp gap, sits directly below Genres text, 8dp top margin |
| Chip (unselected) | `surfaceVariant` bg, `border` outline, `textDim` label |
| Chip (selected) | `accent` bg + border, white label |
| Sync info button | `accentBlue` label on `accentBlue` @ 12% opacity bg, `accentBlue` @ 30% opacity border, 7-8dp radius, icon+label |
| Linked state (Link button / download tag) | `good` label/border @ full opacity for text, `good` @ 14% opacity bg for the small download-row tag |
| Download row filename | single line, `text-overflow: ellipsis`, never wraps |
| File-role row filename (folder-link flow) | base name ellipsis-truncates, **extension never truncates** - split into two spans, base `flex:1 / min-width:0 / overflow hidden`, extension `flex-shrink:0` |
| Pick/Change button (file-role row) | `flex-shrink: 0`, `white-space: nowrap` - must never wrap or push outside the card, this broke in an earlier iteration |
| Placeholder poster box (manual entry) | same radius as real posters, centered dim-text label, no image |

## Alternatives considered

- **Build a real download engine (libtorrent4j) instead of Link** - rejected
  earlier in the project (Phase 03 parked) and reaffirmed here: personal-use
  app, share sheet + Link covers the actual need without the maintenance
  burden of an embedded torrent client.
- **Auto-detect movie file vs. subtitles by extension** instead of explicit
  role assignment - rejected for v1: extension sniffing (`.mkv` vs `.srt`)
  would work for the common case but silently breaks for edge cases (e.g.
  a `.mp4` sample file, multiple video files in one release folder); explicit
  assignment is one extra tap and never wrong.
- **Only prompt for media type when missing** (original framing) - replaced
  with always-editable chips after the user pointed out Browse-derived
  types can also be wrong and need correcting, not just filled in.
- **Single search source hardcoded for manual entry** - rejected; the
  "Search TMDB" step is explicitly scoped as the *first* of possibly
  several future search sources, per user direction.

## Consequences

- `LibraryItem`/`ConfirmMatchViewModel` need a way to represent "fully
  manual, no `tmdbId`" entries - currently `tmdbId: Int` is non-nullable
  and doubles as part of the primary key (`"${mediaType}_${tmdbId}"`).
  Manual entries need either a nullable `tmdbId` with a synthesized local
  key, or a separate manual-entry id space - a real modeling decision to
  make at implementation time, not decided by this ADR.
- New Room additions needed: a `linkedUri`/`linkedType` (movie file) plus
  optional subtitle URIs on `LibraryDownload` or a new linked-file entity;
  a `posterSource` (TMDB / uploaded / none) if uploaded posters are stored
  locally rather than as a remote URL.
- Detail's genre/type row and download rows must be built against the
  Style guide table above, not re-derived from scratch - this is the
  specific thing this ADR exists to prevent recurring.
- Play requires a linked file to function - needs a disabled/explanatory
  state when nothing is linked yet (not fully specified by the mockup;
  decide at implementation time whether it's disabled-grey or tappable
  with a "nothing linked yet" message).
- Watch Together's "coming soon" toast is new scope beyond Phase 13's
  existing spec - Phase 13's doc should note Detail already has a hook
  waiting for it.
