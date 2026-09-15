const rt = require('../lib.js');
const S = require('./setup.json');
(async () => {
  const admin = await rt.adminToken();
  const sum = await rt.api('GET', '/api/promises/summary', { token: admin });
  console.log('UIS-16 /api/promises/summary', sum.status, sum.text);
  const viewer = await rt.login(S.viewer.username, S.password);
  const sales = await rt.login(S.sales.username, S.password);
  for (const [who, t] of [['admin', admin], ['viewer', viewer], ['sales', sales]]) {
    const r = await rt.api('GET', '/api/payments?size=10', { token: t });
    console.log(`UIS-18 ${who} GET /api/payments -> ${r.status} totalElements=${r.json?.totalElements}`);
    const me = await rt.api('GET', '/api/auth/me', { token: t });
    console.log(`  ${who} privileges include PAYMENT_MANAGE: ${JSON.stringify(me.json?.privileges || me.json?.role)?.includes('PAYMENT_MANAGE')}`);
  }
  const d = await rt.api('GET', `/api/disputes/${S.disp.id}`, { token: admin });
  console.log('UIS-05 GET /api/disputes/:id targetSummary =', JSON.stringify(d.json?.targetSummary));
})().catch((e) => { console.error(e); process.exit(1); });
