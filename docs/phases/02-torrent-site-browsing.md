# Phase 02: Torrent Site Browsing + Name Extraction

**Depends on:** 00

## Goal

A WebView-based browsing surface over a configurable list of torrent sites
(starting with yts.vg), with ad-overlay blocking and per-site title
extraction. This is the "Hunter" persona's primary path.

## Requirements (EARS)

- The system SHALL maintain a configurable list of torrent sites, divided
  into TV and Movie categories, starting with `https://yts.vg/`.
- The system SHALL render a selected site inside an in-app WebView.
- WHILE browsing a torrent site, the system SHALL block ad-overlay
  links/popups (comparable to a Brave-like ad blocker), preventing them from
  opening new windows/tabs or redirecting the current page.
- The system SHALL support a per-site mapping of a DOM selector used to
  extract the movie/show title from the page (e.g. for yts:
  `.right-details-box .title-year h1`).
- WHEN a page is loaded on a site with a configured selector, the system
  SHALL extract the title using that selector.
- IF no selector is configured for the current site, THEN the system SHALL
  fall back to extracting and converting page HTML to text for manual/model
  based title detection (e.g. via a small on-device model).
- WHEN the user taps a magnet link on the page, the system SHALL capture the
  magnet URI for hand-off to the download engine (Phase 03).
</content>
