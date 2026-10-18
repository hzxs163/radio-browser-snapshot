// node scripts/test-web-data.cjs
'use strict';
const assert = require('node:assert');
const { parseCsv } = require('./csv.cjs');
const W = require('./web-data.js');

let failed = 0;
const eq = (name, got, want) => {
  const g = JSON.stringify(got), s = JSON.stringify(want);
  if (g === s) console.log(`PASS  ${name}: ${g.length > 90 ? g.slice(0, 90) + '…' : g}`);
  else { failed++; console.log(`FAIL  ${name}\n      得到 ${g}\n      期望 ${s}`); }
};
const ok = (name, cond) => eq(name, !!cond, true);

const row = (o) => Object.assign({
  stationuuid: 'A'.repeat(36), name: 'X', url_resolved: 'http://s/stream',
  countrycode: 'de', language: 'English', tags: 'rock,news,rock',
  votes: 5, bitrate: 128, codec: 'mp3', hls: 0, geo_lat: '52.5200', geo_long: '13.4050',
  lastcheckok: 1,
}, o);

// ---- CSV 解析：引号感知，绝不能用 split(',') ----
eq('CSV: 带逗号字段', parseCsv('a,b\n"x,y",z'), [['a', 'b'], ['x,y', 'z']]);
eq('CSV: 双引号转义', parseCsv('a\n"he said ""hi"""'), [['a'], ['he said "hi"']]);
eq('CSV: 字段内换行', parseCsv('a\n"line1\nline2"'), [['a'], ['line1\nline2']]);
eq('CSV: CRLF 与尾部换行', parseCsv('a,b\r\nc,d\r\n'), [['a', 'b'], ['c', 'd']]);
eq('CSV: 空字段保留', parseCsv('a,,c'), [['a', '', 'c']]);

// ---- 可播筛选 ----
ok('playable: lastcheckok=1 且有地址', W.playable(row({})));
eq('playable: 失效台剔除', W.playable(row({ lastcheckok: 0 })), false);
eq('playable: 无解析地址剔除', W.playable(row({ url_resolved: '' })), false);
eq('playable: 无坐标仍然保留（否则随机池只剩 1.3 万）', W.playable(row({ geo_lat: '0', geo_long: '0' })), true);

// ---- tags ----
eq('splitTags: 去重+码点序', W.splitTags('rock,news,rock,  Techno  '), ['news', 'rock', 'Techno']);
eq('splitTags: 空串', W.splitTags(''), []);

// ---- 坐标 ----
eq('coord: 截到 3 位小数', W.coord('52.5200123'), 52.52);
eq('coord: 非数字归 0', W.coord(''), 0);
eq('hasGeo: 0,0 视为无坐标', W.hasGeo(row({ geo_lat: 0, geo_long: 0 })), false);

// ---- webRow 类型收窄 ----
const w = W.webRow(row({}));
eq('webRow: cc 转大写', w.cc, 'DE');
eq('webRow: lang 转小写', w.lang, 'english');
eq('webRow: codec 转大写', w.codec, 'MP3');
eq('webRow: votes 转数字', w.votes, 5);
eq('webRow: lat 转数字', w.lat, 52.52);

// ---- 字典：同频次按码点序，保证产物可复现 ----
eq('buildDict: 频次降序', W.buildDict(['a', 'a', 'b', 'c', 'c', 'c']), ['c', 'a', 'b']);
eq('buildDict: 同频忽略大小写', W.buildDict(['Techno', 'news']), ['news', 'Techno']);
eq('buildDict: 同频按码点升序', W.buildDict(['b', 'a']), ['a', 'b']);
eq('buildDict: 空串不入字典', W.buildDict(['', 'a', '']), ['a']);

// ---- buildWeb: 行与 ids 下标对齐 ----
const rows = [
  row({ stationuuid: 'U1', tags: 'rock', countrycode: 'de', votes: 1 }),
  row({ stationuuid: 'U2', tags: 'jazz,rock', countrycode: 'cn', language: 'Chinese', votes: 9 }),
  row({ stationuuid: 'U3', lastcheckok: 0 }),
];
const out = W.buildWeb(rows);
const parsed = out.lines.map((l) => JSON.parse(l));
eq('buildWeb: 行数=可播数', [out.kept, out.dropped], [2, 1]);
eq('buildWeb: ids 与行下标对齐', JSON.parse(out.ids), ['U1', 'U2']);
eq('buildWeb: 行内不含 uuid（字段表也没它）', parsed[0].length, W.WEB_FIELDS.length);
// cc 字典同频次按码点升序：CN 在 DE 前，所以 U1(DE) 的下标是 1
eq('buildWeb: 列顺序 name,url_resolved,cc,…', parsed[0].slice(0, 3), ['X', 'http://s/stream', 1]);
eq('buildWeb: tags 编成下标', parsed[1][W.WEB_FIELDS.indexOf('tags')], [1, 0]);
eq('buildWeb: 字典内容', out.headerObj.dict.tags, ['rock', 'jazz']);
eq('buildWeb: cc 字典（大写化后同频按码点）', out.headerObj.dict.cc, ['CN', 'DE']);

console.log(failed ? `\n${failed} 个用例失败` : '\n全部用例通过');
process.exit(failed ? 1 : 0);
