const rt = require('../lib.js');
const S = require('./state.json');
(async () => {
  const a = await rt.adminToken();
  const t = new Date().toISOString().slice(0, 10);
  const y = new Date(Date.now() - 86400000).toISOString().slice(0, 10);
  for (const id of [2, 3]) { const r = await rt.api('GET', `/api/invoices/${id}`, { token: a }); console.log(id, r.json.invoiceNumber, r.json.invoiceDate, r.json.customerId); }
  for (const f of [`invoiceDate:lte:${y}`, 'invoiceDate:relative:yesterday', 'invoiceDate:relative:past', `invoiceDate:between:${y},${y}`, `invoiceDate:lte:${y}T23:59:59.999999Z`, `invoiceDate:lte:${y}T23:59:59.999999999Z`]) {
    const r = await rt.api('GET', `/api/invoices?size=50&filter=${encodeURIComponent('customerId:eq:' + S.A)}&filter=${encodeURIComponent(f)}`, { token: a });
    const c = r.json.content.filter((x) => x.invoiceDate.startsWith(t));
    console.log(f, '->', r.status, 'total', r.json.totalElements, "today's rows included:", c.map((x) => `${x.id}@${x.invoiceDate}`).join(','));
  }
})();
