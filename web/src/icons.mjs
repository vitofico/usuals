// Outline glyphs standing in for the Material icons Android shows at the same places.
const glyphs = {
  chevron: "M7 10l5 5 5-5",
  more: ["M12 5.5h.01M12 12h.01M12 18.5h.01", 3.2],
  history: "M12 8v4l2.8 2.8M6.34 6.34A8 8 0 1 1 4 12M1.8 9.8 4 12l2.2-2.2",
  replay: "M12 5a7 7 0 1 1-7 7M14.5 2.5 12 5l2.5 2.5",
  check: "M5 12.5l4.5 4.5L19 7.5",
  sync: "M19.5 12a7.5 7.5 0 0 1-13.4 4.6M4.5 12a7.5 7.5 0 0 1 13.4-4.6M18.5 3.5v4h-4M5.5 20.5v-4h4",
  offline: "M12 4 21 20H3zM12 10v4M12 17h.01",
  PRODUCE: "M5 19c0-8 5-13 14-14 0 9-5 14-13 14zM5 19l7-7",
  DAIRY: "M12 3c-3.5 0-6.5 6-6.5 10a6.5 6.5 0 0 0 13 0C18.5 9 15.5 3 12 3z",
  BAKERY: "M6 20h12v-8.5a3.5 3.5 0 0 0-.5-7h-11a3.5 3.5 0 0 0-.5 7zM10 8.5l-1 3M14 8.5l-1 3",
  MEAT: "M7 3v7a2 2 0 0 0 4 0V3M9 3v18M17 21V3c-2 1.5-3 4-3 7v3h3",
  FISH: "M3 12c2-3.3 5-5 8-5s5.5 1.7 7 5c-1.5 3.3-4 5-7 5s-6-1.7-8-5zM18 12l3-3.5v7zM7.5 11h.01",
  FROZEN: "M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9M9.5 4.5 12 7l2.5-2.5M9.5 19.5 12 17l2.5 2.5",
  PANTRY: "M7 3h10a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM6 10h12M9 6v1.5M9 13v3",
  DRINKS: "M4 8h12v6a5 5 0 0 1-5 5H9a5 5 0 0 1-5-5zM16 9.5h1.5a2.5 2.5 0 0 1 0 5H16M4 21h12",
  SNACKS: "M12 3a9 9 0 1 0 9 9 3 3 0 0 1-3.5-3A3 3 0 0 1 14 5.5 3 3 0 0 1 12 3zM8.5 9.5h.01M12 13h.01M8 15.5h.01M15.5 16h.01",
  HOUSEHOLD: "M9 8h5l1 3v9a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1v-9zM10 8V5h4M14 5l3-1.5M14 5h3.5",
  PERSONAL_CARE: "M12 20c-4 0-8-2.5-8-7 3 0 6 1.5 8 4 2-2.5 5-4 8-4 0 4.5-4 7-8 7zM12 17c-1.5-2-2-4-2-6s.8-4 2-6c1.2 2 2 4 2 6s-.5 4-2 6z",
  OTHER: "M5 8h14l-1 12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1zM9 10V7a3 3 0 0 1 6 0v3",
};
const SVG = "http://www.w3.org/2000/svg";

/** Decorative: the control that holds it carries the accessible name. */
export function icon(name, size = 24) {
  const [d, width] = Array.isArray(glyphs[name]) ? glyphs[name] : [glyphs[name], 1.8];
  const svg = document.createElementNS(SVG, "svg"), path = document.createElementNS(SVG, "path");
  for (const [key, value] of Object.entries({ viewBox: "0 0 24 24", width: size, height: size, fill: "none", stroke: "currentColor",
    "stroke-width": width, "stroke-linecap": "round", "stroke-linejoin": "round", "aria-hidden": "true", class: "icon" }))
    svg.setAttribute(key, String(value));
  path.setAttribute("d", d);
  svg.append(path);
  return svg;
}
