// Runs the original web prototype's model (Ridge Light/model.mjs) headless in Node, so the
// Kotlin engine can be compared against it on the same route, tiles and pace.
// Usage: node tools/webapp-reference.mjs <track.gpx> <dem-dir> <YYYY-MM-DD> <HH:MM> <zone>
import {readFileSync, existsSync} from 'node:fs';
import {inflateSync} from 'node:zlib';
import {pathToFileURL} from 'node:url';
import path from 'node:path';

const [gpxPath, demDir, date, time, zone] = process.argv.slice(2);
const model = await import(pathToFileURL(path.resolve('Ridge Light/model.mjs')).href);

function decodePng(buf) {
  let pos = 8, width = 0, height = 0, colorType = 0;
  const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos), type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') { width = data.readUInt32BE(0); height = data.readUInt32BE(4); colorType = data[9]; if (data[8] !== 8 || data[12]) throw Error('unsupported PNG'); }
    if (type === 'IDAT') idat.push(data);
    pos += 12 + len;
  }
  const bpp = colorType === 6 ? 4 : colorType === 2 ? 3 : (() => { throw Error('colour type ' + colorType); })();
  const raw = inflateSync(Buffer.concat(idat)), stride = width * bpp, out = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y++) {
    const f = raw[y * (stride + 1)], line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? out[y * stride + x - bpp] : 0, b = y ? out[(y - 1) * stride + x] : 0, c = x >= bpp && y ? out[(y - 1) * stride + x - bpp] : 0;
      const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
      const pred = [0, a, b, (a + b) >> 1, pa <= pb && pa <= pc ? a : pb <= pc ? b : c][f];
      out[y * stride + x] = (line[x] + pred) & 255;
    }
  }
  const h = new Float32Array(width * height);
  for (let i = 0; i < h.length; i++) { const k = i * bpp; h[i] = bpp === 4 && out[k + 3] === 0 ? NaN : out[k] * 256 + out[k + 1] + out[k + 2] / 256 - 32768; }
  return h;
}

const gpx = readFileSync(gpxPath, 'utf8');
const points = [...gpx.matchAll(/<trkpt lat="([^"]+)" lon="([^"]+)">\s*(?:<ele>([^<]+)<\/ele>)?/g)].map(m => ({lat: +m[1], lon: +m[2], ele: m[3] ? +m[3] : NaN}));
const route = model.makeRoute(points);
const byZ = new Map();
let missing = 0;
for (const t of model.tilesFor(route)) {
  const f = path.join(demDir, `${t.z}-${t.x}-${t.y}.png`);
  if (!existsSync(f)) { missing++; continue; }
  if (!byZ.has(t.z)) byZ.set(t.z, new Map());
  const bx = byZ.get(t.z); if (!bx.has(t.x)) bx.set(t.x, new Map());
  bx.get(t.x).set(t.y, decodePng(readFileSync(f)));
}
const terrain = new model.Terrain(byZ);
const samples = model.sampleRoute(route, terrain);
const it = model.itinerary(samples, 4, 6);
const start = model.localToUTC(date, time, zone);
const res = model.analyse(samples, start, terrain);
const fmt = s => `${Math.floor(s / 3600)}h ${String(Math.round(s / 60) % 60).padStart(2, '0')}m`;
console.log(`web prototype · ${(route.total / 1000).toFixed(2)} km · ${fmt(it.seconds)} · missing tiles ${missing}`);
for (const k of ['sun', 'shade', 'dark', 'unknown']) console.log(`  ${k.padEnd(8)} ${fmt(res.totals[k]).padStart(8)}  ${Math.round(100 * res.totals[k] / it.seconds)} %`);
const runs = [];
res.types.forEach((k, i) => { const s = samples.steps[i]; const last = runs.at(-1); if (last?.k === k) { last.d1 = samples.edges[i + 1].d; } else runs.push({k, d0: samples.edges[i].d, d1: samples.edges[i + 1].d}); });
console.log('  stretches: ' + runs.map(r => `${r.k} ${(r.d0 / 1000).toFixed(1)}–${(r.d1 / 1000).toFixed(1)}`).join(', '));
