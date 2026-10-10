# Fire Island & Brooklyn Weather

A single fast-loading, mobile-friendly web page showing current conditions, the
next 24 hours, and a 7-day forecast (temperature, rain, wind) for
**Fire Island, NY** and **Brooklyn, NY**.

Forecasts come from the [Google Maps Platform Weather API](https://developers.google.com/maps/documentation/weather),
which is powered by Google DeepMind's **WeatherNext 3** model.

## Quick start

```bash
cp .env.example .env            # put your key in GOOGLE_MAPS_API_KEY
export $(grep -v '^#' .env | xargs)
npm start                       # http://localhost:3000
```

No key yet? Run with sample data to see the UI:

```bash
npm run dev                     # WEATHER_MOCK=1
```

Requires Node 18+. There are no npm dependencies to install.

## Getting an API key

1. In the [Google Cloud console](https://console.cloud.google.com/google/maps-apis/api-list)
   create or pick a project with billing enabled.
2. Enable the **Weather API**.
3. Create an API key under *Keys & Credentials*. Restrict it to the Weather API
   (and to your server's IP if you deploy it). The key is only ever used
   server-side, so no HTTP-referrer restriction is needed.
4. Set `GOOGLE_MAPS_API_KEY` in your environment or `.env`.

## How it works

- `server.js` is a dependency-free Node HTTP server. It fetches current
  conditions, hourly, and daily forecasts for both locations in parallel,
  normalizes them into a compact JSON snapshot, and caches it in memory.
- `GET /` returns a single self-contained HTML page with the latest snapshot
  **inlined**, so first paint needs exactly one request (about 8 KB gzipped).
- The **Refresh** button calls `GET /api/weather?refresh=1`, which refetches
  from Google. Refreshes closer together than `MIN_REFRESH_SECONDS` (default 60)
  serve the cached snapshot instead, so a tapped button can't burn through quota.
- Snapshots older than `CACHE_TTL_SECONDS` (default 600) are refetched
  automatically on the next request.
- `GET /healthz` reports server status.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `GOOGLE_MAPS_API_KEY` | | Required unless `WEATHER_MOCK=1` |
| `PORT` | `3000` | Listen port |
| `CACHE_TTL_SECONDS` | `600` | Auto-refetch age |
| `MIN_REFRESH_SECONDS` | `60` | Minimum gap between manual refreshes |
| `WEATHER_MOCK` | | `1` to use bundled sample data |

Locations are defined at the top of `lib/weather.js`. Add or change entries
there to track other places.

## Tests

```bash
npm test
```

## Deploying

Any host that runs Node works (Cloud Run, Fly.io, Railway, a VPS behind nginx).
Set `GOOGLE_MAPS_API_KEY` and `PORT` in the host's environment.

Forecasts are not official warnings. For watches and warnings use the
[National Weather Service](https://www.weather.gov/okx/).

## Deploy to Cloud Run (one command)

```bash
export GOOGLE_CLOUD_PROJECT=your-project-id
export GOOGLE_MAPS_API_KEY=your-key
./deploy.sh
```

`deploy.sh` enables the Run, Cloud Build, and Artifact Registry APIs, builds
the container from the `Dockerfile`, deploys it with public access, and prints
the service URL. Set `MIN_INSTANCES=1` to avoid cold starts (a few dollars a
month); the default of 0 is free-tier friendly. If
`GOOGLE_CLOUD_CREDENTIALS_JSON` holds a service-account key, the script logs in
with it first.
