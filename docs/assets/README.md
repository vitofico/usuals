# README assets

The cover is a repository-native SVG using the dotted U mark from
`web/assets/mark.svg` and the four unchanged paths from `web/assets/collage.svg`.
The collage uses dark Plum colours so all four shapes remain visible on the
aubergine panel. It embeds no font, script or remote resource.

Run `scripts/sync-brand-assets` after changing that canonical SVG. It regenerates
Android's mark/launcher, brand colours, the README cover glyph and the F-Droid
metadata icon. Use `scripts/sync-brand-assets --check` to detect drift. Exporting
requires Python 3 and librsvg's `rsvg-convert`; normal app builds use the committed
assets and require neither tool. The widget, reminders and themed icons reuse
those Android resources. Authentik points directly to the served canonical SVG.

Keep the exact `x="856" width="424" height="416"` brand-background rectangle and
the `brand-mark:start` / `brand-mark:end` block in the cover. They are managed by
the exporter. An outer transform positions the mark without changing its exported
geometry. The four `cover-collage-*` path data strings must stay identical to the
corresponding canonical collage paths; adjust their crop, scale or palette
colours rather than drawing replacement shapes.

`palettes.svg` illustrates Plum, Grove and Tide with their light/dark colors.
It is a palette comparison, not an application screenshot.

The current README captures were made on 2026-10-28 from the local development
checkout, including the pending interface changes. They are unaltered screenshots
with synthetic shopping data; no private account, server address or credential
appears. They do not assert that pending code is already in a published APK.

- `readme-list-grove-light.png`: populated Market list, Grove, Artwork, light mode.
- `readme-list-tide-dark.png`: the same list, Tide, Plain, dark mode.
- `readme-shop-finished-dark.png`: completion actions after Finish shop.
- `readme-history-dark.png`: saved purchases from that demo shop.
- `web-editor.png`: current browser UI at 1440 × 1000 in Grove dark with Artwork.

The native capture used the production app and its local database: create a
separate demo list, add and edit groceries, check items, enter shopping mode,
finish the shop and inspect History. Appearance choices were made through Settings.
The browser used the current static client with deterministic synthetic account
and list API responses, plus the actual generated input-language assets. Its
capture verified Preferences → Appearance, changed palette/background through the
real controls, and checked their persistence after reload with no page errors.
This browser capture does not verify a live server connection or synchronization.
All selected images were visually inspected.

The earlier `android-list*.png` and `android-product.png` remain historical assets.
They are no longer used as current README interface screenshots.
