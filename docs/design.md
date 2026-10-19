# Usuals visual design

Usuals gives everyday shopping the calm confidence of an editorial notebook.
Three coordinated palettes keep the grocery list in focus. The original Plum
uses warm ivory, aubergine and champagne; Grove adds botanical greens and oat
tones; Tide pairs ink blue with sea glass. Decoration never competes with names,
quantities or check states.

## Identity

The working product name is **Usuals**. Android reads it from the
nontranslatable `app_name` resource. Branded sentences take that resource as a
format argument in English, Spanish, Italian, French and German, so a name
change has one source.

The mark is a rounded U with a separate dot above its right stem. The canonical
artwork is `web/assets/mark.svg`: champagne on a rounded aubergine tile. Android's
header and empty state retain those brand colours across all palettes. The
launcher uses the same geometry within the adaptive icon safe area; its monochrome
layer reuses the foreground drawable so the silhouettes cannot diverge. Android
controls the launcher's outer mask and themed colours. No bitmap or remote
request is needed on Android. Widget branding uses the same
tile, and notifications share the foreground silhouette. `scripts/sync-brand-assets`
exports the canonical SVG to Android, the README cover and the store icon; its
`--check` mode detects drift. Authentik references the same SVG served by the web
app. The store's PNG is a render of that artwork, not a separate design.

## Palettes

Choose a palette in Android **Settings > Appearance**, or use **Appearance** in
the browser sidebar. Selection applies immediately and survives restart on that
device/browser. It does not sync or change model, lookup, household or privacy
settings. Every palette follows the device's light/dark mode. The widget follows
the Android choice, using resource-based colors even when the app is closed.

| Palette | Light background / action | Dark background / action | Character |
| --- | --- | --- | --- |
| Plum, default | `#F8F5EF` / `#583D53` | `#1D181E` / `#E2BCD4` | Aubergine, champagne and warm paper |
| Grove | `#F3F5ED` / `#36593F` | `#171F19` / `#BCD5AA` | Botanical greens and oat tones |
| Tide | `#EFF5F7` / `#285D70` | `#151F27` / `#A7D6E5` | Ink blue, sea glass and cool paper |

The original Plum roles remain:

| Role | Light | Dark |
| --- | --- | --- |
| Background | `#F8F5EF` | `#1D181E` |
| Paper | `#FFFDFA` | `#28212A` |
| Main text | `#302331` | `#F5EDF1` |
| Supporting text | `#6E5E6B` | `#C6B6C1` |
| Main action | `#583D53` | `#E2BCD4` |
| Mauve container | `#EEDFE8` | `#4B3548` |
| Champagne container | `#EFE1CA` | `#453929` |
| Champagne text | `#664D2D` | `#EDDBBA` |
| Sage container | `#E2E6DB` | `#343E2F` |
| Hairline | `#E3D9DD` | `#4C3E4A` |

Follow the device theme, including the widget. Keep category colours muted and
limited to their small glyphs. Colour always supplements text or an icon.
Normal text needs at least 4.5:1 contrast; category glyphs and interactive
outlines need at least 3:1. Theme tests exercise all six schemes and every
category, including supporting text on tinted containers.

## Typography and layout

- Use Android's bundled serif for display and headline roles, at 34 sp for the
  main list heading. Keep titles and controls in the bundled Manrope variable
  font. Grocery names remain 16 sp, with 14 sp supporting text and 12 sp metadata.
  Serif headings use regular weight; controls use semibold instead of heavy bold.
- Put the mark and brand on a quiet masthead. Give the list title its own line
  so long list names do not compete with recipe, history and sharing actions.
  Keep counts and those actions together below it, separated from the list by
  a fine rule. Preserve list selection and every sync or recovery status.
- Group grocery rows on ivory paper with 16 dp outer corners and inset dividers.
  Quantity badges use the background colour and 6 dp corners. Keep check circles
  visible and completed names struck through. Preserve checkbox semantics.
- Keep rows at least 64 dp high and shopping mode rows at least 84 dp. Every icon
  action retains Material's 48 dp touch target. Text wraps where supported and
  respects system font scaling. A clipped list title retains its existing picker.
- Use a floating capture dock with a subtle hairline, 22 dp corners and no shadow.
  Voice uses the palette's main action; keyboard is neutral and scan uses its
  secondary container.
  Preserve press-and-hold voice capture, keyboard fallback and scanner pause.
- Supporting forms use outlined paper cards with 20 dp corners. History uses
  champagne for its introduction. Recipe review retains explicit selections;
  scan results retain explicit product-name confirmation and attribution.

The unmodified variable font comes from
[Google Fonts' Manrope directory](https://github.com/google/fonts/tree/main/ofl/manrope).
Its SIL Open Font License 1.1 is bundled at
[manrope-OFL.txt](../app/src/main/assets/licenses/manrope-OFL.txt).
The system serif adds no font dependency or license artifact.

Typography, colour and shape definitions live in `:app`'s `ui/theme`; all UI copy
lives in Android string resources. Adding a UI language still only adds complete
`values-xx/` resource files. The web client follows the same palette and type
hierarchy while retaining semantic controls and visible focus states.
