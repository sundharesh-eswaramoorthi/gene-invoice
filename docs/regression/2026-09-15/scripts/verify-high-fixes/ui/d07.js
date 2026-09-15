// D-07: notes-only save on Invoice and Payment details when the named POC has been deactivated.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const out = {};
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const fmt = (n) => n && `[${n.role}] ${lab(n).replace(/\n/g, ' / ').slice(0, 90)} @${n.x},${n.y} ${n.w}x${n.h}`;
async function snap(page, name) {
  const nodes = await rt.semantics(page);
  out['sem_' + name] = nodes.map(fmt);
  out['shot_' + name] = await rt.shot(page, DIR, name);
  return nodes;
}
const byLab = (nodes, m) => nodes.find((n) => (m instanceof RegExp ? m.test(lab(n)) : lab(n) === m));
async function focusBeside(page, nodes, label) {
  const l = nodes.find((n) => lab(n) === label && n.role !== 'button');
  if (!l) throw new Error('no label ' + label);
  await rt.clickAt(page, Math.round(l.x + l.w / 2 + 60), l.y + 8, 600);
}

(async () => {
  const T = await rt.adminToken();
  out.usersBefore = {
    sales: (await rt.api('GET', `/api/users/${S.sx.id}`, { token: T })).json?.active,
    coll: (await rt.api('GET', `/api/users/${S.cx.id}`, { token: T })).json?.active,
  };
  const app = await rt.openApp({ token: T, width: 1366, height: 900 });
  const { page } = app;
  const patches = [];
  page.on('request', (r) => { if (r.method() === 'PATCH') patches.push({ url: r.url().replace(rt.API, ''), body: r.postData() }); });
  try {
    for (const [kind, id, msg] of [['invoices', S.inv7.id, 'Invoice saved'], ['payments', S.pay7.id, 'Payment saved']]) {
      await rt.go(page, `#/${kind}`);
      await rt.go(page, `#/${kind}/${id}`, 4000);
      let n = await snap(page, `d07-${kind}-1-open`);
      await focusBeside(page, n, 'Notes');
      const val = `vu-d07 ${kind} notes ${Date.now().toString(36)}`;
      await rt.typeText(page, val, { clear: true });
      await page.waitForTimeout(600);
      n = await snap(page, `d07-${kind}-2-dirty`);
      const save = byLab(n, 'Save changes');
      await rt.clickAt(page, save.x, save.y, 3000);
      n = await snap(page, `d07-${kind}-3-after-save`);
      const srv = (await rt.api('GET', `/api/${kind}/${id}`, { token: T })).json;
      out[kind] = {
        typed: val,
        texts: n.map(lab).filter((t) => /saved|Unsaved|inactive|cannot|error/i.test(t)),
        serverNotes: srv?.notes,
        serverPoc: kind === 'invoices' ? srv?.salesPoc : srv?.collectionPoc,
      };
    }
  } catch (e) {
    out.error = String(e.stack || e);
    await rt.shot(page, DIR, 'd07-error');
  } finally {
    out.patches = patches;
    out.apiErrors = app.apiErrors;
    out.pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }
  fs.writeFileSync(path.join(DIR, 'd07.json'), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(Object.fromEntries(Object.entries(out).filter(([k]) => !k.startsWith('sem_'))), null, 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
