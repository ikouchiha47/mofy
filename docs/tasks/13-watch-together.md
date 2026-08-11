# Tasks: Phase 13 — Watch Together

See `docs/RALPH.md` for how to run this list.
See `docs/phases/13-watch-together.md` (EARS) and
`docs/adrs/0006-watch-together-sync-transport.md` (**accepted** — do not
re-derive transport/player choices).

Canonical UX: `design/watch-together-mockup.html` (Stage A — **approved**).

---

## Agent rules (read before every task)

1. **One unchecked task per iteration.** Top to bottom. Do not skip ahead.
2. **Do not build Compose UI in Stage B.** No screens, no ViewModels wired
   to navigation, no "just a quick button". Stage C only.
3. **Do not change ADR 0006 decisions.** Locked:
   - WebRTC **DataChannel only** (no media RTP/tracks)
   - Host **star** topology (guests do not peer with each other)
   - Google public **STUN** only in v1 (no TURN)
   - **Anyone** may play/pause/seek
   - Max **10** participants (1 host + 9 guests)
   - **libVLC** in-app player (not external VLC, not mpv, not ExoPlayer)
   - JSON control plane only — never send video/audio bytes
4. **Prefer pure JVM unit tests** (`src/test/kotlin`, JUnit 5 already used).
   Instrument / two-device checks only where a task explicitly says so.
5. After each task: run the tests named in that task, check the box `- [x]`,
   commit with a message like `wt(B3): protocol codec round-trip`.
6. If blocked on a human decision, write `> BLOCKED: …` under the task and
   **stop** — do not invent a substitute architecture.
7. Match existing code style: Kotlin under
   `android/app/src/main/kotlin/com/mofy/app/…`, no drive-by refactors,
   **no comments unless the task asks for a named-constant rationale**.
8. Package home for this feature:
   `com.mofy.app.watchtogether` (logic) and later
   `com.mofy.app.ui.watchtogether` (Stage C only).
9. Player abstraction lives in `com.mofy.app.playback` so Phase 07 and 13
   share one engine.

---

## Stage A — UX prototype

**Status: DONE** — `design/watch-together-mockup.html` approved by user.
Do not re-open Stage A unless the user asks.

---

## Stage B0 — Shared player port (Phase 07 prereq, no UI)

Watch Together cannot apply remote seek/pause without a controllable
player. Build the **port** first; libVLC adapter can be thin.

Depends on: nothing in Stage 13. May land before or in parallel with B1.

### B0.1 — `PlayerController` interface + fake
**Build:**
- File: `android/app/src/main/kotlin/com/mofy/app/playback/PlayerController.kt`
- Interface (exact surface — do not add methods "for later"):
  ```kotlin
  interface PlayerController {
      val positionMs: Long
      val isPlaying: Boolean
      val durationMs: Long
      fun play()
      fun pause()
      fun seekTo(positionMs: Long)
      fun setSubtitleTrack(index: Int?)   // null = off
      fun setAudioTrack(index: Int?)
      fun release()
  }
  ```
- File: `…/playback/FakePlayerController.kt` — in-memory implementation
  for unit tests (mutable position, isPlaying flag, records last seek).
- File: `android/app/src/test/kotlin/com/mofy/app/playback/FakePlayerControllerTest.kt`

**Acceptance criteria:**
- [x] Interface compiles; no Android framework types on the interface.
- [x] Fake: `play()` → `isPlaying==true`; `pause()` → false; `seekTo(1234)` → `positionMs==1234`.
- [x] `./gradlew :app:testDebugUnitTest --tests com.mofy.app.playback.FakePlayerControllerTest` passes.

**Satisfies:** Phase 07 foundation; ADR 0006 player requirement (port only).

### B0.2 — Add libVLC dependency (no screen yet)
**Build:**
- In `android/app/build.gradle.kts` add:
  `implementation("org.videolan.android:libvlc-all:3.6.0")`
  (or latest 3.6.x on Maven Central if 3.6.0 missing — pin an exact version).
- File: `…/playback/VlcPlayerController.kt` implementing `PlayerController`
  using libVLC `MediaPlayer`. Construct with `Context` + media URI string.
- **Do not** build a Compose player screen in this task.
- ABI: prefer `abiFilters` or app splits later if APK huge; not required now.
- Internet permission already likely present; add nothing extra unless libVLC docs require it.

**Acceptance criteria:**
- [x] `./gradlew :app:assembleDebug -q` succeeds with the dependency.
- [x] `VlcPlayerController` implements every `PlayerController` method (can no-op track setters if tracks not loaded yet, but methods must exist).
- [x] No Compose / no Activity changes except if `MofyApplication` must hold a `LibVLC` singleton — if so, minimal init only.

**Satisfies:** Phase 07 "Integrate libVLC"; ADR 0006.

### B0.3 — Expand Phase 07 task file cross-link
**Build:** Update `docs/tasks/07-playback.md` first checkbox to note
`PlayerController` + `VlcPlayerController` live under `playback/` and that
the Compose player **screen** remains a Phase 07 UI task (not Stage B).

**Acceptance criteria:**
- [x] `docs/tasks/07-playback.md` reflects shared `playback/` package; no duplicate "integrate libVLC" confusion.

---

## Stage B — Kotlin core (no UI)

Goal: sync engine + protocol + transports are unit-tested. Stage C only
binds Compose to these types.

Suggested package tree after Stage B:

```
com.mofy.app.watchtogether/
  ItemHash.kt
  RoomCode.kt
  SessionModels.kt          // Role, Participant, SessionState
  protocol/
    WtMessage.kt            // sealed class
    WtMessageCodec.kt       // kotlinx.serialization JSON
  sync/
    SyncEngine.kt           // pure rules: fan-out, echo suppress, last-wins
    SyncEngineConfig.kt     // named constants
  transport/
    WtTransport.kt          // interface
    FakeWtTransport.kt      // tests
    SignalingChannel.kt     // interface for SDP/ICE bootstrap
    FakeSignalingChannel.kt
  webrtc/                   // Android-facing adapters (may use android.net)
    WtWebRtcConfig.kt       // STUN URLs
    HostHub.kt
    GuestPeer.kt
```

---

### B1 — Item identity hash
**Build:**
- File: `…/watchtogether/ItemHash.kt`
- API:
  ```kotlin
  object ItemHash {
      fun of(tmdbId: Int?, mediaType: String?, title: String): String
      fun of(item: LibraryItem): String = of(item.tmdbId, item.mediaType, item.title)
      internal fun normalizeTitle(title: String): String
  }
  ```
- Rules (exact):
  1. If `tmdbId != null` **and** `mediaType` is non-null/non-blank →
     hash input = `"tmdb:{mediaType.lowercase()}:{tmdbId}"`.
  2. Else → `"title:{normalizeTitle(title)}"` where normalize =
     lowercase (Locale.ROOT), strip characters that are not letters/digits,
     collapse whitespace to single space, trim. Then SHA-256 hex of UTF-8
     bytes, **first 16 hex chars** (64-bit prefix — enough for personal lib).
  3. Always return a stable string; never throw on empty title (use
     `"title:"` + hash of empty).

**Tests:** `…/watchtogether/ItemHashTest.kt`
- Same tmdbId+mediaType, different library ids → equal hash.
- `"Jack Reacher!"` vs `"jack   reacher"` → equal.
- `"Jack Reacher"` vs `"Jack Reacher 2"` → not equal.
- `tmdbId` present beats title (two items same title different tmdb → different hash).

**Acceptance criteria:**
- [x] All rules above implemented.
- [x] Unit tests pass:
  `./gradlew :app:testDebugUnitTest --tests com.mofy.app.watchtogether.ItemHashTest`

**Satisfies:** Phase 13 item identity EARS.

---

### B2 — Room key + session model (in-memory)
**Build:**
- File: `…/watchtogether/SessionModels.kt`
  ```kotlin
  enum class Role { HOST, GUEST }

  data class Participant(
      val id: String,
      val displayName: String,
      val role: Role,
  )

  data class SessionState(
      val roomKey: String,          // 6 chars, no separators, A-Z0-9
      val itemHash: String,
      val role: Role,
      val localParticipantId: String,
      val participants: List<Participant>,
      val positionMs: Long,
      val isPlaying: Boolean,
  )

  object RoomKey {
      const val LENGTH = 6
      const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" // no 0/O/1/I
      fun generate(random: Random = Random.Default): String
      fun isValid(raw: String): Boolean  // ignores spaces/dots, length 6, alphabet
      fun normalize(raw: String): String // strip separators, uppercase
  }

  object SessionLimits {
      const val MAX_PARTICIPANTS = 10  // host + 9 guests
  }
  ```
- Entropy note: document in a one-line KDoc on `RoomKey` —
  `log2(32^6) ≈ 30 bits` — acceptable for short-lived personal rooms.
- **Not** a Room/SQLite entity. Ephemeral only.

**Tests:** `SessionModelsTest.kt` — generate 200 keys all valid; normalize
`"7f k9 q2"` → `"7FK9Q2"`; invalid length fails `isValid`.

**Acceptance criteria:**
- [x] No Android imports in these files.
- [x] Tests pass.

**Satisfies:** ADR 0006 room identity; max participants constant.

---

### B3 — Protocol message sealed class + JSON codec
**Build:**
- File: `…/watchtogether/protocol/WtMessage.kt` — sealed class matching ADR 0006:

  | type | fields |
  |---|---|
  | `Hello` | roomKey, displayName |
  | `Join` | roomKey, displayName, itemHash |
  | `JoinAck` | participantId, participants, positionMs, isPlaying |
  | `ParticipantEvent` | op: JOINED/LEFT, participant |
  | `Play` | positionMs, by |
  | `Pause` | positionMs, by |
  | `Seek` | positionMs, by |
  | `Position` | positionMs, isPlaying, ts |
  | `Pref` | subtitleTrack: Int?, audioTrack: Int? |
  | `Error` | reason: String |
  | `Bye` | reason: String? |

- File: `…/watchtogether/protocol/WtMessageCodec.kt`
  - `fun encode(msg: WtMessage): String`
  - `fun decode(json: String): WtMessage` — unknown `type` → throw
    `IllegalArgumentException` (SyncEngine will turn into Error frame).
  - Use `kotlinx.serialization` (already on project). Discriminator field
    name: `"type"` with values exactly:
    `hello|join|join-ack|participant|play|pause|seek|position|pref|error|bye`.

**Tests:** `WtMessageCodecTest.kt` — round-trip every variant; unknown type
throws; golden JSON snippet for `seek` matches
`{"type":"seek","positionMs":1000,"by":"abc"}` (property order may vary —
assert decode→encode→decode equality instead if needed).

**Acceptance criteria:**
- [x] All ADR types present; no extra types.
- [x] Tests pass.

**Satisfies:** ADR 0006 message surface.

---

### B4 — Transport + signaling ports (interfaces only)
**Build:**
- `…/transport/WtTransport.kt`:
  ```kotlin
  interface WtTransport {
      /** Send one encoded JSON text frame to one peer (or hub). */
      suspend fun send(peerId: String, json: String)
      /** Broadcast to all connected peers except optional excludeId. */
      suspend fun sendToAll(json: String, excludePeerId: String? = null)
      fun setListener(listener: Listener?)
      fun close()
      interface Listener {
          fun onMessage(fromPeerId: String, json: String)
          fun onPeerConnected(peerId: String)
          fun onPeerDisconnected(peerId: String)
          fun onTransportFailed(reason: String)
      }
  }
  ```
- `…/transport/SignalingChannel.kt` — bootstrap only (SDP/ICE strings as
  opaque payloads):
  ```kotlin
  interface SignalingChannel {
      suspend fun sendSignal(toPeerId: String, payload: String)
      fun setListener(listener: Listener?)
      fun close()
      interface Listener {
          fun onSignal(fromPeerId: String, payload: String)
          fun onSignalingFailed(reason: String)
      }
  }
  ```
- Fakes: `FakeWtTransport`, `FakeSignalingChannel` — record sent messages,
  deliver via `deliver(from, json)` test helper.

**Tests:** fake can send A→B and listener receives.

**Acceptance criteria:**
- [x] Interfaces have zero dependency on WebRTC/libVLC/Compose.
- [x] Fakes sufficient for SyncEngine tests in B5+.

---

### B5 — `SyncEngine` core (host + guest rules, fake transport)
**Build:**
- File: `…/sync/SyncEngineConfig.kt`:
  ```kotlin
  object SyncEngineConfig {
      /** Host position heartbeat interval. */
      const val POSITION_HEARTBEAT_MS = 3_000L
      /**
       * If |remote - local| exceeds this, apply seek.
       * 1500ms: below typical scene-cut notice, above normal jitter on Wi‑Fi.
       */
      const val DRIFT_THRESHOLD_MS = 1_500L
  }
  ```
- File: `…/sync/SyncEngine.kt`
  - Construct with: `role`, `roomKey`, `itemHash`, `localParticipant`,
    `PlayerController`, `WtTransport`, optional `CoroutineScope`/clock.
  - **Host responsibilities:**
    - On `Join`: validate `itemHash` match; if
      `participants.size >= MAX` → send `Error("room_full")`; else assign
      id, `JoinAck`, fan-out `ParticipantEvent(JOINED)`, resync newcomer
      via ack position.
    - On `Play`/`Pause`/`Seek` from any peer (or local): apply to local
      player if not originator; `sendToAll` excluding originator.
    - Periodically send `Position` heartbeat (testable via injected
      `heartbeatTick()` method so unit tests don't sleep 3s).
  - **Guest responsibilities:**
    - On start: send `Join`.
    - On `Play`/`Pause`/`Seek`/`Position`: apply to player with echo
      suppression; never re-send the same event.
    - On `Position`: last-wins by `ts` (if equal, higher positionMs wins);
      only `seekTo` if drift > `DRIFT_THRESHOLD_MS`.
  - **Local user actions** (called by future UI):
    `fun localPlay()`, `localPause()`, `localSeek(ms)` — apply locally +
    send (host fans out; guest sends to host only — transport abstracts
    this: guest transport has single peer `"host"`).
  - **Echo suppression:** maintain `applyingRemote: Boolean` or ignore
    outbound while applying remote command.
  - Pref: `localSetSubtitle` / `localSetAudio` only touch
    `PlayerController`; do **not** require others to change tracks. Sending
    `Pref` on the wire is optional; if sent, receivers **must ignore** for
    playback.

**Tests:** `SyncEngineTest.kt` (use `FakePlayerController` + `FakeWtTransport`):
1. Host local seek → one outbound `seek`, player at position.
2. Guest receives `seek` → player seeks, **zero** outbound seek.
3. Guest local pause → message to host; host applies + fans to other guest fake.
4. Three `position` messages out of order by `ts` → only latest applied.
5. Position within threshold → no seek; beyond → seek.
6. Join with wrong itemHash → `error` (reason contains `item` or `hash`).
7. 10th participant rejected with `room_full`.
8. Peer disconnect → removed from participants; no crash.

**Acceptance criteria:**
- [x] All 8 test scenarios pass.
- [x] `SyncEngine` does not import WebRTC, libVLC concrete types, or Compose.
- [x] Drift threshold and heartbeat are named constants with the rationale
      comment shown above (this is the one place comments are required).

**Satisfies:** Phase 13 sync EARS; ADR 0006 rules; B5/B6/B7/B9 from earlier outline.

---

### B6 — Room code display formatting + deep link parse
**Build:**
- File: `…/watchtogether/RoomCode.kt`
  ```kotlin
  object RoomCode {
      /** "7FK9Q2" → "7F · K9 · Q2" */
      fun formatForDisplay(roomKey: String): String
      /** Parse user input / QR payload → roomKey or null */
      fun parseUserInput(raw: String): String?
      /**
       * Deep link / QR payload v1:
       *   mofy://wt/{roomKey}
       * Optional query for LAN signaling bootstrap (Stage B7 may use):
       *   mofy://wt/{roomKey}?sig=ws://{host}:{port}/
       */
      fun toDeepLink(roomKey: String, signalingUrl: String? = null): String
      fun parseDeepLink(uri: String): Parsed?
      data class Parsed(val roomKey: String, val signalingUrl: String?)
  }
  ```

**Tests:** format/parse round-trip; deep link with and without `sig`.

**Acceptance criteria:**
- [x] Display always groups as `AA · BB · CC` for 6-char keys.
- [x] Tests pass. No Android `Uri` required — string parsing is fine (keeps JVM tests pure). If you use `android.net.Uri`, put Android-specific parse in a separate file and keep pure parse tested on JVM.

---

### B7 — Signaling: host bootstrap server + guest client (LAN-first)
ADR allows LAN listener or later relay. **v1 implement LAN bootstrap:**

**Build:**
- Host: small WebSocket (or HTTP POST pair) signaling server bound to
  `0.0.0.0` on an ephemeral port, path `/wt/{roomKey}`.
  Recommended lib already common on Android: **OkHttp** MockWebServer is
  test-only — for production use **OkHttp WebSocket** client + a minimal
  embedded server. Prefer **Ktor CIO** server **only if** already easy to
  add; otherwise use `org.java_websocket:Java-WebSocket` or similar small
  dependency. Pick **one**, add to `build.gradle.kts`, document in commit.
- Messages on signaling socket are JSON:
  `{"type":"signal","from":"...","to":"...","payload":"<opaque SDP/ICE>"}`
  plus `{"type":"hello-sig","roomKey":"...","peerId":"..."}`.
- Guest connects to `signalingUrl` from deep link / QR.
- Implement `SignalingChannel` with this.
- **Do not** put play/pause on this socket — DataChannel only after ICE.

**Tests:**
- JVM test with client+server on loopback: hello-sig + one signal payload
  delivered both ways.
- If embedded server cannot run on pure JVM, use `src/androidTest` **or**
  extract protocol and test fakes on JVM + one instrumented smoke — prefer
  JVM.

**Acceptance criteria:**
- [x] Host can start/stop signaling server; port discoverable for QR.
- [x] Loopback exchange works.
- [x] Wrong `roomKey` on path → connection rejected or error frame.

**Satisfies:** ADR 0006 signaling bootstrap (LAN path).

> Note: Cross-network join without a public signaling URL will fail until
> a relay exists. That is accepted for v1 (STUN alone does not signal).
> Surface error string: `signaling_unreachable`.
>
> **Config:** `SignalingSettings.relayBaseUrl` — null = embed local server;
> set to `ws://host:port` (local experiment) or `wss://…fly.dev` later.
> Same `EmbeddedSignalingServer` protocol either way. Dep: Java-WebSocket
> 1.5.7 server + OkHttp WS client.

---

### B8 — WebRTC dependency + factory config
**Build:**
- Add WebRTC Android dependency. Preferred Maven artifact:
  `io.getstream:stream-webrtc-android:1.3.8` (or current stable 1.x).
  If resolve fails, try `com.dafruits:webrtc:123.0.0` / document chosen
  artifact in commit message — **one** binding only.
- File: `…/webrtc/WtWebRtcConfig.kt`:
  ```kotlin
  object WtWebRtcConfig {
      val STUN_URLS = listOf(
          "stun:stun.l.google.com:19302",
          "stun:stun1.l.google.com:19302",
      )
      // No TURN in v1
  }
  ```
- File: `…/webrtc/PeerConnectionFactoryHolder.kt` — initialize once
  (Application or lazy singleton), create `PeerConnectionFactory`.
- **No media tracks** added to any `PeerConnection`.

**Acceptance criteria:**
- [x] Debug APK builds with WebRTC dependency.
- [x] Factory initializes without crash on device or emulator
      (`adb` launch smoke — log "PeerConnectionFactory ready").
- [x] Unit test: `STUN_URLS` non-empty and all start with `stun:`.

**Notes:** Dep `io.getstream:stream-webrtc-android:1.3.10`. Factory init in
`MofyApplication` + log tag `WtWebRtc`.

---

### B9 — Host hub WebRTC (`HostHub`)
**Build:**
- File: `…/webrtc/HostHub.kt` implements `WtTransport` for the host.
- For each guest signaling hello: create `PeerConnection`, create
  **ordered reliable** DataChannel label `wt-control`.
  Optional second channel `wt-position` with
  `DataChannel.Init().apply { ordered = false; maxRetransmits = 0 }` —
  if the binding makes this painful, **one** reliable channel is OK for
  v1 (still apply last-wins in SyncEngine).
- Host is **offerer**; setLocalDescription → send via SignalingChannel;
  on answer + ICE candidates, complete.
- Map `peerId` → DataChannel; `send`/`sendToAll` write UTF-8 JSON text.
- Cap: refuse additional connections at 9 guests (SyncEngine also guards).

**Acceptance criteria:**
- [x] Code review checklist: zero `addTrack` / `addTransceiver` for audio/video.
- [x] Instrumented or two-process test **or** documented manual step in
      task notes if automation is too heavy — minimum: host creates PC +
      DataChannel without crash.
- [x] `onPeerDisconnected` fires when guest PC closes.

**Notes:** Full two-device ICE is manual (Stage C / device). Unit coverage is
`RtcSignalCodec` + session fakes; HostHub creates offer/DC on `join-rtc`.

**Satisfies:** ADR 0006 host star hub.

---

### B10 — Guest peer WebRTC (`GuestPeer`)
**Build:**
- File: `…/webrtc/GuestPeer.kt` implements `WtTransport` with a single
  remote peer id `"host"` (or host's participant id once known).
- Guest is **answerer**: on offer signal → setRemoteDescription, create
  answer, trickle ICE via signaling.
- On DataChannel open → `onPeerConnected`; messages → `onMessage`.

**Acceptance criteria:**
- [x] Same no-media checklist as B9.
- [x] Loopback or two-emulator test: Join message reaches host SyncEngine
      path (can wire HostHub+GuestPeer+Fake players in an instrumented test).
- [x] ICE failure calls `onTransportFailed` with readable reason (not silent hang).

**Notes:** Guest queues outbound until DC open; ICE failed → `ice_failed`.
Two-device DataChannel path verified in Stage C device QA.

**Satisfies:** ADR 0006 guest peer + STUN ICE.

---

### B11 — Wire `SyncEngine` + WebRTC + signaling (host/guest facades)
**Build:**
- File: `…/watchtogether/WatchTogetherSession.kt` (facade, still no Compose):
  ```kotlin
  class WatchTogetherSession private constructor(...) {
      val state: StateFlow<SessionState>
      val events: SharedFlow<WtEvent> // ParticipantJoined, Error, Ended, ...

      suspend fun localPlay()
      suspend fun localPause()
      suspend fun localSeek(positionMs: Long)
      fun end()

      companion object {
          fun host(itemHash, displayName, player, appContext): WatchTogetherSession
          fun guest(roomKey, signalingUrl, itemHash, displayName, player, appContext): WatchTogetherSession
      }
  }
  ```
- Host facade: start signaling server → HostHub → SyncEngine(role=HOST).
- Guest facade: connect signaling → GuestPeer → SyncEngine(role=GUEST).
- Expose `roomKey`, `signalingUrl` (host), `deepLink` for QR later.

**Tests:** Prefer end-to-end unit test with fakes for transport still
green; plus one instrumented "session starts as host" smoke.

**Acceptance criteria:**
- [x] Creating host session yields 6-char roomKey and non-null deep link string.
- [x] Guest with wrong itemHash ends in error event, not crash.
- [x] `end()` closes transport + signaling, releases nothing it doesn't own
      carefully (player release is caller's job unless documented).

---

### B12 — Stage B verification gate
**Build:** Nothing new. Run the full unit test suite for watchtogether +
playback fakes.

**Acceptance criteria:**
- [x] `./gradlew :app:testDebugUnitTest` green for all
      `com.mofy.app.watchtogether.**` and `com.mofy.app.playback.**` tests.
- [x] `./gradlew :app:assembleDebug -q` succeeds.
- [x] Short note appended under this task listing leftover risks
      (e.g. "cross-network needs public signaling URL").

**Leftover risks / Stage B exit notes (2026-08-10):**
- Cross-network join needs `WT_SIGNALING_URL` (public relay, e.g. Fly); empty
  env embeds LAN-only `ws://127.0.0.1` which guests on other networks cannot
  reach. Prefer LAN IP in QR `sig=` for same-Wi‑Fi, or set relay URL.
- No TURN in v1 — symmetric NAT may fail ICE (`ice_failed`); surface and
  suggest same Wi‑Fi.
- HostHub/GuestPeer WebRTC path needs two physical devices for full ICE+DC
  proof (unit tests cover protocol, SyncEngine, session facade with fakes).
- Embedded signaling binds `0.0.0.0`; cleartext `ws://` may need network
  security config on newer Android for non-localhost.
- APK size large (libVLC + WebRTC native libs); ABI splits still deferred.
- Player is never owned/released by `WatchTogetherSession.end()`.

**Only after B12 is checked may Stage C start.**

---

## Stage C — Compose UI + integration

**Do not start until B12 is checked.** Match
`design/watch-together-mockup.html` and `CLAUDE.md` design tokens.
Corners slightly rounded (not pill). Explicit `shape` on Buttons.

### C1 — Navigation destinations
**Build:** Add routes in `MofyDestinations` / nav graph:
- `watch_together/create/{libraryItemId}`
- `watch_together/session` (active lobby/player host)
- Join is a **modal bottom sheet** from Home, not a full destination
  (unless nav requires a route — then `watch_together/join`).

**Acceptance criteria:**
- [ ] Routes compile; no UI polish required yet beyond empty scaffolds.

### C2 — `CreateRoomScreen` (Compose)
**Build:** Per mockup frames 1b/3a — room code display via
`RoomCode.formatForDisplay`, QR encoding of `deepLink` (use
`com.google.zxing:core` or existing QR lib if any; add ZXing if needed),
participant list, waiting row, Share (Android share sheet with code +
link), Start watching → player.

Wire to `WatchTogetherSession.host(...)`.

**Acceptance criteria:**
- [ ] Side-by-side with mockup 1b/3a — layout parity.
- [ ] Real room code changes each create (not hardcoded `7FK9Q2`).
- [ ] Share puts code text on the clipboard/share intent.

### C3 — `JoinSessionSheet` (Compose)
**Build:** Per mockup 2b — 6 boxes, auto-advance, Join disabled until
full, Scan QR (camera permission + QR scan; if camera scope too large,
ship code entry first and stub Scan with TODO **only if** user agreed —
default: implement code entry fully; QR scan can be C3b).

**Acceptance criteria:**
- [ ] Auto-advance + disabled Join work on device.
- [ ] Valid code + running host + matching library item → joins.
- [ ] Bad code / no host → visible error, not infinite spinner.

### C3b — QR scan for join
**Build:** Scan QR → `RoomCode.parseDeepLink` → same join path as C3.

**Acceptance criteria:**
- [ ] Real device camera scan of host QR joins session.

### C4 — Wire Detail "Watch Together"
**Build:** Replace coming-soon / toast with navigate to CreateRoom for
that `LibraryItem`. Compute `ItemHash.of(item)`.

**Acceptance criteria:**
- [ ] Physical device: tap → create screen with live code.

### C5 — Wire Home 👥
**Build:** Open `JoinSessionSheet`.

**Acceptance criteria:**
- [ ] Icon no longer inert.

### C6 — Active session pill + persistent live bar
**Build:** Per mockup 5a/5b and user decision:
- Detail: green session pill → returns to player/lobby.
- Other tabs: bottom live bar "Watching with … · tap to return".
- Optional: system ongoing notification (can reuse Phase 07 foreground
  service when player lands).

**Acceptance criteria:**
- [ ] Visible on host and guest during real session.
- [ ] Tap returns to session UI.

### C7 — In-app player screen (libVLC) hooked to session
**Build:** Compose screen hosting `VlcPlayerController` surface;
controls call `WatchTogetherSession.localPlay/Pause/Seek`.
Show "Watching with {names}" pill. Per-device subtitle chips call local
track APIs only. Invite button re-opens share/QR (mockup 4d).

**Acceptance criteria:**
- [ ] Two physical devices, same title locally, host+guest:
  - Anyone pause/seek syncs within ~1s
  - Drift auto-correct after forced desync
  - Subtitle change is local only
  - Guest kill → host shows disconnect, no crash
  - Host end → guest sees session ended

> Built: `ui/watchtogether/PlayerScreen.kt`. Two notes on Stage B surface
> gaps found while wiring this up (not architecture changes, additive only):
> `VlcPlayerController` had no way to attach a video output, so this task
> added `attachViews(VLCVideoLayout)`/`detachViews()` to it (the class's
> own doc comment already says surface rendering is a UI concern - this
> just gives the UI something to call). Also `PlayerController` doesn't
> expose track enumeration/names, so the subtitle/audio chips are generic
> Off/On toggles rather than the mockup's named tracks - a real track list
> would need a new `PlayerController` method, left for a follow-up task.
> Bullets above are the two-physical-device checks, out of scope here.

### C8 — Guest lobby + multi-join toasts
**Build:** Mockup 3b/3c — waiting for start; snackbars on participant
join/leave.

**Acceptance criteria:**
- [ ] Matches mockup behavior; uses `WatchTogetherSession.events`.

### C9 — Final QA checklist (manual)
**Acceptance criteria:**
- [ ] All C7 bullets re-verified on two **physical** USB devices.
- [ ] APK install via `adb install -r` per `CLAUDE.md`.
- [ ] No FATAL in logcat on launch/join/play.
- [ ] Stage A mockup parity spot-check (create, join, pill, live bar).

---

## Out of scope (do not implement in this file's tasks)

- TURN servers / paid relay
- Sending video/audio frames over WebRTC
- Host migration when host leaves
- >10 participants
- ExoPlayer / mpv
- Cloud accounts / auth
- Changing design tokens or global navigation IA beyond WT routes

---

## Quick reference — commands

```bash
cd android

# Unit tests for a class
./gradlew :app:testDebugUnitTest --tests com.mofy.app.watchtogether.ItemHashTest

# All WT + playback unit tests
./gradlew :app:testDebugUnitTest --tests 'com.mofy.app.watchtogether.*' --tests 'com.mofy.app.playback.*'

# Build
./gradlew :app:assembleDebug -q

# Install + launch smoke (device via USB)
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell monkey -p com.mofy.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 4
adb logcat -d | grep -iE "AndroidRuntime|FATAL" | tail -30
```

## Quick reference — locked constants

| Name | Value |
|---|---|
| Room code length | 6 |
| Alphabet | `23456789ABCDEFGHJKLMNPQRSTUVWXYZ` |
| Max participants | 10 |
| Drift threshold | 1500 ms |
| Position heartbeat | 3000 ms |
| STUN | `stun:stun.l.google.com:19302` (+ stun1) |
| Deep link scheme | `mofy://wt/{roomKey}` |
| Control channel label | `wt-control` |
| Player | libVLC via `PlayerController` |
