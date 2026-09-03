# Problem: SAF content:// URIs stop being readable by Play time

## App context

Android/Kotlin/Jetpack Compose app (personal use, sideloaded, not on Play
Store). Users pick a video file (and optionally a subtitle file) that they
already downloaded via another app (browser, torrent client) somewhere
under `/storage/emulated/0/Download/...`. Mofy stores the picked file's URI
string in a Room database row (`LibraryLink.movieUri`), then later — on a
**different screen**, sometimes minutes/hours later — reads that stored URI
back and opens it with libVLC (`ContentResolver.openFileDescriptor` +
`Media(libVlc, fd.fileDescriptor)`) for in-app playback.

## The problem

The stored `content://` URI reliably throws `SecurityException` when opened
at Play time, even though:

- `ContentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`
  was called immediately after the picker returned the URI.
- No app reinstall, no device reboot, no time gap large enough to explain
  garbage collection of the grant — confirmed within a single app session,
  picking the file and hitting Play within seconds.

Confirmed with **two different SAF providers**, both throwing the same
exception shape:

```
java.lang.SecurityException: Permission Denial: reading
com.android.providers.downloads.DownloadStorageProvider uri
content://com.android.providers.downloads.documents/document/raw:/storage/emulated/0/Download/<file>.mp4
from pid=<pid>, uid=<uid> requires that you obtain access using
ACTION_OPEN_DOCUMENT or related APIs
```

```
java.lang.SecurityException: Permission Denial: reading
com.android.externalstorage.ExternalStorageProvider uri
content://com.android.externalstorage.documents/document/primary:Download/<folder>/<file>.mp4
from pid=<pid>, uid=<uid> requires that you obtain access using
ACTION_OPEN_DOCUMENT or related APIs
```

Both crash inside `ContentResolver.openFileDescriptor()`, called from a
different screen/composable than the one that originally picked the file.

## What's been tried and ruled out

1. **`DocumentsContract.getDocumentId(uri)` for the raw: case** — a real,
   documented Android quirk where `DownloadStorageProvider`'s `raw:`
   document ids embed a real filesystem path. Extracting and reading that
   path directly via `java.io.File`/`Uri.fromFile` does NOT work either:
   confirmed via `adb shell run-as <pkg> cat <path>` returning a
   permission-denied error (45 bytes of error text, not the real file),
   even though `READ_MEDIA_VIDEO` is a granted runtime permission and the
   file itself is world-readable to the `media_rw` group. Scoped storage
   blocks direct `java.io.File` access to that path for a non-owning app,
   permission grant or not.

2. **Resolving to the file's MediaStore row** (`MediaStore.Video.Media`,
   matching by filename, then `openFileDescriptor` on the resulting
   `content://media/external/video/media/<id>` URI) — works in principle
   (confirmed working for files that were already indexed by MediaStore
   normally, e.g. from a real download), but:
   - A file inserted via `MEDIA_SCANNER_SCAN_FILE` broadcast (rather than a
     normal completed-download flow) can sit with `is_pending=1` and
     `owner_package_name=<the app that scanned it>` — invisible to any
     other app's `ContentResolver.query` even with `READ_MEDIA_VIDEO`
     granted, confirmed via `adb shell content query`.
   - Its `content://media/...` URI encodes an opaque numeric row id with
     no filename — bad for displaying "which file is this" in library UI.

3. **Copying the picked file's bytes into app-private storage at pick
   time** (`ContentResolver.openInputStream(uri)` while the picker's own
   callback still has fresh access, `copyTo` into
   `context.filesDir/library_media/<uuid>/<original-filename>`, store that
   `file://` path instead of the original `content://` URI) — this is the
   only approach confirmed to actually work end-to-end, repeatably. The
   downside: it duplicates potentially multi-GB video files into app
   storage, which the user (rightly) doesn't want for a personal video
   library.

4. **Skip the copy, trust `takePersistableUriPermission` for everything
   except the confirmed-broken `DownloadStorageProvider`** — tried as an
   optimization to avoid copying large files. This reintroduced the exact
   crash for `ExternalStorageProvider`-provided URIs (folder-picked via
   `ACTION_OPEN_DOCUMENT_TREE`, then a child document opened via
   `DocumentFile.listFiles()`), disproving the assumption that
   `ExternalStorageProvider` reliably honors persisted grants across
   screens/time on this device (a real device: Nothing Phone 2a, no SD
   card, internal storage only).

## Real question

Is there a way to durably retain read access to a user-picked SAF document
(across screens, without copying the file) that's actually reliable in
practice — not just per API docs — for local storage providers like
`ExternalStorageProvider` and `DownloadStorageProvider`? Or is copying (or
an equivalent: streaming into a stable location, hardlink if same
filesystem, etc.) genuinely the only dependable option once the target is
"play this file from a totally different screen, possibly much later"?

Constraints:
- App is sideloaded only, `MANAGE_EXTERNAL_STORAGE` ("all files access") is
  an option already declared in the manifest but not yet used for this.
- `READ_MEDIA_VIDEO` runtime permission is already granted.
- Target: real device (Nothing Phone (2a), no SD card), files live directly
  under `/storage/emulated/0/Download/...`, both loose files and files
  inside subfolders.
