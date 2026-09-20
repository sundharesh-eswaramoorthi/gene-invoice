// Compiles the testers' per-area JSON into the run's artefacts:
//   test-cases.csv      — every case, one row
//   test-results.md     — pass/fail per area and the full case list
//   report/data.js      — window.REPORT for report/index.html (reused from the 2026-09-15 run)
//
// Narrative (headline, executive summary, defects, recommendations, gaps) is authored by hand in
// data/narrative.json; everything else is derived from the area files, so re-running after a
// tester re-runs is safe.
const fs = require('fs');
const path = require('path');

const RUN = path.join(__dirname, '..');
const DATA = path.join(RUN, 'data');

const TITLES = {
  a: 'A — Payment terms and invoice due dates (API)',
  b: 'B — Overdue status and ageing by days past due',
  c: 'C — Documents: API, security and limits',
  d: 'D — Documents: the tab in the UI',
  e: 'E — Permissions, privileges and scoping',
  f: 'F — Blast radius: did anything that worked stop working',
};

function areaFiles() {
  return fs.readdirSync(DATA)
      .filter(f => /^area-[a-z]\.json$/.test(f))
      .sort();
}

function load() {
  return areaFiles().map(f => {
    const key = f.match(/^area-([a-z])\.json$/)[1];
    const raw = JSON.parse(fs.readFileSync(path.join(DATA, f), 'utf8'));
    const cases = (raw.cases || []).map(c => ({ ...c, area: key }));
    return { key, title: TITLES[key] || raw.area || key, summary: raw.summary || '', cases };
  });
}

const count = (cases, status) => cases.filter(c => c.status === status).length;
const health = (pass, fail, high) =>
    high > 0 ? 'poor' : fail > 0 ? 'fair' : pass > 0 ? 'good' : 'unknown';

// ---- CSV ------------------------------------------------------------------------------------

const CSV_COLUMNS =
    ['area', 'id', 'feature', 'kind', 'ac', 'title', 'status', 'severity', 'steps', 'expected',
     'actual', 'evidence', 'codeRef'];

const csvCell = v => {
  const s = v === undefined || v === null ? '' : String(v);
  return /[",\n\r]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
};

function writeCsv(areas) {
  const rows = [CSV_COLUMNS.join(',')];
  for (const a of areas) {
    for (const c of a.cases) rows.push(CSV_COLUMNS.map(k => csvCell(c[k])).join(','));
  }
  fs.writeFileSync(path.join(RUN, 'test-cases.csv'), rows.join('\n') + '\n');
  return rows.length - 1;
}

// ---- Markdown -------------------------------------------------------------------------------

function writeMarkdown(areas, narrative, totals) {
  const L = [];
  L.push('# Test results — regression run 2026-09-21');
  L.push('');
  L.push(narrative.headline || '');
  L.push('');
  L.push('## Totals');
  L.push('');
  L.push('| Area | Cases | Pass | Fail | Blocked | Not tested | Health |');
  L.push('|---|---:|---:|---:|---:|---:|---|');
  for (const a of areas) {
    const pass = count(a.cases, 'PASS'), fail = count(a.cases, 'FAIL');
    const high = a.cases.filter(c => c.status === 'FAIL' && c.severity === 'high').length;
    L.push(`| ${a.title} | ${a.cases.length} | ${pass} | ${fail} | ` +
           `${count(a.cases, 'BLOCKED')} | ${count(a.cases, 'NOT_TESTED')} | ${health(pass, fail, high)} |`);
  }
  L.push(`| **Total** | **${totals.cases}** | **${totals.pass}** | **${totals.fail}** | ` +
         `**${totals.blocked}** | **${totals.notTested}** | |`);
  L.push('');

  if (narrative.executiveSummary) {
    L.push('## Summary');
    L.push('');
    L.push(narrative.executiveSummary);
    L.push('');
  }

  const failures = areas.flatMap(a => a.cases.filter(c => c.status === 'FAIL'));
  L.push('## Failures');
  L.push('');
  if (!failures.length) {
    L.push('None. Every case that ran passed.');
  } else {
    L.push('| Case | Severity | AC | Title | Observed |');
    L.push('|---|---|---|---|---|');
    for (const c of failures) {
      const obs = (c.actual || '').replace(/\|/g, '\\|').replace(/\n/g, ' ').slice(0, 160);
      L.push(`| ${c.id} | ${c.severity || ''} | ${c.ac || ''} | ${c.title} | ${obs} |`);
    }
  }
  L.push('');

  // Acceptance-criterion coverage, so a reader can see which ACs were actually exercised.
  const byAc = {};
  for (const a of areas) {
    for (const c of a.cases) {
      if (!c.ac) continue;
      for (const ac of String(c.ac).split(/[,\s]+/).filter(Boolean)) {
        (byAc[ac] = byAc[ac] || []).push(c);
      }
    }
  }
  const acKey = s => {
    const m = /^AC-([A-Z])(\d+)$/.exec(s);
    return m ? [m[1], Number(m[2])] : [s, 0];
  };
  const acs = Object.keys(byAc).sort((x, y) => {
    const [xa, xn] = acKey(x), [ya, yn] = acKey(y);
    return xa === ya ? xn - yn : xa < ya ? -1 : 1;
  });
  L.push('## Acceptance-criterion coverage');
  L.push('');
  L.push('| AC | Cases | Pass | Fail |');
  L.push('|---|---:|---:|---:|');
  for (const ac of acs) {
    L.push(`| ${ac} | ${byAc[ac].length} | ${count(byAc[ac], 'PASS')} | ${count(byAc[ac], 'FAIL')} |`);
  }
  L.push('');

  for (const a of areas) {
    L.push(`## ${a.title}`);
    L.push('');
    if (a.summary) { L.push(a.summary); L.push(''); }
    L.push('| Case | Kind | AC | Title | Status |');
    L.push('|---|---|---|---|---|');
    for (const c of a.cases) {
      L.push(`| ${c.id} | ${c.kind || ''} | ${c.ac || ''} | ${c.title} | ${c.status} |`);
    }
    L.push('');
  }

  if (narrative.gaps && narrative.gaps.length) {
    L.push('## What was not tested');
    L.push('');
    for (const g of narrative.gaps) L.push(`- ${g}`);
    L.push('');
  }
  if (narrative.recommendations && narrative.recommendations.length) {
    L.push('## Recommendations');
    L.push('');
    for (const r of narrative.recommendations) L.push(`- ${r}`);
    L.push('');
  }

  fs.writeFileSync(path.join(RUN, 'test-results.md'), L.join('\n'));
}

// ---- report/data.js -------------------------------------------------------------------------

function writeReport(areas, narrative, totals) {
  const shotsDir = path.join(RUN, 'report', 'shots');
  const shots = fs.existsSync(shotsDir) ? fs.readdirSync(shotsDir) : [];
  const defectShots = {};
  for (const d of narrative.defects || []) {
    const hit = shots.filter(s => s.startsWith(d.id + '-') || s.startsWith(d.id + '.'));
    if (hit.length) defectShots[d.id] = hit.map(s => 'shots/' + s);
  }

  const payload = {
    report: {
      headline: narrative.headline || '',
      executiveSummary: narrative.executiveSummary || '',
      areas: areas.map(a => {
        const pass = count(a.cases, 'PASS'), fail = count(a.cases, 'FAIL');
        const high = a.cases.filter(c => c.status === 'FAIL' && c.severity === 'high').length;
        return {
          key: a.key,
          title: a.title,
          pass,
          fail,
          blocked: count(a.cases, 'BLOCKED'),
          confirmedDefects: (narrative.defects || []).filter(d => d.area === a.key).length,
          health: health(pass, fail, high),
          note: a.summary || '',
        };
      }),
      defects: narrative.defects || [],
      notReproduced: narrative.notReproduced || [],
      gaps: narrative.gaps || [],
      recommendations: narrative.recommendations || [],
      totals,
    },
    areas: areas.map(a => ({
      key: a.key,
      title: a.title,
      summary: a.summary || '',
      cases: a.cases,
      notTested: a.cases.filter(c => c.status === 'NOT_TESTED'),
      verdicts: [],
    })),
    defectShots,
  };

  fs.writeFileSync(path.join(RUN, 'report', 'data.js'),
                   'window.REPORT = ' + JSON.stringify(payload) + ';\n');
}

// ---- main -----------------------------------------------------------------------------------

const areas = load();
if (!areas.length) {
  console.error('No area-*.json files in ' + DATA);
  process.exit(1);
}

const narrativePath = path.join(DATA, 'narrative.json');
const narrative = fs.existsSync(narrativePath)
    ? JSON.parse(fs.readFileSync(narrativePath, 'utf8'))
    : {};

const all = areas.flatMap(a => a.cases);
const totals = {
  cases: all.length,
  pass: count(all, 'PASS'),
  fail: count(all, 'FAIL'),
  blocked: count(all, 'BLOCKED'),
  notTested: count(all, 'NOT_TESTED'),
  high: all.filter(c => c.status === 'FAIL' && c.severity === 'high').length,
  medium: all.filter(c => c.status === 'FAIL' && c.severity === 'medium').length,
  low: all.filter(c => c.status === 'FAIL' && c.severity === 'low').length,
};

const rows = writeCsv(areas);
writeMarkdown(areas, narrative, totals);
writeReport(areas, narrative, totals);

console.log(`areas   : ${areas.map(a => a.key).join(', ')}`);
console.log(`cases   : ${totals.cases} (${rows} CSV rows)`);
console.log(`status  : ${totals.pass} pass, ${totals.fail} fail, ` +
            `${totals.blocked} blocked, ${totals.notTested} not tested`);
console.log(`failures: ${totals.high} high, ${totals.medium} medium, ${totals.low} low`);
