# Tasks: Phase 01 — TMDB Client

See `docs/RALPH.md` for how to run this list, `docs/phases/01-tmdb-client.md`
for requirements.

- [ ] Implement authenticated HTTP client wrapper (Bearer token from
      `TMDB_API_KEY`)
- [ ] Implement `searchMovie(query)` wrapping `/search/movie`
- [ ] Implement `searchTv(query)` wrapping `/search/tv`
- [ ] Implement `genreListMovie()` / `genreListTv()` wrapping
      `/genre/movie/list` and `/genre/tv/list`
- [ ] Add local cache (e.g. small local DB/disk store) for genre listings
- [ ] Implement `discoverMovie(params)` / `discoverTv(params)` wrapping
      `/discover/movie` and `/discover/tv`, supporting comma/pipe
      `with_genres` syntax
- [ ] Add typed error handling for non-2xx/network failures across all of
      the above
- [ ] Add fast-fail behavior when offline (no silent hangs/retries)
</content>
