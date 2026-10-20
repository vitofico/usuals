# Translating Usuals

Usuals welcomes translation fixes and new languages. You can edit the XML in
any text editor. You do not need Android Studio, a model API key or a server.
English is the source language in `app/src/main/res/values/`. Spanish, Italian,
French and German have complete `values-es/`, `values-it/`, `values-fr/` and
`values-de/` folders. Each folder uses the same feature filenames as English.

## Improve an existing language

1. Find the English sentence in `app/src/main/res/values/strings*.xml`. Its
   `name` is the stable key; search for that key in the language folder.
2. Edit only the translated text, keeping the key and resource type unchanged.
   A correction can be as small as one sentence.
3. Explain the wording or regional usage in your contribution. For layout
   fixes, include the screen, device/font size and a screenshot if possible.

Translations follow the same [contribution and license rules](../CONTRIBUTING.md),
including the CLA. The maintainer publishes this local branch. Once the
repository is published, translation changes can be submitted as pull requests.
Native speakers are welcome to review wording and improve these translations.

## Add a language

With Python 3, run this from the repository root, for example for Portuguese:

```sh
scripts/new-translation pt
```

This copies the current translatable English resources into `values-pt/`.
It refuses to overwrite an existing folder. Translate every entry before
submitting; the generated English text is a starting point, not a translation.
Brand names marked `translatable="false"` are omitted and use the English source.
The helper creates only XML files in the new locale folder.

You can also copy the translatable resources manually. Language-only folders
such as `values-pt/` usually reach the most people. Use `pt-rBR` for a regional
variant or `b+zh+Hans` for an Android BCP 47 script qualifier when needed.
Do not add Kotlin code, a language registry or a hand-written locale list.
The build discovers new language folders automatically. English remains the
fallback for unsupported languages.

## Preserve the resource contract

- Keep resource `name` attributes, file organization and types unchanged.
  Add every translatable key, including accessibility labels and error messages.
  Do not copy colors, themes or resources marked `translatable="false"`.
- Preserve format arguments such as `%1$s`, `%2$d` and `%d`. `s` is text; `d` is
  an integer. Numbered arguments can move to suit the sentence. For example,
  `%1$s · %2$s` can become `%2$s · %1$s`. Do not translate or drop the arguments.
  Use `%%` for a literal percent sign in a formatted string.
- Keep the `<plurals>` container and always include `quantity="other"`. Translate
  the relevant `zero`, `one`, `two`, `few`, `many` and `other` forms for your
  language. Android chooses the grammatical form, so do not assume `one` means
  only the number 1. Each form keeps the corresponding English arguments;
  extra forms use the English `other` form as their argument contract.
- Keep array entries in their original order. Preserve `\n` line breaks and
  symbols used as controls, such as the unchecked box in shared text.
- Android needs straight apostrophes escaped as `\'` and literal double quotes
  as `\"`. Typographic apostrophes such as `’` need no escaping. Escape XML
  characters, for example `&amp;` for `&`. Keep the quoted `", "` separator
  quoted so Android preserves its trailing space.
- Use sentence case, familiar verbs and concise buttons. Avoid exclamation
  marks and em dashes. Leave Usuals, Open Food Facts, ODbL and provider/model
  names unchanged. Translate the surrounding explanation.
- Keep privacy and recovery instructions precise. Distinguish a local copy
  from a shared list, the recovery file from its separately stored key, and
  deleting this device’s copy from revoking another device. Never strengthen
  a tentative suggestion into a promise or an allergen warning into a guarantee.

## Shared vocabulary

The browser follows the same resource-only approach: copy `web/locales/en.json`
to `web/locales/xx.json`, translate all values and retain each `{placeholder}`.
Set `nativeName` to the language's own name. Packaging discovers catalogs and the
picker automatically. `scripts/dgradle -p server verifyWebInterop` checks catalog
parity. Android translations still require only their `values-xx/` files; browser
translations require only their locale JSON file. See [web development](../web/README.md).

Use the existing translations as the starting glossary, especially in sharing
and recovery screens. Italian uses “gruppo” for the people sharing lists.

| English | Spanish | Italian | French | German |
|---|---|---|---|---|
| Shopping list | Lista de la compra | Lista della spesa | Liste de courses | Einkaufsliste |
| Finish shop | Terminar compra | Concludi la spesa | Terminer les courses | Einkauf abschließen |
| History | Historial | Cronologia | Historique | Verlauf |
| Household | Hogar | Gruppo | Foyer | Haushalt |
| Recovery kit | Kit de recuperación | Kit di recupero | Kit de récupération | Wiederherstellungskit |
| Recovery key | Clave de recuperación | Chiave di recupero | Clé de récupération | Wiederherstellungsschlüssel |
| Local copy | Copia local | Copia locale | Copie locale | Lokale Kopie |
| Device fingerprint | Huella del dispositivo | Impronta del dispositivo | Empreinte de l’appareil | Gerätefingerabdruck |

## Check your changes

The focused Docker check needs only Docker and the repository:

```sh
scripts/dgradle :app:testDebugUnitTest --tests '*StringParityTest' --no-watch-fs
```

It discovers every locale and reports missing/unexpected keys, duplicates,
blank text, invalid or mismatched format arguments, missing plural `other` and
array-entry changes. It allows reordered numbered arguments. These checks also
run with the normal test suite in CI. They check structure, not translation
quality: please read the whole screen in context.

For a complete local build and lint check:

```sh
scripts/dgradle :app:assembleDebug test lint --no-watch-fs
```

On Android 13 or later, select the language in Android Settings, Apps, Usuals,
Language. Older Android versions use the device’s preferred supported language.
Check the list, item editor, Settings, recipe review, scanner, sharing/recovery
warnings and widget, including singular/plural counts and larger font sizes.
Return the app to its previous language afterwards if testing someone else’s
emulator or device. Sharing and recovery screens block screenshots to protect
keys; describe wording or layout issues there without disabling that protection.

UI language and input recognition are separate. A UI translation does not add
recipe/parser vocabulary or change saved item names, purchase history or shared
data. English, Spanish, Italian, French and German input work together, independently
of the UI locale. New input-language packs have
separate [format notes](../core/parse/src/main/resources/input-language/README.md)
and vocabulary tests; they are not required to contribute a UI translation.
