/** The Jira search of these tickets, ready for Jira's bulk change. */
export const jqlUrl = (site: string, keys: string[]) =>
  `${site.replace(/\/+$/, '')}/issues/?jql=${encodeURIComponent(`key in (${keys.join(', ')}) ORDER BY key`)}`;
