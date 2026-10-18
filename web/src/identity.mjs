// Version 1 identity rules mirror the unchanged English and Spanish data packs.
const filler = new Set(
  "of,the,a,some,de,del,la,el,los,las,un,una,unos,unas".split(","),
);
const exceptions = new Set(
  "gas,mes,pais,anis,cuscus,brindis,atun,autobus,paraguas,sacacorchos,cumpleaños,virus,atlas,tos,lunes,martes,miercoles,jueves,viernes,tres,seis,payes,frances,ingles,interes,reves".split(
    ",",
  ),
);
export function normalize(raw) {
  let unaccented = "";
  for (const c of raw.toLowerCase().normalize("NFD"))
    if (c === "\u0303" && unaccented.endsWith("n")) unaccented += c;
    else if (!/\p{M}/u.test(c)) unaccented += c;
  const words = unaccented
      .normalize("NFC")
      .split("")
      .map((character) => (/[\p{L}\p{Nd}]/u.test(character) ? character : " "))
      .join("")
      .replace(/ +/g, " ")
      .trim()
      .split(" ")
      .filter(Boolean),
    content = words.filter((x) => !filler.has(x)),
    kept = content.length ? content : words;
  return kept
    .map((word) => {
      if (word.length < 4 || exceptions.has(word)) return word;
      for (const pattern of [
        /^(.*[aeiou])ces$/,
        /^(.+[aeiou][nlrdjsyz])es$/,
        /^(.+[aeiou])s$/,
      ])
        if (pattern.test(word))
          return word.replace(
            pattern,
            pattern.source.includes("ces") ? "$1z" : "$1",
          );
      return word;
    })
    .join(" ");
}
