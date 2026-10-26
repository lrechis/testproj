# Fire Island Beach Drive

A one-page tide and surf dashboard for driving the ocean beach on Fire Island, NY.
It shows the day's tide curve, the surf forecast, a colour band scoring how
driveable the sand is hour by hour, and lays your plans (hand-entered or from
Google Calendar) over the tide cycle so you can see whether each one lands on
a good window.

## How it works

- **Tides** are computed in the browser from NOAA harmonic constituents, so the
  page needs no tide API and works for any date. Two NOAA stations bracket the
  ocean beach: Democrat Point (west end, a subordinate of Sandy Hook) and
  Moriches Inlet (east end). Station data comes from the
  [Neaps tide database](https://github.com/openwatersio/tide-database) and the
  maths from [`@neaps/tide-predictor`](https://github.com/openwatersio/neaps),
  loaded from jsDelivr.
- **Surf** comes from `waves.json`, a 7-day hourly forecast for open water off
  the beach, produced by `scripts/fetch-waves.mjs` from the Open-Meteo Marine
  API. Republishing the artifact with a fresh `waves.json` refreshes the surf
  layer. If no forecast covers a day, the page scores on tide alone, and you can
  type in the day's surf by hand.
- **Drive score**: a tide step (how far up the beach the water sits, scaled to
  the station's normal range) plus a surf step, plus one more step when the tide
  is rising past mid-tide. 0–1 Easy, 2 Doable, 3–4 Tough, 5+ Avoid.
- **Plans and manual surf entries** are stored in the artifact's shared
  database (`plans` and `surf_manual` collections), so they follow you across
  devices. Google Calendar events load through the viewer's own connector.

## Files

| File | Purpose |
|---|---|
| `index.template.html` | The page source, with a `__STATIONS__` placeholder |
| `stations.json` | Harmonic constituents, datums and offsets for the two stations |
| `build.py` | Inlines `stations.json` into the template to produce `index.html` |
| `index.html` | The built page that gets published |
| `waves.json` | Surf forecast consumed by the page |
| `scripts/fetch-waves.mjs` | Writes `waves.json` from Open-Meteo (Node 18+) |
| `scripts/extract-stations.mjs` | Regenerates `stations.json` from `@neaps/tide-database` |

## Refreshing the surf forecast

```sh
node scripts/fetch-waves.mjs          # writes waves.json
```

Then republish the artifact with `waves.json` alongside the page.

## Caveats

Tides are astronomical predictions and ignore storm surge and wind setup. The
surf number is a model forecast for open water, not the shore break. Check the
park's beach-driving status and the NWS surf zone forecast before you go.
