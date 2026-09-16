// D-72: row selection checkboxes must be in the accessibility tree, say whether they are ticked,
// and still drive selection — the bulk and export toolbar depends on them (W-13).
// Run: node ui-d72.js → prints a summary and writes ui-d72.json next to this file.
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');

const DIR = __dirname;
const out = { checks: [] };
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim().replace(/\n/g, ' / ');

function check(id, name, ok, detail) {
  out.checks.push({ id, defect: 'D-72', name, status: ok ? 'PASS' : 'FAIL', detail });
}

(async () => {
  const admin = await rt.adminToken();

  // ---- the data table at 1366 --------------------------------------------------------------
  {
    const app = await rt.openApp({ token: admin, width: 1366, height: 900 });
    try {
      const page = app.page;
      await rt.go(page, '#/invoices', 5000);
      await rt.enableSemantics(page);
      let nodes = await rt.semantics(page);
      const boxes = nodes.filter((n) => n.role === 'checkbox' && /Select row/.test(lab(n)));
      out.table = {
        checkboxes: boxes.length,
        firstLabels: boxes.slice(0, 3).map(lab),
        columnheaders: nodes.filter((n) => n.role === 'columnheader').length,
        cells: nodes.filter((n) => n.role === 'cell').length,
      };
      check('L-UI-05', 'every row offers a labelled "Select row" checkbox in the accessibility tree',
        boxes.length >= 5 && out.table.columnheaders >= 5 && out.table.cells > 50, out.table);

      if (boxes[0]) {
        await rt.clickAt(page, boxes[0].x, boxes[0].y, 1500);
        await rt.enableSemantics(page);
        nodes = await rt.semantics(page);
        const texts = nodes.map(lab);
        const ticked = nodes.filter((n) => n.role === 'checkbox' && /Select row/.test(lab(n)))
          .filter((n) => n.checked === true || n.selected === true).length;
        out.afterTick = {
          toolbar: texts.filter((t) => /selected|Export|Cancel unpaid|Clear/.test(t)).slice(0, 6),
          tickedNodes: ticked,
        };
        check('L-UI-06', 'ticking a row checkbox still opens the selection toolbar',
          texts.some((t) => /1 selected/.test(t)) && texts.some((t) => /Export selected/.test(t)),
          out.afterTick);
        await rt.shot(page, DIR, 'lo-72-selection-1366');
      }
    } finally {
      out.apiErrors_table = app.apiErrors;
      await app.close();
    }
  }

  // ---- the phone cards at 400 --------------------------------------------------------------
  {
    const app = await rt.openApp({ token: admin, width: 400, height: 780 });
    try {
      const page = app.page;
      await rt.go(page, '#/invoices', 6000);
      await rt.enableSemantics(page);
      const nodes = await rt.semantics(page);
      const boxes = nodes.filter((n) => n.role === 'checkbox' && /Select row/.test(lab(n)));
      out.cards = { checkboxes: boxes.length, labels: boxes.slice(0, 3).map(lab) };
      check('L-UI-07', 'the phone cards expose the same labelled checkbox', boxes.length >= 1, out.cards);
      await rt.shot(page, DIR, 'lo-72-selection-400');
    } finally {
      out.apiErrors_cards = app.apiErrors;
      await app.close();
    }
  }

  fs.writeFileSync(path.join(DIR, 'ui-d72.json'), JSON.stringify(out, null, 2));
  for (const c of out.checks) console.log(`${c.status.padEnd(5)} ${c.id} ${c.defect} ${c.name}`);
  const bad = out.checks.filter((c) => c.status !== 'PASS');
  if (bad.length) console.log('\n' + JSON.stringify(bad, null, 2));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
