# Research: Watch Together sync transport — WebRTC DataChannel vs LAN WebSocket

**Status:** historical research. **Decision locked in ADR 0006 (accepted):**
WebRTC DataChannel (star topology, Google STUN, control-plane JSON only) +
libVLC player — not LAN WebSocket. Cross-network requirement flipped the
recommendation below; keep this doc for the trade-off write-up.

## Why this exists

`docs/phases/13-watch-together.md` deliberately leaves the transport open: *"lightweight
message channel (e.g. websocket)"*, and `docs/tasks/13-watch-together.md` has a single
task: "Implement local (LAN) websocket channel". Nothing about this is locked in by an ADR,
and no WebRTC code exists in the app today (verified: zero `webrtc` references in
`app/src`). This doc establishes an honest baseline: what WebRTC actually buys for a
**positions-and-actions, not video frames** use case, and what it costs.

## The payload profile (constrains the answer)

What Watch Together (Phase 13) will actually send between two peers:

- Play / pause / seek events — user-initiated, at most a few per minute, ordering matters
  (a pause arriving after a play could leave one side running).
- Host position heartbeat / drift-correction ticks — periodic, seconds apart. Stale ticks
  are **not** valuable; the next one supersedes them.
- Per-participant subtitle/audio-track preference messages — one per change, must arrive,
  order irrelevant.
- Each message is a small JSON blob (tens to low hundreds of bytes).

No RTP, no media frames, no large transfers. The channel is a control plane, not a data
plane. Nothing here pushes on the transport the way video does.

## Key structural finding: WebRTC Datachannel doesn't remove the need for signaling

WebRTC is not a drop-in "peer connection appears out of thin air". Every peer connection
requires a prior, out-of-band exchange of SDP offer/answer and ICE candidates (per
JSEP, RFC 8829 — signaling is deliberately out of WebRTC's scope). So even in the
full-WebRTC design, **both devices need a signaling channel before the data channel can
open**. In a two-device LAN scenario the base channel in your phase already provides that
signaling. The "serverless" win isn't real here — you're building the signaling
infrastructure either way; WebRTC adds a second channel on top of it.

## WebSocket on the LAN — what it holds up

- Ordering + delivery guarantees come from TCP: play/pause/seek sequences can't be
  reordered or silently dropped. Since your correctness requirement is exactly
  "every participant sees the same play/pause/seek sequence" (Phase 13, lines 21–28),
  this free guarantee matches the requirement to the letter. Drift-correction ticks are
  the only "drop-acceptable" traffic, and e2e they will still arrive; you'd need to
  pro-actively drop stale ones on re-balance, which your periodic-broadcast design
  already implies (most recent tick wins).
- LAN latency is single-digit ms typical; the interaction that "feels wrong" in
  watch-together is actually media buffering, which we don't share — the sync event
  latency budget is easy to satisfy.
- One channel, one protocol, same small library for both directions; no extra state,
  no DTLS/SCTP/ICE layers to debug; reconnection is a fresh URL/open.

## WebRTC DataChannel — what it actually buys here

Honest set of advantages for this specific use case:

1. **Per-channel reliability profiles.** A data channel can be `ordered=false,
   maxRetransmits=0` — true datagram semantics — so position ticks could be sent
   "never retransmit, drop stale" and play/pause/seek as a reliable ordered channel.
   This is the one thing a WebSocket cannot do. Whether it matters at your message
   rates on a LAN is worth a real argument: with a tick every few seconds on a LAN,
   TCP retransmission delay during the rare loss rarely exceeds a tick anyway.
2. **Encryption without server certificates.** The DataChannel is DTLS-encrypted.
   In the WebSocket wire-endpoint you need a valid TLS cert (hosted, real) or your
   own CA — which the LAN websocket, if local-only, would likely ship as plaintext
   `ws://`. WebRTC gives you confidential sync by default, no PKI.
3. **P2P when the hop is not a LAN** — if a future version works across networks
   (another city), DataChannel doesn't go through a server — but then you also
   inherit STUN/TURN, ICE re-negotiation, and the same signaling channel. For
   LAN-only that's a "go" you don't cost today.

There is **no** advantage in latency, throughput or overhead vs WebSocket for
your payload scale. The RFCs make this explicit: data channels get the SCTP
over-DTLS stack; underneath DTLS is still UDP with its own reliability logic, and
the channel isn't usable until the full handshake has finished. All of WebRTC's
real wins are the three above, none of which depend on payload size.

## Gotchas (the ones discovered/repeated everywhere, this shape)

1. **Channel options are immutable and fix at creation** — `ordered`,
   `maxRetransmits`, `maxPacketLifeTime` cannot be changed after
   `createDataChannel`. Your play/pause vs position-tick split is 2+ channels from
   the start. Fine, but a design requirement, not an afterthought.
2. **Open is later than you think.** A DataChannel is usable only after ICE + DTLS
   handshake + SCTP association — hundreds of ms in. Your room/join screen must gate
   the playback-control buttons on `onopen`, never assume the channel is ready.
3. **Second gotcha: message size (16 KiB) and ordering.** SCTP fragments large
   messages, and on an ordered channel delivery of anything behind a big message
   blocks until its last fragment lands (head-of-line blocking — the SCTP
   association shares one reliability window, even across separate data channels,
   per RFC 8831 §6). Your ≤~1 KiB events stay well below the 16 KiB interoperable
   max (and the negotiated `max-message-size` when both peers support it), so this
   never bites — but if a payload ever grows (e.g. a subtitles-prefs blob), chunk
   it at the app layer.
4. **Two devices: still leverage the signaling channel also as your fallback.**
   The join flow (room code → QR) can carry the websocket URL today; if you layer
   WebRTC over cold start, QR-encoded SDP payloads are large and fragile. Practical
   pattern is: WebSocket does signaling + fallback; DataChannel does live sync; on
   disconnect the code falls back to WebSocket events and converges once reconnected.
5. **Native library weight.** On Android, WebRTC DataChannels means embedding the
   full libwebrtc AAR (`org.webrtc` / `libwebrtc`) — media stack and all — or the
   lighter `libdatachannel` (C++ DataChannel + ICE/DTLS/SCTP only, no media, still
   needs JNI wiring). That's a real, irreversible-ish dependency, notable size +
   build-weight addition to a personal project whose data path is already solved
   by something the size of a ~KiB WebSocket... worth thinking hard before adding.
6. **ICE candidate / NAT nuance**: on one LAN in a room behind one router, host
   candidates almost always work. Both: whatever you choose, wire the "join by
   reachable IP" fallback manually because mobile network switches (WiFi →
   cellular, hotspot) actually kill both IP-based channels and need re-bind — with
   WebRTC that's an ICE restart you must not ignore.

## Comparison: LAN WebSocket vs WebRTC DataChannel

| Dimension | LAN WebSocket | WebRTC DataChannel |
|---|---|---|
| Packets | JSON over TCP | JSON over SCTP/DTLS/UDP |
| Ordering/delivery | TCP-strong (exactly matches sync requirements) | per-channel knobs, incl. drop-stale ticks |
| Signaling server | none needed (a device is host) | required anyway (same as above) |
| PKI/encryption | plaintext unless you certificate the LAN host (annoying) | DTLS-included by default |
| On-device weight | tiny; the classic | libwebrtc (~40MB aar) — or lite, JNI libdatachannel |
| Lib API surface | `OkHttp` (client) + you introspect, bidirectional | PeerConnectionFactory + DataChannel |
| Runtime lib complexity | low | high (ICE, DTLS role, SCTP streams) |

## References

- JSEP — Session Description Protocol for the WebRTC stack:
  <https://datatracker.ietf.org/doc/html/rfc8829>
- SDP: new specification — RFC 8866, SDP: Session Description Protocol:
  <https://datatracker.ietf.org/doc/html/rfc8866>

## Open questions

1. Is "across another room" always the same LAN/household for v1? If yes,
   WebSocket wins on engineering cost. If you see cases of "across the Internet,
   different households," WebRTC (with STUN/TURN + signaling) or even soft signaling
   become required later, and the data channel already opened now.
2. Do we need wire-encryption on day one, or is plaintext-on-private-LAN
   acceptable (personal, two known devices)?
3. Will the room be limited to exactly 2? WebSocket star (host = server) scales to
   a handful naturally; WebRTC mesh grows quickly — direct connection between IPs.

## Recommendation (needs your OK before it becomes ADR-0006)

Build the Phase 13 sync plane on a **tiny embedded WebSocket**: the host device runs
it via a minimal server in the app (`ws://<lan-ip>:<room-port>`, join URL/QR encodes
that); play/pause/seek as ordered string-typed messages, position ticks as
last-wins values with no accumulation. Expected ~1 day of work, right-sized for a
two-device LAN and a personal project; the payload guarantees it 1:1.

Design an interface point (`Transport` boundary) so that if a migration to
WebRTC DataChannel — or a future over-the-internet version that needs signaling
+ ICE — becomes real, the transport implementation can be swapped under the
existing event contract without touching the player sync logic. Note the "two
channels" plan (command vs position) is required if you do go DataChannel.

This is a reversible decision and cheap to revisit — write ADR-0006 to record
the choice as it's made.