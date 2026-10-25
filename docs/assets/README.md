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

The PNGs are unmodified screenshots from the local verification runs:

- `android-list.png` and `android-list-dark.png`: the same six-item synthetic
  Home list in the Android app, in Plum light and dark appearances. They show the
  populated daily header, category groups, quantities and capture dock.
- `android-product.png`: the saved Open Food Facts product record from an
  earlier explicit lookup, displayed from the local demo fixture. Refreshing
  this screenshot requires no network request. The screen includes its source,
  saved date, ODbL credit and packaging reminder.
- `web-editor.png`: the current browser list in Plum dark, using a local
  synthetic account and grocery list. This example is a single-account list;
  sharing is optional.

The list screenshots were copied byte-for-byte from the approved everyday
identity verification: `native-after-plum-light.png`,
`native-after-plum-dark.png` and `web-after-plum-dark-desktop.png`. The approved
`2a57a14` UI is unchanged in the README refresh's `2861633` source base. The
screenshots are not illustrations, mockups or altered composites. README widths
only affect how they are displayed. The Android `<picture>` selects dark mode
when the reader prefers it and falls back to light mode; explicit links expose
both full-resolution images. The browser example is always the supplied dark
frame.

No customer account, invitation, server address or credential appears in these
screenshots. Keep documentation screenshots free of private household data.
