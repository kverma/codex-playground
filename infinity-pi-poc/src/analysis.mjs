/** Deterministic, side-effect-free operation for PoC CI (no inference or credentials). */
export function analyzeFailures(args) {
  if (!args || !Array.isArray(args.failures) || args.failures.length > 1000 ||
      !args.failures.every(x => typeof x === 'string' && x.length <= 1000)) {
    throw new Error('failures must be an array of up to 1000 strings');
  }
  const groups = new Map();
  for (const item of args.failures) {
    const kind = /timeout/i.test(item) ? 'timeout' : /connection|network/i.test(item) ? 'network' : 'other';
    groups.set(kind, (groups.get(kind) ?? 0) + 1);
  }
  return JSON.stringify({ total: args.failures.length,
    groups: Object.fromEntries([...groups.entries()].sort(([a],[b]) => a.localeCompare(b))) });
}
