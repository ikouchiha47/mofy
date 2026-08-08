# Research: Native SQLite extensions on Android (spellfix1, sqlite-vec)

**Status:** research/plan, not yet implemented - see open questions before starting.

## Why this exists

ADR 0002 rejected `sqlite-vec` because "Room's default `SupportSQLiteOpenHelper` doesn't support it, would need a custom driver." That reasoning needs revisiting - a real, Google-maintained custom driver now exists (see below), so the original rejection's premise is out of date. This doc also covers `spellfix1`, needed for typo-tolerant Library search (FTS3/4 has no fuzzy/edit-distance matching built in - confirmed, not assumed, see "FTS has no fuzzy matching" below).

## What's confirmed

### Android's bundled SQLite blocks extension loading by default

Confirmed via the sqlite-extensions-guide reference: "extension loading is disabled by default in Android's SQLite implementation." This is Android's own SQLite build, not a Room limitation specifically.

### FTS3/4/5 have no fuzzy/edit-distance matching built in

Confirmed by testing directly against the app's real on-device data (`library_search` FTS4 table) - prefix matching (`furi*`) works, but there is no edit-distance/typo tolerance in FTS itself. `spellfix1` is the standard SQLite-side answer to this; the app-level Levenshtein/SymSpell fallback (discussed earlier, not built) is the alternative that avoids native extensions entirely.

### `androidx.sqlite` 2.6.0 (stable) supports loading extensions - this is the real unlock

`BundledSQLiteDriver.addExtension()`, introduced in `2.6.0-beta01`, stabilized in `2.6.0` (released 2025-09-10). This is a first-party Google/Jetpack API, not a third-party fork - contributed upstream, in the stable AndroidX SQLite release. Confirmed via the official AndroidX SQLite release notes.

This means: Room can be configured to use `BundledSQLiteDriver` instead of the default framework driver, and that driver can register `.so` extensions before opening connections. **This directly overturns ADR 0002's stated reason for rejecting `sqlite-vec`** - a supported custom driver now exists, from Google itself, not a third-party dependency swap like `requery/sqlite-android`.

### Where the two extensions actually come from

| Extension | Source | Maintenance | Android prebuilt? |
|---|---|---|---|
| `sqlite-vec` (`vec0`) | [asg017/sqlite-vec](https://github.com/asg017/sqlite-vec) | Active - CI/CD builds for Android (aarch64, x86_64, i686, armv7a), recent PR activity (Android/Termux support PR opened 2026-02-07, 16kb-page support PR opened 2025-12-24) | **Yes** - official prebuilt `.so` files published on the project's own [GitHub Releases](https://github.com/asg017/sqlite-vec/releases), first-party from the extension's author (Alex Garcia). Docs explicitly say "drop those files into your Android Studio... project as needed." `.aar` packaging is planned but not yet shipped. |
| `spellfix1` | Part of SQLite's own contrib/extension source (`ext/misc/spellfix.c` in the SQLite source tree) | SQLite core project - extremely well maintained, but **not distributed as a standalone prebuilt Android `.so` by SQLite itself** | **No trustworthy prebuilt found.** The closest hit was [NanoMichael/icu_sqlite3_for_android](https://github.com/NanoMichael/icu_sqlite3_for_android), a third-party project bundling several SQLite extensions (ICU, FTS, presumably spellfix) - but it requires building from source via their build script, has no recent-activity signal checked, and isn't a first-party or clearly-maintained source. Using it as-is would mean trusting an unverified small project's NDK build. |

## Open questions before implementing

1. **`sqlite-vec` is ready to integrate now** (real prebuilt, actively maintained, official driver support exists) - this reopens ADR 0002's rejection and should get a real second look: does bringing back on-device embeddings via `sqlite-vec` change the earlier "start with FTS-only, defer the embedding model" decision? Recommend: no, keep that sequencing - having a viable vector-storage extension doesn't remove the need for the embedding model itself (EmbeddingGemma-300M + LiteRT + DJL), which is the actually large piece of that work. This just means *if/when* that work happens, the "no native extension available" objection to `sqlite-vec` is gone.
2. **`spellfix1` has no vetted prebuilt for Android.** Real options, in order of preference:
   - Build `spellfix1.so` from SQLite's own `ext/misc/spellfix.c` via the NDK ourselves (small, single-file C extension, low complexity to build - the honest "do it right" path, not dependent on an unmaintained third party).
   - Use `icu_sqlite3_for_android`'s build script as a reference/starting point rather than trusting its output directly.
   - Fall back to the app-level Levenshtein/SymSpell approach (no native code at all) if the NDK build path turns out to be more friction than it's worth.
3. Both extensions require switching Room's driver to `BundledSQLiteDriver` - this is an app-wide driver change (affects every table, not just search/vector), needs its own verification pass that nothing regresses (WAL mode, existing queries, migrations) before relying on it.

## Recommendation

Proceed with `sqlite-vec` integration readiness (dependency + driver swap) as the vehicle, since it's needed for the driver swap regardless. For `spellfix1`, build from SQLite's own `spellfix.c` via NDK rather than trusting an unmaintained third-party binary - matches the standard set elsewhere in this project (e.g. rejecting a torrent-search-guessed romanization in favor of Android's real ICU Transliterator, not a shortcut).

## Status: implemented

Both extensions are wired into `AppDatabase.kt` via `BundledSQLiteDriver.addExtension()`. `sqlite-vec`'s official prebuilt `.so` and `spellfix1`'s self-built `.so` (from [ikouchiha47/spellfix-builds](https://github.com/ikouchiha47/spellfix-builds), a small public repo whose CI cross-compiles SQLite's own `ext/misc/spellfix.c` via the NDK and auto-syncs weekly against upstream changes) both live in `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/`.

Neither extension has an actual query wired up yet (no `vec0` virtual table, no `spellfix1` shadow tables) - this was infrastructure-only. `vec0` needs an embedding model (deliberately still deferred, see "Open questions" above); `spellfix1` needs a `edit_distance`/fuzzy-match query added to `LibraryDao`'s search path.

### Two build/packaging crashes hit and fixed while wiring this up

**1. `addExtension()` double-appends `.so`.** Passing a path ending in `.so` (e.g. `.../libvec0.so`) produces a lookup for `libvec0.so.so` - `addExtension()` appends the extension itself. Pass the path *without* the trailing `.so`.

**2. Native libs must be extracted to disk, which isn't AGP's default.** Modern AGP maps native libraries directly out of the (compressed) APK rather than extracting them to `nativeLibraryDir` on the filesystem. `addExtension()` needs a real file path, so without an extraction step it fails with `dlopen failed: library "..." not found`. The fix is **not** `android:extractNativeLibs="true"` in the manifest - AGP explicitly rejects that as of recent versions ("Avoid setting android:extractNativeLibs... instead set android.packagingOptions.jniLibs.useLegacyPackaging"). The correct fix, in `app/build.gradle.kts`:
```kotlin
android {
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}
```

**3. `spellfix1` crashed with `dlopen failed: cannot locate symbol "sqlite3_free"` on first load - a bug in the build, not the Android wiring.** SQLite's extension mechanism works by redirecting calls like `sqlite3_free()` through a runtime-provided `sqlite3_api` function-pointer struct, via macros defined in `sqlite3ext.h`, guarded by `#ifndef SQLITE_CORE`. The `spellfix-builds` CI originally compiled with `-DSQLITE_CORE=0` to mean "this is not core SQLite" - but `#ifndef SQLITE_CORE` only checks whether the macro is *defined at all*, not its value, so `-DSQLITE_CORE=0` still disabled the redirection macros. `spellfix.c` ended up calling `sqlite3_free` directly, which doesn't exist as a linkable symbol in a loadable extension (no `-lsqlite3` to link against, and correctly so - the app supplies its own SQLite via `androidx.sqlite`). Fix: don't define `SQLITE_CORE` at all when compiling as a loadable extension.
