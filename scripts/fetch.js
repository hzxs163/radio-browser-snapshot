#!/usr/bin/env node
// 从 Radio Browser 官方 API 分页拉取全量电台，落地为 CSV.gz + meta.json。
// 无第三方依赖，Node 18+。
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const dns = require('node:dns').promises;

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const m = a.match(/^--([^=]+)(?:=(.*))?$/);
    return m ? [m[1], m[2] === undefined ? true : m[2]] : [a.slice(2), true];
  })
);

const REPO = process.env.SNAPSHOT_REPO || 'hzxs163/radio-browser-snapshot';
const UA = `radio-browser-snapshot/1.0 (+https://github.com/${REPO})`;
const PAGE = Number(args.page || 2000);
const OUT_DIR = path.resolve(args.out || 'data');
const MAX_PAGES = Number(args['max-pages'] || Infinity);
const FALLBACK = ['de1.api.radio-browser.info', 'de2.api.radio-browser.info'];

function log(...m) {
  console.log(new Date().toISOString(), ...m);
}

async function discoverServers() {
  const found = new Set();
  try {
    const recs = await dns.resolve4('all.api.radio-browser.info');
    for (const ip of recs) found.add(ip);
    // 官方文档：all.api.radio-browser.info 的 A 记录即全部可用服务器
    const ptrs = await Promise.allSettled(recs.map((ip) => dns.reverse(ip)));
    for (const p of ptrs) if (p.status === 'fulfilled') for (const h of p.value) if (h.endsWith('api.radio-browser.info')) found.add(h);
  } catch (e) {
    log('DNS 发现失败，使用内置镜像:', e.code || e.message);
  }
  const hosts = [...found].filter((h) => h === 'all.api.radio-browser.info' || h.endsWith('api.radio-browser.info'));
  const servers = hosts.length ? hosts : FALLBACK;
  for (let i = servers.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [servers[i], servers[j]] = [servers[j], servers[i]];
  }
  return servers;
}

async function getJson(server, search, attempt = 1) {
  const url = `https://${server}/json/stations${search}`;
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), Number(args.timeout || 180000));
  try {
    const res = await fetch(url, { headers: { 'User-Agent': UA, Accept: 'application/json' }, signal: ac.signal });
    if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
    const text = await res.text();
    return JSON.parse(text);
  } catch (e) {
    clearTimeout(timer);
    const max = Number(args.retries || 3);
    if (attempt < max) {
      const wait = 2000 * attempt;
      log(`  重试 ${attempt}/${max - 1}（${wait}ms 后）${e.name === 'AbortError' ? '超时' : e.message}`);
      await new Promise((r) => setTimeout(r, wait));
      return getJson(server, search, attempt + 1);
    }
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

const FIELDS = [
  'stationuuid', 'changeuuid', 'serveruuid', 'name', 'url', 'url_resolved', 'homepage', 'favicon',
  'tags', 'country', 'countrycode', 'iso_3166_2', 'state', 'language', 'languagecodes', 'votes',
  'lastchangetime_iso8601', 'codec', 'bitrate', 'hls', 'lastcheckok', 'lastchecktime_iso8601',
  'lastcheckoktime_iso8601', 'clicktimestamp_iso8601', 'clickcount', 'clicktrend',
  'ssl_error', 'geo_lat', 'geo_long', 'has_extended_info',
];

const esc = (v) => {
  if (v === null || v === undefined) return '';
  const s = String(v).replace(/[\r\n\t]+/g, ' ').trim();
  return /[",]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
};

(async () => {
  const servers = await discoverServers();
  log('候选服务器:', servers.join(' '));

  let server = null;
  let stats = null;
  for (const s of servers) {
    try {
      const res = await fetch(`https://${s}/json/stats`, { headers: { 'User-Agent': UA, Accept: 'application/json' } });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      stats = await res.json();
      if (typeof stats.stations !== 'number') throw new Error('stats 响应缺少 stations 字段');
      server = s;
      break;
    } catch (e) {
      log(`  ${s} 不可用: ${e.message}`);
    }
  }
  if (!server) throw new Error('所有官方镜像都连不上');
  log(`使用 ${server} | stations=${stats.stations} broken=${stats.stations_broken} api_version=${stats.software_version}`);

  const rows = [];
  const seen = new Set();
  let offset = 0;
  let pages = 0;
  while (pages < MAX_PAGES) {
    const batch = await getJson(server, `?limit=${PAGE}&offset=${offset}&order=stationuuid`);
    pages++;
    log(`  第 ${pages} 页 offset=${offset} 返回 ${batch.length} 条`);
    for (const r of batch) {
      if (seen.has(r.stationuuid)) continue;
      seen.add(r.stationuuid);
      rows.push(r);
    }
    if (batch.length < PAGE) break;
    offset += PAGE;
  }

  if (!rows.length) throw new Error('拉到 0 条，拒绝写出');
  const expected = stats.stations;
  if (rows.length < expected * 0.8 && Number.isFinite(MAX_PAGES) === false) {
    throw new Error(`数据量异常：得到 ${rows.length} 条，官方 stats 报 ${expected} 条，疑似分页中断`);
  }

  const csv = [FIELDS.join(',')].concat(
    rows.map((r) => FIELDS.map((f) => esc(r[f])).join(','))
  ).join('\n') + '\n';

  const jsonl = rows.map((r) => JSON.stringify(r)).join('\n') + '\n';

  fs.mkdirSync(OUT_DIR, { recursive: true });
  const gz = (s) => zlib.gzipSync(Buffer.from(s, 'utf8'), { level: 9 });
  fs.writeFileSync(path.join(OUT_DIR, 'stations.csv.gz'), gz(csv));
  fs.writeFileSync(path.join(OUT_DIR, 'stations.jsonl.gz'), gz(jsonl));

  const crypto = require('node:crypto');
  const sha = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
  const meta = {
    schema: 1,
    fetched_at: new Date().toISOString(),
    source: `https://${server}/json/stations`,
    api_software_version: stats.software_version,
    stations_in_api_stats: expected,
    stations_broken_in_api_stats: stats.stations_broken,
    rows_written: rows.length,
    unique_stationuuid: seen.size,
    csv_uncompressed_bytes: Buffer.byteLength(csv),
    jsonl_uncompressed_bytes: Buffer.byteLength(jsonl),
    sha256_csv_gz: sha(fs.readFileSync(path.join(OUT_DIR, 'stations.csv.gz'))),
    sha256_jsonl_gz: sha(fs.readFileSync(path.join(OUT_DIR, 'stations.jsonl.gz'))),
    columns: FIELDS,
    user_agent: UA,
  };
  fs.writeFileSync(path.join(OUT_DIR, 'meta.json'), JSON.stringify(meta, null, 2) + '\n');
  log('写出完成:', JSON.stringify({ rows: rows.length, csv_bytes: meta.csv_uncompressed_bytes }));
})().catch((e) => {
  console.error('FATAL', e.message);
  process.exit(1);
});
