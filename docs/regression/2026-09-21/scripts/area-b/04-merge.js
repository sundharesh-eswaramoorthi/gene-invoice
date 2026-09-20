// Merges the three area-B runs into docs/regression/2026-09-21/data/area-b.json.
const fs = require('fs');
const path = require('path');

const OUT = path.join(__dirname, 'out');
const DATA = path.resolve(__dirname, '../../data');

const parts = ['api.json', 'ui.json', 'cross.json']
  .map((f) => JSON.parse(fs.readFileSync(path.join(OUT, f), 'utf8')).cases);
const cases = parts.flat().sort((a, b) => a.id.localeCompare(b.id, 'en', { numeric: true }));

const ids = new Set();
for (const c of cases) {
  if (ids.has(c.id)) throw new Error('duplicate case id ' + c.id);
  ids.add(c.id);
  for (const k of ['id', 'feature', 'kind', 'ac', 'title', 'status', 'severity', 'steps',
    'expected', 'actual', 'evidence', 'codeRef']) {
    if (c[k] === undefined || c[k] === null) throw new Error(`case ${c.id} missing ${k}`);
  }
  // Screenshot paths are recorded relative to the run folder.
  if (c.evidence.includes('/report/shots/')) {
    c.evidence = 'report/shots/' + c.evidence.split('/report/shots/')[1];
  }
}

fs.mkdirSync(DATA, { recursive: true });
fs.writeFileSync(path.join(DATA, 'area-b.json'),
  JSON.stringify({ area: 'B — overdue status and ageing by days past due', cases }, null, 2) + '\n');

const by = (s) => cases.filter((c) => c.status === s).length;
console.log(`${cases.length} cases: ${by('PASS')} PASS, ${by('FAIL')} FAIL, ${by('BLOCKED')} BLOCKED, ${by('NOT_TESTED')} NOT_TESTED`);
console.log('ACs covered:', [...new Set(cases.map((c) => c.ac))].sort().join(', '));
