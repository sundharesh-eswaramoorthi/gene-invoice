// Regression UI cases (Flutter web through the semantics tree). Fixtures come from the API.
// Expected behaviour: the pre-existing screens keep working for a single-region admin, a POC user and a customer
// login (DESIGN §7: the region selector is hidden with one region, the as-of chip is staff-only, lists send
// regionIds/asOf only when set; §6 for the data each screen shows).
const rt = require('../../lib.js');
const H = require('./helpers.js');

const { checks, customer, invoice, payment, getJ, openAs, waitText, allText, fill, waitReq, pick, rowBoxes, toggleRow } = H;

// Harness (r5): a nav entry with a badge (Approvals awaiting me, My tasks, Inbox) carries its count as an extra line of
// the same semantics node ("Approvals\n1"), so the count lines are dropped before comparing entry names.
const navText = (t) => String(t || '').split('\n').map((x) => x.trim()).filter((x) => x && !/^\d+\+?$/.test(x)).join('\n');

const FE = {
  login: 'frontend/lib/features/auth/login_screen.dart',
  shell: 'frontend/lib/shared/widgets/app_shell.dart',
  pw: 'frontend/lib/features/auth/change_password_dialog.dart',
  dash: 'frontend/lib/features/dashboard/dashboard_screen.dart',
  custs: 'frontend/lib/features/customers/customers_screen.dart',
  cust: 'frontend/lib/features/customers/customer_detail_screen.dart',
  pocEd: 'frontend/lib/features/poc/customer_poc_editor.dart',
  invs: 'frontend/lib/features/invoices/invoices_screen.dart',
  invForm: 'frontend/lib/features/invoices/invoice_form_screen.dart',
  inv: 'frontend/lib/features/invoices/invoice_detail_screen.dart',
  payDlg: 'frontend/lib/features/payments/record_payment_dialog.dart',
  pay: 'frontend/lib/features/payments/payment_detail_screen.dart',
  prom: 'frontend/lib/features/promises/promise_form_dialog.dart',
  proms: 'frontend/lib/features/promises/promises_screen.dart',
  dispDlg: 'frontend/lib/features/disputes/dispute_create_dialog.dart',
  disp: 'frontend/lib/features/disputes/dispute_detail_screen.dart',
  docs: 'frontend/lib/features/documents/documents_tab.dart',
  upload: 'frontend/lib/features/documents/upload_document_dialog.dart',
  mail: 'frontend/lib/features/email/send_email_dialog.dart',
  inbox: 'frontend/lib/features/email/inbox_screen.dart',
  notif: 'frontend/lib/features/notifications/notifications_screen.dart',
  audit: 'frontend/lib/features/audit/audit_history_panel.dart',
  table: 'frontend/lib/core/table/data_table_scaffold.dart',
  users: 'frontend/lib/features/users/users_screen.dart',
  roles: 'frontend/lib/features/users/roles_screen.dart',
  prods: 'frontend/lib/features/products/products_screen.dart',
  router: 'frontend/lib/core/router.dart',
};

function uiRunner(R) {
  /** Opens the app, runs body(app, c), always screenshots and closes; adds page/API errors as a no-crash check. */
  return async function ui(id, attempt, user, route, body, { allowStatuses = [], width, height, fresh = false } = {}) {
    const c = checks();
    const app = fresh ? await rt.openApp({ width, height }) : await openAs(user, route, { width, height });
    let shot = '';
    try {
      await body(app, c);
    } catch (e) {
      c.that('UI steps completed', false, String(e.message || e).slice(0, 600));
    } finally {
      try { shot = await rt.shot(app.page, R.shotsDir, `${id}-${attempt}`); } catch { /* ignore */ }
      const errs = app.apiErrors.filter((x) => !allowStatuses.includes(x.status));
      c.that('no page exception (no crash)', app.pageErrors.length === 0, app.pageErrors.slice(0, 3));
      c.that('no unexpected API error', errs.length === 0, errs.slice(0, 5));
      await app.close();
    }
    return { c, shot, requests: app.requests };
  };
}
const result = (x, codeRef, evidence = '', re = /\/api\//) => ({
  ok: x.c.ok, actual: x.c.summary(), screenshot: x.shot, codeRef,
  evidence: `${evidence}${evidence ? '; ' : ''}requests=${JSON.stringify(x.requests.filter((r) => re.test(r.url) && r.method !== 'GET' || /\?.*(filter|sort|size|months)=/.test(r.url)).map((r) => `${r.method} ${r.url} ${r.status}`)).slice(0, 700)}`,
});
const hash = (page) => page.evaluate(() => decodeURIComponent(location.hash));
const nodesText = async (page, pred) => (await rt.semantics(page)).filter(pred).map((n) => `${n.label || ''}|${n.text || ''}`);

module.exports = async function uiCases(w, R, only) {
  const tcase = H.caseRunner(R, only);
  const ui = uiRunner(R);
  const T = w.tokens; const U = w.users; const D = w.D;
  const sales = { username: U.sales.username, password: rt.PASSWORD };

  // ================================================================== LOGIN / LOGOUT / PASSWORD / NAV
  await tcase({ id: 'REG-UI-01', feature: 'Login screen', kind: 'UI', prd: 'B1',
    title: 'The login screen refuses a wrong password with a message and signs admin in to the dashboard',
    steps: 'open the web app signed out; Username admin, Password "bad", Sign in; then Password admin123, Sign in',
    expected: '"Invalid username or password" shown and still on #/login; after the right password: "Welcome, System Administrator", hash #/, token stored' }, async (a) => {
    const x = await ui('REG-UI-01', a, null, null, async ({ page }, c) => {
      await rt.fillField(page, 'Username', 'admin');
      await rt.fillField(page, 'Password', 'bad');
      await rt.tap(page, 'Sign in', { wait: 3000 });
      c.that('error message', !!(await waitText(page, 'Invalid username or password', 5000)));
      c.that('still on login', /login/.test(await hash(page)), await hash(page));
      await rt.fillField(page, 'Password', 'admin123');
      await rt.tap(page, 'Sign in', { wait: 4000 });
      await rt.enableSemantics(page);
      c.that('dashboard welcome', !!(await waitText(page, /Welcome, System Administrator/, 8000)));
      c.that('on the dashboard route (hash "#/" or none)', ['#/', ''].includes(await hash(page)), await hash(page));
      c.that('token stored', !!(await page.evaluate(() => localStorage.getItem('flutter.gene_invoice_token'))));
    }, { fresh: true, allowStatuses: [401] });
    return result(x, FE.login);
  });

  await tcase({ id: 'REG-UI-02', feature: 'Logout', kind: 'UI', prd: 'B1',
    title: 'Account menu -> Logout returns to the login screen and forgets the token',
    steps: 'admin signed in; tap the Account button (app bar); menu shows the user, Gmail connection, Change password, Logout; tap Logout',
    expected: 'menu items present; after Logout hash #/login, the login form shows, localStorage token removed' }, async (a) => {
    const x = await ui('REG-UI-02', a, 'admin', null, async ({ page }, c) => {
      await rt.press(page, /^Account/, { wait: 1500 });
      const items = await nodesText(page, (n) => n.role === 'menuitem');
      c.that('menu items', items.some((s) => /Change password/.test(s)) && items.some((s) => /Logout/.test(s)), items);
      await rt.press(page, 'Logout', { wait: 3000 });
      await rt.enableSemantics(page);
      c.that('login screen', /login/.test(await hash(page)) && !!(await waitText(page, 'Sign in', 5000)), await hash(page));
      c.that('token cleared', !(await page.evaluate(() => localStorage.getItem('flutter.gene_invoice_token'))));
    });
    return result(x, FE.shell);
  });

  await tcase({ id: 'REG-UI-03', feature: 'Change password', kind: 'UI', prd: 'B1',
    title: 'Change password through the Account menu; the new password signs in, the old one no longer does',
    steps: 'fresh CASHIER user (DEFAULT MANAGE/ALL) signed in; Account -> Change password; Current password, New password "Changed9!", Confirm; Change',
    expected: 'POST /api/auth/change-password 200; "Password changed successfully." then Close; the app stays signed in (new token); API login with Changed9! 200, with the old password 401' }, async (a) => {
    const u = await rt.createStaff(T.admin, 'CASHIER', 'rpwui', { grants: [{ regionId: D, accessLevel: 'MANAGE', recordScope: 'ALL' }] });
    const x = await ui('REG-UI-03', a, { username: u.username, password: rt.PASSWORD }, null, async (app, c) => {
      const { page } = app;
      await rt.press(page, /^Account/, { wait: 1500 });
      await rt.press(page, 'Change password', { wait: 1500 });
      await fill(page, 'Current password', rt.PASSWORD);
      await fill(page, 'New password', 'Changed9!');
      await fill(page, 'Confirm new password', 'Changed9!');
      await rt.tap(page, 'Change', { role: 'button', wait: 3000 });
      const req = await waitReq(app, (r) => r.url === '/api/auth/change-password', 5000);
      c.that('POST 200', req && req.status === 200, req);
      c.that('success message', !!(await waitText(page, 'Password changed successfully.', 3000)));
      if (await rt.exists(page, 'Close')) await rt.tap(page, 'Close', { role: 'button', wait: 1500 });
      c.that('dialog closed', !(await rt.exists(page, 'Confirm new password')));
      await rt.go(page, '/invoices', 3500);
      c.that('still signed in', !/login/.test(await hash(page)) && !!(await waitText(page, 'New invoice', 6000)), await hash(page));
      const n = await rt.api('POST', '/api/auth/login', { body: { username: u.username, password: 'Changed9!' } });
      const o = await rt.api('POST', '/api/auth/login', { body: { username: u.username, password: rt.PASSWORD } });
      c.that('new password works', n.status === 200, n.status);
      c.that('old password refused', o.status === 401, o.status);
    });
    return result(x, FE.pw);
  });

  await tcase({ id: 'REG-UI-04', feature: 'Sidebar navigation', kind: 'UI', prd: 'B1',
    title: 'Sidebar (drawer at phone/tablet width) lists every screen for admin and navigates to Customers',
    steps: 'admin at 700x900; tap "Open navigation menu"; read the entries; tap Customers',
    expected: 'entries Dashboard, Inbox, Customers, Invoices, Payments, Promises, Disputes, My tasks, Tasks, Approvals, Automation, Regions, Products, Users, Roles; after the tap hash #/customers and the Customers list shows ("New customer")' }, async (a) => {
    const want = ['Dashboard', 'Inbox', 'Customers', 'Invoices', 'Payments', 'Promises', 'Disputes', 'My tasks', 'Tasks', 'Approvals', 'Automation', 'Regions', 'Products', 'Users', 'Roles'];
    const x = await ui('REG-UI-04', a, 'admin', null, async ({ page }, c) => {
      await rt.tap(page, /Open navigation menu/, { wait: 1500 });
      const got = (await rt.semantics(page)).map((n) => ({ ...n, text: navText(n.text) })).filter((n) => n.role === 'button' && n.x < 320 && want.includes(n.text)).map((n) => n.text);
      c.that('all 15 entries in order', JSON.stringify(got) === JSON.stringify(want), { got, raw: (await rt.semantics(page)).filter((n) => n.role === 'button' && n.x < 320).map((n) => n.text) });
      await rt.tap(page, 'Customers', { role: 'button', wait: 3500 });
      c.that('hash #/customers', (await hash(page)).startsWith('#/customers'), await hash(page));
      c.that('customers list', !!(await waitText(page, 'New customer', 6000)));
    }, { width: 700, height: 900 });
    return result(x, FE.shell);
  });

  await tcase({ id: 'REG-UI-05', feature: 'Sidebar navigation', kind: 'UI', prd: 'B1',
    title: 'A Sales POC\'s sidebar hides Regions, Users and Roles, and the router refuses /users typed by hand',
    steps: 'sales POC at 700x900; open the drawer; read entries; then go to #/users',
    expected: 'entries Dashboard, Inbox, Customers, Invoices, Payments, Promises, Disputes, My tasks, Tasks, Approvals, Automation, Products (no Regions/Users/Roles); /users does not show the users list (no "New user", no GET /api/users)' }, async (a) => {
    const want = ['Dashboard', 'Inbox', 'Customers', 'Invoices', 'Payments', 'Promises', 'Disputes', 'My tasks', 'Tasks', 'Approvals', 'Automation', 'Products'];
    const all = [...want, 'Regions', 'Users', 'Roles'];
    const x = await ui('REG-UI-05', a, sales, null, async (app, c) => {
      const { page } = app;
      await rt.tap(page, /Open navigation menu/, { wait: 1500 });
      const got = (await rt.semantics(page)).map((n) => ({ ...n, text: navText(n.text) })).filter((n) => n.role === 'button' && n.x < 320 && all.includes(n.text)).map((n) => n.text);
      c.that('POC entries', JSON.stringify(got) === JSON.stringify(want), got);
      await rt.dismiss(page);
      await rt.go(page, '/users', 3500);
      c.that('no users list', !(await rt.exists(page, 'New user')), await allText(page));
      c.that('no GET /api/users', !app.requests.some((r) => r.url.startsWith('/api/users')));
    }, { width: 700, height: 900 });
    return result(x, FE.router);
  });

  await tcase({ id: 'REG-UI-06', feature: 'Sidebar & app bar', kind: 'UI', prd: 'B1',
    title: 'Desktop: the rail navigates to Invoices; with one region the region selector is hidden, the as-of chip reads Today, and lists send no regionIds/asOf',
    steps: 'admin at 1440x900; click the rail icon for Invoices (4th, y=281); inspect the app bar; inspect the /api/invoices request',
    expected: 'DESIGN §7 / §6.0: hash #/invoices; no "Regions: …" selector (single region); chip "Today"; GET /api/invoices without regionIds or asOf ("sent only when set")' }, async (a) => {
    const x = await ui('REG-UI-06', a, 'admin', null, async (app, c) => {
      const { page } = app;
      await rt.clickAt(page, 40, 281, 3500);
      c.that('hash #/invoices', (await hash(page)).startsWith('#/invoices'), await hash(page));
      c.that('no region selector', !(await rt.regionSelector(page)));
      const chip = await rt.asOfChip(page);
      c.that('as-of chip Today', chip && /Today/.test(`${chip.label} ${chip.text}`), chip && chip.label);
      const req = await waitReq(app, (r) => r.url.startsWith('/api/invoices?'), 5000);
      c.that('no regionIds/asOf sent', req && !/regionIds=|asOf=/.test(req.url), req && req.url);
    });
    return result(x, FE.shell);
  });

  // ================================================================== DASHBOARD
  await tcase({ id: 'REG-UI-07', feature: 'Dashboard', kind: 'UI', prd: 'B1',
    title: 'Dashboard shows its tiles, charts and tables with the API\'s figures; the period toggle re-asks the charts',
    steps: 'admin opens #/; read tiles and cards; compare "Invoices by status" total with GET /api/invoices/summary count and the top-outstanding first row with the API; tap "3 months"',
    expected: 'tiles Billed/Collected/Outstanding/Open promises/Broken promises; cards "Billed and collected by month", "Invoices by status", "Outstanding by days overdue", "Promises by status", "Top customers by outstanding", "Top paying customers"; status total = summary.count; first top-outstanding row = API first; after 3 months: GET billed-by-month?months=3 and collected-by-month?months=3' }, async (a) => {
    const summary = (await getJ(T.admin, '/api/invoices/summary')).json;
    const top = (await getJ(T.admin, '/api/dashboard/top-outstanding-customers')).json.customers[0];
    const x = await ui('REG-UI-07', a, 'admin', '/', async (app, c) => {
      const { page } = app;
      await waitText(page, /Welcome, /, 8000);
      const txt = await allText(page);
      for (const t of ['Billed', 'Collected', 'Outstanding', 'Open promises', 'Broken promises']) c.that(`tile ${t}`, txt.includes(t));
      for (const t of ['Billed and collected by month', 'Invoices by status', 'Outstanding by days overdue', 'Promises by status', 'Top customers by outstanding', 'Top paying customers']) c.that(`card ${t}`, txt.includes(t));
      const st = (await rt.semantics(page)).find((n) => /^Invoices by status\n/.test(n.label || ''));
      const total = st ? Number((st.label.split('\n')[1] || '').replace(/\D/g, '')) : null;
      c.that('status total = API count', total === summary.count, { ui: total, api: summary.count });
      c.that('top outstanding first row', top ? txt.includes(top.customerName) : true, top && top.customerName);
      const n0 = app.requests.length;
      await rt.tap(page, '3 months', { wait: 3000 });
      const got = app.requests.slice(n0).map((r) => r.url);
      c.that('re-asked with months=3', got.some((u) => /billed-by-month\?months=3/.test(u)) && got.some((u) => /collected-by-month\?months=3/.test(u)), got);
    });
    return result(x, FE.dash);
  });

  // ================================================================== CUSTOMERS
  await tcase({ id: 'REG-UI-08', feature: 'Customers create', kind: 'UI', prd: 'B1',
    title: 'New customer dialog (no region field with one region) creates the customer in DEFAULT',
    steps: 'admin #/customers -> New customer; Name, Phone, Email, Address, Username, Password; Save',
    expected: 'the dialog has no region picker (single region); POST /api/customers 200 without regionId; the customer exists with regionCode DEFAULT and shows in the list' }, async (a) => {
    const name = `UICust-${rt.uniq(w.tag)}`;
    const username = rt.uniq('uicust');
    const x = await ui('REG-UI-08', a, 'admin', '/customers', async (app, c) => {
      const { page } = app;
      await rt.tap(page, 'New customer', { wait: 2000 });
      c.that('no region field', !(await rt.exists(page, /^Region/)), await allText(page));
      await fill(page, 'Name', name);
      await fill(page, 'Phone', '555-0199');
      await fill(page, 'Email', `${username}@e2e.local`);
      await fill(page, 'Address', '7 UI Street');
      await fill(page, 'Username', username);
      await fill(page, 'Password', rt.PASSWORD);
      await rt.tap(page, 'Save', { role: 'button', wait: 3500 });
      const req = await waitReq(app, (r) => r.method === 'POST' && r.url === '/api/customers', 5000);
      c.that('POST 200', req && req.status === 200, req);
      const l = await getJ(T.admin, '/api/customers', { size: 10, filter: `name:eq:${name}` });
      const cu = l.json && l.json.content[0];
      c.that('saved in DEFAULT', cu && cu.regionCode === 'DEFAULT' && cu.phone === '555-0199', cu);
      await rt.go(page, `/customers?f=name:eq:${name}`, 3500);
      c.that('listed', !!(await waitText(page, new RegExp(name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')), 6000)));
    });
    return result(x, FE.custs);
  });

  await tcase({ id: 'REG-UI-09', feature: 'Customers edit', kind: 'UI', prd: 'B1',
    title: 'Customer detail: editing the name shows "Unsaved changes", leaving asks to discard, Save changes persists',
    steps: 'fresh customer; admin #/customers/{id}; change Name; try to go to #/invoices (expect discard dialog -> Keep editing); Save changes',
    expected: '"Unsaved changes" hint; "Discard unsaved changes?" guard on navigation; PUT /api/customers/{id} 200; API name = new name' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIEdit' });
    const newName = `UIEdited ${rt.uniq(w.tag)}`;
    const x = await ui('REG-UI-09', a, 'admin', `/customers/${cu.id}`, async (app, c) => {
      const { page } = app;
      await waitText(page, 'Save changes', 6000);
      const boxes = (await rt.semantics(page)).filter((n) => n.role === 'textbox');
      const nameBox = boxes.find((b) => b.text === cu.name) || boxes[0];
      await page.locator(`[data-rt="${nameBox.i}"]`).first().focus();
      await rt.typeText(page, newName, { clear: true });
      await page.waitForTimeout(800);
      c.that('Unsaved changes shown', !!(await waitText(page, 'Unsaved changes', 3000)));
      await page.evaluate(() => { location.hash = '/invoices'; });
      await page.waitForTimeout(2000); await rt.enableSemantics(page);
      const guard = await waitText(page, 'Discard unsaved changes?', 3000);
      c.that('discard guard', !!guard);
      if (guard) await rt.tap(page, 'Keep editing', { wait: 1500 });
      await rt.tap(page, 'Save changes', { wait: 3000 });
      const req = await waitReq(app, (r) => r.method === 'PUT' && r.url === `/api/customers/${cu.id}`, 5000);
      c.that('PUT 200', req && req.status === 200, req);
      const g = await getJ(T.admin, `/api/customers/${cu.id}`);
      c.that('name saved', g.json.name === newName, g.json.name);
    });
    return result(x, FE.cust);
  });

  await tcase({ id: 'REG-UI-10', feature: 'POC seats', kind: 'UI', prd: 'B1',
    title: 'Customer detail: add a Customer Success POC through the Add dialog',
    steps: 'fresh customer; admin #/customers/{id}; under "Customer Success POCs" tap Add; pick the success user in "Customer Success POC *"; Make primary; Add',
    expected: 'POST /api/customers/{id}/pocs 200; the seat is listed on the page; API successPocs has the user' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIPoc' });
    const x = await ui('REG-UI-10', a, 'admin', `/customers/${cu.id}`, async (app, c) => {
      const { page } = app;
      await waitText(page, 'Customer Success POCs', 6000);
      const adds = (await rt.semantics(page)).filter((n) => n.label === 'Add').sort((p, q) => p.y - q.y);
      await rt.clickAt(page, adds[0].x, adds[0].y, 2000);
      c.that('dialog', !!(await waitText(page, 'Add Customer Success POC', 4000)));
      await pick(page, /^Customer Success POC \*/, new RegExp(U.success.fullName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')), U.success.fullName);
      await rt.tap(page, 'Add', { role: 'button', wait: 3000 });
      const req = await waitReq(app, (r) => r.method === 'POST' && r.url === `/api/customers/${cu.id}/pocs`, 5000);
      c.that('POST 200', req && req.status === 200, req);
      const g = await getJ(T.admin, `/api/customers/${cu.id}`);
      c.that('seat saved', (g.json.successPocs || []).some((p) => (p.user ? p.user.id : p.userId) === U.success.id), g.json.successPocs);
      c.that('seat shown', !!(await waitText(page, new RegExp(U.success.fullName), 4000)));
    });
    return result(x, FE.pocEd);
  });

  await tcase({ id: 'REG-UI-11', feature: 'Customers delete', kind: 'UI', prd: 'B1',
    title: 'Delete customer asks for confirmation and removes a customer without invoices',
    steps: 'fresh customer without invoices; admin #/customers/{id}; Delete customer; dialog "Delete this customer?"; Delete customer',
    expected: 'DELETE /api/customers/{id} 2xx; app leaves the detail page; API GET 404' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIDel' });
    const x = await ui('REG-UI-11', a, 'admin', `/customers/${cu.id}`, async (app, c) => {
      const { page } = app;
      await rt.tap(page, 'Delete customer', { wait: 1500 });
      c.that('confirm dialog', !!(await waitText(page, 'Delete this customer?', 3000)));
      const btns = (await rt.semantics(page)).filter((n) => n.text === 'Delete customer' && n.role === 'button').sort((p, q) => q.y - p.y);
      await rt.clickAt(page, btns[0].x, btns[0].y, 3500);
      const req = await waitReq(app, (r) => r.method === 'DELETE' && r.url === `/api/customers/${cu.id}`, 5000);
      c.that('DELETE 2xx', req && req.status >= 200 && req.status < 300, req);
      c.that('left the page', !(await hash(page)).includes(`/customers/${cu.id}`), await hash(page));
      const g = await getJ(T.admin, `/api/customers/${cu.id}`);
      c.that('gone', g.status === 404, g.status);
    }, { allowStatuses: [404] });
    return result(x, FE.cust);
  });

  // ================================================================== INVOICES
  await tcase({ id: 'REG-UI-12', feature: 'Invoice ageing', kind: 'UI', prd: 'B1',
    title: 'Invoices list shows "Overdue by N days"; the "Overdue only" chip filters with overdue:eq:true',
    steps: 'fresh customer with an invoice due -12 days; admin #/invoices?f=customerId:eq:<id>; read the due-date cell; tap "Overdue only"',
    expected: 'row shows "Overdue by 12 days" and the "Overdue" tag; after the chip the request carries filter=overdue:eq:true and the row stays' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIAge' });
    const inv = await invoice(w, cu, { amount: 111, dueDate: rt.day(-12), invoiceDate: `${rt.day(-20)}T08:00:00Z`, paymentTerm: 'CUSTOM' });
    const x = await ui('REG-UI-12', a, 'admin', `/invoices?f=customerId:eq:${cu.id}`, async (app, c) => {
      const { page } = app;
      c.that('row', !!(await waitText(page, inv.invoiceNumber, 6000)));
      c.that('Overdue by 12 days', !!(await waitText(page, /Overdue by 12 days/, 3000)));
      const n0 = app.requests.length;
      await rt.tap(page, 'Overdue only', { wait: 3000 });
      const req = app.requests.slice(n0).find((r) => r.url.startsWith('/api/invoices?'));
      c.that('filter overdue:eq:true sent', req && /overdue:eq:true/.test(req.url), req && req.url);
      c.that('row still there', !!(await waitText(page, inv.invoiceNumber, 4000)));
    });
    return result(x, FE.invs);
  });

  await tcase({ id: 'REG-UI-13', feature: 'Invoice create', kind: 'UI', prd: 'B2',
    title: 'New invoice form: the due date follows the customer\'s terms (Net 15), lines total up, Create invoice saves it (not held)',
    steps: 'fresh customer NET_15; admin #/invoices/new; Customer = it; Product = Reg Gadget; Qty 3; Create invoice',
    expected: '"Net 15 — due <today+15>" preview; total ₹750.00; POST /api/invoices 200 (not 202); invoice exists with dueDate today+15, NET_15, total 750' }, async (a) => {
    const cu = await customer(w, { prefix: 'UINew', paymentTerm: 'NET_15' });
    const due = new Date(`${rt.day(15)}T00:00:00Z`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
    const x = await ui('REG-UI-13', a, 'admin', '/invoices/new', async (app, c) => {
      const { page } = app;
      c.that('customer picked', await pick(page, /^Customer \*/, new RegExp(`^${cu.name}`), cu.name));
      c.that('terms preview', !!(await waitText(page, new RegExp(`Net 15 — due ${due}`), 4000)), due);
      c.that('product picked', await pick(page, /^Product\n/, /^Reg Gadget/, 'Reg Gadget'));
      await rt.fillField(page, 'Qty', '3');
      await page.waitForTimeout(800);
      c.that('total ₹750.00', !!(await waitText(page, '₹750.00', 3000)));
      // The "me" default comes from the first 25 assignable Sales POCs (pre-existing limit, same on main); when the
      // database holds more, admin is not pre-selected and is picked by hand.
      if (await rt.exists(page, /^Sales POC \*\nSelect…/)) await pick(page, /^Sales POC \*/, /\nSystem Administrator\n@admin/, 'System Administrator');
      await rt.tap(page, 'Create invoice', { wait: 4000 });
      const req = await waitReq(app, (r) => r.method === 'POST' && r.url === '/api/invoices', 5000);
      c.that('POST 200 (not held)', req && req.status === 200, req);
      const l = await getJ(T.admin, '/api/invoices', { size: 10, customerId: cu.id });
      const inv = l.json.content[0];
      c.that('saved: NET_15, +15, 750', inv && inv.paymentTerm === 'NET_15' && inv.dueDate === rt.day(15) && Number(inv.total) === 750, inv);
    });
    return result(x, FE.invForm);
  });

  await tcase({ id: 'REG-UI-14', feature: 'Invoice edit', kind: 'UI', prd: 'B2',
    title: 'Invoice detail: change Payment terms to Net 60 and save; the History tab records the due-date change',
    steps: 'fresh invoice (customer NET_30); admin #/invoices/{id}; Payment terms -> Net 60; Save changes; open History tab',
    expected: 'PATCH /api/invoices/{id} 200 (not held, no INVOICE_TERMS threshold); API paymentTerm NET_60 and dueDate = invoice day + 60; History tab lists the due-date change and "Invoice created"' }, async (a) => {
    const cu = await customer(w, { prefix: 'UITerms', invoices: [{ amount: 222 }] });
    const inv = cu.invoices[0];
    const x = await ui('REG-UI-14', a, 'admin', `/invoices/${inv.id}`, async (app, c) => {
      const { page } = app;
      await waitText(page, 'Save changes', 6000);
      await rt.tap(page, 'Net 30', { role: 'button', wait: 1500 });
      await rt.press(page, 'Net 60', { role: 'menuitem', wait: 1500 });
      await rt.tap(page, 'Save changes', { wait: 3000 });
      const req = await waitReq(app, (r) => r.method === 'PATCH' && r.url === `/api/invoices/${inv.id}`, 5000);
      c.that('PATCH 200', req && req.status === 200, req);
      const g = await getJ(T.admin, `/api/invoices/${inv.id}`);
      c.that('NET_60 +60', g.json.paymentTerm === 'NET_60' && g.json.dueDate === rt.day(60), { t: g.json.paymentTerm, d: g.json.dueDate });
      await rt.tap(page, /History$/, { role: 'tab', wait: 3000 });
      const txt = await allText(page);
      c.that('history: created', /Invoice created/.test(txt), txt.slice(-600));
      c.that('history: due date change', /[Dd]ue date/.test(txt.split('History')[1] || ''), txt.slice(-600));
    });
    return result(x, FE.inv);
  });

  await tcase({ id: 'REG-UI-15', feature: 'Invoice cancel', kind: 'UI', prd: 'B2',
    title: 'Cancel invoice from the list row asks for confirmation and cancels it',
    steps: 'fresh invoice; admin #/invoices?f=customerId:eq:<id>; tap the row\'s "Cancel invoice"; confirm "Cancel invoice"',
    expected: '"Cancel INV-…?" dialog; POST /api/invoices/{id}/cancel 200; status Cancelled in the list and the API' }, async (a) => {
    const cu = await customer(w, { prefix: 'UICancel', invoices: [{ amount: 333 }] });
    const inv = cu.invoices[0];
    const x = await ui('REG-UI-15', a, 'admin', `/invoices?f=customerId:eq:${cu.id}`, async (app, c) => {
      const { page } = app;
      await waitText(page, inv.invoiceNumber, 6000);
      const b = (await rt.semantics(page)).find((n) => n.text === 'Cancel invoice' && n.role === 'button');
      await rt.clickAt(page, b.x, b.y, 2000);
      c.that('confirm dialog', !!(await waitText(page, `Cancel ${inv.invoiceNumber}?`, 3000)));
      const btns = (await rt.semantics(page)).filter((n) => n.text === 'Cancel invoice' && n.role === 'button').sort((p, q) => Math.abs(p.y - 496) - Math.abs(q.y - 496));
      await rt.clickAt(page, btns[0].x, btns[0].y, 3000);
      const req = await waitReq(app, (r) => r.method === 'POST' && r.url === `/api/invoices/${inv.id}/cancel`, 5000);
      c.that('POST cancel 200', req && req.status === 200, req);
      c.that('shown Cancelled', !!(await waitText(page, 'Cancelled', 4000)));
      const g = await getJ(T.admin, `/api/invoices/${inv.id}`);
      c.that('API CANCELLED', g.json.status === 'CANCELLED', g.json.status);
    });
    return result(x, FE.invs);
  });

  // ================================================================== PAYMENTS & PROMISES
  await tcase({ id: 'REG-UI-16', feature: 'Record payment', kind: 'UI', prd: 'B2',
    title: 'Record payment dialog: pick customer, tick one outstanding invoice, amount, Record; the invoice is paid',
    steps: 'fresh customer with invoices 400 and 900; admin #/payments -> Record payment; Customer = it; tick the 900 invoice; Amount 900; Record',
    expected: 'Collection POC pre-filled (the customer\'s primary); the outstanding invoices listed; POST /api/payments 200 (not held); the 900 invoice FULLY_PAID, the 400 one untouched' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIPay', invoices: [{ amount: 400 }, { amount: 900 }] });
    const [i400, i900] = cu.invoices;
    const x = await ui('REG-UI-16', a, 'admin', '/payments', async (app, c) => {
      const { page } = app;
      await rt.tap(page, 'Record payment', { wait: 2000 });
      await pick(page, /^Customer \*/, new RegExp(`^${cu.name}`), cu.name);
      await page.waitForTimeout(1500);
      c.that('collection POC pre-filled', !!(await waitText(page, new RegExp(`^Collection POC \\*\\n${U.coll.fullName}`), 3000)));
      const box = await waitText(page, new RegExp(`^${i900.invoiceNumber}`), 4000);
      c.that('invoice listed', !!box);
      if (box) await rt.tap(page, new RegExp(`^${i900.invoiceNumber}`), { wait: 800 });
      await fill(page, 'Amount *', '900');
      await rt.tap(page, 'Record', { role: 'button', wait: 3500 });
      const req = await waitReq(app, (r) => r.method === 'POST' && r.url === '/api/payments', 5000);
      c.that('POST 200', req && req.status === 200, req);
      const g9 = await getJ(T.admin, `/api/invoices/${i900.id}`);
      const g4 = await getJ(T.admin, `/api/invoices/${i400.id}`);
      c.that('900 paid, 400 untouched', g9.json.status === 'FULLY_PAID' && g4.json.status === 'UNPAID', { i900: g9.json.status, i400: g4.json.status });
    });
    return result(x, FE.payDlg);
  });

  await tcase({ id: 'REG-UI-17', feature: 'Payment detail', kind: 'UI', prd: 'B1',
    title: 'Payment detail shows the amount, the invoices it paid and the credit it left',
    steps: 'fresh customer with invoice 300; payment 450 via API (150 becomes credit); admin #/payments/{id}',
    expected: 'page shows ₹450.00, the invoice number with ₹300.00 applied, and the ₹150.00 credit' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIPayD', invoices: [{ amount: 300 }] });
    const p = await payment(w, cu, { amount: 450 });
    const x = await ui('REG-UI-17', a, 'admin', `/payments/${p.id}`, async ({ page }, c) => {
      c.that('amount', !!(await waitText(page, /₹450\.00/, 6000)));
      c.that('invoice listed', !!(await waitText(page, new RegExp(cu.invoices[0].invoiceNumber), 4000)));
      const txt = await allText(page);
      c.that('₹300.00 applied', /₹300\.00/.test(txt), txt.slice(0, 500));
      c.that('₹150.00 credit', /₹150\.00/.test(txt), txt.slice(0, 500));
    });
    return result(x, FE.pay);
  });

  await tcase({ id: 'REG-UI-18', feature: 'Promises', kind: 'UI', prd: 'B2',
    title: 'Raise promise from an invoice row; it appears on the Promises list',
    steps: 'fresh customer + invoice 800; admin #/invoices?f=customerId:eq; row "Raise promise"; Promised amount 250; Raise promise; open #/promises?f=customerId:eq',
    expected: 'dialog "Payment promise • <customer>" with Collection POC pre-filled; POST /api/promises 200; promise OPEN 250 in the API and listed' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIProm', invoices: [{ amount: 800 }] });
    const x = await ui('REG-UI-18', a, 'admin', `/invoices?f=customerId:eq:${cu.id}`, async (app, c) => {
      const { page } = app;
      await waitText(page, cu.invoices[0].invoiceNumber, 6000);
      const b = (await rt.semantics(page)).find((n) => n.text === 'Raise promise' && n.role === 'button');
      await rt.clickAt(page, b.x, b.y, 2000);
      c.that('dialog', !!(await waitText(page, new RegExp(`Payment promise • ${cu.name}`), 4000)));
      await fill(page, 'Promised amount *', '250');
      const btns = (await rt.semantics(page)).filter((n) => n.text === 'Raise promise' && n.role === 'button').sort((p, q) => q.y - p.y);
      await rt.clickAt(page, btns[0].x, btns[0].y, 3000);
      const req = await waitReq(app, (r) => r.method === 'POST' && r.url === '/api/promises', 5000);
      c.that('POST 200', req && req.status === 200, req);
      const l = await getJ(T.admin, '/api/promises', { size: 10, filter: `customerId:eq:${cu.id}` });
      c.that('API OPEN 250', l.json.totalElements === 1 && l.json.content[0].status === 'OPEN' && Number(l.json.content[0].amount) === 250, l.json.content);
      await rt.go(page, `/promises?f=customerId:eq:${cu.id}`, 4000);
      c.that('listed', !!(await waitText(page, /₹250\.00/, 5000)));
    });
    return result(x, FE.prom);
  });

  // ================================================================== DISPUTES
  await tcase({ id: 'REG-UI-19', feature: 'Disputes (customer)', kind: 'UI', prd: 'B1',
    title: 'A customer raises a dispute on its invoice from the invoice page',
    steps: 'fresh customer + invoice; customer login #/invoices/{id}; Raise dispute; "Why is this wrong?" text; Submit',
    expected: 'customer sees no as-of chip and no region selector; POST /api/disputes 200; dispute PENDING in the API; Disputes tab lists it' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIDisp', invoices: [{ amount: 500 }] });
    const reason = `ui reason ${rt.uniq('r')}`;
    const x = await ui('REG-UI-19', a, { username: cu.username, password: cu.password }, `/invoices/${cu.invoices[0].id}`, async (app, c) => {
      const { page } = app;
      c.that('no as-of chip', !(await rt.asOfChip(page)));
      c.that('no region selector', !(await rt.regionSelector(page)));
      await rt.tap(page, 'Raise dispute', { role: 'button', wait: 2000 });
      c.that('dialog', !!(await waitText(page, new RegExp(`Raise dispute • ${cu.invoices[0].invoiceNumber}`), 3000)));
      await fill(page, 'Why is this wrong?', reason);
      await rt.tap(page, 'Submit', { role: 'button', wait: 3000 });
      const req = await waitReq(app, (r) => r.method === 'POST' && r.url === '/api/disputes', 5000);
      c.that('POST 200', req && req.status === 200, req);
      const l = await getJ(T.admin, '/api/disputes', { size: 10, filter: `customerId:eq:${cu.id}` });
      c.that('PENDING with reason', l.json.totalElements === 1 && l.json.content[0].status === 'PENDING' && l.json.content[0].reason === reason, l.json.content);
      c.that('listed in the tab', !!(await waitText(page, new RegExp(reason), 5000)));
    });
    return result(x, FE.dispDlg);
  });

  await tcase({ id: 'REG-UI-20', feature: 'Disputes (admin)', kind: 'UI', prd: 'B2',
    title: 'Admin approves one dispute and denies another from the dispute page',
    steps: 'customer opens dispute A (update_notes) and B (on another invoice); admin #/disputes/{A} -> Approve; #/disputes/{B} -> type admin notes -> Deny',
    expected: 'POST /approve 200 -> A APPROVED and the invoice notes changed (not held); POST /deny 200 -> B DENIED with the notes; the page shows the new status' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIDecide', invoices: [{ amount: 500 }, { amount: 600 }] });
    const ct = await rt.login(cu.username, cu.password);
    const A = rt.need(await rt.createDispute(ct, { targetType: 'INVOICE', targetId: cu.invoices[0].id, reason: 'A', proposedChangeJson: JSON.stringify({ action: 'update_notes', notes: 'ui approved' }) }), 'A');
    const B = rt.need(await rt.createDispute(ct, { targetType: 'INVOICE', targetId: cu.invoices[1].id, reason: 'B' }), 'B');
    const x = await ui('REG-UI-20', a, 'admin', `/disputes/${A.id}`, async (app, c) => {
      const { page } = app;
      await waitText(page, 'Approve', 6000);
      await rt.tap(page, 'Approve', { role: 'button', wait: 3000 });
      const confirm = await waitText(page, /^Approve/, 1500, { role: 'button' });
      if (await rt.exists(page, /Approve this dispute|Apply/)) await rt.tap(page, /^(Approve|Apply)$/, { role: 'button', wait: 3000 });
      void confirm;
      const ra = await waitReq(app, (r) => r.method === 'POST' && r.url === `/api/disputes/${A.id}/approve`, 5000);
      c.that('approve 200', ra && ra.status === 200, ra);
      const ga = await getJ(T.admin, `/api/disputes/${A.id}`);
      const gi = await getJ(T.admin, `/api/invoices/${cu.invoices[0].id}`);
      c.that('A APPROVED, notes applied', ga.json.status === 'APPROVED' && gi.json.notes === 'ui approved', { s: ga.json.status, n: gi.json.notes });
      c.that('page shows Approved', !!(await waitText(page, /Approved/, 4000)));
      await rt.go(page, `/disputes/${B.id}`, 4000);
      const boxes = (await rt.semantics(page)).filter((n) => n.role === 'textbox').sort((p, q) => q.x - p.x);
      if (boxes[0]) { await page.locator(`[data-rt="${boxes[0].i}"]`).first().focus(); await rt.typeText(page, 'ui denied', { clear: true }); }
      await rt.tap(page, 'Deny', { role: 'button', wait: 3000 });
      if (await rt.exists(page, /Deny this dispute/)) await rt.tap(page, 'Deny', { role: 'button', wait: 3000 });
      const rd = await waitReq(app, (r) => r.method === 'POST' && r.url === `/api/disputes/${B.id}/deny`, 5000);
      c.that('deny 200', rd && rd.status === 200, rd);
      const gb = await getJ(T.admin, `/api/disputes/${B.id}`);
      c.that('B DENIED with notes', gb.json.status === 'DENIED' && gb.json.adminNotes === 'ui denied', { s: gb.json.status, n: gb.json.adminNotes });
    });
    return result(x, FE.disp);
  });

  // ================================================================== DOCUMENTS
  await tcase({ id: 'REG-UI-21', feature: 'Documents upload', kind: 'UI', prd: 'B1',
    title: 'Documents tab: upload a PDF shared with the customer through the Attach dialog',
    steps: 'fresh invoice; admin #/invoices/{id}; Documents tab; Upload; Choose file (ui-shared.pdf); description; "Who can see it" -> shared with customer; Upload',
    expected: 'POST /api/documents 2xx; the tab lists ui-shared.pdf; API document visibility SHARED with the description' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIDoc', invoices: [{ amount: 100 }] });
    const inv = cu.invoices[0];
    const x = await ui('REG-UI-21', a, 'admin', `/invoices/${inv.id}`, async (app, c) => {
      const { page } = app;
      await rt.tap(page, /Documents$/, { role: 'tab', wait: 2500 });
      await rt.tap(page, 'Upload', { role: 'button', wait: 2000 });
      c.that('attach dialog', !!(await waitText(page, /^Attach a document/, 3000)));
      const choose = await rt.find(page, 'Choose file');
      const [fc] = await Promise.all([page.waitForEvent('filechooser', { timeout: 8000 }).catch(() => null), rt.clickAt(page, choose.x, choose.y, 500)]);
      c.that('file chooser opened', !!fc);
      if (fc) await fc.setFiles({ name: 'ui-shared.pdf', mimeType: 'application/pdf', buffer: H.PDF });
      await page.waitForTimeout(1500); await rt.enableSemantics(page);
      await fill(page, 'Description (optional)', 'ui doc');
      await rt.tap(page, /^Who can see it/, { wait: 1500 });
      const opts = await nodesText(page, (n) => n.role === 'menuitem');
      const shared = opts.find((s) => /[Ss]hared|customer/.test(s));
      c.that('visibility options', !!shared, opts);
      if (shared) await rt.press(page, new RegExp(shared.split('|')[0].replace(/[.*+?^${}()|[\]\\]/g, '\\$&') || 'Shared'), { role: 'menuitem', wait: 1200 });
      const ups = (await rt.semantics(page)).filter((n) => n.text === 'Upload' && n.role === 'button').sort((p, q) => q.y - p.y);
      await rt.clickAt(page, ups[0].x, ups[0].y, 3500);
      const req = await waitReq(app, (r) => r.method === 'POST' && r.url === '/api/documents', 6000);
      c.that('POST 2xx', req && req.status >= 200 && req.status < 300, req);
      c.that('listed', !!(await waitText(page, /ui-shared\.pdf/, 5000)));
      const l = await getJ(T.admin, '/api/documents', { entityType: 'INVOICE', entityId: inv.id });
      const d = l.json.content[0];
      c.that('API SHARED + description', d && d.visibility === 'SHARED' && d.description === 'ui doc' && d.filename === 'ui-shared.pdf', d);
    });
    return result(x, FE.upload);
  });

  await tcase({ id: 'REG-UI-22', feature: 'Documents visibility', kind: 'UI', prd: 'B1',
    title: 'The customer\'s Documents tab lists the SHARED document and not the INTERNAL one',
    steps: 'fresh invoice; admin uploads shared.pdf (SHARED) and internal.pdf (INTERNAL) via API; customer login #/invoices/{id} -> Documents tab',
    expected: 'tab shows "Documents (1)" with shared.pdf; internal.pdf absent' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIDocV', invoices: [{ amount: 100 }] });
    const inv = cu.invoices[0];
    rt.need(await H.upload(T.admin, { entityType: 'INVOICE', entityId: inv.id, visibility: 'SHARED', filename: 'shared.pdf' }), 'shared');
    rt.need(await H.upload(T.admin, { entityType: 'INVOICE', entityId: inv.id, visibility: 'INTERNAL', filename: 'internal.pdf' }), 'internal');
    const x = await ui('REG-UI-22', a, { username: cu.username, password: cu.password }, `/invoices/${inv.id}`, async ({ page }, c) => {
      await rt.tap(page, /Documents$/, { role: 'tab', wait: 3000 });
      c.that('shared listed', !!(await waitText(page, /shared\.pdf/, 5000)));
      c.that('internal hidden', !(await rt.exists(page, /internal\.pdf/)));
      c.that('count 1', !!(await waitText(page, /Documents \(1\)/, 2000)));
    });
    return result(x, FE.docs);
  });

  // ================================================================== EMAIL / INBOX / NOTIFICATIONS
  await tcase({ id: 'REG-UI-23', feature: 'Email compose', kind: 'UI', prd: 'A4',
    title: 'Send email from an invoice with an inserted placeholder; the Email tab shows it saved but not sent',
    steps: 'fresh customer + invoice; admin #/invoices/{id} -> Send email; tick "Customer emails"; Subject "Hello " + Insert placeholder -> Customer Name; Message "Invoice " + Insert placeholder -> Invoice Number; Send',
    expected: 'the dialog warns delivery is not configured; placeholder menu has Customer/Invoice/General groups without "Rule name" (F3); POST /api/emails 200; saved subject "Hello <customer>", body "Invoice <number>", status NOT_SENT; Email tab shows "Emails (1)" with the "Not sent" email to the customer' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIMail', invoices: [{ amount: 100 }] });
    const inv = cu.invoices[0];
    const x = await ui('REG-UI-23', a, 'admin', `/invoices/${inv.id}`, async (app, c) => {
      const { page } = app;
      // Diagnostics (r3): the subject each preview was asked for, and a shot right after the Subject insert.
      const subs = []; const t0 = Date.now();
      page.on('request', (q) => { if (q.method() === 'POST' && q.url().endsWith('/api/emails/preview')) { try { subs.push(`${Date.now() - t0}ms:${JSON.parse(q.postData()).subject}`); } catch { /* ignore */ } } });
      c.that('diagnostics: preview subjects', true, subs);
      await rt.tap(page, 'Send email', { role: 'button', wait: 3000 });
      c.that('not-configured warning', !!(await waitText(page, /Email delivery is not configured/, 4000)));
      await rt.tap(page, /^Customer emails/, { wait: 800 });
      // Harness (r3): the typed text is checked before a placeholder is inserted (a keystroke lost to focus
      // timing once left the subject without "Hello "); one re-fill is allowed.
      const fillChecked = async (label, text) => {
        for (let k = 0; k < 2; k++) {
          await fill(page, label, text);
          const box = (await rt.semantics(page)).find((n) => n.role === 'textbox' && n.label === label);
          if (box && box.text === text) return true;
        }
        return false;
      };
      c.that('subject typed', await fillChecked('Subject *', 'Hello '));
      const phs = (await rt.semantics(page)).filter((n) => n.text === 'Insert placeholder').sort((p, q) => p.y - q.y);
      await rt.clickAt(page, phs[0].x, phs[0].y, 1500);
      const items = await nodesText(page, (n) => n.role === 'menuitem');
      c.that('groups Customer/Invoice/General, no Rule name', items.some((s) => s.startsWith('Customer|')) && items.some((s) => s.startsWith('Invoice|')) && items.some((s) => s.startsWith('General|')) && !items.some((s) => /Rule name/.test(s)), items.length);
      await rt.press(page, 'Name', { role: 'menuitem', wait: 1200 });
      subs.push(`${Date.now() - t0}ms:--after-subject-insert--`);
      await rt.shot(page, R.shotsDir, `REG-UI-23-${a}-subject`);
      c.that('message typed', await fillChecked('Message', 'Invoice '));
      const phs2 = (await rt.semantics(page)).filter((n) => n.text === 'Insert placeholder').sort((p, q) => p.y - q.y);
      await rt.clickAt(page, phs2[1].x, phs2[1].y, 1500);
      await rt.press(page, 'Number', { role: 'menuitem', wait: 1200 });
      await rt.tap(page, 'Send', { role: 'button', wait: 3500 });
      const req = await waitReq(app, (r) => r.method === 'POST' && r.url === '/api/emails', 5000);
      c.that('POST 200', req && req.status === 200, req);
      const l = await rt.emailsFor(T.admin, 'INVOICE', inv.id);
      const e = l.json.content[0];
      c.that('rendered + NOT_SENT', e && e.subject.trim() === `Hello ${cu.name}` && e.body.trim() === `Invoice ${inv.invoiceNumber}` && /NOT_SENT|NOT_CONFIGURED/.test(e.status), e && { s: e.subject, b: e.body, st: e.status });
      await rt.tap(page, /Email$/, { role: 'tab', wait: 3000 });
      // The subject is a SelectableText (no semantics value); the card is found by its count, status and recipient.
      c.that('Email tab: Emails (1)', !!(await waitText(page, 'Emails (1)', 5000)));
      c.that('Email tab: Not sent to the customer', !!(await waitText(page, /Not sent/, 2000)) && !!(await waitText(page, new RegExp(cu.name), 2000)));
    });
    return result(x, FE.mail);
  });

  await tcase({ id: 'REG-UI-24', feature: 'Inbox', kind: 'UI', prd: 'B1',
    title: 'Inbox lists an email sent to me; opening it marks it read; Mark all as read clears the count',
    steps: 'sales2: mark all read (API); admin emails sales2 twice about an invoice; sales2 opens #/inbox; tap the first subject; then "Mark all as read"',
    expected: 'both subjects listed; opening one -> POST /api/inbox/{id}/read and unread-count 1; Mark all as read -> POST /api/inbox/mark-all-read and count 0' }, async (a) => {
    const t2 = T.sales2;
    await rt.api('POST', '/api/inbox/mark-all-read', { token: t2, body: {} });
    const cu = await customer(w, { prefix: 'UIInbox', invoices: [{ amount: 100 }] });
    const s1 = `UI inbox A ${rt.uniq('m')}`; const s2 = `UI inbox B ${rt.uniq('m')}`;
    for (const s of [s1, s2]) rt.need(await rt.api('POST', '/api/emails', { token: T.admin, body: { entityType: 'INVOICE', entityId: cu.invoices[0].id, to: [rt.tok.user(U.sales2.id)], subject: s, body: 'hello' } }), 'mail');
    const x = await ui('REG-UI-24', a, { username: U.sales2.username, password: rt.PASSWORD }, '/inbox', async (app, c) => {
      const { page } = app;
      c.that('A listed', !!(await waitText(page, new RegExp(s1), 6000)));
      c.that('B listed', !!(await waitText(page, new RegExp(s2), 2000)));
      await rt.press(page, new RegExp(s1), { wait: 2500 });
      const rd = await waitReq(app, (r) => r.method === 'POST' && /^\/api\/inbox\/\d+\/read$/.test(r.url), 4000);
      c.that('marked read on open', rd && rd.status === 200, rd);
      const cnt = (await getJ(t2, '/api/inbox/unread-count')).json.count;
      c.that('unread 1', cnt === 1, cnt);
      if (await rt.exists(page, 'Close')) await rt.tap(page, 'Close', { wait: 1200 });
      await rt.tap(page, 'Mark all as read', { wait: 2500 });
      const ma = await waitReq(app, (r) => r.method === 'POST' && r.url.startsWith('/api/inbox/mark-all-read'), 4000);
      c.that('mark all POST', ma && ma.status === 200, ma);
      const cnt2 = (await getJ(t2, '/api/inbox/unread-count')).json.count;
      c.that('unread 0', cnt2 === 0, cnt2);
    });
    return result(x, FE.inbox);
  });

  await tcase({ id: 'REG-UI-25', feature: 'Notifications', kind: 'UI', prd: 'B1',
    title: 'Notifications page lists my notices, the bell shows the unread count, Mark all read clears it',
    steps: 'sales2 mark-all-read (API); admin creates 2 invoices with Sales POC sales2; sales2 opens #/notifications; read the bell badge; Mark all read',
    expected: 'two "You are now Sales POC" rows; bell badge "2"; POST /api/notifications/mark-all-read 200; unread-count 0 and the badge disappears' }, async (a) => {
    const t2 = T.sales2;
    await rt.api('POST', '/api/notifications/mark-all-read', { token: t2, body: {} });
    const cu = await customer(w, { prefix: 'UINotif' });
    await invoice(w, cu, { amount: 10, sales: U.sales2.id });
    await invoice(w, cu, { amount: 20, sales: U.sales2.id });
    const x = await ui('REG-UI-25', a, { username: U.sales2.username, password: rt.PASSWORD }, '/notifications', async (app, c) => {
      const { page } = app;
      await waitText(page, /You are now Sales POC/, 6000);
      const rows = (await rt.semantics(page)).filter((n) => n.role === 'cell' && /You are now Sales POC/.test(n.text || ''));
      c.that('2 rows', rows.length >= 2, rows.length);
      const badge = (await rt.semantics(page)).find((n) => n.text === '2' && n.y < 30);
      c.that('bell badge 2', !!badge, (await rt.semantics(page)).filter((n) => n.y < 30).map((n) => n.text));
      await rt.tap(page, 'Mark all read', { wait: 2500 });
      const req = await waitReq(app, (r) => r.method === 'POST' && r.url.startsWith('/api/notifications/mark-all-read'), 4000);
      c.that('POST 200', req && req.status === 200, req);
      const cnt = (await getJ(t2, '/api/notifications/unread-count')).json.count;
      c.that('unread 0', cnt === 0, cnt);
      await page.waitForTimeout(1500); await rt.enableSemantics(page);
      c.that('badge gone', !(await rt.semantics(page)).some((n) => n.text === '2' && n.y < 30));
    });
    return result(x, FE.notif);
  });

  await tcase({ id: 'REG-UI-26', feature: 'Audit history panel', kind: 'UI', prd: 'B1',
    title: 'Customer History tab shows the customer\'s and its records\' events with who did them',
    steps: 'fresh customer + invoice + payment; admin #/customers/{id} -> History tab',
    expected: 'entries for the customer creation, the invoice creation ("Invoice created") and the payment, each "by admin"' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIHist', invoices: [{ amount: 100 }] });
    await payment(w, cu, { amount: 40 });
    const x = await ui('REG-UI-26', a, 'admin', `/customers/${cu.id}`, async ({ page }, c) => {
      await rt.tap(page, /History$/, { role: 'tab', wait: 3500 });
      const txt = await allText(page);
      const hist = txt.split('History').slice(1).join('History');
      c.that('customer created', /[Cc]ustomer created/.test(hist), hist.slice(0, 800));
      c.that('invoice created', /Invoice created/.test(hist), hist.slice(0, 800));
      c.that('payment', /[Pp]ayment/.test(hist), hist.slice(0, 800));
      c.that('by admin', /by admin/.test(hist));
    });
    return result(x, FE.audit);
  });

  // ================================================================== LIST MECHANICS
  await tcase({ id: 'REG-UI-27', feature: 'List sort & paging', kind: 'UI', prd: 'B1',
    title: 'Invoices list: clicking a header sorts, the Rows menu changes the page size, Next page pages, and the URL keeps the query',
    steps: 'admin #/invoices; click the "Total" header; Rows -> 10; Next page',
    expected: 'requests /api/invoices?…sort=total,asc; then size=10; then page=1; hash carries sort/size/page; "Page 2 of N" shown' }, async (a) => {
    const x = await ui('REG-UI-27', a, 'admin', '/invoices', async (app, c) => {
      const { page } = app;
      await waitText(page, 'Invoice #', 6000);
      let n0 = app.requests.length;
      await rt.press(page, 'Total', { role: 'columnheader', wait: 2500 });
      let r = app.requests.slice(n0).find((q) => q.url.startsWith('/api/invoices?'));
      c.that('sort=total,asc', r && /sort=total,asc/.test(r.url), r && r.url);
      await rt.press(page, '20', { role: 'button', wait: 1500 });
      n0 = app.requests.length;
      await rt.press(page, '10', { role: 'menuitem', wait: 2500 });
      r = app.requests.slice(n0).find((q) => q.url.startsWith('/api/invoices?'));
      c.that('size=10', r && /size=10/.test(r.url) && /sort=total,asc/.test(r.url), r && r.url);
      n0 = app.requests.length;
      await rt.tap(page, 'Next page', { wait: 2500 });
      r = app.requests.slice(n0).find((q) => q.url.startsWith('/api/invoices?'));
      c.that('page=1', r && /page=1/.test(r.url), r && r.url);
      c.that('hash keeps query', /page=1/.test(await hash(page)) && /size=10/.test(await hash(page)) && /sort=total,asc/.test(await hash(page)), await hash(page));
      c.that('Page 2 of', !!(await waitText(page, /^Page 2 of \d+/, 3000)));
    });
    return result(x, FE.table);
  });

  await tcase({ id: 'REG-UI-28', feature: 'Export & bulk', kind: 'UI', prd: 'B2',
    title: 'Select rows, Export selected downloads CSV, and the bulk "Cancel unpaid" cancels them (nothing held)',
    steps: 'fresh customer with 2 unpaid invoices; admin #/invoices?f=customerId:eq; tick both rows; Export selected; tick both again; Cancel unpaid; confirm',
    expected: '"2 selected" bar with Select all / Cancel unpaid / Reassign Sales POC / Send email / Export selected; POST /api/invoices/export 200 and an "Exported CSV" dialog; POST /api/invoices/bulk 200; both invoices CANCELLED' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIBulk', invoices: [{ amount: 101 }, { amount: 102 }] });
    const x = await ui('REG-UI-28', a, 'admin', `/invoices?f=customerId:eq:${cu.id}`, async (app, c) => {
      const { page } = app;
      await waitText(page, cu.invoices[0].invoiceNumber, 6000);
      await toggleRow(page, 1); await toggleRow(page, 2);
      c.that('2 selected bar', !!(await waitText(page, '2 selected', 3000)));
      for (const b of ['Cancel unpaid', 'Reassign Sales POC', 'Export selected']) c.that(`action ${b}`, await rt.exists(page, b));
      await rt.tap(page, 'Export selected', { wait: 3000 });
      const ex = await waitReq(app, (r) => r.method === 'POST' && r.url.startsWith('/api/invoices/export'), 5000);
      c.that('export 200', ex && ex.status === 200, ex);
      const dlg = await waitText(page, 'Exported CSV', 3000);
      // The CSV body is SelectableText (no semantics value): the dialog title is checked, the rows by the API response.
      c.that('"Exported CSV" dialog', !!dlg);
      if (await rt.exists(page, 'Close')) await rt.tap(page, 'Close', { role: 'button', wait: 1500 });
      if (!(await rt.exists(page, '2 selected'))) { await toggleRow(page, 1); await toggleRow(page, 2); }
      await rt.tap(page, 'Cancel unpaid', { wait: 2000 });
      const confirm = (await rt.semantics(page)).filter((n) => n.role === 'button' && /^(Cancel unpaid|Cancel invoices|Cancel \d+ invoices?|Confirm|Yes)/.test(n.text || '')).sort((p, q) => q.y - p.y)[0];
      if (confirm && (await rt.exists(page, /Dismiss/))) await rt.clickAt(page, confirm.x, confirm.y, 3000);
      const bk = await waitReq(app, (r) => r.method === 'POST' && r.url.startsWith('/api/invoices/bulk'), 5000);
      c.that('bulk 200', bk && bk.status === 200, bk);
      const s = await Promise.all(cu.invoices.map((i) => getJ(T.admin, `/api/invoices/${i.id}`)));
      c.that('both cancelled', s.every((g) => g.json.status === 'CANCELLED'), s.map((g) => g.json.status));
    });
    return result(x, FE.table);
  });

  // ================================================================== ADMIN SCREENS
  await tcase({ id: 'REG-UI-29', feature: 'Users admin', kind: 'UI', prd: 'B1',
    title: 'New user dialog creates a staff user with a role and Manage access in DEFAULT',
    steps: 'admin #/users -> New user; Username, Email, Full name, Password; Role = SALES_POC; Region access DEFAULT: Manage (scope shows "Book (set by role)"); Save',
    expected: 'the dialog offers Region access for DEFAULT (Read/Manage/Approve, All records/Own book); POST /api/users 200 with regionGrants; API user role SALES_POC, grant DEFAULT level MANAGE; the user can log in' }, async (a) => {
    const username = rt.uniq('uiuser');
    const x = await ui('REG-UI-29', a, 'admin', '/users', async (app, c) => {
      const { page } = app;
      await rt.tap(page, 'New user', { wait: 2000 });
      await fill(page, 'Username', username);
      await fill(page, 'Email', `${username}@e2e.local`);
      await fill(page, 'Full name', 'UI User');
      await fill(page, 'Password', rt.PASSWORD);
      await rt.tap(page, 'Role', { role: 'button', wait: 1500 });
      await rt.press(page, 'SALES_POC', { wait: 1500 });
      c.that('region access DEFAULT row', await rt.exists(page, /DEFAULT — Default region/));
      await rt.tap(page, 'Manage', { role: 'button', wait: 800 });
      // For a role without SCOPE_OVERRIDE the scope buttons are disabled ("Book (set by role)"); not tapped.
      c.that('scope set by role', !!(await waitText(page, /Book \(set by role\)/, 1500)));
      await rt.tap(page, 'Save', { role: 'button', wait: 3500 });
      const req = await waitReq(app, (r) => r.method === 'POST' && r.url === '/api/users', 5000);
      c.that('POST 200', req && req.status === 200, req);
      const u = await rt.findUser(T.admin, username);
      const g = u ? (await getJ(T.admin, `/api/users/${u.id}`)).json : null;
      c.that('role + grant', g && g.role === 'SALES_POC' && (g.regionGrants || []).length === 1 && g.regionGrants[0].level === 'MANAGE', g && { role: g.role, grants: g.regionGrants });
      const l = await rt.api('POST', '/api/auth/login', { body: { username, password: rt.PASSWORD } });
      c.that('can log in', l.status === 200, l.status);
    });
    return result(x, FE.users);
  });

  await tcase({ id: 'REG-UI-30', feature: 'Roles admin', kind: 'UI', prd: 'B1',
    title: 'New role dialog shows each privilege\'s region level and saves the ticked privileges',
    steps: 'admin #/roles -> New role; Name; tick CUSTOMER_VIEW and INVOICE_VIEW; Save',
    expected: 'privilege rows carry "Read"/"Manage"/"Approve"/"Global" badges (§6.2 regionLevel); POST /api/roles 200; the role has exactly those 2 privileges' }, async (a) => {
    const name = rt.uniq('UIROLE').toUpperCase().replace(/-/g, '_');
    const x = await ui('REG-UI-30', a, 'admin', '/roles', async (app, c) => {
      const { page } = app;
      await rt.tap(page, 'New role', { wait: 2500 });
      c.that('level badges', await rt.exists(page, /\nCUSTOMER_VIEW\nRead$/) && await rt.exists(page, /\nCHANGE_APPROVE\nApprove$/) && await rt.exists(page, /\nEXPORT_DATA\nGlobal$/));
      await fill(page, 'Name', name);
      await rt.tap(page, /\nCUSTOMER_VIEW\n/, { role: 'checkbox', wait: 600 });
      await rt.tap(page, /\nINVOICE_VIEW\n/, { role: 'checkbox', wait: 600 });
      const save = (await rt.semantics(page)).find((n) => n.text === 'Save' && n.role === 'button');
      if (save) await page.locator(`[data-rt="${save.i}"]`).first().dispatchEvent('click');
      await page.waitForTimeout(3000);
      const req = await waitReq(app, (r) => r.method === 'POST' && r.url === '/api/roles', 5000);
      c.that('POST 200', req && req.status === 200, req);
      const role = (await rt.roles(T.admin)).find((r) => r.name === name);
      c.that('2 privileges', role && role.privileges.slice().sort().join() === 'CUSTOMER_VIEW,INVOICE_VIEW', role && role.privileges);
    });
    return result(x, FE.roles);
  });

  await tcase({ id: 'REG-UI-31', feature: 'Products admin', kind: 'UI', prd: 'B1',
    title: 'New product dialog creates an active product that the list shows',
    steps: 'admin #/products -> New product; Name, Description, Price 12.34; Save',
    expected: 'POST /api/products 200; product active with price 12.34; listed with ₹12.34' }, async (a) => {
    const name = rt.uniq('UIProd');
    const x = await ui('REG-UI-31', a, 'admin', '/products', async (app, c) => {
      const { page } = app;
      await rt.tap(page, 'New product', { wait: 2000 });
      await fill(page, 'Name', name);
      await fill(page, 'Description', 'ui product');
      await fill(page, 'Price', '12.34');
      await rt.tap(page, 'Save', { role: 'button', wait: 3000 });
      const req = await waitReq(app, (r) => r.method === 'POST' && r.url === '/api/products', 5000);
      c.that('POST 200', req && req.status === 200, req);
      const p = (await rt.list(T.admin, '/api/products')).json.content.find((x) => x.name === name);
      c.that('saved active 12.34', p && p.active === true && Number(p.price) === 12.34, p);
      await rt.go(page, `/products?f=name:eq:${encodeURIComponent(name)}`, 3500);
      c.that('listed', !!(await waitText(page, name, 5000)) && !!(await waitText(page, '₹12.34', 2000)));
    });
    return result(x, FE.prods);
  });

  // ================================================================== POC USER / CUSTOMER LOGIN
  await tcase({ id: 'REG-UI-32', feature: 'POC user lists', kind: 'UI', prd: 'B1',
    title: 'A Sales POC\'s Invoices list shows only his invoices with the "My records only" lock chip',
    steps: 'customer with invoice A (Sales POC sales) and B (Sales POC admin); sales opens #/invoices?f=customerId:eq:<id>',
    expected: 'row A shown, row B absent; chip "My records only" (DESIGN §5.6.4 single region = today\'s locked chip); no "Your book only in:" strip (pure book view)' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIPocL', invoices: [{ amount: 71 }, { amount: 72, sales: U.admin.id }] });
    const [A, B] = cu.invoices;
    const x = await ui('REG-UI-32', a, sales, `/invoices?f=customerId:eq:${cu.id}`, async ({ page }, c) => {
      c.that('A shown', !!(await waitText(page, A.invoiceNumber, 6000)));
      c.that('B hidden', !(await rt.exists(page, B.invoiceNumber)));
      c.that('lock chip', !!(await waitText(page, 'My records only', 2000)));
      c.that('no mixed-book strip', !(await rt.exists(page, /^Your book only in:/)));
    });
    return result(x, FE.table);
  });

  await tcase({ id: 'REG-UI-33', feature: 'Customer login', kind: 'UI', prd: 'B1',
    title: 'A customer login sees its own dashboard and only its own invoices; its sidebar is the customer one',
    steps: 'fresh customer with 2 invoices; another customer\'s invoice exists; customer opens #/ at 700px, opens the drawer, then #/invoices',
    expected: '"Welcome, <customer>"; drawer entries Dashboard, Inbox, Invoices, Payments, Promises, Disputes; invoices list = its 2 invoices only' }, async (a) => {
    const cu = await customer(w, { prefix: 'UICustL', invoices: [{ amount: 81 }, { amount: 82 }] });
    const other = await customer(w, { prefix: 'UIOther', invoices: [{ amount: 83 }] });
    const x = await ui('REG-UI-33', a, { username: cu.username, password: cu.password }, null, async ({ page }, c) => {
      c.that('welcome', !!(await waitText(page, new RegExp(`Welcome, ${cu.name}`), 6000)));
      await rt.tap(page, /Open navigation menu/, { wait: 1500 });
      const all = ['Dashboard', 'Inbox', 'Customers', 'Invoices', 'Payments', 'Promises', 'Disputes', 'My tasks', 'Tasks', 'Approvals', 'Automation', 'Regions', 'Products', 'Users', 'Roles'];
      const got = (await rt.semantics(page)).map((n) => ({ ...n, text: navText(n.text) })).filter((n) => n.role === 'button' && n.x < 320 && all.includes(n.text)).map((n) => n.text);
      c.that('customer entries', JSON.stringify(got) === JSON.stringify(['Dashboard', 'Inbox', 'Invoices', 'Payments', 'Promises', 'Disputes']), got);
      await rt.dismiss(page);
      await rt.go(page, '/invoices', 4000);
      // Phone/tablet width renders rows as merged cards: match the number inside the card text.
      c.that('own A', !!(await waitText(page, new RegExp(cu.invoices[0].invoiceNumber), 5000)));
      c.that('own B', !!(await waitText(page, new RegExp(cu.invoices[1].invoiceNumber), 2000)));
      c.that('not other', !(await rt.exists(page, new RegExp(other.invoices[0].invoiceNumber))));
    }, { width: 700, height: 900 });
    return result(x, FE.shell);
  });
  // ================================================================== DRILL-DOWNS, FILTERS, POC FLOWS
  await tcase({ id: 'REG-UI-34', feature: 'Dashboard drill-down', kind: 'UI', prd: 'B1',
    title: 'Clicking an ageing bucket on the dashboard opens the invoices it counts',
    steps: 'fresh customer with an invoice 45 days overdue; admin #/; click the "31–60 days" bar of "Outstanding by days overdue"',
    expected: 'navigates to #/invoices with f=status:in:UNPAID,PARTIALLY_PAID and f=dueDate:between:<today-60>,<today-31>; GET /api/invoices carries both filters; totalElements = the bucket\'s count from /api/dashboard/outstanding-by-age' }, async (a) => {
    const cu = await customer(w, { prefix: 'UIDrill' });
    await invoice(w, cu, { amount: 45, dueDate: rt.day(-45), invoiceDate: `${rt.day(-60)}T08:00:00Z`, paymentTerm: 'CUSTOM' });
    const bucket = (await getJ(T.admin, '/api/dashboard/outstanding-by-age')).json.buckets.find((b) => b.label === '31–60 days');
    const x = await ui('REG-UI-34', a, 'admin', '/', async (app, c) => {
      const { page } = app;
      await waitText(page, /Welcome, /, 8000);
      const bar = await waitText(page, /31–60 days/, 5000, { role: 'button' });
      c.that('bucket bar', !!bar);
      if (!bar) return;
      await rt.clickAt(page, bar.x, bar.y, 3500);
      const h = await hash(page);
      c.that('hash filters', h.startsWith('#/invoices') && /status:in:UNPAID,PARTIALLY_PAID/.test(h) && new RegExp(`dueDate:between:${rt.day(-60)},${rt.day(-31)}`).test(h), h);
      const req = await waitReq(app, (r) => r.url.startsWith('/api/invoices?') && /dueDate:between/.test(r.url), 5000);
      c.that('request carries both filters', req && /status:in:UNPAID,PARTIALLY_PAID/.test(req.url), req && req.url);
      const l = await getJ(T.admin, '/api/invoices', { size: 10, filter: ['status:in:UNPAID,PARTIALLY_PAID', `dueDate:between:${rt.day(-60)},${rt.day(-31)}`] });
      c.that('list count = bucket count', l.json.totalElements === bucket.count, { list: l.json.totalElements, bucket: bucket.count });
    });
    return result(x, 'frontend/lib/features/dashboard/dashboard_charts.dart');
  });

  await tcase({ id: 'REG-UI-35', feature: 'Notifications deep link', kind: 'UI', prd: 'B1',
    title: 'Opening a notification marks it read and follows its link to the record',
    steps: 'sales2 mark-all-read; admin creates an invoice with Sales POC sales2; sales2 #/notifications; tap the "You are now Sales POC" row',
    expected: 'POST /api/notifications/{id}/read 200; hash #/invoices/{id}; the invoice page shows its number (by-id read inside his book)' }, async (a) => {
    const t2 = T.sales2;
    await rt.api('POST', '/api/notifications/mark-all-read', { token: t2, body: {} });
    const cu = await customer(w, { prefix: 'UIDeep' });
    const inv = await invoice(w, cu, { amount: 77, sales: U.sales2.id });
    const x = await ui('REG-UI-35', a, { username: U.sales2.username, password: rt.PASSWORD }, '/notifications', async (app, c) => {
      const { page } = app;
      const cell = await waitText(page, /You are now Sales POC/, 6000, { role: 'cell' });
      c.that('row', !!cell);
      if (!cell) return;
      await rt.clickAt(page, cell.x, cell.y, 3500);
      const rd = await waitReq(app, (r) => r.method === 'POST' && /^\/api\/notifications\/\d+\/read$/.test(r.url), 4000);
      c.that('marked read', rd && rd.status === 200, rd);
      c.that('followed link', (await hash(page)) === `#/invoices/${inv.id}`, await hash(page));
      c.that('invoice shown', !!(await waitText(page, inv.invoiceNumber, 5000)));
    });
    return result(x, FE.notif);
  });

  await tcase({ id: 'REG-UI-36', feature: 'List filters', kind: 'UI', prd: 'B1',
    title: 'Add filter -> Status is Cancelled filters the invoices list and shows a removable chip',
    steps: 'admin #/invoices; Add filter; Column = Status; Value = CANCELLED; Apply; then remove the chip',
    expected: 'the column menu still has the old columns plus Region / Region name / Awaiting approval; GET /api/invoices?…filter=status:eq:CANCELLED; every row Cancelled; chip shown; removing it re-queries without the filter' }, async (a) => {
    const x = await ui('REG-UI-36', a, 'admin', '/invoices', async (app, c) => {
      const { page } = app;
      await waitText(page, 'Invoice #', 6000);
      await rt.tap(page, 'Add filter', { wait: 2000 });
      await rt.tap(page, /^Column/, { wait: 1500 });
      const cols = await nodesText(page, (n) => n.role === 'menuitem');
      c.that('columns incl. new ones', ['Invoice #', 'Due date', 'Status', 'Sales POC', 'Region', 'Region name', 'Awaiting approval'].every((k) => cols.some((s) => s.startsWith(`${k}|`))), cols);
      await rt.press(page, 'Status', { role: 'menuitem', wait: 1500 });
      await rt.tap(page, /^Value/, { role: 'button', wait: 1500 });
      await rt.press(page, /^(Cancelled|CANCELLED)$/, { role: 'menuitem', wait: 1200 });
      const n0 = app.requests.length;
      await rt.tap(page, 'Apply', { role: 'button', wait: 3000 });
      const req = app.requests.slice(n0).find((r) => r.url.startsWith('/api/invoices?'));
      c.that('filter sent', req && /filter=status:eq:CANCELLED/.test(req.url), req && req.url);
      const statuses = (await rt.semantics(page)).filter((n) => n.role === 'cell' && /^(Unpaid|Partially paid|Fully paid|Cancelled)$/.test(n.text || '')).map((n) => n.text);
      c.that('rows all Cancelled', statuses.length > 0 && statuses.every((t) => t === 'Cancelled'), statuses.slice(0, 6));
      const chip = (await rt.semantics(page)).find((n) => /Status/.test(n.text || n.label || '') && /Cancelled|CANCELLED/.test(n.text || n.label || '') && n.y < 300);
      c.that('chip shown', !!chip, chip);
      const del = (await rt.semantics(page)).find((n) => /^Delete/.test(n.label || n.text || '') && n.y < 300);
      if (del) {
        const n1 = app.requests.length;
        await rt.clickAt(page, del.x, del.y, 3000);
        const r2 = app.requests.slice(n1).find((r) => r.url.startsWith('/api/invoices?'));
        c.that('chip removed re-queries unfiltered', r2 && !/status:eq:CANCELLED/.test(r2.url), r2 && r2.url);
      }
    });
    return result(x, 'frontend/lib/core/table/filter_editor.dart');
  });

  await tcase({ id: 'REG-UI-37', feature: 'Customer tabs', kind: 'UI', prd: 'B1',
    title: 'Customer detail tabs show its promises and disputes',
    steps: 'fresh customer + invoice; promise 120 (API); customer login disputes the invoice; admin #/customers/{id}; open "Payment Promise" then "Disputes"',
    expected: 'Payment Promise tab lists ₹120.00 Open; Disputes tab lists the dispute reason' }, async (a) => {
    const cu = await customer(w, { prefix: 'UITabs', invoices: [{ amount: 500 }] });
    rt.need(await rt.createPromise(T.admin, { customerId: cu.id, amount: 120, collectionPocUserId: U.coll.id }), 'promise');
    const ct = await rt.login(cu.username, cu.password);
    const reason = `tab reason ${rt.uniq('t')}`;
    rt.need(await rt.createDispute(ct, { targetType: 'INVOICE', targetId: cu.invoices[0].id, reason }), 'dispute');
    const x = await ui('REG-UI-37', a, 'admin', `/customers/${cu.id}`, async ({ page }, c) => {
      await rt.tap(page, /Payment Promise$/, { role: 'tab', wait: 3000 });
      c.that('promise ₹120.00', !!(await waitText(page, /₹120\.00/, 5000)));
      await rt.tap(page, /Disputes$/, { role: 'tab', wait: 3000 });
      c.that('dispute reason', !!(await waitText(page, new RegExp(reason), 5000)));
    });
    return result(x, FE.cust);
  });

  await tcase({ id: 'REG-UI-38', feature: 'POC user flows', kind: 'UI', prd: 'B1',
    title: 'A Sales POC creates an invoice through the form for a customer in his book; he is the default Sales POC',
    steps: 'customer K with one invoice owned by sales (his book); sales opens #/invoices/new; Customer = K; Product = Reg Widget; Qty 2; Create invoice',
    expected: 'Sales POC field pre-filled with himself; the customer picker offers K; POST /api/invoices 200; the new invoice has salesPoc = sales, total 200' }, async (a) => {
    const K = await customer(w, { prefix: 'UISalesK', invoices: [{ amount: 10 }] });
    const x = await ui('REG-UI-38', a, sales, '/invoices/new', async (app, c) => {
      const { page } = app;
      c.that('Sales POC = himself', !!(await waitText(page, new RegExp(`^Sales POC \\*\\n${U.sales.fullName}`), 5000)));
      c.that('customer picked', await pick(page, /^Customer \*/, new RegExp(`^${K.name}`), K.name));
      c.that('product picked', await pick(page, /^Product\n/, /^Reg Widget/, 'Reg Widget'));
      await rt.fillField(page, 'Qty', '2');
      await page.waitForTimeout(800);
      await rt.tap(page, 'Create invoice', { wait: 4000 });
      const req = await waitReq(app, (r) => r.method === 'POST' && r.url === '/api/invoices', 5000);
      c.that('POST 200', req && req.status === 200, req);
      const l = await getJ(T.admin, '/api/invoices', { size: 10, customerId: K.id, sort: 'id,desc' });
      const inv = l.json.content[0];
      c.that('saved: sales POC, total 200', inv && inv.salesPoc && inv.salesPoc.id === U.sales.id && Number(inv.total) === 200, inv && { poc: inv.salesPoc && inv.salesPoc.id, total: inv.total });
    });
    return result(x, FE.invForm);
  });
};
