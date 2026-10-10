'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { LOCATIONS, normalize, buildSnapshot } = require('../lib/weather');
const mock = require('../lib/mock');

test('normalize trims raw API payload into the page shape', async () => {
  const loc = LOCATIONS[0];
  const raw = await mock.fetchRaw(loc);
  const out = normalize(loc, raw);

  assert.equal(out.id, 'fire-island');
  assert.equal(out.timeZone, 'America/New_York');

  assert.equal(typeof out.current.temp, 'number');
  assert.equal(typeof out.current.wind.speed, 'number');
  assert.equal(typeof out.current.wind.gust, 'number');
  assert.match(out.current.wind.dir, /^[NESW]{1,3}$/);
  assert.ok(out.current.precip.chance >= 0 && out.current.precip.chance <= 100);

  assert.equal(out.hourly.length, 24);
  assert.ok(out.hourly.every((h) => h.hour >= 0 && h.hour <= 23));
  assert.ok(out.hourly.every((h) => typeof h.temp === 'number'));

  assert.equal(out.daily.length, 7);
  assert.match(out.daily[0].date, /^\d{4}-\d{2}-\d{2}$/);
  assert.ok(out.daily.every((d) => d.hi >= d.lo));
  assert.ok(out.daily.every((d) => d.precip.chance !== null));
  assert.ok(out.daily[0].sunrise);
});

test('normalize tolerates missing fields', () => {
  const out = normalize(LOCATIONS[1], { current: {}, hourly: {}, daily: {} });
  assert.equal(out.current.temp, null);
  assert.equal(out.current.wind, null);
  assert.equal(out.current.condition.type, 'UNKNOWN');
  assert.deepEqual(out.hourly, []);
  assert.deepEqual(out.daily, []);
});

test('cardinal direction falls back to degrees', () => {
  const out = normalize(LOCATIONS[1], {
    current: { wind: { direction: { degrees: 270 }, speed: { value: 5 } } },
  });
  assert.equal(out.current.wind.dir, 'W');
  assert.equal(out.current.wind.speed, 5);
});

test('buildSnapshot in mock mode returns both locations', async () => {
  const snap = await buildSnapshot({ apiKey: '', mock: true });
  assert.equal(snap.locations.length, 2);
  assert.deepEqual(
    snap.locations.map((l) => l.name),
    ['Fire Island', 'Brooklyn']
  );
  assert.equal(snap.source.mock, true);
  assert.ok(Date.now() - snap.fetchedAt < 5000);
});
