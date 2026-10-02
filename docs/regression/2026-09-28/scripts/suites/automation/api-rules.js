// Rules: create / read / update / enable / disable / delete, validation, ownership (A1, A2, A3, A5).
const rt = require('../../lib.js');
const H = require('./helpers.js');

module.exports = async function rulesCases(ctx) {
  const { R, t, w } = ctx;
  const K = (meta, fn, o) => H.kase(R, { kind: 'API', feature: 'Rules', ...meta }, fn, o);
  const inv = w.invoices.n2[0];

  await K({ id: 'AUT-API-01', prd: 'A1', title: 'A rule can be created for every trigger kind on Customer, Invoice and Payment; the creator owns it',
    steps: 'admin POST /api/automation/rules for {ENTITY_CREATED, ENTITY_UPDATED, ENTITY_CREATED_OR_UPDATED, SCHEDULE_DAILY 09:00, SCHEDULE_WEEKLY MONDAY 09:00, MANUAL} x {CUSTOMER, INVOICE, PAYMENT}, enabled=false',
    expected: 'DESIGN §6.14: 200 RuleDto each; trigger echoed; owner = admin; status DISABLED; schedule rules carry time/day/zone' },
  async () => {
    const bad = []; const ids = [];
    for (const entityType of ['CUSTOMER', 'INVOICE', 'PAYMENT']) {
      for (const kind of ['ENTITY_CREATED', 'ENTITY_UPDATED', 'ENTITY_CREATED_OR_UPDATED', 'SCHEDULE_DAILY', 'SCHEDULE_WEEKLY', 'MANUAL']) {
        const trigger = { kind, entityType };
        if (kind.startsWith('SCHEDULE')) Object.assign(trigger, { time: '09:00', zone: 'UTC', day: kind === 'SCHEDULE_WEEKLY' ? 'MONDAY' : null });
        const r = await H.createRule(t, { enabled: false, trigger, actions: [rt.action.task()] });
        if (r.status !== 200) { bad.push(`${entityType}/${kind} -> ${r.status} ${H.short(r.text, 200)}`); continue; }
        ids.push(r.json.id);
        const j = r.json;
        if (j.trigger.kind !== kind || j.trigger.entityType !== entityType || j.owner?.id !== ctx.adminId || j.status !== 'DISABLED'
          || (kind.startsWith('SCHEDULE') && j.trigger.time !== '09:00') || (kind === 'SCHEDULE_WEEKLY' && j.trigger.day !== 'MONDAY')) {
          bad.push(`${entityType}/${kind} -> ${H.short(j.trigger)} owner=${j.owner?.id} status=${j.status}`);
        }
      }
    }
    ctx.cleanup.push(...ids);
    return { ok: bad.length === 0, actual: bad.length ? bad.join(' ; ') : `18 rules created (ids ${ids[0]}..${ids[ids.length - 1]}), all echoed, owner admin, DISABLED` };
  });

  await K({ id: 'AUT-API-02', prd: 'A1', title: 'Invalid trigger definitions are refused with field errors',
    steps: 'POST rules with time "25:00", weekly without day, zone "Mars/Base", kind "ENTITY_DELETED", entityType "PROMISE"',
    expected: 'DESIGN §6.14 field rules: 400 each, with fieldErrors on trigger.*' },
  async () => {
    const cases = [
      ['time 25:00', { kind: 'SCHEDULE_DAILY', entityType: 'INVOICE', time: '25:00', zone: 'UTC' }],
      ['weekly no day', { kind: 'SCHEDULE_WEEKLY', entityType: 'INVOICE', time: '09:00', zone: 'UTC', day: null }],
      ['bad zone', { kind: 'SCHEDULE_DAILY', entityType: 'INVOICE', time: '09:00', zone: 'Mars/Base' }],
      ['unknown kind', { kind: 'ENTITY_DELETED', entityType: 'INVOICE' }],
      ['entity PROMISE', { kind: 'ENTITY_CREATED', entityType: 'PROMISE' }],
    ];
    const out = [];
    for (const [label, trigger] of cases) {
      const r = await H.createRule(t, { trigger, actions: [rt.action.task()] });
      out.push({ label, status: r.status, fe: r.json?.fieldErrors ? Object.keys(r.json.fieldErrors).join(',') : r.json?.message });
    }
    return { ok: out.every((o) => o.status === 400), actual: out };
  });

  await K({ id: 'AUT-API-03', prd: 'A3', title: 'A rule needs 1..5 actions with unique keys',
    steps: 'POST a rule with 0 actions, 6 actions, and 2 actions sharing a key',
    expected: 'DESIGN §6.14: "Actions: 1..5, with unique keys" -> 400 fieldErrors.actions*' },
  async () => {
    const k = 'aaaaaaaa-1111-2222-3333-444444444444';
    const r0 = await H.createRule(t, { trigger: H.manual(), actions: [] });
    const r6 = await H.createRule(t, { trigger: H.manual(), actions: Array.from({ length: 6 }, () => rt.action.task()) });
    const rd = await H.createRule(t, { trigger: H.manual(), actions: [rt.action.task({ key: k }), rt.action.task({ key: k })] });
    const r5 = await H.createRule(t, { enabled: false, trigger: H.manual(), actions: Array.from({ length: 5 }, () => rt.action.task()) });
    if (r5.ok) ctx.cleanup.push(r5.json.id);
    const a = { zero: [r0.status, r0.json?.fieldErrors], six: [r6.status, r6.json?.fieldErrors], dupKeys: [rd.status, rd.json?.fieldErrors], five: r5.status };
    return { ok: r0.status === 400 && r6.status === 400 && rd.status === 400 && r5.status === 200, actual: a };
  });

  await K({ id: 'AUT-API-04', prd: 'A5', title: 'Editing a rule bumps its version; a stale version is a 409',
    steps: 'create rule; PUT with version 0 (rename) -> 200; PUT again with version 0',
    expected: 'DESIGN §6.14: PUT -> RuleDto with version+1; stale -> 409' },
  async () => {
    const rule = await H.mustRule(t, { enabled: false, trigger: H.manual(), actions: [rt.action.task()] });
    ctx.cleanup.push(rule.id);
    const body = { ...rt.rule({ name: rt.uniq('renamed'), enabled: false, trigger: H.manual(), actions: rule.actions }), version: rule.version };
    const u1 = await rt.automation.update(t, rule.id, body);
    const u2 = await rt.automation.update(t, rule.id, { ...body, name: rt.uniq('again') });
    return { ok: u1.status === 200 && u1.json.version === rule.version + 1 && u1.json.name === body.name && u2.status === 409,
      actual: { first: [u1.status, u1.json?.version, u1.json?.name], stale: [u2.status, u2.json?.message] } };
  });

  await K({ id: 'AUT-API-05', prd: 'A5', title: 'Disable and enable a rule; a schedule gets its next run only while enabled',
    steps: 'create SCHEDULE_DAILY 09:00 UTC enabled; POST /disable; POST /enable',
    expected: 'DESIGN §5.10.8: nextRunAt set when enabled, cleared on disable, recomputed on enable; status ACTIVE/DISABLED' },
  async () => {
    const rule = await H.mustRule(t, { trigger: { kind: 'SCHEDULE_DAILY', entityType: 'INVOICE', time: '09:00', zone: 'UTC' },
      condition: H.and(`id:eq:${inv.id}`), actions: [rt.action.task()] });
    const d = await rt.automation.disable(t, rule.id);
    const e = await rt.automation.enable(t, rule.id, {});
    await rt.automation.disable(t, rule.id);
    ctx.cleanup.push(rule.id);
    const a = { created: [rule.status, rule.nextRunAt], disabled: [d.status, d.json?.status, d.json?.nextRunAt], enabled: [e.status, e.json?.status, e.json?.nextRunAt] };
    return { ok: rule.status === 'ACTIVE' && !!rule.nextRunAt && /T09:00:00/.test(rule.nextRunAt) && d.json?.status === 'DISABLED' && !d.json?.nextRunAt
      && e.json?.status === 'ACTIVE' && !!e.json?.nextRunAt, actual: a };
  });

  await K({ id: 'AUT-API-06', prd: 'A5', title: 'Deleting a rule removes it from reads; its audit trail stays',
    steps: 'create rule; DELETE /rules/{id}; GET /rules/{id}; GET /rules?filter=id:eq:{id}; GET /api/audit?entityType=RULE&entityId={id}',
    expected: 'DESIGN §6.14: 204; then 404; not listed; audit has RULE_CREATED and RULE_DELETED' },
  async () => {
    const rule = await H.mustRule(t, { enabled: false, trigger: H.manual(), actions: [rt.action.task()] });
    const del = await rt.automation.remove(t, rule.id);
    const g = await rt.automation.get(t, rule.id);
    const l = await rt.automation.list(t, { filter: `id:eq:${rule.id}` });
    const au = await rt.api('GET', '/api/audit', { token: t, query: { entityType: 'RULE', entityId: rule.id } });
    const actions = (au.json || []).map((x) => x.action);
    return { ok: del.status === 204 && g.status === 404 && l.json?.totalElements === 0 && actions.includes('RULE_DELETED') && actions.includes('RULE_CREATED'),
      actual: { delete: del.status, get: g.status, listed: l.json?.totalElements, audit: actions } };
  });

  await K({ id: 'AUT-API-07', prd: 'A5', title: 'Only the owner edits a rule; an administrator must confirm taking it over',
    steps: 'nora creates a MANUAL North rule; admin PUT without confirmOwnershipChange; admin PUT with it; nora then PUT (no longer owner, no AUTOMATION_ADMIN)',
    expected: 'DESIGN §5.10.7: admin 400 fieldErrors.confirmOwnershipChange, then 200 with owner = admin; nora 403 afterwards' },
  async () => {
    const nr = await H.mustRule(w.tokens.nora, { enabled: false, trigger: H.manual(), regionIds: [w.regions.NORTH.id], actions: [rt.action.task()] });
    ctx.cleanup.push(nr.id);
    const base = { ...rt.rule({ name: nr.name, enabled: false, trigger: H.manual(), regionIds: nr.regionIds, actions: nr.actions }), version: nr.version };
    const noConfirm = await rt.automation.update(t, nr.id, base);
    const withConfirm = await rt.automation.update(t, nr.id, { ...base, confirmOwnershipChange: true });
    const noraAfter = await rt.automation.update(w.tokens.nora, nr.id, { ...base, version: withConfirm.json?.version });
    return { ok: noConfirm.status === 400 && !!noConfirm.json?.fieldErrors?.confirmOwnershipChange && withConfirm.status === 200
      && withConfirm.json.owner?.id === ctx.adminId && noraAfter.status === 403,
      actual: { noConfirm: [noConfirm.status, noConfirm.json?.fieldErrors], withConfirm: [withConfirm.status, withConfirm.json?.owner], nora: noraAfter.status } };
  });

  await K({ id: 'AUT-API-08', prd: 'A5', title: 'Rule names are unique (case-insensitive) among live rules',
    steps: 'create rule "Dup-<tag>"; create "dup-<tag>"',
    expected: 'DESIGN §3.6: unique among non-deleted rules, case-insensitive -> 400 fieldErrors.name' },
  async () => {
    const n = rt.uniq('Dup');
    const a = await H.createRule(t, { name: n, enabled: false, trigger: H.manual(), actions: [rt.action.task()] });
    const b = await H.createRule(t, { name: n.toLowerCase(), enabled: false, trigger: H.manual(), actions: [rt.action.task()] });
    if (a.ok) ctx.cleanup.push(a.json.id);
    if (b.ok) ctx.cleanup.push(b.json.id);
    return { ok: a.status === 200 && b.status === 400 && !!b.json?.fieldErrors?.name, actual: { first: a.status, second: [b.status, b.json?.fieldErrors] } };
  });

  await K({ id: 'AUT-API-09', prd: 'A5', title: 'A user without AUTOMATION_MANAGE cannot create rules; without AUTOMATION_VIEW cannot list them',
    steps: 'cashier (seeded role) POST /api/automation/rules; GET /api/automation/rules',
    expected: 'DESIGN §6.14 gates: 403 on create (AUTOMATION_MANAGE) and on list (AUTOMATION_VIEW) unless the role holds it' },
  async () => {
    const ct = await rt.cashierToken();
    const me = await rt.me(ct);
    const c = await H.createRule(ct, { trigger: H.manual(), actions: [rt.action.task()] });
    const l = await rt.automation.list(ct);
    const hasView = (me.privileges || []).includes('AUTOMATION_VIEW');
    const hasManage = (me.privileges || []).includes('AUTOMATION_MANAGE');
    if (c.ok) ctx.cleanup.push(c.json.id);
    return { ok: (hasManage ? c.status !== 403 : c.status === 403) && (hasView ? l.status === 200 : l.status === 403),
      actual: { cashierPrivileges: (me.privileges || []).filter((p) => p.startsWith('AUTOMATION')), create: c.status, list: l.status } };
  });

  await K({ id: 'AUT-API-10', prd: 'A5', title: 'Rules list and summary answer with counts and filters',
    steps: 'GET /api/automation/rules?filter=enabled:eq:false&sort=name,asc; GET /rules/summary',
    expected: 'DESIGN §6.14: PageResponse of RuleSummary; summary {active, disabled, suspended, failedRuns24h} consistent with the list' },
  async () => {
    const all = await rt.list(t, '/api/automation/rules');
    const dis = await rt.list(t, '/api/automation/rules', { filter: ['enabled:eq:false'] });
    const s = await rt.api('GET', '/api/automation/rules/summary', { token: t });
    const rows = all.json?.content || [];
    const act = rows.filter((r) => r.status === 'ACTIVE').length; const off = rows.filter((r) => r.status === 'DISABLED').length;
    return { ok: all.status === 200 && s.status === 200 && s.json.active === act && s.json.disabled === off && (dis.json?.content || []).every((r) => r.enabled === false),
      actual: { listed: rows.length, active: act, disabled: off, summary: s.json } };
  });
};
