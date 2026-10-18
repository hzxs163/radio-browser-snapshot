# Radio Browser 每日快照

每天 04:00 UTC 从 **Radio Browser 官方 API** 拉一次全量电台目录，提交到本仓库的 `data` 分支。

## 数据源

- 项目官网：`https://radio-browser.info`，API 文档：`https://api.radio-browser.info`
- 官方 API 镜像当前只有两台：`de1.api.radio-browser.info`、`de2.api.radio-browser.info`
- 脚本按官方文档的做法，先查 `all.api.radio-browser.info` 的 A 记录并反解出主机名、随机打乱后依次尝试；解析失败则退回上面两台。
- 每次请求带可识别的 UA：`radio-browser-snapshot/1.0 (+https://github.com/<本仓库>)`（官方要求客户端必须有明确 User-Agent）。
- 服务器 `tiny-http (Rust)` 不支持 `Content-Encoding: gzip`，所以传输的是原始 JSON，只能本地压缩后再入库。

## 怎么用

```bash
# 取最新快照
curl -L -o stations.csv.gz https://github.com/hzxs163/radio-browser-snapshot/raw/data/stations.csv.gz
gunzip stations.csv.gz

# 或者直接下 data 分支打包
curl -L -o data.tar.gz https://github.com/hzxs163/radio-browser-snapshot/archive/refs/heads/data.tar.gz
```

## 产物

| 文件 | 内容 | 实测体量（2026-10-18 首次快照，69072 行） |
| --- | --- | --- |
| `stations.csv.gz` | 30 列表格，含 `stationuuid` / `name` / `url` / `url_resolved` / `country` / `countrycode` / `state` / `language` / `tags` / `codec` / `bitrate` / `votes` / `clickcount` / `lastcheckok` / `lastchecktime_iso8601` / `geo_lat` / `geo_long` 等 | 9,351,094 B（未压缩 31,607,528 B） |
| `stations.jsonl.gz` | 官方接口原样逐条 JSON，不丢字段 | 12,369,249 B |
| `meta.json` | 本次拉取时间、来源服务器、行数、官方 stats 报的可用/失效数、两个产物的 sha256、列清单 | <2 KB |

注意行数口径：官方 `/json/stats` 的 `stations` 只统计**可用**台（62046），另有 `stations_broken`（7026）；全量行数 = 两者之和 = 69072，脚本按这个数做完整性下限校验（95%）。CSV 里每行都带 `lastcheckok` / `ssl_error` / `bitrate`，可自行过滤掉官方判定已失效的台。

## 为什么 `data` 分支只有一个提交

电台的 `clickcount`、`lastchecktime` 每天都在变，全量快照不可能做增量 diff。每天两个产物合计约 22 MB，若每天追加提交，一年就是 ~8 GB 并被 GitHub 限制。所以 `data` 分支每天被强制重置为单个提交，仓库体积稳定在 ~22 MB。历史版本不保留 —— 需要留存的话，把 workflow 改成按日期分文件提交，但要接受体积线性增长。

## 手动触发

Actions → `daily-snapshot` → Run workflow。可选参数：每页条数（默认 2000）、是否提交 `stations.jsonl.gz`。

跑完会把摘要（行数、来源、sha256）写成 `data` 分支最新提交的评论，匿名访问也能看到，不用去翻 job 日志。

## 本地跑

```bash
node scripts/fetch.js --page=2000 --out=data
# 冒烟测试（只拉一页）
node scripts/fetch.js --page=200 --max-pages=1 --out=tmpdata
```

Node 18+，无任何依赖。
