// Round 5: one step around the r4 fix (RGN-R4-1, PendingChangeScope.recordRegion: an approval's record visibility follows the
// record's own region, live and as of a date, read through Temporal.fromValid over the version rows). On migrated data the
// only version rows are the upgrade's history baseline, so this checks recordRegion over those rows, for migrated users.
const rt = require('../../lib.js');
const NO_ACCESS = 'You no longer have access to this record';
const RO = 'Read-only in the as-of view';

module.exports = async function migA47({ kase, cn, I, state, usersAList, tokenFor, levelLines, LOG, short }) {
  await kase({ id: 'MIG-A-47', feature: 'Approvals on migrated data', prd: 'B2',
    title: 'A held terms change on a migrated invoice follows the record when its migrated customer moves region: migrated DEFAULT-only users lose the now-values (or the change), admin keeps them; as of before the move they see it read-only',
    steps: 'on the upgraded dev DB (after MIG-A-46), as admin: POST /api/regions {MIGR5}; PUT DEFAULT thresholds [INVOICE_CREATE 5, INVOICE_TERMS 1]; PATCH the migrated open invoice {paymentTerm NET_90, dueDate +90d} -> held; GET /api/approvals/{pc} + list as every migrated staff user (DEFAULT/APPROVE/ALL from the upgrade); ageWorld(1); POST /api/customers/{migrated}/move-region MIGR5; GET invoice, approval and list as each user live and asOf=<yesterday>; move back',
    expected: 'TRIAGE r4 userDecision + FIXLOG r4 B1: before the move every migrated staff user who can open the invoice gets the approval 200 with current filled; move 200 and pc CLOSED; after: DEFAULT-only users get the invoice 404 and the approval either 404 (no approval right) or 200 with current null, every changes[].current/currentText null and blockedReason "You no longer have access to this record" -- never live values; admin (MIGR5 creator) 200 with current; list membership == detail 200 for each user; asOf yesterday: each user who saw it before gets 200, current null, blockedReason "Read-only in the as-of view"',
    codeRef: 'backend/src/main/java/com/geneinvoice/approval/PendingChangeScope.java (recordRegion / canSeeRecord / isVisible)' },
  async () => {
    if (!state.writeA) return { status: 'BLOCKED', actual: 'no suitable migrated customer' };
    const w = state.writeA;
    const invId = w.paymentPost.invoiceIds[0];
    const t = await tokenFor(cn, 'admin', 'admin123');
    const regs = await cn.api('GET', '/api/regions', { token: t, query: { size: 50 } });
    const def = (regs.json && (regs.json.content || regs.json) || []).find((r) => r.defaultRegion || r.code === 'DEFAULT');
    let nr = (regs.json && (regs.json.content || regs.json) || []).find((r) => r.code === 'MIGR5');
    if (!nr) { const c = await cn.api('POST', '/api/regions', { token: t, body: { code: 'MIGR5', name: 'Mig R5', active: true } }); nr = c.json; if (c.status !== 200 && c.status !== 201) return { ok: false, actual: ['create region', c.status, c.text.slice(0, 300)] }; }
    const th = await cn.api('PUT', `/api/regions/${def.id}/approval-thresholds`, { token: t, body: { thresholds: [{ operation: 'INVOICE_CREATE', amount: 5, windowHours: 24 }, { operation: 'INVOICE_TERMS', amount: 1, windowHours: 24 }] } });
    const inv0 = await cn.api('GET', `/api/invoices/${invId}`, { token: t });
    const patch = await cn.api('PATCH', `/api/invoices/${invId}`, { token: t, body: { paymentTerm: 'NET_90' } });
    const pcId = patch.json && patch.json.pendingChange && patch.json.pendingChange.id;
    if (patch.status !== 202 || !pcId) return { ok: false, severity: 'high', actual: { threshold: [th.status, th.text.slice(0, 200)], invoice: [inv0.status, inv0.json && { status: inv0.json.status, balance: inv0.json.balance, paymentTerm: inv0.json.paymentTerm, dueDate: inv0.json.dueDate }], patch: [patch.status, patch.text.slice(0, 300)] } };
    // Every migrated staff user with the reset password.
    const staff = usersAList().filter((u) => !u.customer && u.username !== 'admin');
    const tok = { admin: t };
    for (const u of staff) tok[u.username] = await tokenFor(cn, u.username, u.password);
    const noNow = (j) => j && j.current == null && (j.changes || []).every((x) => x.current == null && x.currentText == null);
    const f = (r) => r.status === 200 ? { s: 200, status: r.json.status, statusAt: r.json.statusAt, current: r.json.current != null, rowsNow: (r.json.changes || []).filter((x) => x.current != null || x.currentText != null).map((x) => x.field), canApprove: r.json.canApprove, blockedReason: r.json.blockedReason } : { s: r.status };
    const inList = async (tk, q = {}) => { const l = await cn.listAll(tk, '/api/approvals', { view: 'ALL', ...q }); return l.status === 200 ? (l.json.content || []).some((x) => x.id === pcId) : `list ${l.status}`; };
    const before = {}; const after = {}; const asOf = {}; const fails = [];
    for (const [u, tk] of Object.entries(tok)) {
      if (!tk) { fails.push(`${u}: login failed`); continue; }
      const iv = await cn.api('GET', `/api/invoices/${invId}`, { token: tk });
      const a = await cn.api('GET', `/api/approvals/${pcId}`, { token: tk });
      before[u] = { invoice: iv.status, approval: f(a), listed: await inList(tk) };
      if (iv.status === 200 && !(a.status === 200 && a.json.current != null)) fails.push(`before: ${u} opens the invoice but not the change with now-values`);
      if ((before[u].listed === true) !== (a.status === 200)) fails.push(`before: ${u} list/detail disagree`);
    }
    rt.ageWorld(1, { db: I.DB_NEW });
    const cust = await cn.api('GET', `/api/customers/${w.customerId}`, { token: t });
    const mv = await cn.api('POST', `/api/customers/${w.customerId}/move-region`, { token: t, body: { regionId: nr.id, version: cust.json && cust.json.version, reason: 'e2e r5 migrated move' } });
    if (mv.status !== 200) fails.push(`move ${mv.status} ${mv.text.slice(0, 200)}`);
    for (const [u, tk] of Object.entries(tok)) {
      if (!tk) continue;
      const iv = await cn.api('GET', `/api/invoices/${invId}`, { token: tk });
      const a = await cn.api('GET', `/api/approvals/${pcId}`, { token: tk });
      after[u] = { invoice: iv.status, approval: f(a), listed: await inList(tk) };
      if (u === 'admin') {
        if (!(a.status === 200 && a.json.current != null && a.json.status === 'CLOSED' && iv.status === 200)) fails.push('after: admin should see CLOSED with now-values and open the invoice');
      } else {
        if (iv.status !== 404) fails.push(`after: ${u} invoice ${iv.status} (expected 404: DEFAULT-only user)`);
        if (a.status === 200 && !(noNow(a.json) && a.json.blockedReason === NO_ACCESS)) fails.push(`after: ${u} sees the change of a record they can no longer open WITH now-values or without the no-access reason`);
        if (a.status !== 200 && a.status !== 404) fails.push(`after: ${u} approval ${a.status}`);
      }
      if ((after[u].listed === true) !== (a.status === 200)) fails.push(`after: ${u} list/detail disagree`);
      const y = rt.day(-1);
      const ay = await cn.api('GET', `/api/approvals/${pcId}`, { token: tk, query: { asOf: y } });
      asOf[u] = { approval: f(ay), listed: await inList(tk, { asOf: y }) };
      const sawBefore = before[u].approval.s === 200;
      if (sawBefore && !(ay.status === 200 && ay.json.current == null && ay.json.blockedReason === RO)) fails.push(`asOf: ${u} saw it before the move but as of ${y} gets ${JSON.stringify(f(ay))}`);
      if ((asOf[u].listed === true) !== (ay.status === 200)) fails.push(`asOf: ${u} list/detail disagree`);
    }
    // Put things back (customer to DEFAULT) so later reads are unaffected.
    const cust2 = await cn.api('GET', `/api/customers/${w.customerId}`, { token: t });
    const back = await cn.api('POST', `/api/customers/${w.customerId}/move-region`, { token: t, body: { regionId: def.id, version: cust2.json && cust2.json.version, reason: 'e2e r5 back' } });
    const pcRow = I.psql(I.DB_NEW, `select status, region_id from pending_changes where id=${pcId}`).map((r) => r.join('/'));
    const errs = levelLines(LOG.newA2, 'ERROR').slice(-3);
    return { ok: fails.length === 0, severity: 'high', actual: { pendingChange: pcId, invoice: invId, move: mv.status, moveBack: back.status, pcRow, fails, before, after, asOf },
      evidence: `threshold ${th.status}; patch ${patch.status}; backend log ${LOG.newA2} last ERRORs: ${errs.join(' | ')}` };
  });
};
