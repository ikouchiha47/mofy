# Watch Together host resilience — leader election, evaluated

Written before any code changes, per explicit request: think through use
cases, check what real systems actually do (not assumed), and propose a
design — not a 1:1 copy of any one system.

## The problem, restated precisely

Mofy's Watch Together is a **star topology**: `HostHub` (`watchtogether/webrtc/HostHub.kt`)
holds one `PeerConnection`/`DataChannel` per guest; guests never connect to
each other. The host's phone also runs `EmbeddedSignalingServer` — the
one thing every guest's WebRTC offer/answer/ICE exchange goes through.
This means the host is a **single point of failure** in two distinct ways
that need different handling:

1. **Signaling dependency** — even after a guest's data channel is
   established, a *new* guest joining, or a *dropped* guest reconnecting,
   needs the host's signaling server specifically.
2. **Authority dependency** — `SyncEngine`'s conflict resolution
   (last-timestamp-wins on `Position` broadcasts) assumes the host's
   heartbeat is the ground truth. If the host is gone, there's no ground
   truth left.

## Use cases that need distinct answers

| # | Scenario | Who's affected | What should happen |
|---|---|---|---|
| 1 | A **guest's** connection flakes (WiFi drop, cell handoff) | Only that guest | Reconnect, re-sync `SessionState` from host (already the source of truth) — no election needed, host and other guests are untouched. |
| 2 | The **host's** connection flakes briefly (seconds) | Everyone | Should NOT immediately kill the room — needs a grace period, since the underlying WebRTC/DataChannel can itself recover from brief ICE disruption. |
| 3 | The **host** is gone for real (app closed, phone died, user tapped Leave) | Everyone | Room should end gracefully — this is the already-planned "demote every guest to a local solo player" path (redesign doc), not leader election. |
| 4 | The **host's connection specifically** is gone but the **host device/app** is still fine (e.g. host walked out of ZeroTier/WiFi range while guests are still mutually reachable) | Everyone, but recoverable | This is the one case that's genuinely ambiguous between (2) and (3) from a guest's point of view — a dead host and a merely-unreachable host look identical from the outside. This is *why* real distributed systems need timeouts + quorum, not certainty. |
| 5 | Two guests both notice the host is gone at the same moment | Everyone | Must not end up with two guests both trying to become the new host — this is specifically what leader election prevents (split-brain). |

Case 5 is the one that actually requires "leader election" as a distinct
mechanism, rather than just "reconnect" or "grace period + give up."
Cases 1-3 don't need it at all.

## What real systems actually do (verified against source, not assumed)

### Syncplay — sidesteps the whole problem

[Syncplay](https://github.com/Syncplay/syncplay) (client/server sync for
mpv/VLC/MPC, closest mainstream analog to what Mofy does — synced
playback of *independently held local copies*) is **plain client-server**.
A `syncplay-server` process is the one fixed authority every client
connects to; there's nothing to elect because the server just *is* the
leader, permanently, by construction. This is the "obvious" fix (a small
always-on relay) — explicitly **not** what you asked for here, but worth
naming as the reason nobody else has solved this the hard way: they
didn't need to.

### peer-party — no failure handling exists at all

Read the actual source (not the README): [djalilhebal/peer-party](https://github.com/djalilhebal/peer-party)
is pre-alpha. It uses a **public MQTT broker** (`broker.mqttdashboard.com`)
as a pub/sub relay — every participant publishes/subscribes to a shared
topic `room/{sessionKey}`, filtering messages by a `destinationId` field
client-side. The room key is just the owner's own generated UUID. There is
**no code anywhere** handling owner disconnection — `AbstractSession.destroy()`
is a no-op, and grepping the whole source for `leader`/`promote`/`migrat`/
`election`/`owner` turns up nothing but a plain `OWNER` role-tag constant.
Video sync itself isn't even wired up yet (`Theater.tsx` is a bare
`<video>` tag). Not a precedent — a cautionary example.

### Watchparty-LAN — fixed host = the server, explicitly no recovery

Read the actual source: [Balaji120533/Watchparty-LAN](https://github.com/Balaji120533/Watchparty-LAN)
runs a Node/Express server *on the host's own machine* — the host device
**is** the server process. Guests scan a QR code encoding the host's LAN
IP and connect via WebSocket + WebRTC (star topology, one `RTCPeerConnection`
per guest, same shape as Mofy's `HostHub`). On the host's socket closing,
the server just sets `hostSocket = null` — **no notification is sent to
guests at all**. On any peer connection failure, both host and guest UI
just show "Connection lost. Refresh to retry." — an explicit manual-only
recovery instruction. Its "sync" mode (separate from its WebRTC
audio-broadcast mode) doesn't even synchronize playback — a code comment
states outright that guests control playback independently, "no
mirroring between guests or back to the host," despite the feature's
name. Zero leader-election/host-migration code found by grep. Same
conclusion as peer-party: not a precedent, a cautionary example of what
"we never got to this part" looks like in a small hobby project — which
is exactly the trap of shipping the happy path and calling host death
"refresh the page."

### BitTorrent's DHT — decentralized discovery, not leader election, but a useful pattern

Verified via BEP 5 documentation, not memory: BitTorrent's Mainline DHT
(Kademlia-based) is genuinely decentralized peer discovery — nodes with
IDs numerically close (XOR distance) to a torrent's info-hash store that
torrent's peer list, and lookups walk the DHT toward that info-hash with
no central server involved. The one centralized piece is **bootstrapping**:
a brand-new client with zero known peers hits a small number of
well-known bootstrap servers (`router.bittorrent.com`,
`dht.transmissionbt.com`) exactly once to get its first batch of live DHT
node addresses; after that, it caches real peers and never needs the
bootstrap server again. This is a genuinely useful *shape* — a minimal,
replaceable rendezvous step, with everything after that fully P2P — but
DHT itself (a network built for thousands of mutually-untrusting nodes)
is the wrong tool for a room capped at `SessionLimits.MAX_PARTICIPANTS = 10`
trusted devices. Building a Kademlia table for 10 phones is solving a
much harder problem than this one needs.

### Does this even need a leader? — the BitTorrent objection

A fair challenge raised mid-discussion: BitTorrent's swarm has no leader
at all — any seed serves any leecher, multiple simultaneous seeders,
no election, ever. Why does Watch Together need one?

The honest answer is that it's **not** a P2P-vs-client-server question —
it's about whether the shared state is safe to touch without
coordination. BitTorrent's chunks are immutable and hash-verifiable: any
seed's copy of chunk N is provably identical to any other seed's, so
there's never a "whose version is right" question, and therefore no need
for anyone to arbitrate. Watch Together's play/pause/position is mutable,
real-time, and can be written concurrently by any participant — that's
the actual precondition that makes leaderless coordination hard, not
"P2P" as a category. Mofy already has a leaderless answer for *that*
specific problem: last-timestamp-wins (already decided, effectively a
simple LWW/CRDT-style rule) resolves concurrent play/pause/seek writes
without needing a single arbiter. So the sync-state problem doesn't
inherently need a leader either.

**What does still create an asymmetry is the transport, not the sync
logic**: ADR 0006 made the host the WebRTC hub — every guest's
`PeerConnection` terminates on the host's phone specifically
(`HostHub.kt`). That's a real, load-bearing architectural choice, and
it's the actual reason "the host disappears" is expensive: it's not a
consensus problem, it's that every surviving guest's ICE/DataChannel was
built pointing at one specific device, which now has to be rebuilt
pointing at a different one. Syncplay never faces this because its hub
is a fixed server clients simply reconnect to — a stable server address
is what buys away the migration cost, not anything about being
client-server per se.

### Prior art for the actual asymmetric-tie-breaking problem

Not a watch-party-specific library anywhere (confirmed — none of the
three real projects checked above implement this), but the underlying
machinery is well established once the real problem is named correctly
as "who wins a tie / who takes over a vacated asymmetric role," not
"who has quorum":

- **WebRTC's own "perfect negotiation" pattern** (verified against the
  spec) is the canonical micro-election every symmetric WebRTC app
  already runs: two peers are arbitrarily assigned "polite"/"impolite"
  roles purely to break negotiation collisions (glare) — MDN's own
  guidance is explicit that the assignment method is arbitrary as long
  as it's agreed in advance ("first peer to connect," a random-number
  exchange, or sorting peer IDs). This is precedent for exactly the kind
  of *deterministic, no-voting-round* tie-break proposed below (e.g.
  "lowest participant id wins") — it's the same primitive, just applied
  to "who becomes hub" instead of "whose SDP offer wins."
- **Game-networking host migration** is the closest real analog to "the
  WebRTC hub disappears and someone else has to become it." Unity's
  UNet had a documented Host Migration feature; its successor, Netcode
  for GameObjects, does not (confirmed via Unity's own migration docs
  and multiple open feature-request threads on Unity Discussions) — the
  gap is real enough that Unity's own community treats it as
  bespoke/hard, not a solved library feature, and Unity only shipped a
  new supported version of it in a *different, newer* package (Netcode
  for Entities' Distributed Authority model) rather than retrofitting
  the old one. The handful of working game implementations are
  hand-rolled priority-list-plus-reconnect, same shape as the successor-
  adoption approach below, not off-the-shelf.
- **Raft / etcd / hashicorp/raft / ZooKeeper** — real, battle-tested
  consensus leader election, for when peers are numerous, mutually
  distrustful, and correctness must survive network partitions with
  quorum. Genuinely oversized here: ≤10 friends' phones for a two-hour
  movie don't need a quorum-based failure detector.
- **CRDTs (Yjs, Automerge)** — the fully leaderless philosophy, applied
  to mutable state instead of immutable chunks; this is what
  Google-Docs-style co-editing runs on. Doesn't fit cleanly here though:
  CRDTs merge *documents* (state that only needs to converge eventually);
  a movie's playhead is closer to a live stream's clock than a document
  — it needs to be right *now*, not eventually. Last-timestamp-wins is
  already the pragmatic, right-sized version of this idea for a live
  position value.

## Recommendation — sized to what Mofy actually needs

Three real options, cheapest first, each solving a strictly larger
problem than the last:

1. **Do nothing to the session on host loss** — host drops →
   `DemotedToSolo` (already the plan in ADR 0011). Guests keep watching
   their own local copy, just unsynced. Captures nearly all of the
   user-facing value (nobody's playback is interrupted) at zero
   additional engineering cost. Good default; ship this regardless of
   what else gets built.
2. **Successor adoption, not election** — a backup is picked
   deterministically (lowest participant id, same "arbitrary but agreed
   in advance" trick as WebRTC's polite/impolite assignment), not voted
   on. On host loss, that backup starts its own hub/signaling for the
   same room, and surviving guests reconnect and hot-join at its current
   position. This is genuine game-style host migration — real
   reconnection machinery (ICE teardown/rebuild against a new device),
   but no consensus protocol, because the successor is deterministic and
   known to everyone in advance from data they already receive
   (`ParticipantEvent` broadcasts). This is the option that requires the
   protocol changes sketched below (participant addresses, every device
   host-capable).
3. **Full Raft-style election** — solves a problem Mofy doesn't have
   (untrusted peers actively racing to be leader, needing a
   failure-detector-plus-quorum vote) at a cost that isn't justified for
   a trusted friend group this size.

If (2) is worth building, the concrete gap to close first is that
`Participant` (`SessionModels.kt`) carries only `id`/`displayName`/`role`
— no address — so guests have no way to reach each other or a promoted
successor at all today. Two things would need to exist: participants
share a reachable address at join time (their own ZeroTier IP, given
that's already confirmed stable and AP-isolation-proof in this setup —
this is the "ZeroTier as mediator" idea, applied as the address source,
not as a new rendezvous service), and every device runs its own dormant
`EmbeddedSignalingServer` from the moment it joins, not just the current
host, so "promotion" means "point everyone at an already-running server"
rather than "guest builds new infrastructure from scratch."

**Suggested path**: ship (1) as part of ADR 0011 (it's already in scope
there). Treat (2) as a real, separate future ADR — it touches the wire
protocol and requires every participant to be host-capable, which is a
genuine scope jump, not a small addition. Don't build (3) unless (1) and
(2) both turn out to be insufficient in practice.

Nothing here has been implemented — this is the requested write-up only.
