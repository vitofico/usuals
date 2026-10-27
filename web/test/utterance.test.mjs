import test from "node:test";
import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { utteranceParser } from "../src/utterance.mjs";

// The same packs the server build stages, read straight from :core:parse in file order.
const directory = new URL("../../core/parse/src/main/resources/input-language/", import.meta.url);
const packs = await Promise.all((await readdir(directory)).filter((name) => name.endsWith(".properties")).sort().map(async (name) =>
  Object.fromEntries((await readFile(new URL(name, directory), "utf8")).split("\n")
    .filter((line) => line.includes("=") && !line.startsWith("#"))
    .map((line) => [line.slice(0, line.indexOf("=")).trim(), line.slice(line.indexOf("=") + 1).trim()]))));
const parse = utteranceParser(packs);

const item = (name, quantity = null, unit = null, note = null) => ({ name, quantity, unit, note });
const litres = (name, amount, note = null) => item(name, amount, "LITRE", note);

// LocalUtteranceParserTest's table, row for row.
const cases = [
  ["tomates", [item("tomates")]],
  ["pan de payés", [item("pan de payés")]],
  ["aceite de oliva virgen extra", [item("aceite de oliva virgen extra")]],
  ["Leche Entera", [item("Leche Entera")]],
  ["añade tomates", [item("tomates")]],
  ["apunta pan", [item("pan")]],
  ["add milk", [item("milk")]],
  ["necesitamos huevos", [item("huevos")]],
  ["por favor añade sal", [item("sal")]],
  ["hay que comprar papel de cocina", [item("papel de cocina")]],
  ["remember to buy bread", [item("bread")]],
  ["el pan", [item("pan")]],
  ["dos litros de leche", [litres("leche", 2)]],
  ["2 litros de leche", [litres("leche", 2)]],
  ["1,5 l de leche", [litres("leche", 1.5)]],
  ["medio kilo de tomates", [item("tomates", 0.5, "KILOGRAM")]],
  ["2kg de patatas", [item("patatas", 2, "KILOGRAM")]],
  ["500 gramos de arroz", [item("arroz", 500, "GRAM")]],
  ["500g rice", [item("rice", 500, "GRAM")]],
  ["750 ml de aceite", [item("aceite", 750, "MILLILITRE")]],
  ["half a litre of milk", [litres("milk", 0.5)]],
  ["pack de cervezas", [item("cervezas", 1, "PACK")]],
  ["dos botellas de vino", [item("vino", 2, "PACK")]],
  ["tres manzanas", [item("manzanas", 3, "PIECE")]],
  ["3 manzanas", [item("manzanas", 3, "PIECE")]],
  ["un pan", [item("pan", 1, "PIECE")]],
  ["veinte huevos", [item("huevos", 20, "PIECE")]],
  ["dieciséis cervezas", [item("cervezas", 16, "PIECE")]],
  ["una docena de huevos", [item("huevos", 12, "PIECE")]],
  ["media docena de huevos", [item("huevos", 6, "PIECE")]],
  ["un par de cervezas", [item("cervezas", 2, "PIECE")]],
  ["medio pan", [item("pan", 0.5, "PIECE")]],
  ["leche x2", [item("leche", 2, "PIECE")]],
  ["coca cola 2l", [litres("coca cola", 2)]],
  ["leche 2 l", [litres("leche", 2)]],
  ["leche, huevos y pan", [item("leche"), item("huevos"), item("pan")]],
  ["milk and bread", [item("milk"), item("bread")]],
  ["leche;huevos", [item("leche"), item("huevos")]],
  ["leche\nhuevos\npan", [item("leche"), item("huevos"), item("pan")]],
  ["compra dos kilos de patatas y un litro de leche", [item("patatas", 2, "KILOGRAM"), litres("leche", 1)]],
  ["necesito 6 huevos, medio kilo de tomates y pan", [item("huevos", 6, "PIECE"), item("tomates", 0.5, "KILOGRAM"), item("pan")]],
  ["leche para el café", [item("leche", null, null, "para el café")]],
  ["milk for the coffee", [item("milk", null, null, "for the coffee")]],
  ["leche (desnatada)", [item("leche", null, null, "desnatada")]],
  ["pan (integral) para el desayuno", [item("pan", null, null, "integral, para el desayuno")]],
  ["dos litros de leche para el café", [litres("leche", 2, "para el café")]],
  ["", []], ["   ", []], ["añade", []], ["por favor", []], ["3", []], ["dos litros de", []],
  ["!!! ,,, ???", []], ["¿?", []], ["de", []], ["y y y", []], ["\n\n", []],
  ["añade tomates.", [item("tomates")]],
  ["¿huevos?", [item("huevos")]],
  ["  añade tomates  ", [item("tomates")]],
  ["AÑADE TOMATES", [item("TOMATES")]],
];

test("parses the shapes people actually say, as the app does", () => {
  for (const [utterance, expected] of cases) assert.deepEqual(parse(utterance), expected, utterance);
});

test("never throws and never returns a blank or letterless name", () => {
  const hostile = ["", " ", "\n", "\t\t", "((((", "))))", "()", "( )", "[]", "\\", "//", ",,,,", ";;;;", " y ", " and ", " e ",
    "x2", "×3", "2l", "500g", "1,5", "1.5.5", "0,,5 l", "leche (", "leche )", "leche ((doble))", "🍅", "🍅 tomates", "áéíóú",
    "ñ", "ÑÑÑ", "-", "----", "'", "\"", "un un un", "medio medio", "docena docena", "de de de", "a".repeat(500),
    "1".repeat(500), "leche ".repeat(200), "AÑADE, apunta; compra y trae", "para", "para el café", null, 42];
  for (const utterance of hostile)
    for (const parsed of parse(utterance)) assert.ok(parsed.name.trim() && /\p{L}/u.test(parsed.name), String(utterance));
});

test("keeps the person's words and a decimal comma", () => {
  assert.deepEqual(parse("añade Pan de Payés"), [item("Pan de Payés")]);
  assert.deepEqual(parse("1,5 l de leche"), [litres("leche", 1.5)]);
});
