import { stationsById } from "@neaps/tide-database";
import { useStation } from "@neaps/tide-predictor";
import fs from "node:fs";
const picks = [
  { id: "noaa/8515228", label: "Democrat Point (west end, Robert Moses side)" },
  { id: "noaa/8513398", label: "Moriches Inlet (east end, Smith Point side)" },
];
const r = n => Math.round(n * 10000) / 10000;
const out = picks.map(p => {
  const s = stationsById.get(p.id);
  const d = s.datums;
  const keep = ["MLLW","MLW","MSL","MTL","MHW","MHHW","MN","GT","LAT","HAT","STND"];
  const datums = Object.fromEntries(keep.filter(k => d[k] != null).map(k => [k, r(d[k])]));
  return {
    id: s.id, label: p.label, name: s.name, type: s.type, latitude: r(s.latitude), longitude: r(s.longitude),
    timezone: s.timezone, chart_datum: s.chart_datum, datums,
    offsets: s.offsets ? { time: s.offsets.time, height: { high: r(s.offsets.height.high), low: r(s.offsets.height.low), type: s.offsets.height.type }, reference: s.offsets.reference, referenceName: stationsById.get(s.offsets.reference)?.name } : undefined,
    attribution: s.attribution, source: s.source,
    harmonic_constituents: s.harmonic_constituents.map(c => ({ name: c.name, amplitude: r(c.amplitude), phase: r(c.phase), speed: c.speed })),
  };
});
fs.writeFileSync("stations.compact.json", JSON.stringify(out));
console.log("bytes", fs.statSync("stations.compact.json").size);
// verify the compact copy predicts identically to the DB copy
for (const st of out) {
  const p = useStation(st);
  const ex = p.getExtremesPrediction({ start: new Date("2026-10-26T04:00:00Z"), end: new Date("2026-10-27T04:00:00Z"), units: "feet", datum: "MLLW" });
  console.log(st.name, ex.extremes.map(e => e.label[0] + " " + e.time.toLocaleTimeString("en-US",{timeZone:"America/New_York",hour:"numeric",minute:"2-digit"}) + " " + e.level.toFixed(2)).join(" | "));
  const tl = p.getTimelinePrediction({ start: new Date("2026-10-26T04:00:00Z"), end: new Date("2026-10-26T06:00:00Z"), timeFidelity: 1800, units: "feet", datum: "MLLW" });
  console.log("  timeline sample", tl.timeline.map(t => t.level.toFixed(2)).join(","), "datum", tl.datum, tl.units);
  console.log("  attribution:", st.attribution);
}
