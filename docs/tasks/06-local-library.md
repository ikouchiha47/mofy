# Tasks: Phase 06 — Local Library + Metadata Storage

See `docs/RALPH.md` for how to run this list,
`docs/phases/06-local-library.md` for requirements.

- [ ] Design local library schema (title, tmdb id, media type, genres,
      overview, poster path, file paths, source)
- [ ] Implement local persistence layer (e.g. local DB)
- [ ] Wire Phase 03 download-completion callback to create/update library
      entries
- [ ] Wire Phase 05 import-confirmation flow to create/update library
      entries
- [ ] Support library entries with no local file yet (placeholder/to-watch
      state)
- [ ] Implement remove-item flow that records a not-interested/dislike
      signal alongside deletion
- [ ] Implement plain-SQLite BLOB-column vector table for catalog
      embeddings (no native extension loading)
- [ ] Implement FTS5 virtual table for title/overview keyword search
</content>
