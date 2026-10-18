# Input language packs

These UTF-8 properties files are independent of Android UI translations. Add a
file named for a standard ISO language code, such as `pt.properties`, and tests
for its vocabulary. The classpath loader discovers it without a registry edit.

`numbers` and `multipliers` map words to numeric values. `units` maps words to
`MeasureUnit` enum names. Other comma-separated fields supply noise, connectors,
articles, conjunctions, note markers and normalization filler. `plural-rules`
contains ordered `regex=>replacement` entries separated by semicolons, with
`plural-exceptions` protected first. Existing Spanish plural rules are preserved;
add rules for another language only after real input shows they are needed.

`category.<Category enum name>` fields contain category seeds, consumed by the
smart module. `recipe-measures` preserves unsupported source measures, and
`pantry-staples` controls which reviewed recipe rows start unticked. These all
live in the same language data file. UI copy belongs exclusively to the app
values resources.

English, Spanish, Italian, French and German are bundled. For example:

```text
aggiungi due litri di latte e pane
ajoute deux litres de lait et pain
kaufe zwei Liter Milch und Brot
```

Use folded spellings for grammar tokens: lowercase and unaccented, preserving
Spanish ñ. Category seeds may use natural or folded spelling because the
classifier normalizes them. Note markers preserve the original spelling and
need accented variants when relevant. Recipe measures may be single or multiple tokens; the longest matching entry
wins, with numeric or spoken quantities. Ambiguous conversions stay in notes.

The three new packs deliberately leave normalization filler/plural fields empty.
Adding capture vocabulary must preserve existing identities; changing those
fields requires a normalizer version/migration decision and regression evidence.
The regression test compares every existing number/unit/multiplier mapping and
all normalization fields against the original English/Spanish packs.
