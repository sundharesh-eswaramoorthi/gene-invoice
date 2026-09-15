const fs = require('fs');
const path = require('path');

function makeHarness(name) {
  const R = [];
  async function tc(id, title, fn) {
    try {
      const r = await fn();
      const status = r.ok ? 'PASS' : 'FAIL';
      R.push({ id, title, status, actual: r.actual, evidence: r.evidence || '' });
      console.log(`${status} ${id} ${title}\n    actual: ${r.actual}${r.evidence ? `\n    evidence: ${r.evidence}` : ''}`);
    } catch (e) {
      R.push({ id, title, status: 'BLOCKED', actual: String(e && e.stack || e), evidence: '' });
      console.log(`BLOCKED ${id} ${title}: ${e && e.stack || e}`);
    }
  }
  function save() {
    const f = path.join(__dirname, `results-${name}.json`);
    fs.writeFileSync(f, JSON.stringify(R, null, 2));
    console.log(`saved ${f}: ${R.filter((x) => x.status === 'PASS').length} pass, ${R.filter((x) => x.status === 'FAIL').length} fail, ${R.filter((x) => x.status === 'BLOCKED').length} blocked`);
  }
  return { tc, save, R };
}
const snip = (r) => `${r.status} ${(r.text || '').slice(0, 220)}`;
module.exports = { makeHarness, snip };
