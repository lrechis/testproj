#!/usr/bin/env node
// Fetches a 7-day hourly surf forecast for the ocean off Fire Island from the
// Open-Meteo Marine API and writes it as waves.json next to index.html.
//
//   node scripts/fetch-waves.mjs [output path]
//
// The page reads waves.json (relative fetch), so republishing the artifact with
// this file refreshes the surf layer. No API key is needed.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const out = process.argv[2] || path.join(here, "..", "waves.json");

// A point in open water about 2 nautical miles south of the Fire Island beach,
// between Democrat Point and Moriches Inlet.
const LAT = 40.6;
const LON = -73.1;

const params = new URLSearchParams({
  latitude: String(LAT),
  longitude: String(LON),
  hourly: [
    "wave_height",
    "wave_period",
    "wave_direction",
    "swell_wave_height",
    "swell_wave_period",
    "wind_wave_height",
  ].join(","),
  timezone: "UTC",
  forecast_days: "7",
  past_days: "1",
});
const url = `https://marine-api.open-meteo.com/v1/marine?${params}`;

const M_TO_FT = 3.28084;
const round = (n, d = 1) => (n == null ? null : Math.round(n * 10 ** d) / 10 ** d);

const res = await fetch(url, { headers: { accept: "application/json" } });
if (!res.ok) {
  console.error(`Open-Meteo responded ${res.status} ${res.statusText}`);
  process.exit(1);
}
const data = await res.json();
const h = data.hourly;
if (!h || !Array.isArray(h.time)) {
  console.error("Unexpected response shape", JSON.stringify(data).slice(0, 300));
  process.exit(1);
}

const hourly = h.time.map((t, i) => ({
  t: t.endsWith("Z") ? t : `${t}:00Z`,
  h: round(h.wave_height?.[i] * M_TO_FT),
  p: round(h.wave_period?.[i]),
  d: h.wave_direction?.[i] ?? null,
  sh: round(h.swell_wave_height?.[i] * M_TO_FT),
  sp: round(h.swell_wave_period?.[i]),
  wh: round(h.wind_wave_height?.[i] * M_TO_FT),
}));

const doc = {
  source: "Open-Meteo Marine API (https://open-meteo.com)",
  model: "Open-Meteo marine forecast",
  generated: new Date().toISOString(),
  lat: LAT,
  lon: LON,
  units: { h: "ft", p: "s", d: "deg", sh: "ft", sp: "s", wh: "ft" },
  hourly,
};

fs.writeFileSync(out, JSON.stringify(doc));
console.log(`wrote ${out}: ${hourly.length} hours, ${hourly[0]?.t} to ${hourly.at(-1)?.t}`);
