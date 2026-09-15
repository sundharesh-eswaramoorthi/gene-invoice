// Detail screens and dashboard at phone width (400x850).
const { rt, S, DIR, hash, allText, results } = require('./uih.js');
const R = results();
(async () => {
  const app = await rt.openApp({ token: await rt.adminToken(), width: 400, height: 850 });
  const { page } = app;
  await rt.shot(page, DIR, 'm0-dashboard');
  const d0 = await allText(page);
  await rt.go(page, `#/invoices/${S.A1.id}`, 4500);
  await rt.shot(page, DIR, 'm1-invoice');
  const t1 = await allText(page);
  await rt.go(page, `#/invoices/${S.A1.id}?tab=history`, 4500);
  for (let i = 0; i < 6; i++) { await page.mouse.move(200, 600); await page.mouse.wheel(0, 400); await page.waitForTimeout(150); }
  await rt.enableSemantics(page);
  await rt.shot(page, DIR, 'm2-invoice-history-scrolled');
  const t2 = await allText(page);
  await rt.go(page, `#/payments/${S.PA1.id}`, 4500);
  await rt.shot(page, DIR, 'm3-payment');
  R.rec('M-PHONE', 'detail screens at 400px: header, status chip, Raise dispute and tabs visible; history reachable', t1.includes(S.A1.invoiceNumber) && t1.includes('Raise dispute') && /History/.test(t1) && /Invoice created|Payment applied|Dispute/.test(t2),
    { dashboard: d0.slice(0, 200), inv: t1.slice(0, 300), hist: t2.slice(0, 300), pageErrors: app.pageErrors.slice(0, 3), apiErrors: app.apiErrors });
  R.save('mobile.json');
  await app.close();
})().catch((e) => { console.error('SCRIPT ERROR', e.message); process.exit(1); });
