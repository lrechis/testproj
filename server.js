// WeatherNext 3 dashboard server.
// Zero dependencies: Node 18+ (uses global fetch, zlib, http).
//
// Data source: Google Maps Platform Weather API, whose forecasts are powered by
// Google DeepMind's WeatherNext 3 model. The API key never leaves this server;
// the browser only ever talks to /api/weather.

'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

const { LOCATIONS, buildSnapshot } = require('./lib/weather');

const PORT = Number(process.env.PORT || 3000);
const HOST = process.env.HOST || '0.0.0.0';
const API_KEY = process.env.GOOGLE_MAPS_API_KEY || process.env.WEATHER_API_KEY || '';
const MOCK = /^(1|true|yes)$/i.test(process.env.WEATHER_MOCK || '');
const CACHE_TTL_MS = Number(process.env.CACHE_TTL_SECONDS || 600) * 1000; // 10 min
const MIN_REFRESH_MS = Number(process.env.MIN_REFRESH_SECONDS || 60) * 1000; // protect quota

const PUBLIC_DIR = path.join(__dirname, 'public');
const INDEX_HTML = fs.readFileSync(path.join(PUBLIC_DIR, 'index.html'), 'utf8');
const INDEX_MARKER = '<!--WEATHER_DATA-->';

if (!API_KEY && !MOCK) {
  console.error(
    'No API key. Set GOOGLE_MAPS_API_KEY (Weather API enabled on the key), ' +
      'or run with WEATHER_MOCK=1 to use bundled sample data.'
  );
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Cache: one snapshot for all locations, refreshed on demand or when stale.
// ---------------------------------------------------------------------------
let snapshot = null; // { fetchedAt, locations: [...] }
let inflight = null; // Promise, dedupes concurrent refreshes
let lastForcedRefresh = 0;

async function getSnapshot({ force = false } = {}) {
  const now = Date.now();
  const fresh = snapshot && now - snapshot.fetchedAt < CACHE_TTL_MS;
  const canForce = now - lastForcedRefresh >= MIN_REFRESH_MS;

  if (fresh && !(force && canForce)) return { data: snapshot, cached: true };
  if (inflight) return { data: await inflight, cached: false };

  if (force) lastForcedRefresh = now;
  inflight = buildSnapshot({ apiKey: API_KEY, mock: MOCK })
    .then((data) => {
      snapshot = data;
      return data;
    })
    .finally(() => {
      inflight = null;
    });

  try {
    return { data: await inflight, cached: false };
  } catch (err) {
    // If a refresh fails but we still hold an older snapshot, serve it.
    if (snapshot) return { data: snapshot, cached: true, error: String(err.message || err) };
    throw err;
  }
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------
function send(req, res, status, body, headers = {}) {
  const buf = Buffer.isBuffer(body) ? body : Buffer.from(body);
  const accept = String(req.headers['accept-encoding'] || '');
  const h = { 'Content-Length': buf.length, ...headers };
  if (buf.length > 512 && /\bgzip\b/.test(accept)) {
    const gz = zlib.gzipSync(buf, { level: 6 });
    h['Content-Encoding'] = 'gzip';
    h['Content-Length'] = gz.length;
    h.Vary = 'Accept-Encoding';
    res.writeHead(status, h);
    res.end(req.method === 'HEAD' ? undefined : gz);
    return;
  }
  res.writeHead(status, h);
  res.end(req.method === 'HEAD' ? undefined : buf);
}

function sendJson(req, res, status, obj, extra = {}) {
  send(req, res, status, JSON.stringify(obj), {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    ...extra,
  });
}

function renderIndex(data) {
  // Inline the warm snapshot so first paint needs exactly one request.
  const inline = data
    ? `<script id="weather-data" type="application/json">${JSON.stringify(data).replace(/</g, '\\u003c')}</script>`
    : '';
  return INDEX_HTML.replace(INDEX_MARKER, inline);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const started = Date.now();
  res.on('finish', () => {
    console.log(`${new Date().toISOString()} ${req.method} ${url.pathname}${url.search} ${res.statusCode} ${Date.now() - started}ms`);
  });

  if (req.method !== 'GET' && req.method !== 'HEAD') {
    return send(req, res, 405, 'Method Not Allowed', { Allow: 'GET, HEAD' });
  }

  try {
    if (url.pathname === '/' || url.pathname === '/index.html') {
      // Serve immediately with whatever we have; the page fetches if needed.
      let data = snapshot;
      if (!data || Date.now() - data.fetchedAt >= CACHE_TTL_MS) {
        try {
          data = (await getSnapshot()).data;
        } catch (err) {
          console.error('initial fetch failed:', err.message || err);
          data = null;
        }
      }
      return send(req, res, 200, renderIndex(data), {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'no-cache',
      });
    }

    if (url.pathname === '/api/weather') {
      const force = url.searchParams.get('refresh') === '1';
      const { data, cached, error } = await getSnapshot({ force });
      return sendJson(req, res, 200, { ...data, cached, ...(error ? { warning: error } : {}) });
    }

    if (url.pathname === '/healthz') {
      return sendJson(req, res, 200, { ok: true, mock: MOCK, haveSnapshot: Boolean(snapshot) });
    }

    if (url.pathname === '/manifest.webmanifest') {
      return send(req, res, 200, fs.readFileSync(path.join(PUBLIC_DIR, 'manifest.webmanifest')), {
        'Content-Type': 'application/manifest+json',
        'Cache-Control': 'public, max-age=86400',
      });
    }

    if (url.pathname === '/icon.svg') {
      return send(req, res, 200, fs.readFileSync(path.join(PUBLIC_DIR, 'icon.svg')), {
        'Content-Type': 'image/svg+xml',
        'Cache-Control': 'public, max-age=604800',
      });
    }

    return send(req, res, 404, 'Not Found', { 'Content-Type': 'text/plain' });
  } catch (err) {
    console.error(err);
    const status = err.status && err.status >= 400 && err.status < 600 ? err.status : 502;
    return sendJson(req, res, status, { error: String(err.message || err) });
  }
});

server.listen(PORT, HOST, () => {
  console.log(`WeatherNext 3 dashboard listening on http://${HOST}:${PORT} ${MOCK ? '(MOCK DATA)' : ''}`);
  console.log(`Locations: ${LOCATIONS.map((l) => l.name).join(', ')}`);
  // Warm the cache so the first visitor gets an inlined page.
  getSnapshot().catch((err) => console.error('warm-up failed:', err.message || err));
});
