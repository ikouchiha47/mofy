# ADR 0006: Watch Together sync transport, protocol, and player

**Status:** accepted

Supersedes the "e.g. websocket" placeholder in
`docs/phases/13-watch-together.md` and the LAN-WebSocket recommendation in
`docs/research/watch-together-transport-webrtc-vs-websocket.md`. Also
supersedes Phase 07 / ADR 0001's "mpv-based player" choice in favor of
**libVLC** (playback engine is shared by solo play and Watch Together).

## Context

`docs/phases/13-watch-together.md` requires syncing play/pause/seek, periodic
position broadcast with drift correction, per-participant subtitle/audio
prefs, and join-by-room-code/QR — with the explicit rule that **no video bytes
transfer between devices**.

UX decisions locked via Stage A mockup (`design/watch-together-mockup.html`)
and follow-up discussion:

- Works on the **same network and across different networks** (not LAN-only).
- **Anyone** in the session can play / pause / seek (not host-only controls).
- **Max 10 participants** per room.
- Payload is **timing + actions only** (JSON control plane), never media frames.
- In-app player required so remote seek/pause can be applied programmatically
  (external VLC/mpv via `ACTION_VIEW` cannot do this).

Research (`docs/research/watch-together-transport-webrtc-vs-websocket.md`)
originally preferred LAN WebSocket. Cross-network + "no backend of our own"
changes the trade-off: WebRTC DataChannel + public STUN gives NAT traversal
without standing up a media server. Signaling is still required (JSEP); it is
scoped below, separate from the data plane.

Playback: Phase 07 already required an embedded engine. **libVLC**
(`org.videolan.android:libvlc-all`) ships as a Maven AAR with a stable Java
API for position, pause, seek, and external SRT — lower integration cost than
building libmpv JNI from mpv-android sources (which is explicitly not an AAR).

## Decision

### Transport

Implement Phase 13's sync plane as **WebRTC DataChannels**, star topology:

- **Host is the hub.** Each guest has one peer connection to the host.
  Guests do not mesh with each other. Host fans out control messages.
  At 10 participants that is 9 peer connections on the host — acceptable
  for tiny JSON; a full mesh would be 45 links and is rejected.
- **DataChannel only — no media transceivers.** Never open RTP/SRTP audio
  or video. No codecs, no frame containers. Channels carry JSON text
  (or binary-encoded JSON) only.
- **Reliability profile:**
  - `play` / `pause` / `seek` / room lifecycle → reliable, ordered channel
    (default DataChannel).
  - `position` ticks → may use an unordered / partial-reliability channel
    (`maxRetransmits=0` or short `maxPacketLifeTime`); receivers apply
    **last-wins** and never queue stale ticks.
- **ICE / NAT:** use Google's public STUN servers to start
  (`stun:stun.l.google.com:19302`, plus the usual stun1–4 fallbacks).
  No TURN in v1 — if both peers are on symmetric NAT and P2P fails, surface
  a clear "couldn't connect — try same Wi‑Fi or a network that allows P2P"
  error. TURN can be added later without changing the message surface.
- **Signaling (SDP/ICE exchange):** out-of-band from the DataChannel, as
  required by JSEP (RFC 8829). v1 approach:
  - Room code (6 chars, grouped `7F · K9 · Q2`) identifies the room.
  - Host creates the room and is the offerer for each guest.
  - Signaling messages (`signal` envelope below) ride a short-lived
    bootstrap path established at join time (QR/deep link carries enough
    to start; subsequent ICE candidates exchange over that path until the
    DataChannel is up). Implementation may use a minimal host-side
    listener on LAN when both peers share a network, or a later thin
    relay for cross-network bootstrap — **the DataChannel message surface
    does not change either way**.
  - Once the DataChannel is open, all session traffic (including further
    joins' coordination from the host's point of view) uses it; signaling
    sockets can idle out.

### Message surface (JSON over DataChannel)

```
{type:"hello",      roomKey, displayName}
{type:"join",       roomKey, displayName, itemHash}
{type:"join-ack",   participantId, participants[], positionMs, isPlaying}
{type:"participant", op:"joined"|"left", participant}
{type:"play",       positionMs, by}
{type:"pause",      positionMs, by}
{type:"seek",       positionMs, by}
{type:"position",   positionMs, isPlaying, ts}   // host heartbeat; last-wins
{type:"pref",       subtitleTrack, audioTrack} // local only; never applied remotely
{type:"error",      reason}
{type:"bye",        reason?}
```

Rules:

- **Anyone** may emit `play` / `pause` / `seek`. Host validates and fans out
  to all other participants (including echoing state, never echoing back to
  the originator — **echo suppression**).
- Host emits `position` on an interval (named constant, e.g. every 2–5s)
  and on meaningful drift; receivers apply last-wins.
- Hot-join: host's `join-ack` carries current `positionMs` + `isPlaying` so
  the newcomer seeks immediately.
- `pref` is never forwarded as a playback command to others.
- Soft cap **10 participants**; further `join` attempts get
  `{type:"error", reason:"room_full"}`.
- Room identity for the library item is the **item hash** from Stage B
  (`tmdbId+mediaType` or normalized-title hash) — guests must already have
  a matching local copy (Phase 13: no file transfer).

### Player engine

Use **libVLC** (official Android AAR) as the in-app player for Phase 07 and
Phase 13:

- Dependency: `org.videolan.android:libvlc-all` (Maven Central).
- Mofy owns a Compose-hosted `VLCVideoLayout` / `SurfaceView` and drives
  `MediaPlayer` for play, pause, seek-to-ms, track selection, and external
  `.srt`.
- Do **not** shell out to an installed VLC app via `ACTION_VIEW` for
  playback that participates in Watch Together (no remote control surface).
- Solo "Play" on Detail uses the same embedded engine (one player stack).

## Alternatives considered

- **LAN WebSocket on the host (previous draft of this ADR).** Simpler on a
  single subnet, no STUN/ICE. Rejected because the product requirement is
  cross-network (different Wi‑Fi / mobile data), which WebSocket-on-host
  cannot do without a publicly reachable host or a relay we do not want to
  run yet.
- **Full WebRTC mesh.** Unnecessary for control-plane JSON; connection count
  grows O(n²); host-star is enough and matches "host broadcasts position".
- **WebRTC with media transceivers.** Out of scope — we deliberately do not
  send video/audio frames; each device plays its own file.
- **TURN from day one.** Improves connect rate behind symmetric NATs but
  needs a server or paid provider. Defer until STUN-only failure rate hurts.
- **libmpv / mpv-android.** Already named in Phase 07 / ADR 0001. mpv-android
  is not an AAR — embedding means vendoring JNI + native build scripts.
  libVLC gives the same codec/SRT coverage as a maintained Maven artifact.
  Revisit only if libVLC blocks a hard requirement.
- **External VLC/mpv app via Intent.** Cannot apply remote seek/pause; breaks
  Watch Together and the in-app session pill/player UX.
- **Cloud room relay for all messages (no P2P).** Works, but adds a backend
  and latency hop for every scrub. Prefer P2P DataChannel; backend only for
  optional signaling/TURN later.

## Consequences

- APK grows by libVLC native libs (significant; ABI splits recommended) and
  a WebRTC stack (e.g. Google WebRTC AAR or a maintained wrapper).
- Phase 07 tasks switch from "integrate mpv-android" to "integrate libVLC".
- Phase 13 tasks switch from "embedded websocket server" to "WebRTC host hub
  + guest peer + STUN ICE + DataChannel message surface".
- Cross-network join works when ICE succeeds via STUN; some networks will
  still fail without TURN — UX must explain that.
- Star topology means if the **host** drops, the room ends (or a later
  revision elects a new host — not in v1).
- Echo suppression and last-wins position apply regardless of who scrubbed.
- Subtitle/audio remain per-device via libVLC track APIs; `pref` frames are
  informational only if we keep them at all.
- Research doc remains as historical comparison; this ADR is the decision.

## Prior art

- **Jellyfin SyncPlay** — control-plane sync of play/pause/seek + ready
  state; drift thresholds; host-authoritative clock ideas.
- **Syncplay** — major-desync seek vs minor-desync rate adjust.
- **WebRTC DataChannel (RFC 8831) + JSEP (RFC 8829) + SDP (RFC 8866)** —
  data-only usage; STUN (RFC 8489) for ICE.
- **libVLC Android** — official AAR + samples
  (`libvlc-android-samples`).

## Tasks consequence

`docs/tasks/13-watch-together.md` Stage B should read against this ADR:

- **B3** — host WebRTC hub (N≤9 guest peer connections, DataChannel fan-out)
- **B4** — guest peer + ICE via Google STUN + reconnect behavior
- **B5** — play/pause/seek from **any** participant + echo suppression
- **B6** — host `position` heartbeat + last-wins drift correction
- **B7** — per-participant subtitle/audio via libVLC (not synced)
- **B8** — room code / QR bootstrap for signaling + roomKey
- **B9** (new) — soft cap 10; `room_full` error
- **B10** (new) — itemHash match on join; refuse if guest lacks local copy

`docs/tasks/07-playback.md`: replace mpv-android integration with libVLC.

## References

- RFC 8829 (JSEP), RFC 8831 (DataChannel), RFC 8866 (SDP), RFC 8489 (STUN)
- Google public STUN: `stun:stun.l.google.com:19302`
- libVLC Android: `org.videolan.android:libvlc-all`
- `docs/phases/13-watch-together.md`
- `docs/phases/07-playback.md`
- `docs/research/watch-together-transport-webrtc-vs-websocket.md`
- `design/watch-together-mockup.html`
