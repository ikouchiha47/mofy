# Tasks: Phase 02 — Torrent Site Browsing + Name Extraction

See `docs/RALPH.md` for how to run this list,
`docs/phases/02-torrent-site-browsing.md` for requirements.

- [ ] Define configurable site list data structure (name, url, category:
      tv/movie, optional title-selector)
- [ ] Seed the list with yts.vg (movie category, selector
      `.right-details-box .title-year h1`)
- [ ] Build WebView screen that loads a selected site
- [ ] Implement ad-overlay/popup blocking in the WebView (block
      window.open/new-tab attempts, popup redirect patterns)
- [ ] Implement JS-injection based title extraction using the configured
      per-site selector
- [ ] Implement fallback HTML-to-text extraction path for sites without a
      configured selector
- [ ] Implement magnet-link tap capture, handing the URI off to a pending
      download queue (consumed by Phase 03)
</content>
