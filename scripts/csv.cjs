// RFC4180 CSV 解析：字段可含逗号、双引号（"" 转义）、换行。
// 必须用状态机而不是 split(',')——电台名/tags 里满是逗号，split 会串列。
'use strict';

function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = '';
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') { field += '"'; i++; }
        else quoted = false;
      } else field += c;
      continue;
    }
    if (c === '"' && field === '') { quoted = true; continue; }
    if (c === ',') { row.push(field); field = ''; continue; }
    if (c === '\n') { row.push(field); rows.push(row); row = []; field = ''; continue; }
    if (c === '\r') continue;
    field += c;
  }
  if (field !== '' || row.length) { row.push(field); rows.push(row); }
  return rows;
}

// 只按顶层逗号切一行（表头这类无引号字段够用）；用于取列下标
function splitHeader(line) {
  return line.split(',');
}

module.exports = { parseCsv, splitHeader };
