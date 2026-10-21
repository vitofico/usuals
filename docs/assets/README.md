# README assets

The cover is a repository-native SVG using the app's palette and dotted U mark
from `web/assets/mark.svg`. It embeds no font, script or remote resource.

Run `scripts/sync-brand-assets` after changing that canonical SVG. It regenerates
Android's mark/launcher, brand colours, the README cover glyph and the F-Droid
metadata icon. Use `scripts/sync-brand-assets --check` to detect drift. Exporting
requires Python 3 and librsvg's `rsvg-convert`; normal app builds use the committed
assets and require neither tool. The widget, reminders and themed icons reuse
those Android resources. Authentik points directly to the served canonical SVG.

`palettes.svg` illustrates Plum, Grove and Tide with their light/dark colors.
It is a palette comparison, not an application screenshot.

The PNGs are unmodified screenshots from the local verification runs:

- `android-list.png`: Android emulator with the development grocery fixtures.
- `android-product.png`: the saved Open Food Facts product record from an
  earlier explicit lookup, displayed from the local demo fixture. Refreshing
  this screenshot requires no network request. The screen includes its source,
  saved date, ODbL credit and packaging reminder.
- `web-editor.png`: the local browser test list with sample groceries. It was
  taken before server-held lists and still shows the old household navigation
  and encryption note.

No customer account, invitation, server address or credential appears in these
screenshots. Keep documentation screenshots free of private household data.
