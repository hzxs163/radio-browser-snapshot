// 前端数据集的编码：字段收窄 + tags/language/codec/countrycode 走「表头字典 + 行内下标」。
// 这些列在 6 万行里高度重复，直接写字符串要吃掉 30%+ 体积；字典化后行内只剩小整数。
// 纯函数，可单测：node scripts/test-web-data.cjs
'use strict';

// 行内列顺序（列名只在表头出现一次，所以名字保留可读性不占体积）
// stationuuid 不在这儿：它是 140bit 随机量，实测单独占 gz 1.17MB（整包的 1/3），
// 但只有「收藏 / 上报官方 clickcount」才用得到，所以拆到 ids 文件里按下标对齐懒加载。
const WEB_FIELDS = [
  'name', 'url_resolved',
  'cc', 'country', 'lang', 'tags', 'votes', 'bitrate', 'codec', 'hls', 'lat', 'lon',
];

// 数据集列 -> 官方原始列名
const DICT_SOURCE = { cc: 'countrycode', country: 'country', lang: 'language', codec: 'codec', tags: 'tags' };

// 必须存在的原始列，缺了就报错而不是静默产出半残数据集
const REQUIRED_SOURCE = [
  'stationuuid', 'name', 'url_resolved', 'countrycode', 'country', 'language', 'tags',
  'votes', 'bitrate', 'codec', 'hls', 'geo_lat', 'geo_long', 'lastcheckok',
];

// 明确不进数据集的原始列及理由（免得以后被当成漏项）
const DROPPED_SOURCE = {
  url: 'url_resolved 已够用，两条 URL 只是翻倍体积',
  homepage: '占 9% 体积，不影响收听',
  favicon: '占 14% 体积，且 http 图标在 https 页面会被混合内容拦掉',
  state: '占 2% 体积，展示价值低',
  lastchecktime_iso8601: '每天全量重算，单条检查时间没意义',
  clickcount: '随机权重用 votes 足够',
  lastcheckok: '数据集内恒为 1，写在文件头一次即可',
  certified: '老快照没这一列，依赖它会让本地产物与 CI 不一致',
};

// 确定性比较：先忽略大小写（字典列表要能读），同值再按码点（结果与 locale 无关）
function cmpStr(a, b) {
  const la = a.toLowerCase(), lb = b.toLowerCase();
  if (la !== lb) return la < lb ? -1 : 1;
  return a < b ? -1 : a > b ? 1 : 0;
}

function str(v) {
  const s = String(v == null ? '' : v).trim();
  return s === 'null' ? '' : s;
}

function num(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

// 只按「官方标记可用 + 有解析后播放地址」筛。
// 坐标不能进筛选条件：实测 62046 条可播台里 48540 条没有 geo_lat，
// 要求坐标会把随机池从 6.2 万台砍到 1.3 万台。地图只画有坐标的，其余走国家质心。
function playable(row) {
  return Number(row.lastcheckok) === 1 && str(row.url_resolved) !== '';
}

// tags 在官方库里是逗号串，含重复与乱序；去重 + 码点序后行内值才可比较
function splitTags(raw) {
  const set = new Set();
  for (const t of String(raw == null ? '' : raw).split(',')) {
    const v = t.trim();
    if (v) set.add(v);
  }
  return [...set].sort(cmpStr);
}

// 经纬度截到 3 位小数（约 110m）：地图精度够用，串短一半；0 视为无坐标
function coord(v) {
  const n = Number(v);
  return Number.isFinite(n) ? Math.round(n * 1000) / 1000 : 0;
}

function hasGeo(row) {
  return num(row.geo_lat) !== 0 && num(row.geo_long) !== 0;
}

// 一行原始记录 -> 中间对象（还没编成下标）
function webRow(row) {
  if (!playable(row)) return null;
  return {
    stationuuid: str(row.stationuuid),
    name: str(row.name),
    url_resolved: str(row.url_resolved),
    cc: str(row.countrycode).toUpperCase(),
    country: str(row.country),
    lang: str(row.language).toLowerCase(),
    tags: splitTags(row.tags),
    votes: num(row.votes),
    bitrate: num(row.bitrate),
    codec: str(row.codec).toUpperCase(),
    hls: num(row.hls),
    lat: coord(row.geo_lat),
    lon: coord(row.geo_long),
    geo: hasGeo(row),
  };
}

// 字典顺序：出现次数降序，同频次按码点升序 —— 同输入必得同输出，产物可复现
function buildDict(values) {
  const freq = new Map();
  for (const v of values) if (v !== '') freq.set(v, (freq.get(v) || 0) + 1);
  return [...freq.entries()]
    .sort((a, b) => (b[1] - a[1]) || cmpStr(a[0], b[0]))
    .map(([v]) => v);
}

// rows: 原始对象数组（CSV 解码后或官方 API 返回）→ { headerObj, lines, kept, dropped }
function buildWeb(rows) {
  const recs = [];
  let dropped = 0;
  for (const r of rows) {
    const w = webRow(r);
    if (w) recs.push(w);
    else dropped++;
  }

  const dict = {
    cc: buildDict(recs.map((r) => r.cc)),
    country: buildDict(recs.map((r) => r.country)),
    lang: buildDict(recs.map((r) => r.lang)),
    codec: buildDict(recs.map((r) => r.codec)),
    tags: buildDict(recs.flatMap((r) => r.tags)),
  };
  const indexOf = {};
  for (const k of Object.keys(dict)) indexOf[k] = new Map(dict[k].map((v, i) => [v, i]));
  const or = (v) => (v === undefined ? -1 : v);

  const lines = recs.map((r) => JSON.stringify([
    r.name, r.url_resolved,
    or(indexOf.cc.get(r.cc)),
    or(indexOf.country.get(r.country)),
    or(indexOf.lang.get(r.lang)),
    r.tags.map((t) => indexOf.tags.get(t)),
    r.votes, r.bitrate,
    or(indexOf.codec.get(r.codec)),
    r.hls, r.lat, r.lon,
  ]));
  // 与 lines 下标一一对齐的 uuid 表，单独成文件、按需加载
  const ids = JSON.stringify(recs.map((r) => r.stationuuid));

  return { headerObj: { fields: WEB_FIELDS, dict }, lines, ids, kept: recs.length, dropped };
}

module.exports = {
  WEB_FIELDS, DICT_SOURCE, REQUIRED_SOURCE, DROPPED_SOURCE,
  str, num, cmpStr, playable, splitTags, coord, hasGeo, webRow, buildDict, buildWeb,
};
