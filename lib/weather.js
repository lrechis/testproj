// Fetches and normalizes forecasts from the Google Maps Platform Weather API
// (forecasts powered by Google DeepMind WeatherNext 3).
//
// Endpoints used (all GET, key passed as ?key=):
//   https://weather.googleapis.com/v1/currentConditions:lookup
//   https://weather.googleapis.com/v1/forecast/hours:lookup   (up to 240 h)
//   https://weather.googleapis.com/v1/forecast/days:lookup    (up to 10 d)

'use strict';

const BASE = 'https://weather.googleapis.com/v1';
const HOURS = 24;
const DAYS = 7;

const LOCATIONS = [
  {
    id: 'fire-island',
    name: 'Fire Island',
    subtitle: 'Ocean Beach, NY',
    lat: 40.6456,
    lon: -73.1577,
  },
  {
    id: 'brooklyn',
    name: 'Brooklyn',
    subtitle: 'Brooklyn, NY',
    lat: 40.6782,
    lon: -73.9442,
  },
];

const SOURCE = {
  provider: 'Google Maps Platform Weather API',
  model: 'Google DeepMind WeatherNext 3',
  units: 'imperial',
};

// ---------------------------------------------------------------------------
// Raw API access
// ---------------------------------------------------------------------------
async function apiGet(apiKey, endpoint, params) {
  const url = new URL(`${BASE}/${endpoint}`);
  url.searchParams.set('key', apiKey);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, String(v));

  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 15000);
  let res;
  try {
    res = await fetch(url, { signal: ctrl.signal, headers: { Accept: 'application/json' } });
  } catch (err) {
    throw Object.assign(new Error(`Weather API network error (${endpoint}): ${err.message}`), { status: 502 });
  } finally {
    clearTimeout(timer);
  }

  const text = await res.text();
  let body;
  try {
    body = text ? JSON.parse(text) : {};
  } catch {
    body = { raw: text.slice(0, 300) };
  }
  if (!res.ok) {
    const msg = body?.error?.message || body?.raw || res.statusText;
    throw Object.assign(new Error(`Weather API ${res.status} on ${endpoint}: ${msg}`), { status: res.status });
  }
  return body;
}

async function fetchRaw(apiKey, loc) {
  const base = {
    'location.latitude': loc.lat,
    'location.longitude': loc.lon,
    unitsSystem: 'IMPERIAL',
    languageCode: 'en',
  };
  const [current, hourly, daily] = await Promise.all([
    apiGet(apiKey, 'currentConditions:lookup', base),
    apiGet(apiKey, 'forecast/hours:lookup', { ...base, hours: HOURS, pageSize: HOURS }),
    apiGet(apiKey, 'forecast/days:lookup', { ...base, days: DAYS, pageSize: DAYS }),
  ]);
  return { current, hourly, daily };
}

// ---------------------------------------------------------------------------
// Normalization: trim the verbose API payload to what the page renders.
// Every accessor is defensive; the API omits fields it has no data for.
// ---------------------------------------------------------------------------
const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const round = (v, d = 0) => (num(v) === null ? null : Number(v.toFixed(d)));

function condition(c) {
  if (!c) return { type: 'UNKNOWN', text: '' };
  return { type: c.type || 'UNKNOWN', text: c.description?.text || '' };
}

function wind(w) {
  if (!w) return null;
  return {
    speed: round(w.speed?.value),
    gust: round(w.gust?.value),
    dirDeg: num(w.direction?.degrees),
    dir: cardinal(w.direction?.cardinal, w.direction?.degrees),
  };
}

function cardinal(apiCardinal, degrees) {
  if (apiCardinal && apiCardinal !== 'CARDINAL_DIRECTION_UNSPECIFIED') {
    // e.g. NORTH_NORTHEAST -> NNE, SOUTH -> S
    return apiCardinal
      .split('_')
      .map((w) => w[0])
      .join('');
  }
  if (num(degrees) === null) return null;
  const names = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
  return names[Math.round(degrees / 45) % 8];
}

function precip(p) {
  if (!p) return null;
  return {
    chance: num(p.probability?.percent),
    type: p.probability?.type || null,
    qpf: round(p.qpf?.quantity, 2), // inches (liquid equivalent)
    snow: round(p.snowQpf?.quantity, 2),
  };
}

function normalizeCurrent(c) {
  if (!c) return null;
  return {
    time: c.currentTime || null,
    isDaytime: c.isDaytime !== false,
    condition: condition(c.weatherCondition),
    temp: round(c.temperature?.degrees),
    feelsLike: round(c.feelsLikeTemperature?.degrees),
    dewPoint: round(c.dewPoint?.degrees),
    humidity: num(c.relativeHumidity),
    uv: num(c.uvIndex),
    cloudCover: num(c.cloudCover),
    thunder: num(c.thunderstormProbability),
    pressure: round(c.airPressure?.meanSeaLevelMillibars),
    visibility: round(c.visibility?.distance, 1),
    wind: wind(c.wind),
    precip: precip(c.precipitation),
    todayHi: round(c.currentConditionsHistory?.maxTemperature?.degrees),
    todayLo: round(c.currentConditionsHistory?.minTemperature?.degrees),
  };
}

function normalizeHourly(h) {
  const hours = Array.isArray(h?.forecastHours) ? h.forecastHours : [];
  return hours.slice(0, HOURS).map((x) => {
    const d = x.displayDateTime || {};
    return {
      time: x.interval?.startTime || null,
      hour: num(d.hours), // local hour 0-23 at the location
      day: num(d.day),
      isDaytime: x.isDaytime !== false,
      condition: condition(x.weatherCondition),
      temp: round(x.temperature?.degrees),
      feelsLike: round(x.feelsLikeTemperature?.degrees),
      humidity: num(x.relativeHumidity),
      thunder: num(x.thunderstormProbability),
      wind: wind(x.wind),
      precip: precip(x.precipitation),
    };
  });
}

function normalizeDaily(d) {
  const days = Array.isArray(d?.forecastDays) ? d.forecastDays : [];
  return days.slice(0, DAYS).map((x) => {
    const dd = x.displayDate || {};
    const day = x.daytimeForecast || {};
    const night = x.nighttimeForecast || {};
    const pd = precip(day.precipitation);
    const pn = precip(night.precipitation);
    const chance = Math.max(pd?.chance ?? -1, pn?.chance ?? -1);
    const qpfSum = (pd?.qpf ?? 0) + (pn?.qpf ?? 0);
    return {
      date:
        dd.year && dd.month && dd.day
          ? `${dd.year}-${String(dd.month).padStart(2, '0')}-${String(dd.day).padStart(2, '0')}`
          : null,
      condition: condition(day.weatherCondition),
      nightCondition: condition(night.weatherCondition),
      hi: round(x.maxTemperature?.degrees),
      lo: round(x.minTemperature?.degrees),
      precip: { chance: chance < 0 ? null : chance, qpf: round(qpfSum, 2), type: pd?.type || pn?.type || null },
      wind: wind(day.wind),
      nightWind: wind(night.wind),
      humidity: num(day.relativeHumidity),
      uv: num(day.uvIndex),
      thunder: Math.max(num(day.thunderstormProbability) ?? 0, num(night.thunderstormProbability) ?? 0),
      sunrise: x.sunEvents?.sunriseTime || null,
      sunset: x.sunEvents?.sunsetTime || null,
    };
  });
}

function normalize(loc, raw) {
  return {
    id: loc.id,
    name: loc.name,
    subtitle: loc.subtitle,
    lat: loc.lat,
    lon: loc.lon,
    timeZone: raw.current?.timeZone?.id || raw.hourly?.timeZone?.id || 'America/New_York',
    current: normalizeCurrent(raw.current),
    hourly: normalizeHourly(raw.hourly),
    daily: normalizeDaily(raw.daily),
  };
}

// ---------------------------------------------------------------------------
// Snapshot builder: one call fetches every location in parallel.
// ---------------------------------------------------------------------------
async function buildSnapshot({ apiKey, mock }) {
  const getRaw = mock ? require('./mock').fetchRaw : (loc) => fetchRaw(apiKey, loc);
  const results = await Promise.allSettled(LOCATIONS.map((loc) => getRaw(loc)));

  const locations = results.map((r, i) => {
    const loc = LOCATIONS[i];
    if (r.status === 'fulfilled') return normalize(loc, r.value);
    return { id: loc.id, name: loc.name, subtitle: loc.subtitle, error: String(r.reason?.message || r.reason) };
  });

  // If every location failed, surface the first error to the caller.
  if (locations.every((l) => l.error)) {
    const first = results.find((r) => r.status === 'rejected');
    throw first.reason;
  }

  return {
    fetchedAt: Date.now(),
    source: { ...SOURCE, mock: Boolean(mock) },
    locations,
  };
}

module.exports = { LOCATIONS, SOURCE, buildSnapshot, normalize, fetchRaw };
