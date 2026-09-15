// D-11: unsaved-changes guard on Customer, Invoice and Payment details (desktop 1366x900).
// Usage: node d11.js [customer|invoice|payment]   (default: all three). Writes d11-<kind>.json.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const fmt = (n) => n && `[${n.role}] ${lab(n).replace(/\n/g, ' / ').slice(0, 90)} @${n.x},${n.y} ${n.w}x${n.h}`;
const hash = (page) => page.evaluate(() => location.hash);
const href = (page) => page.evaluate(() => location.href);
const dialogCount = async (page) => (await rt.semantics(page)).filter((n) => lab(n) === 'Discard unsaved changes?').length;
const inputs = (page) => page.$$eval('input, textarea', (els) => els.map((e) => e.value).filter(Boolean));
// The desktop NavigationRail items are not in the semantics tree on detail screens, so click them
// at their painted position (read from screenshots at 1366x900).
const RAIL = { Dashboard: 85, Invoices: 129, Payments: 173, Promises: 217, Disputes: 261, Customers: 305 };
async function sidebar(page, label) {
  const n = (await rt.semantics(page)).find((x) => lab(x) === label && x.x < 250);
  return n || { x: 110, y: RAIL[label], role: 'pixel', label };
}

const KINDS = {
  customer: { list: '#/customers', path: `#/customers/${S.c11.id}`, field: 'Phone', api: `/api/customers/${S.c11.id}`, prop: 'phone', value: () => `555-${Date.now().toString().slice(-4)}` },
  invoice: { list: '#/invoices', path: `#/invoices/${S.inv11.id}`, field: 'Notes', api: `/api/invoices/${S.inv11.id}`, prop: 'notes', value: () => `vu-d11 inv ${Date.now().toString(36)}` },
  payment: { list: '#/payments', path: `#/payments/${S.pay11.id}`, field: 'Notes', api: `/api/payments/${S.pay11.id}`, prop: 'notes', value: () => `vu-d11 pay ${Date.now().toString(36)}` },
};

async function run(kind) {
  const K = KINDS[kind];
  const out = { kind };
  const T = await rt.adminToken();
  out.serverBefore = (await rt.api('GET', K.api, { token: T })).json?.[K.prop];
  const app = await rt.openApp({ token: T, width: 1366, height: 900 });
  const { page } = app;
  let step = 0;
  const shot = async (name) => rt.shot(page, DIR, `d11-${kind}-${String(++step).padStart(2, '0')}-${name}`);
  async function makeDirty(fresh = true) {
    if (fresh) {
      await rt.go(page, K.list);
      await rt.go(page, K.path, 4000);
    }
    const n = await rt.semantics(page);
    const l = n.find((x) => lab(x) === K.field && x.role !== 'button');
    await rt.clickAt(page, Math.round(l.x + l.w / 2 + 60), l.y + 8, 600);
    const v = K.value();
    await rt.typeText(page, v, { clear: true });
    await page.waitForTimeout(600);
    return v;
  }
  async function keep() {
    await rt.tap(page, 'Keep editing', { wait: 1500 });
    await rt.enableSemantics(page);
  }
  async function attempt(name, action) {
    const r = { hashBefore: await hash(page) };
    await action();
    await page.waitForTimeout(1200);
    await rt.enableSemantics(page);
    r.dialogs = await dialogCount(page);
    r.hashWithDialog = await hash(page);
    r.shotDialog = await shot(name + '-dialog');
    if (r.dialogs > 0) {
      await keep();
      await page.waitForTimeout(800);
      r.dialogsAfterKeep = await dialogCount(page);
      r.hashAfterKeep = await hash(page);
      r.hrefAfterKeep = await href(page);
      r.inputsAfterKeep = await inputs(page);
      r.shotAfterKeep = await shot(name + '-after-keep');
    }
    return r;
  }
  try {
    const v1 = await makeDirty();
    out.typed = v1;
    await shot('dirty');
    // (a) sidebar
    out.sidebar = await attempt('sidebar', async () => {
      const nav = await sidebar(page, 'Dashboard');
      await rt.clickAt(page, nav.x, nav.y, 800);
    });
    // (b) browser back
    out.browserBack = await attempt('browser-back', async () => { await page.goBack(); });
    // after Keep editing on browser Back, try the sidebar again to see the guard still holds
    out.sidebarAfterBrowserBack = await attempt('sidebar-again', async () => {
      const nav = await sidebar(page, 'Payments');
      await rt.clickAt(page, nav.x, nav.y, 800);
    });
    // (c) bell
    out.bell = await attempt('bell', async () => {
      const bell = await rt.find(page, 'Notifications');
      await rt.clickAt(page, bell.x, bell.y, 800);
    });
    // (d) allocation row (payment only)
    if (kind === 'payment') {
      out.allocationRow = await attempt('allocation-row', async () => {
        const n = await rt.semantics(page);
        const row = n.find((x) => /INV-\d{8}-\d+/.test(lab(x)) && /allocated/.test(lab(x)));
        out.allocationRowNode = fmt(row);
        await rt.clickAt(page, row.x, row.y, 800);
      });
    }
    // (e) tabs: no prompt expected, edit kept
    const tabs = {};
    for (const t of ['History', 'Payment Promise', 'Disputes']) {
      const node = (await rt.semantics(page)).find((x) => x.role === 'tab' && lab(x).includes(t));
      await rt.clickAt(page, node.x, node.y, 2000);
      await rt.enableSemantics(page);
      tabs[t] = { dialogs: await dialogCount(page), hash: await hash(page), inputs: await inputs(page) };
      if (tabs[t].dialogs) await keep();
    }
    out.tabs = tabs;
    out.shotTabs = await shot('after-tabs');
    // Back arrow: one prompt; Keep editing leaves none behind
    out.backArrowKeep = await attempt('back-arrow', async () => {
      const b = await rt.find(page, 'Back');
      await rt.clickAt(page, b.x, b.y, 800);
    });
    // (f) Discard via the Back arrow: exactly one prompt, then navigation, no second prompt
    {
      const b = await rt.find(page, 'Back');
      await rt.clickAt(page, b.x, b.y, 1500);
      await rt.enableSemantics(page);
      const r = { dialogs: await dialogCount(page) };
      await rt.tap(page, 'Discard', { wait: 2500 });
      await rt.enableSemantics(page);
      r.dialogsAfterDiscard = await dialogCount(page);
      r.hashAfterDiscard = await hash(page);
      r.shot = await shot('back-arrow-discard');
      r.serverAfter = (await rt.api('GET', K.api, { token: T })).json?.[K.prop];
      out.backArrowDiscard = r;
    }
    // Discard via the sidebar
    {
      await makeDirty();
      const nav = await sidebar(page, 'Invoices');
      await rt.clickAt(page, nav.x, nav.y, 1500);
      await rt.enableSemantics(page);
      const r = { dialogs: await dialogCount(page) };
      await rt.tap(page, 'Discard', { wait: 2500 });
      await rt.enableSemantics(page);
      r.dialogsAfterDiscard = await dialogCount(page);
      r.hashAfterDiscard = await hash(page);
      r.shot = await shot('sidebar-discard');
      r.serverAfter = (await rt.api('GET', K.api, { token: T })).json?.[K.prop];
      out.sidebarDiscard = r;
    }
    // Discard via browser Back
    {
      await makeDirty();
      await page.goBack();
      await page.waitForTimeout(1500);
      await rt.enableSemantics(page);
      const r = { dialogs: await dialogCount(page), hashWithDialog: await hash(page) };
      if (r.dialogs) await rt.tap(page, 'Discard', { wait: 2500 });
      await rt.enableSemantics(page);
      r.dialogsAfterDiscard = await dialogCount(page);
      r.hashAfterDiscard = await hash(page);
      r.shot = await shot('browser-back-discard');
      out.browserBackDiscard = r;
    }
    // Save first, then navigate: no prompt
    {
      const v = await makeDirty();
      let n = await rt.semantics(page);
      let save = n.find((x) => lab(x) === 'Save changes');
      if (save.y > 500) {
        // The customer's top pane scrolls; bring the button into view.
        await page.mouse.move(800, 300);
        await page.mouse.wheel(0, 600);
        await page.waitForTimeout(1000);
        await rt.enableSemantics(page);
        n = await rt.semantics(page);
        save = n.find((x) => lab(x) === 'Save changes');
      }
      const r = { saveNode: fmt(save) };
      r.shotBeforeSave = await shot('save-first-before');
      await rt.clickAt(page, save.x, save.y, 3000);
      r.shotAfterSave = await shot('save-first-after');
      r.serverAfterSave = (await rt.api('GET', K.api, { token: T })).json?.[K.prop];
      r.typed = v;
      const nav = await sidebar(page, 'Dashboard');
      await rt.clickAt(page, nav.x, nav.y, 2000);
      await rt.enableSemantics(page);
      r.dialogs = await dialogCount(page);
      r.hashAfterNav = await hash(page);
      out.saveThenNavigate = r;
    }
  } catch (e) {
    out.error = String(e.stack || e);
    await rt.shot(page, DIR, `d11-${kind}-error`);
  } finally {
    out.apiErrors = app.apiErrors;
    out.pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }
  fs.writeFileSync(path.join(DIR, `d11-${kind}.json`), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(out, null, 1));
}

(async () => {
  const which = process.argv[2] ? [process.argv[2]] : Object.keys(KINDS);
  for (const k of which) await run(k);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
