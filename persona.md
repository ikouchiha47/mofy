# Mofy — User Personas

Since Mofy is single-user (personal use only), these aren't classic multi-user
market personas — they're **usage-mode personas**: the different contexts and
mindsets you'll be in when opening the app. They shape feature prioritization
more usefully than a generic persona would.

## Persona 1: "The Hunter" — Active Search Mode

**Context:** Heard about a specific movie/show (from a friend, a trailer,
Reddit) and wants it now.

**Behavior:** Opens app → searches by name → goes straight to a torrent site
webview → finds magnet link → downloads.

**Needs:**
- Fast search-to-download path, minimal friction
- Reliable ad-overlay blocking on torrent sites (biggest daily annoyance)
- Auto-detection of movie name from the page so they don't retype it

**Pain point today:** Manually navigating shady torrent UIs full of popups/fake
buttons.

## Persona 2: "The Grazer" — Mood-Based Browsing

**Context:** It's evening, nothing specific in mind, wants something to
watch.

**Behavior:** Opens the Netflix-like home screen → uses mood query ("feeling
bored", "something thrilling") → skims recommendations → likes/dislikes to
refine.

**Needs:**
- Recommendation quality (genre + embedding + RRF ranking)
- Fast feedback loop: like/double-like/not-interested should visibly reshape
  results
- Low-commitment browsing — copy-to-clipboard for "maybe later"

**Pain point today:** Doesn't exist yet — this is the core differentiating
feature to build.

## Persona 3: "The Resumer" — Mid-Watch Return

**Context:** Started a movie/show last night, got interrupted, wants to pick
up again.

**Behavior:** Gets a notification 4-5 min before their usual watch time →
taps → resumes in mpv-like player.

**Needs:**
- Accurate watch-position tracking
- Notification timing that adapts to actual viewing patterns, not just a
  fixed clock time
- Clear "continue watching" row on home

**Pain point today:** No tracking exists yet; this is pure habit-support, low
glamour but high daily value.

## Persona 4: "The Archivist" — Bulk Import

**Context:** Has a folder of movies/TV + subtitles from an external drive or
old downloads, wants them in the library without re-downloading.

**Behavior:** Points app at a directory → fills in movie name manually →
triggers TMDB autofill for metadata → done.

**Needs:**
- Directory picker and SRT pairing
- Fuzzy-match TMDB search so typos still resolve
- Never blocks on the app's own downloader — must work standalone

**Pain point today:** No import flow exists; this determines whether the app
feels "owned" vs. "another silo."

## Persona 5: "The Guest" — Girlfriend / Co-viewer (Watch Together)

**Context:** She's joining a Watch Together session but isn't the "owner" of
the app/library, and has downloaded her own copy of the movie separately
(not received from me directly).

**Behavior:** Joins via room code, her own local copy of the movie is
matched to mine by title/tmdb id, and from there only playback state
(play/pause/seek position) syncs between us — no video ever gets transferred.

**Needs:**
- Dead-simple join flow (QR code scan > typing a code)
- Guest mode UI — just playback controls, no access to my download
  history/library management
- Per-viewer subtitle/audio-track preference in a shared session (she may
  want subs, I may not)

**Feature idea:** This is basically covered by the Watch Together spec now —
matching by normalized title/tmdb id instead of file identity, syncing only
playback events.

## Persona 6: "The Binger" — Multi-Show Tracker

**Context:** Following 4-5 ongoing TV shows simultaneously, new episodes drop
weekly.

**Behavior:** Wants to know what's new without manually checking each show.

**Needs:**
- "Up next" queue across all in-progress shows
- Auto-check for new episodes of tracked shows (maybe via TMDB + a torrent
  site search on a schedule)
- Batch download to "get caught up" after being away

**Feature idea:** A subscribed-shows list with new-episode badges, like a
podcast app.

## Persona 7: "The Completionist" — Franchise / Collection Watcher

**Context:** Wants to watch an entire franchise or director's filmography in
order (all MCU movies, all Miyazaki films).

**Behavior:** Searches for a franchise, wants the whole list, tracks progress
across it.

**Needs:**
- TMDB "collection" API support (it has franchise groupings) to auto-suggest
  the full set
- A checklist/progress view ("6 of 23 watched")

**Feature idea:** Import a whole collection as a to-watch list in one action.

---

**PM take:** Persona 1 (Hunter) and Persona 4 (Archivist) are the "get content
in" side — build these first since nothing else works without content.
Persona 2 (Grazer) is the actual differentiator and where most future effort
should go, now expanded to cover nostalgia and overview/plot-based search.
Persona 3 (Resumer) is a retention feature — worth doing but can lag behind
the others. Persona 5 (Guest) is folded directly into the Watch Together spec
in `ideate.md`. Persona 6 (Binger) and Persona 7 (Completionist) are good
follow-on features once the core library/recommendation loop works — the
Completionist angle (TMDB collections) is a cheap, high-value addition worth
prioritizing early since the API already supports it.
