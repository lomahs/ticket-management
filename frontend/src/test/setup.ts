// Node 25 ships its own `localStorage` global, which hides jsdom's and has no methods without --localstorage-file.
// Tests get an in-memory Storage instead; browsers are unaffected.
if (typeof globalThis.localStorage?.setItem !== 'function') {
  const data = new Map<string, string>();
  const storage: Storage = {
    get length() { return data.size; },
    clear: () => data.clear(),
    getItem: (k) => data.get(k) ?? null,
    key: (i) => [...data.keys()][i] ?? null,
    removeItem: (k) => { data.delete(k); },
    setItem: (k, v) => { data.set(k, String(v)); },
  };
  Object.defineProperty(globalThis, 'localStorage', { value: storage, configurable: true });
}
