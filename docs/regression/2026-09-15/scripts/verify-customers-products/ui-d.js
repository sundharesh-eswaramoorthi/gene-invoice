// UI-008 (Name-required error persistence) with the Name field located from the screenshot, plus a UI-002 re-run.
const rt = require('../lib.js');
const fs = require('fs');
const path = require('path');
const D = __dirname;
const has = (s, re) => s.some((n) => re.test(n.label || '') || re.test(n.text || ''));
(async () => {
  const A = await rt.adminToken();
  const TAG = rt.uniq('vcpud');
  const a = (await rt.api('POST', '/api/customers', { token: A, body: { name: `${TAG} one`, phone: '555-8', email: `${TAG}@rt.local`, address: '9 UI St', username: TAG, password: rt.PASSWORD } })).json;
  console.log('customer', a.id, a.name);

  // UI-002 re-run at 400x800
  const n = await rt.openApp({ token: A, width: 400, height: 800 });
  await rt.go(n.page, '#/customers?f=' + encodeURIComponent(`name:contains:${TAG}`), 5000);
  console.log('narrow shot', await rt.shot(n.page, D, 'd-narrow'));
  await n.close();

  const app = await rt.openApp({ token: A, width: 1366, height: 1200 });
  const { page } = app;
  const puts = [];
  page.on('request', (r) => { if (r.method() === 'PUT' && r.url().includes(`/api/customers/${a.id}`)) puts.push(r.postData()); });
  await rt.go(page, '#/customers');
  await rt.go(page, `#/customers/${a.id}`, 4500);
  const FX = 886, FY = 238;
  await rt.clickAt(page, FX, FY, 500);
  await rt.typeText(page, '', { clear: true });
  await page.waitForTimeout(500);
  await rt.tap(page, 'Save changes', { wait: 1500 });
  let s = await rt.semantics(page);
  console.log('empty shot', await rt.shot(page, D, 'd-empty'), 'errorShown', has(s, /Name is required/), 'putsSent', puts.length);
  await rt.clickAt(page, FX, FY, 400);
  await rt.typeText(page, `${TAG} valid again`);
  await page.waitForTimeout(3000);
  s = await rt.semantics(page);
  console.log('typed shot', await rt.shot(page, D, 'd-typed'), 'errorStill', has(s, /Name is required/), 'unsaved', has(s, /Unsaved changes/));
  // Save now works and clears the error
  await rt.tap(page, 'Save changes', { wait: 2500 });
  s = await rt.semantics(page);
  const g = (await rt.api('GET', `/api/customers/${a.id}`, { token: A })).json;
  console.log('after save shot', await rt.shot(page, D, 'd-saved'), 'errorStill', has(s, /Name is required/), 'db name', g.name, 'puts', puts.length);
  console.log('apiErrors', JSON.stringify(app.apiErrors), 'pageErrors', JSON.stringify(app.pageErrors));
  await app.close();
})().catch((e) => { console.error('UI-D FAILED', e); process.exit(1); });
