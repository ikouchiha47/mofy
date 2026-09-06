# Watch Together flow tests — implement this file

Hand this to a coding model. Job: **write the tests**, not the product
behavior. Source of truth: `docs/tasks/watch-together-flows.md`.

Replace (do not keep the 5 weak cases in)
`android/app/src/test/kotlin/com/mofy/app/watchtogether/sync/SyncEngineFlowSpecTest.kt`.

Do **not** rewrite `SyncEngineTest.kt` except the one conflict in §0.4.

Most cases will **fail** against current `SyncEngine`. That is the point.
Do not weaken asserts to make them green.

---

## 0. Rules for the implementer

### 0.1 Scope

**This assignment is SyncEngine unit tests only.** Appendix A lists
cases that belong in other classes — write those only if you finish
the SyncEngine list.

### 0.2 Do not use `FakePlayerController` for release/headless cases

`FakePlayerController.release()` still returns stale position and
`isPlaying`. Real `VlcPlayerController` **throws** after release
(`can't get VLCObject instance`). Copy the existing
`ThrowingAfterReleasePlayerController` from the current spec file into
the new file. Use `FakePlayerController` only when the player stays
bound.

### 0.3 Assertion rules

- Never `positionMs > 0`. Assert the **expected ms** ± a named epsilon.
- Epsilon for virtual-clock advance: `±50ms` if the clock is injected;
  if the engine uses the injected clock only, assert **exact**.
- Decode outbound JSON with `WtMessageCodec`. Filter by type. Assert
  count **and** fields (`positionMs`, `isPlaying`, `by`, `ts`).
- One behavior per `@Test`. Name: `` `SE-ID short clause` ``.

### 0.4 Conflict with existing tests

`SyncEngineTest` `` `9 guest loses host connection emits HostLost not silence` ``
locks **immediate** `HostLost`. The flows doc requires a grace period.

When you add `SE-NET-01`, **change test 9** to:
- disconnect host → **no** `HostLost` yet
- advance clock by grace → `tick()` → `HostLost`

Do not leave both “immediate HostLost” and “grace” green. They cannot
both be the spec.

Do not touch tests 1–8.

### 0.5 OPEN product decisions

Do **not** write tests that lock §15 of the flows doc (who taps Start
watching, pause-all-on-buffer, movie-ended auto-leave, audio focus,
empty-lobby confirm). Those are not SyncEngine.

### 0.6 Seams you may add so tests compile

You may add these to production types as **stubs** if missing. Do not
implement the real logic in this assignment (tests stay red).

| Seam | Where | Why |
|---|---|---|
| `clock: () -> Long` | already on `SyncEngine` | Use a `var nowMs` in tests. Never `Thread.sleep`. |
| `fun tick()` | add on `SyncEngine` | Pump debounce, ignore window, grace using `clock()`. No-op stub is fine. |
| `CONTROL_DEBOUNCE_MS = 250L` | `SyncEngineConfig` | Tests import this. |
| `CONTROL_IGNORE_WINDOW_MS = 500L` | `SyncEngineConfig` | |
| `HOST_DISCONNECT_GRACE_MS = 10_000L` | `SyncEngineConfig` | Suggested default from flows §5. |
| `DURATION_MISMATCH_MS = 10 * 60 * 1000L` | `SyncEngineConfig` | Join duration gate. |
| `Join.durationMs: Long? = null` | `WtMessage.Join` | Default null = skip gate so old tests keep compiling. |
| `Play/Pause/Seek.seq: Long? = null` and `ts: Long? = null` | `WtMessage` | Default null so codec round-trips stay green. Tests that need seq still write the field. |

Do **not** add `Thread.sleep`, Robolectric, or Android APIs. These are
JVM unit tests like `SyncEngineTest`.

### 0.7 Shared fixtures (put in the spec test class)

```kotlin
val roomKey = "7FK9Q2"
val itemHash = "abc123def4567890"
var nowMs = 1_000_000L
val clock = { nowMs }

fun hostEngine(player, transport, events = null) = SyncEngine(
    role = Role.HOST, roomKey, itemHash,
    localParticipant = Participant("host-1", "Alex", Role.HOST),
    player, transport, clock = clock, events = events,
)
fun guestEngine(player, transport, id = "guest-temp", events = null) = SyncEngine(
    role = Role.GUEST, roomKey, itemHash,
    localParticipant = Participant(id, "Priya", Role.GUEST),
    player, transport, clock = clock, events = events,
)

fun messages(transport) = transport.sent.map { WtMessageCodec.decode(it.json) }
fun <T> ofType(): List<T> = messages.filterIsInstance<T>()
```

Host tests that need a guest connected: `transport.connectPeer("g1")` then
`transport.sent.clear()` before the act.

Inject `clock` on **every** engine in this file so time is deterministic.

---

## 1. Constants the tests assume

```kotlin
// SyncEngineConfig (add if missing)
CONTROL_DEBOUNCE_MS = 250L
CONTROL_IGNORE_WINDOW_MS = 500L
HOST_DISCONNECT_GRACE_MS = 10_000L
DURATION_MISMATCH_MS = 600_000L   // 10 minutes
POSITION_HEARTBEAT_MS = 3_000L    // already exists
DRIFT_THRESHOLD_MS = 1_500L       // already exists
```

---

## 2. §3 Virtual clock / headless player

Use `ThrowingAfterReleasePlayerController` unless noted.

### SE-VCLK-01 — heartbeat after host release must not lie

- Bind host player at `42_000`, `play()`.
- `start()`, connect `g1`, `sent.clear()`.
- `player.release()`.
- `host.heartbeatTick()`.
- Assert exactly one `WtMessage.Position`.
- Assert `positionMs == 42_000` (or last virtual position if you already
  advanced; here clock has not moved → **42000**).
- Assert `isPlaying == true`.
- Must **not** be `0` / `false` (current `safePositionMs`/`safeIsPlaying`
  defaults).
- Must **not** crash.

### SE-VCLK-02 — rebind after release resumes last position

- Player at `77_000`, playing.
- `release()`.
- `rebindPlayer(newPlayer)` (fresh throwing-capable player, not released).
- Assert `newPlayer.positionMsBacking == 77_000`.
- Must not be `0`.

### SE-VCLK-03 — playing unbound clock advances

- Player at `42_000`, playing. `release()`.
- `nowMs += 5_000`.
- `host.heartbeatTick()`.
- Assert Position `positionMs == 47_000`, `isPlaying == true`.

### SE-VCLK-04 — paused unbound clock frozen

- Player at `42_000`, **pause**. `release()`.
- `nowMs += 5_000`.
- `host.heartbeatTick()`.
- Assert Position `positionMs == 42_000`, `isPlaying == false`.

### SE-VCLK-05 — remote seek updates unbound host clock

- Host playing at `10_000`. `release()`.
- Deliver from `g1`: `Seek(positionMs = 55_000, by = "guest-1")`.
  (Guest must be joined so `by` echo-suppress does not drop it; join
  first with `idGenerator` or deliver a seek `by` that is not `host-1`.)
- `heartbeatTick()`.
- Assert Position `positionMs == 55_000`.
- Must not crash on released player.

### SE-VCLK-06 — remote pause updates unbound host clock

- Host playing at `10_000`. `release()`.
- Deliver `Pause(positionMs = 10_000, by = <guest id>)`.
- `nowMs += 5_000`.
- `heartbeatTick()`.
- Assert `positionMs == 10_000`, `isPlaying == false`.

### SE-VCLK-07 — rebind while paused does not play

- Pause at `20_000`. `release()`.
- `rebindPlayer(newPlayer)`.
- Assert `newPlayer.positionMsBacking == 20_000`.
- Assert `newPlayer.isPlayingBacking == false`.
- Current code calls `localPlay()` inside `rebindPlayer` — this test
  must fail until that is fixed.

### SE-VCLK-08 — join-ack after host release uses virtual clock

- Host playing at `33_000`. `release()`.
- New peer `g2` joins with correct `itemHash`.
- Assert the `JoinAck` to `g2` has `positionMs == 33_000`, `isPlaying == true`.
- Not `0` / `false`.

### SE-VCLK-09 — guest released player: inbound seek does not crash, no outbound seek

- Guest engine, player at `5_000`. `release()`.
- Deliver host `Seek(90_000, by = "host-1")`.
- Assert **no** throw.
- `sent` has **zero** `Seek` (guest must not echo).
- (Applying to a released player is a no-op; session memory is enough.)

### SE-VCLK-10 — host localPause after release must not emit pause@0

- Host playing at `42_000`. Connect `g1`. `sent.clear()`. `release()`.
- `host.localPause()`.
- If a `Pause` is emitted, `positionMs == 42_000`, never `0`.
- Prefer: still emits pause (room pauses) at 42_000 **or** is a no-op
  because host is headless. **Pick one in the test name and assert it:
  emit Pause(42000).** Headless host who hits pause in notification
  should still pause the room (flows §12). Do not swallow it as 0.

### SE-VCLK-11 — guest heartbeatTick is a no-op

- Guest engine, connect host, `sent.clear()`.
- `guest.heartbeatTick()`.
- Assert no `Position` outbound.

### SE-VCLK-12 — heartbeat after release must not throw

- Same setup as VCLK-01.
- `heartbeatTick()` must not throw `IllegalStateException`.
- (Redundant with 01 if 01 already requires a Position; keep as a
  distinct test only if 01 is split. Otherwise fold into 01 and skip
  this id.)

**Skip SE-VCLK-12** if 01 already implies no throw.

---

## 3. §4 Control plane

Clock does not advance between burst seeks unless the case says so.

### SE-CTL-01 — scrub burst coalesces to last seek

- Host `start()`, connect `g1`, `sent.clear()`.
- `localSeek(1000)`, `2000`, `3000`, `4000`, `5000` with `nowMs` **unchanged**.
- Assert `Seek` count `== 1`.
- Assert that seek `positionMs == 5_000`.
- This is coalesce-in-one-tick (clock delta `< CONTROL_DEBOUNCE_MS`).
- Do **not** assert “1 immediately after a 250ms timer” and also
  “1 with no tick” — pick this coalesce rule.

### SE-CTL-02 — after debounce window, a new seek emits

- After CTL-01 setup, one seek at 5000 already sent. `sent.clear()`.
- `nowMs += CONTROL_DEBOUNCE_MS`.
- `tick()`.
- `localSeek(8_000)`.
- Assert one `Seek` at `8_000`.

### SE-CTL-03 — pending-replace: two guest seeks, host fans only the latest

- Host with `g1` and `g2` connected and joined.
- `sent.clear()`.
- Deliver from `g1`: `Seek(10_000, by = g1Id)` then immediately
  `Seek(80_000, by = g1Id)` (`nowMs` unchanged).
- Assert fan-out to `g2` contains **one** `Seek` at `80_000`.
- Originator `g1` is echo-suppressed (no Seek back to `g1`).

If current engine fans both, this fails. Good.

### SE-CTL-04 — echo suppress play

- Guest `localPlay()` reaches host; host applies; host fans to **other**
  guests only.
- Assert no Play sent back to the originating peer id.
- (Pause version already exists as `SyncEngineTest` 3 — do **not**
  duplicate pause; this is play.)

### SE-CTL-05 — ignore window drops a competing seek

- Guest player bound.
- Deliver host `Seek(90_000, by = "host-1")`.
- Assert player at 90_000.
- `nowMs += 100` (`< CONTROL_IGNORE_WINDOW_MS`).
- Deliver `Seek(10_000, by = "other")`.
- Assert player **still 90_000**.
- `nowMs += CONTROL_IGNORE_WINDOW_MS`.
- `tick()`.
- Deliver `Seek(10_000, by = "other")`.
- Assert player at `10_000`.

### SE-CTL-06 — stale Position does not override a newer Seek

- Guest. Deliver `Seek(90_000, by = "host-1")`. Player 90_000.
- Deliver `Position(positionMs = 40_000, isPlaying = true, ts = 1)`.
- Assert player still `90_000`.
- Current wire: Seek has no `ts`/`seq`, Position does. This test **defines
  the required behavior**. If you added `seq`/`ts` on Seek, a Position
  with lower seq/ts must lose.

### SE-CTL-07 — stale Position does not override a newer Pause

- Guest playing at 50_000.
- Deliver `Pause(50_000, by = "host-1")`. Assert not playing.
- Deliver `Position(60_000, isPlaying = true, ts = 1)`.
- Assert still paused at 50_000 (not playing at 60_000).

### SE-CTL-08 — newer Position after Seek still applies

- Guest. Seek to 90_000.
- `nowMs += 5_000`.
- Deliver `Position(95_000, isPlaying = true, ts = nowMs)` with ts/seq
  **newer** than the seek.
- Drift `> DRIFT_THRESHOLD_MS` so seek applies.
- Assert player `95_000`, playing.

### SE-CTL-09 — pause is not coalesced away

- Host connect `g1`, `sent.clear()`.
- `localPause()` then `localPause()` (`nowMs` unchanged).
- At least one `Pause` is sent (pause is a control, not a scrub).
- Do not require debounce on pause/play.

### SE-CTL-10 — host-assigned ts on fan-out

- Guest delivers `Seek(12_000, by = g1Id)` to host.
- Host fan-out `Seek` to `g2` has `ts == nowMs` (host clock) if the
  field exists, **not** guest wall-clock.
- If `ts` is still absent on Seek, assert at least that host echoed
  `positionMs == 12_000` and skip ts with `assumeTrue` only if you
  **cannot** add the field. Prefer adding the optional field.

### SE-CTL-11 — guest localSeek after release does not crash and uses virtual position

- Guest at 15_000. `release()`.
- `localSeek(40_000)`.
- No throw.
- Outbound Seek to host `positionMs == 40_000` (user scrubs from
  notification / rebound later; if you treat headless guest seeks as
  allowed, this is the assert). 

**If** flows §7 G4 “lock controls while reconnecting” — that is OPEN.
This case is **bound-session headless**, not reconnecting. Keep it.

---

## 4. §5 Network: flap vs dead

Guest listener: `mutableListOf<SyncEngine.SyncEvent>()`.

### SE-NET-01 — host disconnect does not HostLost immediately

- Guest `start()`, `connectPeer(HOST_PEER_ID)`.
- `disconnectPeer(HOST_PEER_ID)`.
- Assert `HostLost !in events`.
- (This is the grace. Current code fails.)

### SE-NET-02 — HostLost after grace

- Same as 01.
- `nowMs += HOST_DISCONNECT_GRACE_MS`.
- `guest.tick()`.
- Assert `HostLost in events`.
- Exactly one `HostLost` even if you `tick()` twice.

### SE-NET-03 — reconnect inside grace cancels demote

- Disconnect host.
- `nowMs += HOST_DISCONNECT_GRACE_MS / 2`.
- `tick()`.
- `connectPeer(HOST_PEER_ID)` again.
- `nowMs += HOST_DISCONNECT_GRACE_MS`.
- `tick()`.
- Assert `HostLost !in events`.

### SE-NET-04 — guest flap does not kill the room

- Host, join `g1` and `g2`.
- `disconnectPeer("g1")`.
- Assert host events do **not** contain `HostLost`.
- Assert `g1` removed from `participants()`.
- Assert a `ParticipantEvent(LEFT)` was sent to `g2`.
- Host engine still `heartbeatTick()`s after this.

This is largely `SyncEngineTest` 8. **Do not duplicate** unless you
add the explicit `HostLost not in host events` assert — then one short
test is ok.

### SE-NET-05 — transport fail is Error, not HostLost

- Guest connected to host.
- `transport.fail("ice failed")`.
- Assert `Error` in events, `HostLost` not in events.
- FailedP2P ≠ host-dead.

### SE-NET-06 — guest ignores disconnect of a non-host peer id

- Guest. `connectPeer(HOST_PEER_ID)` and `connectPeer("other")` if the
  fake allows it.
- `disconnectPeer("other")`.
- Assert no `HostLost`.

### SE-NET-07 — HostLost still fires on real death (grace elapsed)

Covered by NET-02. Don’t duplicate.

---

## 5. Join handshake (SyncEngine)

`SyncEngineTest` 6 (hash) and 7 (`room_full`) stay. Add:

### SE-JOIN-01 — duration mismatch rejects

- Host player `durationMs = 100 * 60 * 1000` (100 min). Use a throwing
  player or Fake with `durationMsImpl` — Fake is fine here (bound).
- Guest join with `durationMs = 100*60*1000 + DURATION_MISMATCH_MS + 1`.
- Assert `Error` reason contains `duration` (ignore case).
- Assert host `participants().size == 1` (host only).

Requires `Join.durationMs`. Default null on old joins = no gate
(`SyncEngineTest` 6–7 must still pass).

### SE-JOIN-02 — duration within gate accepted

- Same host duration.
- Join with `durationMs` off by `DURATION_MISMATCH_MS - 1`.
- Assert a `JoinAck` (not Error).
- Participant count 2.

### SE-JOIN-03 — null duration does not reject (compat)

- Join without duration (default null).
- Assert `JoinAck`, not duration error.
- Keeps current clients working.

### SE-JOIN-04 — hot-join snapshot while playing

- Host playing at `12_345`.
- Guest joins.
- `JoinAck.positionMs == 12_345`, `isPlaying == true`.
- Guest applying ack: if you also construct a guest engine and deliver
  that ack, guest player seeks to `12_345` and plays.

Can be one test (host emit) + one test (guest apply). Guest apply is
already implied by `handleJoinAck`. Prefer **host emit** here because
VCLK-08 covers unbound; this one is **bound** player.

### SE-JOIN-05 — wrong itemHash still mismatch

Do **not** reimplement `SyncEngineTest` 6.

### SE-JOIN-06 — join-ack after host paused

- Host paused at `8_000`.
- Join.
- Ack `positionMs == 8_000`, `isPlaying == false`.

---

## 6. Explicitly out of scope (do not fake them in SyncEngine tests)

| Flows § | Why not here |
|---|---|
| Listing / Leave confirm / Cancel | UI |
| FGS / notification 1 vs N | `WatchTogetherForegroundService` |
| Cap 4 sessions | `WatchTogetherSessionManagerTest` already |
| Dual Wi‑Fi / ICE / STUN | `HostHub` / signaling |
| Who taps Start watching | UI + `rebindPlayer` already has a live-device story |
| Multi-room audio | Player / manager |
| Phone call, headphones, rotation | Android |
| Movie ended auto-leave | OPEN |
| Presence `rendering` bit | not in `WtMessage` yet; don’t invent in this pass |

---

## 7. File shape

One class: `SyncEngineFlowSpecTest`.

Group with comments:

```
// --- §3 virtual clock ---
// --- §4 control plane ---
// --- §5 network grace ---
// --- join duration / snapshot ---
```

KDoc on the class:

```
Tests for docs/tasks/watch-together-flows.md.
Expected RED against current SyncEngine until the engine matches the doc.
```

Reuse helpers. Do not copy-paste `hostEngine` 20 times beyond the two
factory methods.

After writing, run:

```
cd android && ./gradlew :app:testDebugUnitTest --tests com.mofy.app.watchtogether.sync.SyncEngineFlowSpecTest
```

Also run `SyncEngineTest` to prove 1–8 still pass.

Report: which IDs failed (expected) vs which could not be expressed.

---

## Appendix A — later files (not this assignment)

Complete matrix from flows §16. Implement only after SyncEngine list.

### `WatchTogetherSessionManagerTest` (additions)

- SM-01 add 5th session no-ops (already exists).
- SM-02 adding a session with an existing `roomKey` replaces, does not
  duplicate (already exists).
- SM-03 remove host session ends transport (already exists).
- SM-04 **new**: `itemFor` / `sessionFor` after remove is null.

No audio tests here.

### Session / UI (instrumented or not yet)

Do not write these in this pass:

- Player Back does not call `session.end()`.
- Notification tap routes 1 session → Player, N → Listing.
- Host Leave confirm copy.
- Demote-to-solo on `WtEvent.HostLost` (`SoloPlayerScreen`).
- Dead link after `Ended`.

### Protocol codec

If you add optional `durationMs` / `seq` / `ts`, add **one** round-trip
case in `WtMessageCodecTest` for a Seek-with-seq and Join-with-duration.
Default-null must still decode golden Seek JSON in that file.

---

## Appendix B — ID checklist (SyncEngine assignment)

Must exist in `SyncEngineFlowSpecTest`:

- [ ] SE-VCLK-01
- [ ] SE-VCLK-02
- [ ] SE-VCLK-03
- [ ] SE-VCLK-04
- [ ] SE-VCLK-05
- [ ] SE-VCLK-06
- [ ] SE-VCLK-07
- [ ] SE-VCLK-08
- [ ] SE-VCLK-09
- [ ] SE-VCLK-10
- [ ] SE-VCLK-11
- [ ] SE-CTL-01
- [ ] SE-CTL-02
- [ ] SE-CTL-03
- [ ] SE-CTL-04
- [ ] SE-CTL-05
- [ ] SE-CTL-06
- [ ] SE-CTL-07
- [ ] SE-CTL-08
- [ ] SE-CTL-09
- [ ] SE-CTL-10
- [ ] SE-CTL-11
- [ ] SE-NET-01
- [ ] SE-NET-02
- [ ] SE-NET-03
- [ ] SE-NET-05
- [ ] SE-NET-06
- [ ] SE-JOIN-01
- [ ] SE-JOIN-02
- [ ] SE-JOIN-03
- [ ] SE-JOIN-04
- [ ] SE-JOIN-06

Optional short: SE-NET-04 if not duplicating test 8.

Also: update `SyncEngineTest` 9 to the grace contract.

**Count: 31 required + test 9 edit.**
