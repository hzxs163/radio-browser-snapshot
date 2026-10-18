#!/usr/bin/env node
// 由 stations.csv.gz 生成前端数据集 stations.web.json.gz（字段收窄 + 字典编码 + 只留可播台）。
// 与每日 CI 走同一份代码，本地跑出来的字节和 CI 一致。
// 用法：node scripts/build-web.mjs --in=build/data/stations.csv.gz [--out=...]
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { parseCsv } = require('./csv.cjs');
const { WEB_FIELDS, REQUIRED_SOURCE, buildWeb } = require('./web-data.js');

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const m = a.match(/^--([^=]+)(?:=(.*))?$/);
    return m ? [m[1], m[2] === undefined ? true : m[2]] : [a.slice(2), true];
  })
);
const CSV = String(args.in || 'build/data/stations.csv.gz');
const OUT = String(args.out || CSV.replace(/stations\.csv\.gz$/, 'stations.web.json.gz'));
const log = (...a) => console.log(new Date().toISOString(), ...a);

function build(csvText) {
  const rows = parseCsv(csvText);
  if (rows.length < 2) throw new Error('CSV 只有表头，拒绝写出');
  const header = rows[0];
  const idx = {};
  header.forEach((h, i) => { idx[h] = i; });
  const missing = REQUIRED_SOURCE.filter((f) => !(f in idx));
  if (missing.length) throw new Error(`CSV 缺少必需列 ${missing.join(', ')}`);

  const src = [];
  for (let i = 1; i < rows.length; i++) {
    const cells = rows[i];
    if (cells.length !== header.length) {
      throw new Error(`第 ${i} 行字段数 ${cells.length} 与表头 ${header.length} 不符`);
    }
    const o = {};
    for (const f of REQUIRED_SOURCE) o[f] = cells[idx[f]];
    src.push(o);
  }
  const out = buildWeb(src);
  return { ...out, headerLen: header.length, rowsTotal: rows.length - 1 };
}

const raw = fs.readFileSync(CSV);
const csvText = raw[0] === 0x1f && raw[1] === 0x8b ? zlib.gunzipSync(raw).toString('utf8') : raw.toString('utf8');
const { headerObj, lines, ids, kept, dropped, headerLen, rowsTotal } = build(csvText);
const body = JSON.stringify(headerObj) + '\n' + lines.join('\n') + '\n';
const gz = zlib.gzipSync(Buffer.from(body, 'utf8'), { level: 9 });
const idsGz = zlib.gzipSync(Buffer.from(ids, 'utf8'), { level: 9 });
const outDir = path.dirname(path.resolve(OUT));
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(OUT, gz);
const IDS_OUT = OUT.replace(/stations\.web\.json\.gz$/, 'stations.ids.json.gz');
fs.writeFileSync(IDS_OUT, idsGz);

const metaPath = path.join(path.dirname(CSV), 'meta.json');
const snap = fs.existsSync(metaPath) ? JSON.parse(fs.readFileSync(metaPath, 'utf8')) : {};
const meta = {
  schema: 2,
  generated_at: new Date().toISOString(),
  from: path.basename(CSV),
  csv_rows: rowsTotal,
  csv_columns: headerLen,
  web_fields: WEB_FIELDS,
  web_rows: kept,
  dropped_not_playable: dropped,
  dict_sizes: Object.fromEntries(Object.entries(headerObj.dict).map(([k, v]) => [k, v.length])),
  uncompressed_bytes: Buffer.byteLength(body),
  gz_bytes: gz.length,
  sha256_gz: crypto.createHash('sha256').update(gz).digest('hex'),
  ids_rows: JSON.parse(ids).length,
  ids_gz_bytes: idsGz.length,
  ids_sha256_gz: crypto.createHash('sha256').update(idsGz).digest('hex'),
  snapshot_fetched_at: snap.fetched_at ?? null,
  snapshot_rows_written: snap.rows_written ?? null,
};
fs.writeFileSync(path.join(path.dirname(path.resolve(OUT)), 'meta_web.json'), JSON.stringify(meta, null, 2) + '\n');
log('前端数据集完成:', JSON.stringify({
  web_rows: kept, dropped, gz_bytes: gz.length, dict_sizes: meta.dict_sizes,
}));
