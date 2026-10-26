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

/** The Jira search of these tickets, ready for Jira's bulk change. */
export const jqlUrl = (site: string, keys: string[]) =>
  `${site.replace(/\/+$/, '')}/issues/?jql=${encodeURIComponent(`key in (${keys.join(', ')}) ORDER BY key`)}`;
