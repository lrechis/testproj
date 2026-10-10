// Deterministic sample data in the raw Weather API response shape.
// Used when WEATHER_MOCK=1 so the UI can be developed and tested without a key.

'use strict';

const TYPES = [
  ['CLEAR', 'Sunny'],
  ['MOSTLY_CLEAR', 'Mostly sunny'],
  ['PARTLY_CLOUDY', 'Partly cloudy'],
  ['CLOUDY', 'Cloudy'],
  ['LIGHT_RAIN', 'Light rain'],
  ['RAIN', 'Rain'],
  ['SCATTERED_SHOWERS', 'Scattered showers'],
  ['THUNDERSTORM', 'Thunderstorm'],
  ['WINDY', 'Windy'],
];
const CARDINALS = ['NORTH', 'NORTHEAST', 'EAST', 'SOUTHEAST', 'SOUTH', 'SOUTHWEST', 'WEST', 'NORTHWEST'];

// Tiny seeded PRNG so each location gets stable but different data.
function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

function localParts(date, timeZone) {
  const f = new Intl.DateTimeFormat('en-US', {
    timeZone,
    hourCycle: 'h23',
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
  });
  const p = Object.fromEntries(f.formatToParts(date).map((x) => [x.type, x.value]));
  return { year: +p.year, month: +p.month, day: +p.day, hours: +p.hour % 24 };
}

function cond(i) {
  const [type, text] = TYPES[i % TYPES.length];
  return {
    iconBaseUri: `https://maps.gstatic.com/weather/v1/${type.toLowerCase()}`,
    description: { text, languageCode: 'en' },
    type,
  };
}

function windObj(speed, gust, deg) {
  return {
    direction: { degrees: deg, cardinal: CARDINALS[Math.round(deg / 45) % 8] },
    speed: { value: speed, unit: 'MILES_PER_HOUR' },
    gust: { value: gust, unit: 'MILES_PER_HOUR' },
  };
}

function precipObj(percent, inches) {
  return {
    probability: { percent, type: percent > 0 ? 'RAIN' : 'NONE' },
    qpf: { quantity: inches, unit: 'INCHES' },
    snowQpf: { quantity: 0, unit: 'INCHES' },
  };
}

async function fetchRaw(loc) {
  const timeZone = 'America/New_York';
  const rand = rng(Math.round(loc.lat * 1000) ^ Math.round(loc.lon * 1000));
  const now = new Date();
  const base = new Date(now);
  base.setUTCMinutes(0, 0, 0);
  const coastal = loc.id === 'fire-island';

  const hours = [];
  for (let i = 0; i < 48; i++) {
    const t = new Date(base.getTime() + i * 3600e3);
    const lp = localParts(t, timeZone);
    const diurnal = Math.sin(((lp.hours - 9) / 24) * Math.PI * 2);
    const temp = (coastal ? 58 : 62) + diurnal * 7 + rand() * 2;
    const rainy = i >= 14 && i <= 22;
    const condIdx = rainy ? 4 + Math.floor(rand() * 3) : Math.floor(rand() * 4);
    const speed = (coastal ? 14 : 9) + rand() * 8 + (rainy ? 6 : 0);
    hours.push({
      interval: { startTime: t.toISOString(), endTime: new Date(t.getTime() + 3600e3).toISOString() },
      displayDateTime: { ...lp, minutes: 0, seconds: 0, nanos: 0, utcOffset: '-14400s' },
      isDaytime: lp.hours >= 7 && lp.hours < 19,
      weatherCondition: cond(condIdx),
      temperature: { degrees: Math.round(temp * 10) / 10, unit: 'FAHRENHEIT' },
      feelsLikeTemperature: { degrees: Math.round((temp - speed / 8) * 10) / 10, unit: 'FAHRENHEIT' },
      relativeHumidity: Math.round(60 + rand() * 30),
      thunderstormProbability: rainy ? Math.round(rand() * 30) : 0,
      wind: windObj(Math.round(speed), Math.round(speed * 1.5), Math.round(180 + rand() * 90)),
      precipitation: precipObj(rainy ? Math.round(50 + rand() * 45) : Math.round(rand() * 15), rainy ? Math.round(rand() * 20) / 100 : 0),
    });
  }

  const days = [];
  for (let i = 0; i < 10; i++) {
    const t = new Date(base.getTime() + i * 86400e3);
    const lp = localParts(t, timeZone);
    const hi = (coastal ? 63 : 68) + rand() * 8 - i * 0.6;
    const lo = hi - 10 - rand() * 4;
    const wet = i === 1 || i === 4;
    const sunrise = new Date(Date.UTC(lp.year, lp.month - 1, lp.day, 11, 2 + i));
    const sunset = new Date(Date.UTC(lp.year, lp.month - 1, lp.day, 22, 25 - i));
    const half = (pctBase, idx) => ({
      interval: {},
      weatherCondition: cond(idx),
      relativeHumidity: Math.round(55 + rand() * 30),
      uvIndex: Math.round(2 + rand() * 5),
      precipitation: precipObj(wet ? pctBase + Math.round(rand() * 20) : Math.round(rand() * 20), wet ? Math.round(rand() * 60) / 100 : 0),
      thunderstormProbability: wet ? Math.round(rand() * 25) : 0,
      wind: windObj(Math.round((coastal ? 13 : 9) + rand() * 7), Math.round((coastal ? 22 : 16) + rand() * 8), Math.round(rand() * 360)),
      cloudCover: Math.round(rand() * 100),
    });
    days.push({
      interval: { startTime: sunrise.toISOString(), endTime: new Date(sunrise.getTime() + 86400e3).toISOString() },
      displayDate: { year: lp.year, month: lp.month, day: lp.day },
      daytimeForecast: half(55, wet ? 5 : Math.floor(rand() * 4)),
      nighttimeForecast: half(45, wet ? 4 : Math.floor(rand() * 3)),
      maxTemperature: { degrees: Math.round(hi), unit: 'FAHRENHEIT' },
      minTemperature: { degrees: Math.round(lo), unit: 'FAHRENHEIT' },
      feelsLikeMaxTemperature: { degrees: Math.round(hi - 1), unit: 'FAHRENHEIT' },
      feelsLikeMinTemperature: { degrees: Math.round(lo - 2), unit: 'FAHRENHEIT' },
      sunEvents: { sunriseTime: sunrise.toISOString(), sunsetTime: sunset.toISOString() },
      moonEvents: { moonPhase: 'WAXING_GIBBOUS', moonriseTimes: [], moonsetTimes: [] },
    });
  }

  const h0 = hours[0];
  const current = {
    currentTime: now.toISOString(),
    timeZone: { id: timeZone },
    isDaytime: h0.isDaytime,
    weatherCondition: h0.weatherCondition,
    temperature: h0.temperature,
    feelsLikeTemperature: h0.feelsLikeTemperature,
    dewPoint: { degrees: 50, unit: 'FAHRENHEIT' },
    heatIndex: h0.temperature,
    windChill: h0.feelsLikeTemperature,
    relativeHumidity: h0.relativeHumidity,
    uvIndex: 3,
    precipitation: h0.precipitation,
    thunderstormProbability: h0.thunderstormProbability,
    airPressure: { meanSeaLevelMillibars: 1014.6 },
    wind: h0.wind,
    visibility: { distance: 10, unit: 'MILES' },
    cloudCover: 35,
    currentConditionsHistory: {
      temperatureChange: { degrees: 1.2, unit: 'FAHRENHEIT' },
      maxTemperature: days[0].maxTemperature,
      minTemperature: days[0].minTemperature,
      qpf: { quantity: 0, unit: 'INCHES' },
    },
  };

  // Simulate a little network latency.
  await new Promise((r) => setTimeout(r, 50));
  return {
    current,
    hourly: { forecastHours: hours, timeZone: { id: timeZone } },
    daily: { forecastDays: days, timeZone: { id: timeZone } },
  };
}

module.exports = { fetchRaw };
