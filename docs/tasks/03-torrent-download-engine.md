# Tasks: Phase 03 — Torrent Download Engine

See `docs/RALPH.md` for how to run this list,
`docs/phases/03-torrent-download-engine.md` for requirements.

- [ ] Integrate libtorrent4j (or equivalent) into the project
- [ ] Implement start-download-from-magnet-uri function
- [ ] Wrap active downloads in a foreground service with persistent
      notification
- [ ] Implement progress reporting (percent, speed, ETA) surfaced to a
      downloads screen
- [ ] Implement pause/resume/cancel controls per download
- [ ] Implement completion callback that hands off file path(s) to the
      library layer (Phase 06)
- [ ] Implement stalled-download detection and user-facing state (vs.
      infinite spinner)
</content>
