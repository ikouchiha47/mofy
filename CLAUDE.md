# Mofy

Personal-use-only Android app (Kotlin/Compose). Never distributed via app
store. See `docs/adrs/` for architecture decisions and `design/browse-flow-mockup.html`
for the canonical visual reference.

## Design guide

Dark-only, cinematic UI - not an adaptive light/dark theme (forced dark on
purpose, see `ui/theme/Theme.kt`).

**Colors** (`ui/theme/Color.kt`, matches the mockup exactly):
- Background `#0E0E10`, Surface `#1A1A1D`, Surface Variant `#232327`, Surface Container `#2B2B30`
- Border `#2E2E33`
- Text `#F2F2F2`, Text Dim `#9A9AA2`
- Accent (primary) `#E94560`, Accent Blue (secondary) `#4EA1FF`, Good `#3ECF8E`

**Type**: Bungee for display, Manrope for body (`ui/theme/Type.kt`).

**Shapes/corners** (`ui/theme/Shape.kt`): corners are **slightly rounded,
never pill/stadium-shaped**. Scale: 6dp (extraSmall) / 8dp (small) / 10dp
(medium) / 12dp (large) / 16dp (extraLarge, posters/cards). Material3's
`Button` defaults to a fully-rounded stadium shape regardless of the theme's
`Shapes` in recent Compose Material3 versions, so **always pass an explicit
`shape = MaterialTheme.shapes.small` (or similar) to `Button`/`OutlinedButton`
calls** - don't rely on the theme override alone to fix a rounded button.

## Working conventions

- Do not make unilateral navigation/UX/architecture decisions - confirm with
  the user first, especially anything affecting flow behavior.
- Verify real builds on a physical device (USB ADB preferred over wireless -
  wireless has been flaky/slow in this project) before claiming a change works.
- No API-based LLM calls anywhere in the app.
- `.env.prod` must never be committed.
