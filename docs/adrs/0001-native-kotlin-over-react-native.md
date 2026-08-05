# ADR 0001: Native Kotlin (Android) over React Native

**Status:** accepted

## Context

Mofy is a personal-use, single-platform (Android) app — it will never go
into an app store (see [[project_distribution]] context). Its heaviest
requirements are:

- A torrent download engine
- mpv-based video playback
- A WebView with ad-overlay blocking and JS-based content extraction
- A foreground service + wakelock so playback/downloads survive
  backgrounding
- SAF-based directory import, notification scheduling with adaptive timing

All of these require native Android APIs or native library bindings
regardless of the app shell chosen.

## Decision

Build Mofy as a native Android app in Kotlin with Jetpack Compose for UI.
Do not use React Native.

## Alternatives considered

- **React Native** — every heavy subsystem (libtorrent4j, mpv-android,
  custom WebViewClient, foreground service/wakelock) still requires a
  native module written in Kotlin. RN would add a JS/native bridge layer on
  top of code that has to exist natively anyway, with no cross-platform
  payoff since this is a single-device, single-platform personal app.

## Consequences

- Full, direct access to `libtorrent4j`, `mpv-android` (libmpv JNI
  bindings), custom `WebViewClient`/`WebChromeClient`, `Foreground Service`,
  wakelocks, SAF, and exact-alarm APIs — no bridge translation layer.
- No cross-platform portability (iOS, web) — acceptable since this will
  never leave one person's Android device.
- All future phases assume Kotlin/Compose as the implementation target;
  phase docs should not need to re-justify library choices already covered
  here (libtorrent4j for torrenting, mpv-android for playback).
</content>
