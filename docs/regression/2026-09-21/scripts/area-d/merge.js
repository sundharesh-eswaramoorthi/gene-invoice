// Collects every script's cases into docs/regression/2026-09-21/data/area-d.json.
const fs = require('fs');
const path = require('path');
const h = require('./helpers');

const out = path.join(h.RUN, 'data', 'area-d.json');
// Files are read in name order, so a later script re-running a case (the 08 re-check against the
// rebuilt web bundle) replaces the earlier result for that id.
const latest = new Map();
for (const f of fs.readdirSync(h.RESULTS).filter((x) => x.endsWith('.json')).sort()) {
  for (const c of JSON.parse(fs.readFileSync(path.join(h.RESULTS, f), 'utf8'))) latest.set(c.id, c);
}
const cases = [...latest.values()].sort((a, b) => a.id.localeCompare(b.id, 'en', { numeric: true }));

const KEYS = ['id', 'feature', 'kind', 'ac', 'title', 'status', 'severity', 'steps', 'expected',
  'actual', 'evidence', 'codeRef'];
const ids = new Set();
const ordered = cases.map((c) => {
  if (ids.has(c.id)) throw new Error(`duplicate case id ${c.id}`);
  ids.add(c.id);
  const row = {};
  for (const k of KEYS) {
    if (!(k in c)) throw new Error(`${c.id} missing ${k}`);
    row[k] = c[k];
  }
  return row;
});

fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, JSON.stringify({ area: 'D — Documents tab (UI)', cases: ordered }, null, 2));

const by = (s) => cases.filter((c) => c.status === s).length;
console.log(`${cases.length} cases -> ${out}`);
console.log(`PASS ${by('PASS')}  FAIL ${by('FAIL')}  BLOCKED ${by('BLOCKED')}  NOT_TESTED ${by('NOT_TESTED')}`);
for (const c of cases.filter((x) => x.status !== 'PASS')) {
  console.log(`${c.status} ${c.id} [${c.severity}] ${c.ac} — ${c.title}`);
}
