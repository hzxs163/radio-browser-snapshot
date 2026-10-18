import { isoWeekTag, planPrune } from './archive.mjs';

let failed = 0;
const eq = (label, got, want) => {
  const g = JSON.stringify(got), w = JSON.stringify(want);
  console.log(`${g === w ? 'PASS' : 'FAIL'}  ${label}: ${g}${g === w ? '' : ' != ' + w}`);
  if (g !== w) failed++;
};
const rel = (tag, created, size) => ({ tag_name: tag, created_at: created, assets: [{ size }] });

eq('2026-10-18(周日) 属于本 ISO 周', isoWeekTag(new Date('2026-10-18T00:00:00Z')), 'snap-2026-W42');
eq('2026-01-01(周四) = W01', isoWeekTag(new Date('2026-01-01T00:00:00Z')), 'snap-2026-W01');
eq('2027-01-01(周五) 仍属 2026-W53', isoWeekTag(new Date('2027-01-01T00:00:00Z')), 'snap-2026-W53');
eq('2023-01-01(周日) 仍属 2022-W52', isoWeekTag(new Date('2023-01-01T00:00:00Z')), 'snap-2022-W52');
eq('2024-01-01(周一) = 2024-W01', isoWeekTag(new Date('2024-01-01T00:00:00Z')), 'snap-2024-W01');

const a = rel('snap-2026-W40', '2026-10-01T00:00:00Z', 300);
const b = rel('snap-2026-W41', '2026-10-08T00:00:00Z', 200);
const c = rel('snap-2026-W42', '2026-10-15T00:00:00Z', 100);

eq('未超预算不删', planPrune([c, b, a], 999).toDelete.map((r) => r.tag_name), []);
eq('总体积正好等于预算不删', planPrune([c, b, a], 600).toDelete.map((r) => r.tag_name), []);
eq('超预算从最老的删起', planPrune([c, b, a], 350).toDelete.map((r) => r.tag_name), ['snap-2026-W40']);
eq('删够即停，不多删', planPrune([c, b, a], 300).toDelete.map((r) => r.tag_name), ['snap-2026-W40']);
eq('只剩一个时不再删', planPrune([c], 1).toDelete.map((r) => r.tag_name), []);
eq('两个都超预算时保留最新', planPrune([b, c], 1).toDelete.map((r) => r.tag_name), ['snap-2026-W41']);
eq('kept 与 toDelete 互补', planPrune([c, b, a], 350).kept.map((r) => r.tag_name), ['snap-2026-W41', 'snap-2026-W42']);
eq('remainingBytes 为删后余量', planPrune([c, b, a], 350).remainingBytes, 300);
eq('忽略非 snap- 前缀由调用方负责，total 计算含全部入参', planPrune([c, b, a], 1).total, 600);

// created_at 并列（GitHub 对同一 target_commitish 会给相同时间戳）时，必须按 tag 名定序
const same1 = rel('snap-2026-W42', '2026-10-18T03:02:31Z', 100);
const same2 = rel('snap-2026-W01', '2026-10-18T03:02:31Z', 100);
const same3 = rel('snap-2026-W20', '2026-10-18T03:02:31Z', 100);
eq('created_at 并列时按 tag 名从最老删', planPrune([same1, same2, same3], 250).toDelete.map((r) => r.tag_name), ['snap-2026-W01']);
eq('created_at 并列且预算极小时删到只剩 1 个（留最新的 W42）', planPrune([same1, same2, same3], 1).toDelete.map((r) => r.tag_name), ['snap-2026-W01', 'snap-2026-W20']);
const xYear = [rel('snap-2026-W01', '2026-01-05T00:00:00Z', 10), rel('snap-2025-W52', '2025-12-29T00:00:00Z', 10), rel('snap-2025-W01', '2025-01-06T00:00:00Z', 10)];
eq('跨年字典序即时间序', planPrune(xYear, 1).kept.map((r) => r.tag_name), ['snap-2026-W01']);

// created_at 与 tag 顺序矛盾时（手改过、或指向不同提交），以 tag 名为准
const lie = [rel('snap-2026-W42', '2020-01-01T00:00:00Z', 100), rel('snap-2026-W30', '2029-01-01T00:00:00Z', 100)];
eq('created_at 与 tag 矛盾时以 tag 名定序', planPrune(lie, 1).toDelete.map((r) => r.tag_name), ['snap-2026-W30']);

console.log(failed ? `\n${failed} 个用例失败` : '\n全部用例通过');
process.exit(failed ? 1 : 0);
