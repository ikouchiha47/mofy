# ADR 0010: Foreground Service + WakeLock for Model File Downloads

**Status:** proposed

## Context

`HttpModelDownloader` (`search/ModelDownloader.kt`) downloads the on-device
ML assets — the DistilBERT-based facet ONNX model, EmbeddingGemma, and
their `tokenizer.json`/`vocab.txt` — via a plain blocking HTTP call on
whatever coroutine/thread invokes it (`OnDeviceEmbedder`,
`ModelBasedFacetDecoder`), with a `NotificationCompat` progress
notification. There is no `Service`, no `WorkManager`, no wake lock. Some
of these files (the ONNX facet model alone is ~127MB) can take long enough
on a slow connection that screen-lock/backgrounding during the download is
a real scenario, and Android's background execution limits / Doze can
throttle or kill plain background-thread network I/O once the app is no
longer in the foreground.

This was verified against real comparable open-source projects rather than
assumed:

- **LibreTorrent** (closest real analog for "large file transfer that must
  survive lock/background") runs a plain foreground `Service`
  (`foregroundServiceType="dataSync|specialUse"`) with a `WAKE_LOCK`, no
  WorkManager anywhere in it.
- **YTDLnis** did build its downloader on WorkManager and hit
  foreground-service-lifecycle races and stalled/silently-dying downloads
  in its own issue tracker, later adding an `AlarmManager` fallback because
  WorkManager's behavior wasn't reliable enough for this exact job. This is
  a maintainer partially retreating from WorkManager for large transfers,
  not just an untested option.

This is unrelated to ADR 0009's periodic TMDB sync worker — that job is
small, deferrable, and genuinely fits WorkManager's intended use case. This
ADR is specifically about resumable, must-survive-backgrounding, one-shot
large binary downloads, which is a different shape of problem.

This ADR does **not** touch or reverse ADR 0004 ("Mofy never downloads
anything itself") — that decision is about torrent/media file downloading
for user library content, unrelated to the app downloading its own bundled
ML model weights.

## Decision

Replace `HttpModelDownloader`'s in-process blocking download with a plain
foreground `Service` + partial `WAKE_LOCK`, mirroring LibreTorrent's
pattern, and persist per-model download state so a Settings section can
show queued/downloading/failed/complete and offer retry/resume.

## Task DAG

```
 1 ──► 2 ──► 3 ──┬─► 5 ──► 7
       4 ────────┘         │
                            6 ◄──┘
```

1. **`ModelDownloadState` Room entity + DAO** in `data/library/` or a new
   `data/models/` package: `modelKey` (PK, e.g. "distilbert-onnx",
   "embeddinggemma", "tokenizer", "vocab"), `status`
   (QUEUED|DOWNLOADING|COMPLETE|FAILED), `bytesDownloaded`, `bytesTotal`,
   `destPath`, `lastErrorMessage`, `updatedAtEpochMillis`. Add to
   `AppDatabase`. No dependencies.

2. **Add `Manifest.permission.WAKE_LOCK` and a `foregroundServiceType`
   declaration** to `AndroidManifest.xml` for the new service (below).
   Android 14+ requires an explicit type; `dataSync` fits a one-shot file
   download (matches LibreTorrent's declared type for the same reason).
   Depends on nothing.

3. **`ModelDownloadService : Service()`**: accepts a model key + URL +
   dest path via `Intent` extras, calls `startForeground()` immediately
   with an ongoing notification (reuse `HttpModelDownloader`'s existing
   `NotificationCompat` builder code), acquires a partial `WakeLock`
   (`PowerManager.PARTIAL_WAKE_LOCK`, tagged `"mofy:model-download"`,
   released in `onDestroy`/on completion — never held past the transfer).
   Moves `HttpModelDownloader.downloadWithProgress`'s existing byte-copy
   loop into this service, updating 1's DAO row on each percent tick
   instead of (or in addition to) the notification. Depends on 1, 2.

4. **Resume-on-restart support**: on download start, if `dest.tmp` from a
   prior attempt exists, send an HTTP `Range` request header
   (`bytes=<existing-size>-`) instead of restarting from zero, if the
   server responds `206 Partial Content` (else fall back to full restart).
   Depends on nothing (pure `HttpModelDownloader` logic change), lands
   inside 3's moved code.

5. **`ModelDownloadRepository`**: thin wrapper that starts the service via
   `ContextCompat.startForegroundService()`, exposes 1's DAO as a `Flow`
   for UI observation, and provides a `retry(modelKey)` that re-triggers
   the service for one previously-failed model. `OnDeviceEmbedder`/
   `ModelBasedFacetDecoder` call this instead of constructing
   `HttpModelDownloader` directly. Depends on 3, 4.

6. **Boot/process-death recovery**: on `MofyApplication.onCreate()`, query
   1's DAO for any row stuck in `DOWNLOADING` from a killed process
   (no matching running service) and mark it `FAILED` (not silently stuck
   "downloading" forever) so the Settings screen (7) can offer retry.
   Depends on 1.

7. **Settings screen section**: `SettingsScreen.kt` adds a "Model
   downloads" list (one row per `modelKey`) showing status + a Retry
   button wired to 5's `retry()`, sourced from 5's `Flow`. Depends on 5, 6.

## Alternatives considered

- **Keep WorkManager (the earlier, unverified suggestion)** — rejected
  after checking real projects: the one comparable project that tried it
  for this exact shape of work hit foreground-service races and reliability
  issues and partially reverted; LibreTorrent (closer analog, more mature)
  never used it.
- **Android's system `DownloadManager`** — not investigated in depth here
  since a prior attempt at this ("the whole DownloadManager saga") already
  cost significant time; a plain foreground `Service` avoids that class of
  system-component integration entirely and matches what LibreTorrent
  actually ships.
- **Do nothing, keep current behavior** — rejected per user's original
  concern: current downloads have no persisted state to resume/retry from
  and are vulnerable to being killed on backgrounding.

## Consequences

- New `AppDatabase` entity/migration (acceptable given
  `fallbackToDestructiveMigration`, same as ADR 0009).
- App now holds a wake lock during model downloads — must be scoped
  tightly (acquired only for the service's active transfer, released
  immediately on completion/failure/cancel) to avoid battery complaints;
  no blanket `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is requested by this
  ADR — OEM-specific aggressive killers (MIUI/Samsung) are a known residual
  risk noted here but not solved by this decision.
- `HttpModelDownloader`'s existing notification/progress code is moved,
  not rewritten from scratch — reduces risk of introducing new bugs in
  already-working progress-reporting logic.
- Settings screen gains a new section; exact UI (list layout, retry button
  placement/styling) follows `CLAUDE.md`'s design tokens but is not
  specified by this ADR — implementation-time UI detail, confirm with user
  before building per standing project practice.
