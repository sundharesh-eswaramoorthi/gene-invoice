// Case runner for the regions suite: every case is a function returning {ok, actual, evidence, ...}.
// A FAIL is re-run once (the function builds its own fixtures where it writes); if the second attempt
// passes the case is NOT_TESTED ("not reproduced"), per the harness rules. Exceptions -> BLOCKED.
const path = require('path');
const fs = require('fs');
const rt = require('../../lib.js');

function makeRunner({ round }) {
  const E2E = rt.HOME;
  const file = path.join(E2E, 'runs', `r${round}`, 'regions.json');
  const shotsDir = path.join(E2E, 'runs', `r${round}`, 'regions', 'shots');
  const cases = [];
  let only = process.env.ONLY ? new RegExp(process.env.ONLY) : null;

  function put(c) {
    const i = cases.findIndex((x) => x.id === c.id);
    if (i >= 0) cases[i] = c; else cases.push(c);
    const tag = c.status === 'FAIL' ? `FAIL(${c.severity})` : c.status;
    console.log(`[${tag}] ${c.id} ${c.title}${c.status !== 'PASS' ? '\n    actual: ' + String(c.actual).slice(0, 600) : ''}`);
    save();
  }

  function save() {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify({ area: 'regions', round, env: { api: rt.API, web: rt.WEB }, cases }, null, 2));
  }

  const str = (v) => (typeof v === 'string' ? v : JSON.stringify(v));

  /**
   * meta: {id, feature, kind, prd, title, steps, expected, severity, codeRef}
   * fn(attempt) -> {ok, actual, evidence?, screenshot?, codeRef?, severity?, status?}
   */
  async function C(meta, fn) {
    if (only && !only.test(meta.id)) return null;
    let r1;
    try {
      r1 = await fn(1);
    } catch (e) {
      put(base(meta, { status: 'BLOCKED', actual: `exception: ${e && e.stack ? e.stack.split('\n').slice(0, 3).join(' ') : e}` }));
      return null;
    }
    if (r1.status) { put(base(meta, r1)); return r1; }
    if (r1.ok) { put(base(meta, { ...r1, status: 'PASS' })); return r1; }
    // Reproduce once more before it counts.
    let r2;
    try { r2 = await fn(2); } catch (e) { r2 = { ok: false, actual: `exception on re-run: ${e}` }; }
    if (r2.ok) {
      put(base(meta, { ...r1, status: 'NOT_TESTED', actual: `Not reproduced on the second attempt. First attempt: ${str(r1.actual)}`, evidence: str(r1.evidence || '') }));
      return r2;
    }
    put(base(meta, { ...r1, status: 'FAIL', actual: `${str(r1.actual)}  [reproduced on 2nd attempt: ${str(r2.actual).slice(0, 400)}]` }));
    return r1;
  }

  function base(meta, r) {
    return {
      id: meta.id, feature: meta.feature || '', kind: meta.kind || 'API', prd: meta.prd || 'B1', title: meta.title,
      status: r.status, severity: r.status === 'FAIL' ? (r.severity || meta.severity || 'medium') : '',
      steps: meta.steps || '', expected: meta.expected || '', actual: str(r.actual ?? ''),
      evidence: str(r.evidence ?? ''), screenshot: r.screenshot || '', codeRef: r.codeRef || (r.status === 'FAIL' ? meta.codeRef || '' : ''),
    };
  }

  return { C, cases, save, file, shotsDir };
}

// ---- small assertion helpers ----
const sortN = (a) => [...a].map(Number).sort((x, y) => x - y);
const same = (a, b) => JSON.stringify(sortN(a || [])) === JSON.stringify(sortN(b || []));
const ids = (r) => (r && r.json && Array.isArray(r.json.content) ? r.json.content.map((x) => x.id) : null);
const excerpt = (r, n = 300) => `${r.status} ${(r.text || '').slice(0, n)}`;

module.exports = { makeRunner, sortN, same, ids, excerpt };
