#!/usr/bin/env node
// 把 data 分支的最新 stations.csv.gz 归档成一个 Release，并把所有归档 Release 的总体积压到预算内（从最老的开始删）。
// Release 资产不进 git 历史，所以删了就是真回收空间——这是不用 data 分支存历史的原因。
// 用法：GITHUB_TOKEN=... GITHUB_REPOSITORY=owner/repo node scripts/archive.mjs --file=snapshot/stations.csv.gz [--budget-bytes=990000000] [--dry-run]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const m = a.match(/^--([^=]+)(?:=(.*))?$/);
    return m ? [m[1], m[2] === undefined ? true : m[2]] : [a.slice(2), true];
  })
);

const TOKEN = process.env.GITHUB_TOKEN;
const REPO = process.env.GITHUB_REPOSITORY || process.env.SNAPSHOT_REPO;
const API = (process.env.GITHUB_API_URL || 'https://api.github.com').replace(/\/$/, '');
const BUDGET = Number(args['budget-bytes'] || 990000000);
const FILE = args.file || 'snapshot/stations.csv.gz';
const DRY = args['dry-run'] === true || args['dry-run'] === 'true';
const PREFIX = 'snap-';

const log = (...m) => console.log(new Date().toISOString(), ...m);

async function gh(p, opts = {}) {
  const res = await fetch(API + p, {
    ...opts,
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      ...(opts.headers || {}),
    },
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`GitHub API ${res.status} ${p}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}

const sizeOf = (r) => (r.assets || []).reduce((x, a) => x + a.size, 0);

// ISO 周编号：snap-2026-W42，避免 CI 延迟一天滑到另一周
export function isoWeekTag(d = new Date()) {
  const DAY = 86400000;
  const src = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
  // 本日期所在 ISO 周的周四
  const dayNum = (src.getUTCDay() + 6) % 7;
  const thursday = new Date(src.getTime() - dayNum * DAY + 3 * DAY);
  const isoYear = thursday.getUTCFullYear();
  // 该 ISO 年第一周的周四：1 月 4 日必定落在第 1 周内，再回退到那一周的周四
  const jan4 = new Date(Date.UTC(isoYear, 0, 4));
  const w1Thursday = new Date(jan4.getTime() - ((jan4.getUTCDay() + 6) % 7) * DAY + 3 * DAY);
  const week = 1 + Math.round((thursday.getTime() - w1Thursday.getTime()) / (7 * DAY));
  return `${PREFIX}${isoYear}-W${String(week).padStart(2, '0')}`;
}

// 纯函数：给定归档 Release 列表和预算，算出该删哪些（从最老的开始），并保证至少留 1 个
export function planPrune(releases, budgetBytes) {
  // 按 tag 名排序：snap-YYYY-Www 定宽补零，字典序即时间序。
  // 不能用 created_at —— GitHub 对 target_commitish 指向同一提交的 Release 会给出一模一样的 created_at，并列时顺序不可控。
  const sorted = [...releases].sort((a, b) => {
    // 用原生 < > 而不是 localeCompare：Actions runner 的 ICU locale 会变，
    // 而这里必须严格等于码点序才和 tag 的时间序一致
    const ta = String(a.tag_name), tb = String(b.tag_name);
    const t = ta < tb ? -1 : ta > tb ? 1 : 0;
    return t !== 0 ? t : new Date(a.created_at) - new Date(b.created_at);
  });
  const total = sorted.reduce((s, r) => s + sizeOf(r), 0);
  const toDelete = [];
  let acc = total;
  for (const r of sorted) {
    if (acc <= budgetBytes) break;
    if (sorted.length - toDelete.length <= 1) break;
    toDelete.push(r);
    acc -= sizeOf(r);
  }
  return { total, toDelete, kept: sorted.filter((r) => !toDelete.includes(r)), remainingBytes: acc };
}

export async function main() {
  if (!TOKEN) throw new Error('需要 GITHUB_TOKEN');
  if (!REPO) throw new Error('需要 GITHUB_REPOSITORY');
  const [owner, repo] = REPO.split('/');
  const buf = fs.readFileSync(FILE);
  const tag = args.tag || isoWeekTag();

  const all = await gh(`/repos/${owner}/${repo}/releases?per_page=100`);
  const archives = all.filter((r) => (r.tag_name || '').startsWith(PREFIX));
  const { total, toDelete } = planPrune(archives, BUDGET);
  log(`已有 ${PREFIX}* 归档 ${archives.length} 个，总体积 ${total} B / 预算 ${BUDGET} B`);
  log(`本次归档 ${tag}，文件 ${buf.length} B`);

  if (DRY) {
    log('dry-run 决定：删除', toDelete.map((r) => `${r.tag_name}(${sizeOf(r)} B)`).join(', ') || '(无需删除)');
    const existing = archives.find((r) => r.tag_name === tag);
    log('dry-run 决定：', existing ? `${tag} 已存在，替换其资产` : `新建 ${tag}`);
    return { deleted: toDelete.map((r) => r.tag_name), tag, dry: true };
  }

  for (const r of toDelete) {
    await gh(`/repos/${owner}/${repo}/releases/${r.id}`, { method: 'DELETE' });
    log(`  已删除最老归档 ${r.tag_name}（释放 ${sizeOf(r)} B）`);
  }

  let rel = archives.find((r) => r.tag_name === tag);
  if (rel) {
    for (const a of rel.assets) {
      await gh(`/repos/${owner}/${repo}/releases/assets/${a.id}`, { method: 'DELETE' });
      log(`  已移除同周的旧资产 ${a.name}`);
    }
  } else {
    rel = await gh(`/repos/${owner}/${repo}/releases`, {
      method: 'POST',
      body: JSON.stringify({
        tag_name: tag,
        name: `Radio Browser 周归档 ${tag}`,
        body: 'Radio Browser 官方 API 全量电台表的每周归档（仅 CSV）。每日最新全量在 `data` 分支。',
        draft: false,
        prerelease: false,
        target_commitish: 'data',
        generate_release_notes: false,
      }),
    });
    log(`  已创建 Release ${rel.tag_name} (id=${rel.id})`);
  }

  // upload_url 形如 .../assets{?name,label}；必须先剥掉模板再拼查询串，
  // 否则 new URL() 会把 {} 编码成 %7B%7D，路径脏掉后 GitHub 报 "Multipart form data required"
  const up = rel.upload_url.replace('{?name,label}', '') + '?name=' + encodeURIComponent(path.basename(FILE));
  const res = await fetch(up, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      'Content-Type': 'application/gzip',
      'X-GitHub-Api-Version': '2022-11-28',
    },
    body: buf,
  });
  const txt = await res.text();
  if (!res.ok) throw new Error(`上传资产失败 ${res.status}: ${txt.slice(0, 300)}`);
  const asset = JSON.parse(txt);
  if (asset.size !== buf.length) throw new Error(`上传后体积不符：GitHub 报 ${asset.size} B，本地 ${buf.length} B`);
  log(`  已上传 ${asset.name} ${asset.size} B -> ${asset.browser_download_url}`);

  const after = (await gh(`/repos/${owner}/${repo}/releases?per_page=100`)).filter((r) => (r.tag_name || '').startsWith(PREFIX));
  const sum = after.reduce((s, r) => s + sizeOf(r), 0);
  log(`完成：${after.length} 个归档，总体积 ${sum} B（预算 ${BUDGET} B）`);
  return { tag, asset: asset.size, archives: after.length, sum };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
}
