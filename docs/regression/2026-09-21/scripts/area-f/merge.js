// Merges every parts/*.json into docs/regression/2026-09-21/data/area-f.json.
const fs = require('fs');
const path = require('path');

const PARTS = path.join(__dirname, 'parts');
const OUT = path.join(__dirname, '..', '..', 'data', 'area-f.json');

const cases = fs.readdirSync(PARTS)
  .filter((f) => f.endsWith('.json') && f !== 'fixtures.json')
  .sort()
  .flatMap((f) => JSON.parse(fs.readFileSync(path.join(PARTS, f), 'utf8')))
  .sort((a, b) => Number(a.id.slice(2)) - Number(b.id.slice(2)));

const ids = cases.map((c) => c.id);
const dupes = ids.filter((id, i) => ids.indexOf(id) !== i);
if (dupes.length) throw new Error('duplicate case ids: ' + dupes.join(','));

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify({ area: 'F — blast radius', cases }, null, 2));

const by = (s) => cases.filter((c) => c.status === s).length;
console.log(`${cases.length} cases -> ${OUT}`);
console.log(`PASS ${by('PASS')}  FAIL ${by('FAIL')}  BLOCKED ${by('BLOCKED')}  NOT_TESTED ${by('NOT_TESTED')}`);
cases.filter((c) => c.status === 'FAIL').forEach((c) => console.log(`  ${c.id} [${c.severity}] ${c.title}`));
