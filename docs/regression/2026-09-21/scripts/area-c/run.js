// Area C — documents at the API level, with the weight on security and limits.
// Usage: node docs/regression/2026-09-21/scripts/area-c/run.js
const fs = require('fs');
const path = require('path');
const h = require('./helpers.js');
const fixtures = require('./fixtures.js');

const OUT = path.join(__dirname, '..', '..', 'data', 'area-c.json');

const MODULES = [
  ['01-crud', require('./01-crud.js')],
  ['02-limits', require('./02-limits.js')],
  ['03-content', require('./03-content.js')],
  ['04-traversal', require('./04-traversal.js')],
  ['05-authz', require('./05-authz.js')],
  ['06-storage', require('./06-storage.js')],
];

(async () => {
  const cases = [];
  const rec = h.recorder(cases);
  const ctx = await fixtures.build();
  ctx.uploaded = [];
  console.log(`fixtures: customer A=${ctx.A.customer.id} invoice=${ctx.A.invoice.id} payment=${ctx.A.payment.id}; `
    + `customer B=${ctx.B.customer.id} invoice=${ctx.B.invoice.id} payment=${ctx.B.payment.id}`);

  for (const [name, run] of MODULES) {
    console.log(`\n--- ${name} ---`);
    try {
      await run(ctx, rec);
    } catch (e) {
      rec({
        ac: '',
        title: `${name} could not finish`,
        status: 'BLOCKED',
        severity: 'high',
        steps: `node area-c/run.js -> ${name}`,
        expected: 'the module runs to the end',
        actual: `threw: ${e.message}`,
      });
      console.error(e);
    }
  }

  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify({ area: 'C — documents (API, security and limits)', cases }, null, 2));

  const by = (s) => cases.filter((c) => c.status === s).length;
  console.log(`\n${cases.length} cases: ${by('PASS')} PASS, ${by('FAIL')} FAIL, ${by('BLOCKED')} BLOCKED, ${by('NOT_TESTED')} NOT_TESTED`);
  console.log('written to ' + OUT);
  for (const c of cases.filter((x) => x.status !== 'PASS')) {
    console.log(`  ${c.status} ${c.id} [${c.severity || '-'}] ${c.ac} ${c.title}\n        ${c.actual}`);
  }
})().catch((e) => { console.error(e); process.exit(1); });
