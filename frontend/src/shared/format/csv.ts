/** One CSV cell, quoted. */
const cell = (v: string | number | null | undefined) => `"${String(v ?? '').replace(/"/g, '""')}"`;

/** Offers a CSV file (UTF-8 with a BOM, which Jira's importer and Excel read) to save. */
export function downloadCsv(name: string, header: string[], rows: (string | number | null | undefined)[][]) {
  const text = '﻿' + [header.map(cell).join(','), ...rows.map((r) => r.map(cell).join(','))].join('\n');
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/** Hours as Jira's importer takes an estimate: seconds. */
export const seconds = (h: string | number | null | undefined) => (h === '' || h == null ? '' : Math.round(Number(h) * 3600));

/**
 * The records of a CSV file (a BOM dropped), separated by "," or by ";" (as Excel saves it in some locales): whichever the
 * header line has more of. Quoted cells may hold separators, quotes ("") and line breaks; blank records are dropped.
 */
export function parseCsv(text: string): { sep: ',' | ';'; records: string[][] } {
  const t = text.replace(/^\uFEFF/, '');
  const head = t.split(/\r?\n/, 1)[0];
  const sep = head.split(';').length > head.split(',').length ? ';' : ',';
  const records: string[][] = [];
  let rec: string[] = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < t.length; i++) {
    const c = t[i];
    if (quoted) {
      if (c === '"' && t[i + 1] === '"') { cell += '"'; i++; }
      else if (c === '"') quoted = false;
      else cell += c;
    } else if (c === '"') quoted = true;
    else if (c === sep) { rec.push(cell); cell = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && t[i + 1] === '\n') i++;
      rec.push(cell);
      records.push(rec);
      rec = [];
      cell = '';
    } else cell += c;
  }
  if (cell || rec.length) records.push([...rec, cell]);
  return { sep, records: records.filter((r) => r.some((x) => x.trim())) };
}
