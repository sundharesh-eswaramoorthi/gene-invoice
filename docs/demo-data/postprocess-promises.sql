-- Pass 2, after the application has judged the promises against their real dates: the start-up
-- sweep (overdue open promises, with "promise broken" notifications) and
-- POST /api/promises/recompute?apply=true (everything else, without notifications).
--
-- Broken promises were sent with their real date, so the application judged them broken the moment
-- they were made; that transition, its notification and broken_notified_at are dated to the morning
-- after the promised date. Anything the sweep or the recompute wrote "now" is dated to when it
-- happened: the morning after the promised date for a partly kept promise whose date has gone, the
-- last counted payment otherwise — never before the promise's earlier history, never after the end of
-- the simulated year. Expects demo_gen.meta.fix_start: the real time just before the restart.

\set ON_ERROR_STOP on
BEGIN;

CREATE TABLE demo_gen.promise_decided AS
WITH base AS (
  SELECT p.id, p.status, p.created_at, p.promised_date,
         ((p.promised_date + 1)::timestamp + interval '15 minutes') AT TIME ZONE 'UTC' AS morning_after,
         (SELECT max(pay.paid_at) FROM payment_promise_payments pp
            JOIN payments pay ON pay.id = pp.payment_id WHERE pp.promise_id = p.id) AS last_paid,
         (SELECT max(a.created_at) FROM audit_logs a, demo_gen.meta m
           WHERE a.entity_type = 'PROMISE' AND a.entity_id = p.id AND a.created_at < m.fix_start) AS last_history
  FROM payment_promises p
)
SELECT b.id, b.status,
       LEAST(m.sim_end,
         CASE
           WHEN b.status = 'BROKEN' THEN GREATEST(b.morning_after, b.created_at + interval '1 minute')
           ELSE GREATEST(
             CASE
               WHEN b.status = 'PARTIALLY_KEPT' AND b.promised_date < (m.fix_start AT TIME ZONE 'UTC')::date THEN b.morning_after
               ELSE coalesce(b.last_paid, b.morning_after)
             END,
             coalesce(b.last_history, b.created_at) + interval '1 minute')
         END) AS decided_at
FROM base b, demo_gen.meta m;

DO $$
DECLARE
  m demo_gen.meta%ROWTYPE;
  n bigint;
BEGIN
  SELECT * INTO m FROM demo_gen.meta;

  UPDATE audit_logs a SET created_at = d.decided_at FROM demo_gen.promise_decided d
  WHERE d.status = 'BROKEN' AND a.entity_type = 'PROMISE' AND a.entity_id = d.id
    AND a.action = 'PROMISE_STATUS_CHANGED' AND convert_from(lo_get(a.after_json::oid), 'UTF8') = '"BROKEN"';
  GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'broken transitions dated: %', n;

  UPDATE notifications x SET created_at = d.decided_at FROM demo_gen.promise_decided d
  WHERE d.status = 'BROKEN' AND x.type = 'PROMISE_BROKEN' AND x.link = '/promises/' || d.id;
  GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'broken notifications dated: %', n;

  UPDATE payment_promises p SET broken_notified_at = d.decided_at FROM demo_gen.promise_decided d
  WHERE d.status = 'BROKEN' AND p.id = d.id AND p.broken_notified_at IS NOT NULL;
  GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'broken_notified_at dated: %', n;

  UPDATE audit_logs a SET created_at = d.decided_at FROM demo_gen.promise_decided d
  WHERE a.entity_type = 'PROMISE' AND a.entity_id = d.id AND a.created_at >= m.fix_start;
  GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'sweep/recompute history rows dated: %', n;

  UPDATE notifications x SET created_at = d.decided_at FROM demo_gen.promise_decided d
  WHERE x.created_at >= m.fix_start AND x.link = '/promises/' || d.id;
  GET DIAGNOSTICS n = ROW_COUNT; RAISE NOTICE 'sweep notifications dated: %', n;

  UPDATE payment_promises p SET updated_at = d.decided_at FROM demo_gen.promise_decided d
  WHERE p.id = d.id AND p.updated_at >= m.fix_start;
  UPDATE payment_promises p SET broken_notified_at = d.decided_at FROM demo_gen.promise_decided d
  WHERE p.id = d.id AND p.broken_notified_at >= m.fix_start;
END $$;

COMMIT;
