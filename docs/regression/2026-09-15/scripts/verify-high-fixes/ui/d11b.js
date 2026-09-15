// D-11 follow-up: browser Back from a dirty detail (with Keep editing), Back after a blocked
// navigation, and the notifications bell clicked off / on its unread badge.
// Usage: node d11b.js customer|invoice|payment|phone   Writes d11b-<kind>.json.
const fs = require('fs');
const path = require('path');
const rt = require('../../lib.js');
const DIR = __dirname;
const S = JSON.parse(fs.readFileSync(path.join(DIR, 'state.json'), 'utf8'));
const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim();
const hash = (page) => page.evaluate(() => location.hash);
const dialogCount = async (page) => (await rt.semantics(page)).filter((n) => lab(n) === 'Discard unsaved changes?').length;
const RAIL = { Dashboard: 85, Invoices: 129, Payments: 173 };

const KINDS = {
  customer: { w: 1366, h: 900, list: '#/customers', path: `#/customers/${S.c11.id}`, field: 'Phone', api: `/api/customers/${S.c11.id}`, prop: 'phone' },
  invoice: { w: 1366, h: 900, list: '#/invoices', path: `#/invoices/${S.inv11.id}`, field: 'Notes', api: `/api/invoices/${S.inv11.id}`, prop: 'notes' },
  payment: { w: 1366, h: 900, list: '#/payments', path: `#/payments/${S.pay11.id}`, field: 'Notes', api: `/api/payments/${S.pay11.id}`, prop: 'notes' },
  phone: { w: 400, h: 820, list: '#/invoices', path: `#/invoices/${S.inv4.id}`, field: 'Notes', api: `/api/invoices/${S.inv4.id}`, prop: 'notes' },
};

(async () => {
  const kind = process.argv[2];
  const K = KINDS[kind];
  const out = { kind };
  const T = await rt.adminToken();
  out.serverBefore = (await rt.api('GET', K.api, { token: T })).json?.[K.prop];
  const app = await rt.openApp({ token: T, width: K.w, height: K.h });
  const { page } = app;
  let step = 0;
  const shot = (name) => rt.shot(page, DIR, `d11b-${kind}-${String(++step).padStart(2, '0')}-${name}`);
  const state = async () => ({ dialogs: await dialogCount(page), hash: await hash(page) });
  const settle = async (ms = 1500) => { await page.waitForTimeout(ms); await rt.enableSemantics(page); };
  async function open() {
    await rt.go(page, K.list);
    await rt.go(page, K.path, 4000);
  }
  async function dirty() {
    const n = await rt.semantics(page);
    const l = n.find((x) => lab(x) === K.field && x.role !== 'button');
    await rt.clickAt(page, Math.min(Math.round(l.x + l.w / 2 + 60), K.w - 40), l.y + 8, 600);
    await rt.typeText(page, `vu-d11b-${Date.now().toString(36)}`, { clear: true });
    await page.waitForTimeout(600);
  }
  async function keep() { await rt.tap(page, 'Keep editing', { wait: 1500 }); await rt.enableSemantics(page); }
  async function leaveByNav(label) {
    if (K.w < 900) {
      await rt.clickAt(page, 28, 28, 1500);
      await rt.enableSemantics(page);
      const item = (await rt.semantics(page)).find((x) => lab(x) === label && x.x < 320);
      await rt.clickAt(page, item.x, item.y, 800);
    } else {
      await rt.clickAt(page, 110, RAIL[label], 800);
    }
    await settle(1200);
  }
  const bellNode = async () => (await rt.semantics(page)).find((x) => lab(x) === 'Notifications');
  const badgeNode = async () => (await rt.semantics(page)).find((x) => /^(99\+|\d+)$/.test(lab(x)) && x.y < 50);

  try {
    // 1. Browser Back straight from a dirty detail page
    await open();
    await dirty();
    await shot('dirty');
    await page.goBack();
    await settle();
    out.back1 = await state();
    out.back1.shot = await shot('back1-dialog');
    if (out.back1.dialogs) {
      await keep();
      await page.waitForTimeout(1000);
      out.back1.afterKeep = await state();
      out.back1.hrefAfterKeep = await page.evaluate(() => location.href);
      out.back1.shotAfterKeep = await shot('back1-after-keep');
    }
    // 2. Browser Back a second time
    await page.goBack();
    await settle();
    out.back2 = await state();
    out.back2.shot = await shot('back2');
    if (out.back2.dialogs) {
      await keep();
      out.back2.afterKeep = await state();
    }

    // 3. Bell clicked away from the badge
    let bell = await bellNode();
    const badge = await badgeNode();
    out.bellNode = bell && `${bell.x},${bell.y}`;
    out.badgeNode = badge && `${lab(badge)} ${badge.x},${badge.y} ${badge.w}x${badge.h}`;
    await rt.clickAt(page, bell.x - 10, bell.y + 12, 800);
    await settle(1200);
    out.bellOffBadge = await state();
    out.bellOffBadge.shot = await shot('bell-off-badge');
    if (out.bellOffBadge.dialogs) {
      await keep();
      out.bellOffBadge.afterKeep = await state();
    }
    // 4. Bell clicked on its badge
    if (badge) {
      await rt.clickAt(page, badge.x, badge.y, 800);
      await settle(1200);
      out.bellOnBadge = await state();
      out.bellOnBadge.shot = await shot('bell-on-badge');
      if (out.bellOnBadge.dialogs) await keep();
    }

    // 5. Back after a blocked sidebar/drawer navigation
    await leaveByNav('Payments');
    out.blockedNav = await state();
    if (out.blockedNav.dialogs) await keep();
    out.blockedNav.afterKeep = await state();
    await page.goBack();
    await settle();
    out.backAfterBlocked1 = await state();
    out.backAfterBlocked1.shot = await shot('back-after-blocked-1');
    if (out.backAfterBlocked1.dialogs) {
      await keep();
    } else {
      await page.goBack();
      await settle();
      out.backAfterBlocked2 = await state();
      out.backAfterBlocked2.shot = await shot('back-after-blocked-2');
      if (out.backAfterBlocked2.dialogs) await keep();
    }
    out.stateAtEnd = await state();
    out.serverAfter = (await rt.api('GET', K.api, { token: T })).json?.[K.prop];

    // 6. Clean page: does the bell (badge / off badge) navigate at all?
    await open();
    const b2 = await badgeNode();
    if (b2) {
      await rt.clickAt(page, b2.x, b2.y, 800);
      await settle(1500);
      out.cleanBadgeClick = await state();
      out.cleanBadgeClick.shot = await shot('clean-badge-click');
      if (out.cleanBadgeClick.hash !== K.path) await open();
    }
    bell = await bellNode();
    await rt.clickAt(page, bell.x - 10, bell.y + 12, 800);
    await settle(1500);
    out.cleanBellOffBadge = await state();
    out.cleanBellOffBadge.shot = await shot('clean-bell-off-badge');
  } catch (e) {
    out.error = String(e.stack || e);
    await rt.shot(page, DIR, `d11b-${kind}-error`);
  } finally {
    out.apiErrors = app.apiErrors;
    out.pageErrors = app.pageErrors.slice(0, 5);
    await app.close();
  }
  fs.writeFileSync(path.join(DIR, `d11b-${kind}.json`), JSON.stringify(out, null, 1));
  console.log(JSON.stringify(out, (k, v) => (/shot/i.test(k) ? undefined : v), 1));
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
