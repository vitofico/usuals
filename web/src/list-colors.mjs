export const listColors = ["plum", "sage", "teal", "slate", "ochre", "rose"];
export const appearanceKey = "usuals:list-appearance";
export function listColor(projection) {
  const value = projection.preferences?.[appearanceKey]?.color;
  return listColors.includes(value) ? value : "automatic";
}
export function colorChange(projection, color) {
  if (color !== "automatic" && !listColors.includes(color)) throw new Error("invalid_color");
  return { color: color === "automatic" ? null : color, expected: projection.preferences?.[appearanceKey]?.revision ?? null };
}
