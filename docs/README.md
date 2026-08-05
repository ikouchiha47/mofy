# Mofy Docs

This directory holds the working documentation for building Mofy.

- **`adrs/`** — Architecture Decision Records. One file per significant,
  hard-to-reverse decision (framework choice, library choice, protocol
  choice). See `adrs/0000-template.md` for the format.
- **`phases/`** — One file per build phase, from `docs/phases/*.md`. Each
  phase file states its goal, dependencies, and requirements written in
  **EARS** (Easy Approach to Requirements Syntax) so that "done" is testable,
  not vibes-based.
- **`tasks/`** — One file per phase, mirroring `phases/`, but broken into a
  checkbox task list meant to be run via a **Ralph loop** (see `RALPH.md`).

See `../ideate.md` for the original brainstorm and `../persona.md` for the
usage-mode personas that motivated these phases.

## Phase order

Phases are ordered by dependency, not calendar time (an AI executes this
plan, not a human sprint team — do not slice phases by "how long something
feels like it should take").

```
{TMDB client}
  -> {webview scraping, manual import}
  -> {torrent engine, intent handling}
  -> {library}
  -> {playback}
  -> {home UI/signals}
  -> {recommendation, notifications}
  -> {collections, show tracking, watch together}
```

| # | Phase | Depends on |
|---|-------|-----------|
| 00 | Skeleton | - |
| 01 | TMDB client | 00 |
| 02 | Torrent site browsing + name extraction | 00 |
| 03 | Torrent download engine | 02 |
| 04 | System intent handling (magnet/.torrent) | 03 |
| 05 | Manual import | 01 |
| 06 | Local library + metadata storage | 03, 05 |
| 07 | Playback | 06 |
| 08 | Home UI + signals | 06, 07 |
| 09 | Recommendation engine | 01, 08 |
| 10 | Notifications | 07, 08 |
| 11 | Collections (Completionist) | 01, 06 |
| 12 | Show tracking (Binger) | 02, 03, 06 |
| 13 | Watch Together | 06, 07 |
</content>
