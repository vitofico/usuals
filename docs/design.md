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
launcher, widget and tutorial retain those brand colours across all palettes. The
launcher uses the same geometry within the adaptive icon safe area; its monochrome
layer reuses the foreground drawable so the silhouettes cannot diverge. Android
controls the launcher's outer mask and themed colours. No bitmap or remote
request is needed on Android. Widget branding uses the same
tile, and notifications share the foreground silhouette. `scripts/sync-brand-assets`
exports the canonical SVG to Android, the README cover and the store icon; its
`--check` mode detects drift. Authentik references the same SVG served by the web
app. The store's PNG is a render of that artwork, not a separate design.

## Collage and first-run tour

The colour collage is the decorative motif. `web/assets/collage.svg` holds its
four shapes; `scripts/sync-brand-assets` exports matching browser masks and
Android vectors. Each layer takes a semantic colour from the selected palette,
including dark appearance. The logo and launcher keep their canonical artwork.
The collage appears in the daily selected-list header as well as welcome,
tutorial and empty-list screens, with a smaller crop in the browser sidebar.
On Android, the list name and chooser sit directly on the page, with a faint
collage crop and opaque backings for helper text and controls. The browser keeps
its palette container with text and controls in the foreground, preserving their
contrast.
The canonical shapes stay unchanged. Populated grocery rows remain clear.

Artwork backgrounds also use a large, faint edge crop on populated Android and mobile browser lists and a quieter settings crop.
Grocery rows and inputs keep opaque surfaces. The four vector layers take a
soft pigment treatment without remote images or continuous animation.
The paper grain is a static alpha texture over those same silhouettes: a small
deterministic procedural tile on Android, and `web/assets/paper-grain.svg` in the
browser. It only fades pigment, so it cannot exceed the tested ambient contrast
bounds. The tile is cached; there are no frame loops or remote artwork requests.
Exposed counts, sync captions and helper text also keep small opaque ground
backings so artwork cannot reduce their contrast; headings and category glyphs
are checked against the strongest ambient layer in all six appearance schemes.

**Background style** is independent of the palette and system light/dark mode.
Artwork is the default when no choice is saved. Plain removes ambient page and
sidebar backgrounds, while preserving the logo and welcome/empty illustrations.
The choice applies immediately and stays on the device/browser through restarts
and palette changes. Appearance offers labelled Plain and Artwork previews.

`web/assets/theme.css` owns the browser palette definitions. The shopping client
and Usuals Cloud load those same definitions and `collage.css`. Cloud reads the
shopping client's saved palette and follows system appearance; there is no
separate cloud palette or copy of the collage geometry.

The first normal Android launch opens a five-step guided tour: the offline list,
capture, scans/recipes, shopping/history and optional sharing through an account.
Next, Back and Skip remain explicit; the final step offers **Start my list** or
**Set up account**, which opens the Account screen. It scrolls independently of its controls for smaller displays and large
text. Completion is local to the installation, and Settings can reopen the tour.
Incoming text shares, connection links and widget launches defer automatic
onboarding so their requested action can continue.

The web client's three-step tour (Welcome, List, Share) opens on its first visit.
Its completion flag is local to the browser, with a Guided tour navigation action
for replay. Keyboard users can move through the dialog; Escape skips it. An
interrupted tour can start again. Neither client requests permissions, enables
product lookup or creates accounts through the tutorial.

## Palettes

Choose a palette in Android **Settings > Appearance**, or use **Appearance** in
the browser sidebar. Selection applies immediately and survives restart on that
device/browser. It does not sync or change model, lookup, account or privacy
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
- Give the actual list name the header, with its picker and one labelled
  options menu. Its integrated collage band is about 88 dp at normal text size;
  typing and shopping use a smaller crop and title to keep content space useful.
  Typing hides the item count and reduces the band to about 64 dp at normal text.
  The shopping status and exit action wrap onto separate lines when large text
  or translated labels need the width, so they cannot squeeze the list away.
  Allow the band to grow for system text scaling and sync warnings. Keep the
  item count quiet, the one-tap History shortcut available, and every sync warning.
  History is also in the menu alongside favourites, import and sharing; list
  management has its own group. Avoid separate brand, actions and tools bars
  above the list.
- Suggestions start as a counted disclosure. Each suggestion has one Add
  action; Not now and Don't suggest again live in its named options menu.
  Show one store selector when filtering is useful. While typing, keep the
  heading compact and focus on the draft and purchase-history completions.
- Group grocery rows on ivory paper with 16 dp outer corners and inset dividers.
  Quantity badges use the background colour and 6 dp corners. Keep check circles
  visible and completed names struck through. Preserve checkbox semantics.
- Keep rows at least 64 dp high and shopping mode rows at least 84 dp. Every icon
  action retains Material's 48 dp touch target. Text wraps where supported and
  respects system font scaling. A clipped list title retains its existing picker.
- Use a floating capture dock with a subtle hairline, 22 dp corners and no shadow.
  Add items is explicitly labelled; hold-to-speak remains labelled and scan
  stays secondary. Controls wrap into two rows on narrow screens or large text.
  Preserve press-and-hold voice capture, keyboard fallback and scanner pause.
- Supporting forms use outlined paper cards with 20 dp corners. Start item
  editing with name and quantity, then disclose optional details and secondary
  actions. Keep Save, Cancel and sheet Close outside the scrolling body.
  Favourites are compact reusable rows, with preferences and aisle order
  disclosed separately. History starts with search and purchases; data
  management stays in its options menu. Recipe review retains explicit selections;
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

## Browser hierarchy

The selected list is the page title. Its open heading carries a faint, soft-edged
collage crop on desktop and mobile, at the ambient artwork's opacity, so the title
and header icons sit directly on the page as on Android. The sync state, Replay,
History and List options are icon buttons beside the title; on narrow screens they
share the row below it with the item count. The artwork is decorative and does
not receive focus or intercept pointer input. The title opens list selection.
The capture field and its labelled Add button are the clear next step.
Items to pick up are grouped by category in Android's aisle order, each group with
its icon, name and count above its own card. The browser classifies with the same
input-language seeds (staged by the server build as `category-seeds.json`) and the
same shared per-item category choice; until the seeds load it shows one ungrouped
section. Quantities sit in a compact badge at the row's end. Picked-up rows have a
counted disclosure and Finish shopping remains reachable without expanding it.

Replay reviews factual recent purchases using the same eligibility and ordering
as Android. History, favourites, shopping mode, store filtering, repeat settings,
text sharing and move/copy actions are available from the list and item menus.
Favourites and hidden Replay suggestions belong to the signed-in account and
list in this browser; the server remains the authority for lists and purchases.

Navigation shows Lists and Account. Appearance, language and the guided tour
share Preferences, which keeps the mobile header to one small band. Account
starts with connecting a phone, then named groups for optional AI and security
settings. Forms retain explicit consent and review before external processing.
Dialogs have accessible titles, scrolling content, visible actions and focus
restoration. Text wraps even for unbroken names; controls have 48 px targets.


## List identity colors

Manage list offers List color with Automatic and six muted choices: plum, sage,
teal, slate, ochre and rose. The dialog previews the name before Save; Cancel
keeps the current choice. Automatic follows the selected app palette. Every
explicit hue is gently blended with that palette and has a light and dark shade.

The list name, picker swatch and category count pills use the list accent.
Category icons keep their category colors. The web client uses the same hue
identities on names, picker swatches and section counts. Labels and selection
controls accompany swatches, so color is never the only way to choose a list.
Colors belong to the shared list and follow it across clients and backups.

## Undo after a swipe

On Android, swiping an item away shows a bottom message with Undo and a dismiss
button. Further quick swipes join the same batch and restart the Undo window.
Undo reveals the original rows with their quantities, notes, checked state and
positions intact. Deletion is committed only after the message times out or is
dismissed, against the row versions that were swiped. Switching lists cancels
pending removal. If pending UI state is lost before a deletion commits, the stored
items remain.
