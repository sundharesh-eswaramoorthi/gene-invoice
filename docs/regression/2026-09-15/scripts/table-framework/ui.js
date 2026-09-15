// UI regression tests for the list/table framework on Invoices and Customers. Run: node ui.js
const fs = require('fs');
const path = require('path');
const rt = require('../lib.js');
const S = require('./state.json');
const P = S.P;
const only = process.argv.slice(2); const run = (n) => !only.length || only.includes(n);
const DIR = __dirname;
const results = [];
function rec(id, feature, title, ok, expected, actual, evidence = '') {
  results.push({ id, feature, title, status: ok ? 'PASS' : 'FAIL', expected, actual, evidence });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${id} ${title}${ok ? '' : `\n     expected: ${expected}\n     actual:   ${actual}`}\n     evidence: ${evidence}`);
}
const lbl = (n) => (n.label || n.text || '');
async function text(page) { return (await rt.semantics(page)).map(lbl).join(' | '); }
async function nodes(page, re, role) { return (await rt.semantics(page)).filter((n) => re.test(lbl(n)) && (!role || n.role === role)); }
async function tapNode(page, n, wait = 1500) { await page.locator(`flt-semantics[data-rt="${n.i}"]`).dispatchEvent('click'); await page.waitForTimeout(wait); }
async function clickRe(page, re, role, wait = 1500) { const [n] = await nodes(page, re, role); if (!n) throw new Error(`no node ${re}`); await rt.clickAt(page, n.x, n.y, wait); return n; }
async function tapRe(page, re, role, wait) { const [n] = await nodes(page, re, role); if (!n) throw new Error(`no node ${re} on: ${(await text(page)).slice(0, 800)}`); await tapNode(page, n, wait); return n; }
const url = (page) => decodeURIComponent(page.url());
const pager = async (page) => (await nodes(page, /^\d+–\d+ of \d+$|^Page \d+ of \d+$/)).map(lbl).join(' / ');

async function addFilter(page, column, condition, { value, chips } = {}) {
  await rt.tap(page, 'Add filter');
  await tapRe(page, /^Column\n/, 'button', 1000);
  await tapRe(page, new RegExp(`^${column.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`), 'menuitem', 1000);
  if (condition) {
    await tapRe(page, /^Condition\n/, 'button', 1000);
    await tapRe(page, new RegExp(`^${condition}$`), 'menuitem', 1000);
  }
  if (value !== undefined) { await rt.clickAt(page, 683, 478, 600); await rt.typeText(page, value, { clear: true }); await page.waitForTimeout(400); }
  for (const c of chips || []) await tapRe(page, new RegExp(`^${c}$`), null, 600);
  await rt.tap(page, 'Apply', { wait: 3000 });
}
async function apiCount(entity, filters) {
  const u = new URLSearchParams(); u.append('size', '10'); filters.forEach((f) => u.append('filter', f));
  return (await rt.api('GET', `/api/${entity}?${u}`, { token: ADMIN })).json.totalElements;
}
let ADMIN;

(async () => {
  ADMIN = await rt.adminToken();
  const app = await rt.openApp({ token: ADMIN });
  const { page } = app;
  const reqs = [];
  page.on('request', (r) => { if (r.url().startsWith(rt.API)) reqs.push({ m: r.method(), u: decodeURIComponent(r.url().slice(rt.API.length)), body: r.postData() }); });
  const exportBodies = [];
  page.on('response', async (r) => { if (/\/export$/.test(r.url())) { try { exportBodies.push({ req: r.request().postData(), body: await r.text() }); } catch { /* ignore */ } } });

  // ---------------- Invoices: Add filter / chip / URL ----------------
  await rt.go(page, '#/invoices', 5000);
  const n30 = await apiCount('invoices', [`customerName:contains:${P}`]);
  if (run('filters')) try {
    await addFilter(page, 'Customer name', null, { value: P });
    const u = url(page); const t = await text(page); const pg = await pager(page);
    const shot = await rt.shot(page, DIR, 'ui01-invoices-filter-chip');
    rec('UI-01', 'UI Invoices / filters', 'Add filter builds a "Customer name contains <prefix>" chip, writes it to the URL, and filters the rows',
      u.includes(`f=customerName:contains:${P}`) && t.includes(`Customer name contains ${P}`) && pg.includes(`of ${n30}`),
      `URL has f=customerName:contains:${P}; chip visible; pager "of ${n30}"`, `URL ${u}; chip present=${t.includes(`Customer name contains ${P}`)}; pager ${pg}`, shot);
  } catch (e) { rec('UI-01', 'UI Invoices / filters', 'Add filter builds a chip', false, 'chip', e.message.slice(0, 400)); }

  if (run('filters')) try {
    await addFilter(page, 'Status', 'is any of', { chips: ['UNPAID', 'PARTIALLY_PAID'] });
    const u = url(page); const pg = await pager(page);
    const want = await apiCount('invoices', [`customerName:contains:${P}`, 'status:in:UNPAID,PARTIALLY_PAID']);
    const shot = await rt.shot(page, DIR, 'ui02-invoices-two-chips');
    const t = await text(page);
    rec('UI-02', 'UI Invoices / filters', 'A second enum "is any of" chip ANDs with the first (both in URL, count matches API)',
      /status:in:(UNPAID,PARTIALLY_PAID|PARTIALLY_PAID,UNPAID)/.test(u) && u.includes('customerName:contains') && pg.includes(`of ${want}`),
      `URL has both f= params; pager "of ${want}"`, `URL ${u}; pager ${pg}; chips: ${(t.match(/Status is any of [^|]*/) || [''])[0]}`, shot);
  } catch (e) { rec('UI-02', 'UI Invoices / filters', 'Second enum chip', false, 'two chips', e.message.slice(0, 400)); }

  if (run('filters')) try {
    const chip = (await nodes(page, /^Status is any of/))[0];
    const dels = (await nodes(page, /^Delete$/, 'button')).filter((d) => Math.abs(d.y - chip.y) < 10 && d.x > chip.x - chip.w / 2 && d.x < chip.x + chip.w / 2 + 5);
    await tapNode(page, dels[0], 3000);
    const u = url(page); const pg = await pager(page);
    const shot = await rt.shot(page, DIR, 'ui03-invoices-chip-removed');
    rec('UI-03', 'UI Invoices / filters', 'Removing a chip (its delete icon) drops it from the URL and the list', !u.includes('status:in') && u.includes('customerName:contains') && pg.includes(`of ${n30}`),
      `URL without status filter; pager back to "of ${n30}"`, `URL ${u}; pager ${pg}`, shot);
    await rt.tap(page, 'Clear all', { wait: 3000 });
    const u2 = url(page); const t2 = await text(page);
    const shot2 = await rt.shot(page, DIR, 'ui04-invoices-clear-all');
    rec('UI-04', 'UI Invoices / filters', '"Clear all" removes every chip and the f= params', !u2.includes('f=') && !t2.includes('Customer name contains') && !t2.includes('Clear all'),
      'no f= in URL, no chips, Clear all hidden', `URL ${u2}; chip still shown=${t2.includes('Customer name contains')}`, shot2);
  } catch (e) { rec('UI-03', 'UI Invoices / filters', 'Remove chip / Clear all', false, 'removed', e.message.slice(0, 400)); }

  // ---------------- Invoices: page size, paging, memory, request count ----------------
  const fA = encodeURIComponent(`customerId:eq:${S.A}`);
  if (run('paging')) try {
    await rt.go(page, `#/invoices?f=${fA}`, 4500);
    const pg0 = await pager(page);
    await tapRe(page, /^20$/, 'button', 1000);
    await tapRe(page, /^10$/, 'menuitem', 3000);
    const u1 = url(page); const pg1 = await pager(page);
    reqs.length = 0;
    await rt.tap(page, 'Next page', { wait: 3000 });
    const u2 = url(page); const pg2 = await pager(page);
    const listReqs = reqs.filter((r) => /^\/api\/invoices\?/.test(r.u)).length;
    const sumReqs = reqs.filter((r) => /^\/api\/invoices\/summary/.test(r.u)).length;
    const shot = await rt.shot(page, DIR, 'ui05-invoices-page2-size10');
    rec('UI-05', 'UI Invoices / paging', 'Rows 20 -> 10 updates URL and pager; Next page -> page=1, rows 11–20',
      pg0.includes('1–20 of 24') && u1.includes('size=10') && pg1.includes('1–10 of 24') && pg1.includes('Page 1 of 3') && u2.includes('page=1') && pg2.includes('11–20 of 24') && pg2.includes('Page 2 of 3'),
      'default 1–20 of 24; after 10: 1–10 of 24 / Page 1 of 3; next: page=1, 11–20 of 24 / Page 2 of 3', `before ${pg0}; after size ${u1} ${pg1}; after next ${u2} ${pg2}`, shot);
    rec('UI-06', 'UI Invoices / paging (AC-D1)', 'Changing page fires exactly one backend request (tiles are not refetched)', listReqs === 1 && sumReqs === 0,
      '1 GET /api/invoices, 0 GET /api/invoices/summary on Next page (AC-D1; tiles depend only on filters)', `list requests ${listReqs}, summary requests ${sumReqs}: ${reqs.map((r) => r.u).join(' ; ')}`);

    await rt.go(page, '#/customers', 3500);
    await rt.go(page, '#/invoices', 4500);
    const u3 = url(page); const rowsBtn = (await nodes(page, /^(10|20|50)$/, 'button')).map(lbl); const pg3 = await pager(page);
    await page.reload(); await page.waitForTimeout(6000); await rt.enableSemantics(page);
    await rt.go(page, '#/invoices', 4500);
    const u4 = url(page); const rowsBtn2 = (await nodes(page, /^(10|20|50)$/, 'button')).map(lbl); const pg4 = await pager(page);
    const shot2 = await rt.shot(page, DIR, 'ui07-invoices-size-remembered');
    rec('UI-07', 'UI Invoices / paging', 'Chosen page size (10) is remembered when coming back to Invoices, and after a browser reload',
      rowsBtn.includes('10') && rowsBtn2.includes('10') && /^1–10 of/.test(pg3) && /^1–10 of/.test(pg4),
      'Rows 10 and 1–10 of N on return and after reload (D.1)', `return: ${u3} rows=${rowsBtn} ${pg3}; after reload: ${u4} rows=${rowsBtn2} ${pg4}`, shot2);
  } catch (e) { rec('UI-05', 'UI Invoices / paging', 'page size & paging', false, 'works', e.message.slice(0, 400)); }

  // ---------------- Invoices: sorting ----------------
  if (run('sort')) try {
    await rt.go(page, `#/invoices?size=10&f=${fA}`, 4500);
    await clickRe(page, /^Total$/, 'columnheader', 3000);
    const u1 = url(page);
    const cells1 = (await nodes(page, /^₹[\d,.]+$/, 'cell')).filter((n) => Math.abs(n.x - 894) < 5).map(lbl);
    const shot1 = await rt.shot(page, DIR, 'ui08-invoices-sort-total-asc');
    await clickRe(page, /^Total$/, 'columnheader', 3000);
    const u2 = url(page);
    const cells2 = (await nodes(page, /^₹[\d,.]+$/, 'cell')).filter((n) => Math.abs(n.x - 894) < 5).map(lbl);
    const shot2 = await rt.shot(page, DIR, 'ui08-invoices-sort-total-desc');
    const toN = (s) => Number(s.replace(/[₹,]/g, ''));
    const asc = cells1.map(toN); const desc = cells2.map(toN);
    const okAsc = asc.every((v, i) => i === 0 || asc[i - 1] <= v); const okDesc = desc.every((v, i) => i === 0 || desc[i - 1] >= v);
    rec('UI-08', 'UI Invoices / sorting', 'Clicking the sortable "Total" header sorts asc, clicking again sorts desc (URL + rows)',
      u1.includes('sort=total,asc') && u2.includes('sort=total,desc') && okAsc && okDesc && asc.length === 10,
      'sort=total,asc then total,desc; totals ascending then descending', `${u1} [${cells1.join(' ')}] ; ${u2} [${cells2.join(' ')}]`, `${shot1} , ${shot2}`);
  } catch (e) { rec('UI-08', 'UI Invoices / sorting', 'header sort', false, 'sorts', e.message.slice(0, 400)); }

  // ---------------- Invoices: empty / error / loading / out of range ----------------
  if (run('states')) try {
    await rt.go(page, `#/invoices?f=${encodeURIComponent('invoiceNumber:eq:NOPE-XYZ-000')}`, 4500);
    const t = await text(page);
    const shot = await rt.shot(page, DIR, 'ui09-invoices-empty');
    const ok = t.includes('No invoices match this filter') && t.includes('Clear all filters') && !t.includes('Request failed') && !t.includes('Loading');
    await rt.tap(page, 'Clear all filters', { wait: 3500 });
    const u = url(page);
    rec('UI-09', 'UI Invoices / states (AC-D11)', 'Empty result shows "No invoices match this filter" with "Clear all filters", which clears the URL filters', ok && !u.includes('f='),
      'empty message + Clear all filters; no error or spinner; clicking clears f=', `text has empty msg=${t.includes('No invoices match this filter')}; after clear URL ${u}`, shot);

    await rt.go(page, `#/invoices?f=${encodeURIComponent('total:contains:x')}`, 4500);
    const t2 = await text(page);
    const shot2 = await rt.shot(page, DIR, 'ui10-invoices-error');
    rec('UI-10', 'UI Invoices / states (AC-D11)', 'A failing request shows a distinct error state ("Request failed", server message, Retry) — not the empty state',
      t2.includes('Request failed') && t2.includes('Retry') && /not valid/.test(t2) && !t2.includes('No invoices match'),
      '"Request failed" + message "Operator contains is not valid…" + Retry', (t2.match(/Request failed[^]*?Retry/) || [t2.slice(0, 300)])[0].slice(0, 300), shot2);

    await page.route('**/api/invoices?**', async (route) => { await new Promise((r) => setTimeout(r, 7000)); await route.continue().catch(() => {}); });
    await rt.go(page, `#/invoices?size=10&f=${fA}&sort=total,asc`, 1800);
    const t3 = await text(page);
    const shot3 = await rt.shot(page, DIR, 'ui11-invoices-loading');
    await page.unroute('**/api/invoices?**');
    await page.waitForTimeout(6000);
    rec('UI-11', 'UI Invoices / states (AC-D11)', 'While the list request is in flight a distinct loading state ("Loading…" spinner) shows — not empty, not error',
      t3.includes('Loading') && !t3.includes('No invoices match') && !t3.includes('Request failed'), '"Loading…" with spinner', `semantics: ${t3.includes('Loading') ? 'Loading… present' : t3.slice(0, 300)}`, shot3);

    await rt.go(page, `#/invoices?page=99&size=10&f=${fA}`, 4500);
    const t4 = await text(page); const pg4 = await pager(page);
    const shot4 = await rt.shot(page, DIR, 'ui12-invoices-page-out-of-range');
    rec('UI-12', 'UI Invoices / states (AC-D11)', 'Opening a page beyond the end does not claim "no rows match this filter" when 24 rows match',
      !t4.includes('No invoices match this filter'),
      'a distinct "page out of range" / reset to last page, not the no-match empty state (AC-D11)', `text: ${t4.includes('No invoices match this filter') ? '"No invoices match this filter" shown' : 'no empty msg'}; pager "${pg4}"`, shot4);
  } catch (e) { rec('UI-09', 'UI Invoices / states', 'states', false, 'distinct', e.message.slice(0, 400)); }

  // ---------------- Invoices: selection toolbar / select all / export / bulk count ----------------
  if (run('select')) try {
    await rt.go(page, `#/invoices?size=10&f=${fA}&sort=total,asc`, 4500);
    const invCells = (await nodes(page, /^INV-/, 'cell')).sort((a, b) => a.y - b.y);
    await rt.clickAt(page, 290, invCells[0].y, 900);
    const t1 = await text(page);
    const inv2 = (await nodes(page, /^INV-/, 'cell')).sort((a, b) => a.y - b.y);
    await rt.clickAt(page, 290, inv2[1].y, 900);
    const t2 = await text(page);
    const shot1 = await rt.shot(page, DIR, 'ui13-invoices-2-selected');
    const selNums = [inv2[0], inv2[1]].map(lbl);
    exportBodies.length = 0;
    await rt.tap(page, 'Export selected', { wait: 2500 });
    const t3 = await text(page);
    const shot2 = await rt.shot(page, DIR, 'ui14-invoices-export-dialog');
    const eb = exportBodies[0] || {};
    const csvRows = (eb.body || '').trim().split('\r\n');
    rec('UI-13', 'UI Invoices / selection', 'Ticking rows shows the selection toolbar with the count ("1 selected", "2 selected") and the bulk/export actions',
      t1.includes('1 selected') && t2.includes('2 selected') && t2.includes('Export selected') && t2.includes('Cancel unpaid') && t2.includes('Select all 24 matching this filter'),
      '1 selected -> 2 selected; Select all 24…, Cancel unpaid, Reassign Sales POC, Export selected, Clear', `after 1: ${t1.includes('1 selected')}; after 2: ${(t2.match(/2 selected[^]*?Clear/) || [''])[0].slice(0, 200)}`, shot1);
    rec('UI-14', 'UI Invoices / export', '"Export selected" with 2 rows opens the "Exported CSV" dialog with exactly those 2 rows',
      t3.includes('Exported CSV') && csvRows.length === 3 && selNums.every((n) => (eb.body || '').includes(n)) && JSON.parse(eb.req || '{}').ids?.length === 2,
      'dialog "Exported CSV"; request ids = the 2 selected; CSV = header + 2 rows with those invoice numbers', `dialog=${t3.includes('Exported CSV')}; request ${eb.req}; CSV rows ${csvRows.length}: ${csvRows.slice(0, 3).join(' / ')}`, shot2);
    await rt.tap(page, 'Close', { wait: 1200 });

    await tapRe(page, /^Select all 24 matching this filter$/, 'button', 1200);
    const t4 = await text(page);
    const shot3 = await rt.shot(page, DIR, 'ui15-invoices-select-all-matching');
    exportBodies.length = 0;
    await rt.tap(page, 'Export selected', { wait: 2500 });
    const eb2 = exportBodies[0] || {};
    const rows2 = (eb2.body || '').trim().split('\r\n').length - 1;
    await rt.tap(page, 'Close', { wait: 1200 });
    await rt.tap(page, 'Cancel unpaid', { wait: 1500 });
    const t5 = await text(page);
    const shot4 = await rt.shot(page, DIR, 'ui16-invoices-bulk-confirm-count');
    const confirmMsg = (t5.match(/This will apply[^|]*/) || [''])[0];
    await rt.tap(page, 'Cancel', { wait: 1200 });
    rec('UI-15', 'UI Invoices / select all matching (AC-D7)', '"Select all 24 matching this filter" -> "24 selected"; export sends selectAllMatchingFilter with the filter and returns 24 rows; bulk confirm states the exact count',
      t4.includes('24 selected') && JSON.parse(eb2.req || '{}').selectAllMatchingFilter === true && rows2 === 24 && /to 24 records/.test(confirmMsg),
      '24 selected; export request selectAllMatchingFilter:true + filters; 24 CSV rows; confirm "…to 24 records."', `toolbar ${t4.includes('24 selected')}; export req ${eb2.req}; rows ${rows2}; confirm "${confirmMsg}"`, `${shot3} , ${shot4}`);

    // header checkbox selects the page; changing page clears selection
    await rt.tap(page, 'Clear', { wait: 1000 });
    const hdr = (await nodes(page, /^Invoice #$/, 'columnheader'))[0];
    await rt.clickAt(page, 290, hdr.y, 1000);
    const t6 = await text(page);
    await rt.tap(page, 'Next page', { wait: 3000 });
    const t7 = await text(page);
    rec('UI-16', 'UI Invoices / selection', 'Header checkbox selects the loaded page ("10 selected"); changing page clears the selection',
      t6.includes('10 selected') && !/\d+ selected/.test(t7), '10 selected; after Next page no selection toolbar', `header: ${(t6.match(/\d+ selected/) || ['none'])[0]}; after next: ${(t7.match(/\d+ selected/) || ['none'])[0]}`);
  } catch (e) { rec('UI-13', 'UI Invoices / selection', 'selection', false, 'toolbar', e.message.slice(0, 400)); }

  // ---------------- Customers ----------------
  if (run('customers')) try {
    await rt.go(page, '#/customers', 4500);
    const n28 = await apiCount('customers', [`name:contains:${P}`]);
    await addFilter(page, 'Name', 'contains', { value: P });
    const u = url(page); const pg = await pager(page); const t = await text(page);
    const shot = await rt.shot(page, DIR, 'ui17-customers-filter');
    rec('UI-17', 'UI Customers / filters', 'Customers: Add filter Name contains <prefix> -> chip, URL f=name:contains:<prefix>, count matches API',
      u.includes(`f=name:contains:${P}`) && t.includes(`Name contains ${P}`) && pg.includes(`of ${n28}`), `URL f=…; chip; "of ${n28}"`, `URL ${u}; pager ${pg}`, shot);

    await tapRe(page, /^20$/, 'button', 1000);
    await tapRe(page, /^50$/, 'menuitem', 3500);
    const u1 = url(page); const pg1 = await pager(page);
    await rt.go(page, '#/invoices', 3500);
    await rt.go(page, '#/customers', 4500);
    const u2 = url(page); const rb = (await nodes(page, /^(10|20|50)$/, 'button')).map(lbl); const pgR = await pager(page);
    rec('UI-18', 'UI Customers / paging', 'Customers Rows 20 -> 50 (URL size=50, "1–28 of 28") and 50 is remembered on return (per table)',
      u1.includes('size=50') && pg1.includes(`1–${n28} of ${n28}`) && rb.includes('50') && !rb.includes('20'),
      'size=50 now and on return', `after: ${u1} ${pg1}; return: ${u2} rows=${rb} ${pgR}`);

    await rt.go(page, `#/customers?size=10&f=${encodeURIComponent(`name:contains:${P}-cust-`)}`, 4500);
    const u3 = url(page);
    await clickRe(page, /^Name$/, 'columnheader', 3000);
    const u4 = url(page);
    const names = (await nodes(page, new RegExp(`^${P}-cust-\\d\\d(\n|$)`), 'cell')).sort((a, b) => a.y - b.y).map(lbl);
    await clickRe(page, /^Success POC$/, 'columnheader', 2500);
    const u5 = url(page);
    await clickRe(page, /^Credit$/, 'columnheader', 3000);
    const u6 = url(page);
    const shot2 = await rt.shot(page, DIR, 'ui19-customers-sort');
    rec('UI-19', 'UI Customers / sorting (AC-D3)', 'Sortable "Name" header toggles name,asc -> name,desc; unsortable "Success POC" header does nothing; "Credit" sorts by creditBalance',
      u3.includes('sort=name,asc') && u4.includes('sort=name,desc') && names[0].startsWith(`${P}-cust-23`) && u5 === u4 && u6.includes('sort=creditBalance,asc'),
      'name,desc with cust-23 first; URL unchanged after Success POC; creditBalance,asc', `start ${u3}; after Name ${u4} first=${names[0]}; after Success POC ${u5}; after Credit ${u6}`, shot2);

    await rt.go(page, `#/customers?size=10&f=${encodeURIComponent(`name:contains:${P}-cust-`)}&sort=name,asc`, 4500);
    const cells = (await nodes(page, new RegExp(`^${P}-cust-\\d\\d(\n|$)`), 'cell')).sort((a, b) => a.y - b.y);
    await rt.clickAt(page, 290, cells[0].y, 900);
    exportBodies.length = 0;
    await rt.tap(page, 'Export selected', { wait: 2500 });
    const t2 = await text(page);
    const eb = exportBodies[0] || {};
    const shot3 = await rt.shot(page, DIR, 'ui20-customers-export');
    const lines = (eb.body || '').trim().split('\r\n');
    rec('UI-20', 'UI Customers / export', 'Customers: select 1 row -> "1 selected" -> Export selected dialog shows header + that customer',
      t2.includes('Exported CSV') && lines.length === 2 && lines[0].startsWith('Id,Name,Phone,Email') && lines[1].includes(`${P}-cust-01`),
      'Exported CSV dialog with header + cust-01', `dialog=${t2.includes('Exported CSV')} csv: ${lines.join(' / ')}`, shot3);
    await rt.tap(page, 'Close', { wait: 1000 });

    await rt.go(page, `#/customers?f=${encodeURIComponent('name:contains:zzzz-no-such-customer')}`, 4500);
    const t3 = await text(page);
    const shot4 = await rt.shot(page, DIR, 'ui21-customers-empty');
    rec('UI-21', 'UI Customers / states', 'Customers empty filter result shows "No customers match this filter" and Clear all filters',
      t3.includes('No customers match this filter') && t3.includes('Clear all filters'), 'empty state text', t3.slice(0, 200), shot4);
  } catch (e) { rec('UI-17', 'UI Customers', 'customers flow', false, 'works', e.message.slice(0, 400)); }

  const outF = path.join(DIR, 'ui-results.json'); const prev = fs.existsSync(outF) ? JSON.parse(fs.readFileSync(outF, 'utf8')) : {}; const merged = Array.isArray(prev) ? Object.fromEntries(prev.map((r) => [r.id, r])) : prev; for (const r of results) merged[r.id] = r; fs.writeFileSync(outF, JSON.stringify(merged, null, 2));
  console.log('apiErrors', JSON.stringify(app.apiErrors).slice(0, 600));
  console.log('pageErrors', JSON.stringify(app.pageErrors).slice(0, 600));
  console.log(`${results.filter((r) => r.status === 'PASS').length} pass / ${results.filter((r) => r.status === 'FAIL').length} fail`);
  await app.close();
})().catch((e) => { console.error('UI RUN FAILED', e); process.exit(1); });
