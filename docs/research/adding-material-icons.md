# Adding a Material icon without material-icons-extended

Mofy doesn't depend on `androidx.compose.material:material-icons-extended` -
that artifact bundles every Material icon (thousands of classes) to support
maybe two dozen an app actually uses, and it's a real, measured chunk of
dex weight in a release build (confirmed via `unzip -l app-release.apk`
before/after removing it - see git history around 2026-09-03 for the exact
numbers). Instead, each icon this app uses is a small local
`res/drawable/ic_*.xml` VectorDrawable, exposed through
`ui/icons/AppIcons.kt` as a drop-in replacement for `Icons.Filled.X`.

If you need a Material icon that isn't already in `AppIcons.kt`, don't add
the `material-icons-extended` dependency back - follow this instead.

## 1. Get the icon's Android XML from Google Fonts

`fonts.google.com/icons` supports jumping straight to a specific icon's
detail panel via URL, no manual search-and-click needed:

```
https://fonts.google.com/icons?selected=Material+Icons:<icon_name>:&icon.set=Material+Icons&icon.style=Filled&icon.platform=android
```

- `<icon_name>` is the icon's snake_case identifier (e.g. `home`,
  `arrow_back`, `forward_10`) - this is almost always the snake_case form of
  the Compose `Icons.Filled.X` name (`ArrowBack` -> `arrow_back`,
  `Forward10` -> `forward_10`). If unsure, search the site's UI once to find
  the exact id, then use the URL form for anything after.
- `icon.set=Material+Icons` selects the classic set (matches
  `Icons.Filled.*`/`androidx.compose.material.icons.filled`) - **not**
  "Material Symbols (new)", which is a different, incompatible glyph family
  the site defaults to.
- `icon.style=Filled` matches `Icons.Filled.*` specifically (there's also
  Outlined/Rounded/Sharp/Two tone, matching `Icons.Outlined.*` etc. if you
  ever need one of those instead).
- `icon.platform=android` pre-selects the "Android" tab in the detail panel,
  which is what exposes the Download button (Web/iOS/Compose tabs don't).

On that page, click **Download** - it saves `<icon_name>_black-android.zip`
to your Downloads folder. Unzip it; the file you want is
`res/drawable/baseline_<icon_name>_24.xml` (ignore the `_20.xml` variant and
the `drawable-*dpi/*.png` files - those are legacy raster fallbacks, not
needed here).

## 2. Strip the framework tint attribute

The downloaded XML has an `android:tint="?attr/colorControlNormal"`
attribute on the `<vector>` root. `colorControlNormal` is a legacy
AppCompat/Material Components theme attribute this Compose-only app's theme
doesn't define - leaving it in risks a resource-resolution failure at
runtime. Remove that one attribute (and only that attribute - **don't**
just delete the line if it's also carrying the tag's closing `>`; check the
result parses as valid XML). Compose's `Icon(imageVector, tint = ...)`
already handles tinting at the call site, same as the library icons did, so
nothing is lost by removing it.

The `android:fillColor="@android:color/white"` on the `<path>` is fine to
leave as-is - Compose's `Icon()` tint is applied as a `ColorFilter` over the
whole vector regardless of the path's own fill color, so this value never
actually shows up in the rendered app.

Resulting file should look like:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
  <path
      android:fillColor="@android:color/white"
      android:pathData="...."/>
</vector>
```

Save it as `android/app/src/main/res/drawable/ic_<icon_name>.xml`.

### RTL-mirrored icons (`Icons.AutoMirrored.Filled.X`)

If the icon needs to flip in RTL layouts (this app uses that for the
back-arrow app-bar buttons via `Icons.AutoMirrored.Filled.ArrowBack`), add
`android:autoMirrored="true"` to the `<vector>` tag - it must be a
**separate** drawable file (`android:autoMirrored` is a property of the
resource itself, not something you can toggle per-usage), so keep a plain
non-mirrored version too if anything in the app needs that. See
`ic_arrow_back.xml` (plain) vs `ic_arrow_back_automirrored.xml` (mirrored)
for the existing example.

## 3. Add it to AppIcons.kt

Add one property to `android/app/src/main/kotlin/com/mofy/app/ui/icons/AppIcons.kt`,
matching the existing pattern exactly:

```kotlin
val IconName: ImageVector @Composable get() = ImageVector.vectorResource(R.drawable.ic_icon_name)
```

`@Composable get()`, not a plain `val` - `ImageVector.vectorResource()`
needs `LocalContext`, which only exists inside composition. This is also
why `MofyDestinations.kt`'s `TopLevelDestination` enum (evaluated eagerly at
class init, not inside a composable) stores a `R.drawable` `Int` instead of
an `ImageVector` directly, resolving it via `vectorResource()` only at its
one actual render call site in `MainActivity.kt`. Follow that pattern if you
need an icon somewhere similarly non-composable.

Then use it like the old `Icons.Filled.X`:

```kotlin
Icon(AppIcons.IconName, contentDescription = "...")
```
