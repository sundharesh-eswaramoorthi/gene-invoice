// D-11 re-run (3): with unsaved edits, a History-tab record link and a Disputes-tab row each prompt
// exactly once; Keep editing stays (history unchanged); Discard navigates once with no second prompt.
// Usage: node d11r.js customer|invoice|payment   Writes d11r-<kind>.json.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const KINDS = {
  customer: { list: '#/customers', path: `#/customers/${S.c11.id}`, field: 'Phone', api: `/api/customers/${S.c11.id}`, prop: 'phone', link: /Payment #173/, linkTo: `#/payments/${S.pay11.id}` },
  invoice: { list: '#/invoices', path: `#/invoices/${S.inv11.id}`, field: 'Notes', api: `/api/invoices/${S.inv11.id}`, prop: 'notes', link: /Dispute #149/, linkTo: '#/disputes/149' },
  payment: { list: '#/payments', path: `#/payments/${S.pay11.id}`, field: 'Notes', api: `/api/payments/${S.pay11.id}`, prop: 'notes', link: /Dispute #150/, linkTo: '#/disputes/150' },
};

(async () => {
  const kind = process.argv[2];
  const K = KINDS[kind];
  const out = { kind };
  const T = await rt.adminToken();
  out.serverBefore = (await rt.api('GET', K.api, { token: T })).json?.[K.prop];
  const app = await rt.openApp({ token: T, width: 1366, height: 900 });
  const { page } = app;
  let step = 0;
  const shot = (name) => rt.shot(page, DIR, `d11r-${kind}-${String(++step).padStart(2, '0')}-${name}`);
  const settle = async (ms = 1500) => { await page.waitForTimeout(ms); await rt.enableSemantics(page); };
  const st = async () => ({
    hash: await page.evaluate(() => location.hash),
    historyLength: await page.evaluate(() => history.length),
    dialogs: (await rt.semantics(page)).filter((n) => lab(n) === 'Discard unsaved changes?').length,
  });
  async function openDirty() {
    await rt.go(page, K.list);
    await rt.go(page, K.path, 4000);
    for (let i = 0; i < 3; i++) {
      const n = await rt.semantics(page);
      const l = n.find((x) => lab(x) === K.field && x.role !== 'button');
      await rt.clickAt(page, Math.round(l.x + l.w / 2 + 60), l.y + 8, 800);
      await rt.typeText(page, kind === 'customer' ? `555-${Date.now().toString().slice(-4)}` : `vu-d11r-${Date.now().toString(36)}`, { clear: true });
      await settle(800);
      if ((await rt.semantics(page)).some((x) => lab(x) === 'Unsaved changes')) return true;
    }
    return false;
  }
  async function openTab(name) {
    const t = (await rt.semantics(page)).find((x) => x.role === 'tab' && lab(x).includes(name));
    await rt.clickAt(page, t.x, t.y, 2500);
    await rt.enableSemantics(page);
    return st();
  }
  // Clicks the History record link (inside its merged card) or the Disputes row; returns what happened.
  async function clickTarget(which) {
    const n = await rt.semantics(page);
    let x; let y; let node;
    if (which === 'history') {
      node = n.find((c) => c.role === 'button' && K.link.test(lab(c)) && c.h >= 60);
      x = 360; y = node.y + 1; // the link line of the card (from screenshots)
    } else {
      node = n.find((c) => c.role === 'button' && /vu D-11 rerun row/.test(lab(c)));
      x = node.x - 300; y = node.y;
    }
    await rt.clickAt(page, x, y, 600);
    await settle(1200);
    return { target: `${lab(node).replace(/\n/g, ' / ').slice(0, 80)} @${x},${y}`, ...(await st()) };
  }

  try {
    for (const which of ['history', 'disputes']) {
      const r = (out[which] = {});
      r.dirtyConfirmed = await openDirty();
      r.dirty = await st();
      r.tabSwitch = await openTab(which === 'history' ? 'History' : 'Disputes');
      r.unsavedHintAfterTab = (await rt.semantics(page)).some((x) => lab(x) === 'Unsaved changes');
      // Keep editing
      r.click1 = await clickTarget(which);
      r.click1.shot = await shot(`${which}-prompt`);
      if (r.click1.dialogs) {
        await rt.tap(page, 'Keep editing', { wait: 1500 });
        await settle(600);
        r.afterKeep = await st();
        r.afterKeep.unsavedHint = (await rt.semantics(page)).some((x) => lab(x) === 'Unsaved changes');
      }
      // Discard
      r.click2 = await clickTarget(which);
      if (r.click2.dialogs) {
        await rt.tap(page, 'Discard', { wait: 2500 });
        await settle(1000);
        r.afterDiscard = await st();
        r.afterDiscard.shot = await shot(`${which}-after-discard`);
      }
      r.serverAfter = (await rt.api('GET', K.api, { token: T })).json?.[K.prop];
    }
    out.expectedHistoryLinkTarget = K.linkTo;
  } catch (e) {
    out.error = String(e.stack || e);
    await rt.shot(page, DIR, `d11r-${kind}-error`);
  } finally {
    out.apiErrors = app.apiErrors;
    out.pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }
  fs.writeFileSync(path.join(DIR, `d11r-${kind}.json`), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(out, (k, v) => (/shot/i.test(k) ? undefined : v)));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
