-- Consistency checks on the generated data. Every "failures" count must be 0.
-- Works with or without demo_gen (it is dropped once the data is accepted): the checks that need
-- the generation window are skipped when demo_gen.meta is absent.

\set ON_ERROR_STOP on
\pset footer off

CREATE TEMP TABLE checks(name text, failures bigint);

INSERT INTO checks
SELECT 'invoice paid amount = its allocations', count(*)
FROM invoices i LEFT JOIN (SELECT invoice_id, sum(amount) s FROM payment_allocations GROUP BY 1) a ON a.invoice_id = i.id
WHERE i.paid_amount <> coalesce(a.s, 0);

INSERT INTO checks
SELECT 'invoice status matches its amounts', count(*) FROM invoices
WHERE (status = 'UNPAID' AND paid_amount <> 0)
   OR (status = 'PARTIALLY_PAID' AND NOT (paid_amount > 0 AND paid_amount < total))
   OR (status = 'FULLY_PAID' AND paid_amount < total)
   OR (status = 'CANCELLED' AND paid_amount <> 0);

INSERT INTO checks
SELECT 'active payment = allocations + credit applied', count(*)
FROM payments p LEFT JOIN (SELECT payment_id, sum(amount) s FROM payment_allocations GROUP BY 1) a ON a.payment_id = p.id
WHERE p.status = 'ACTIVE' AND p.amount <> coalesce(a.s, 0) + p.credit_applied;

INSERT INTO checks
SELECT 'voided payment holds no money', count(*)
FROM payments p WHERE p.status = 'VOIDED'
  AND (p.credit_applied <> 0 OR EXISTS (SELECT 1 FROM payment_allocations a WHERE a.payment_id = p.id));

INSERT INTO checks
SELECT 'customer credit = unspent credit of its payments, never negative', count(*)
FROM customers c LEFT JOIN (SELECT customer_id, sum(credit_applied) s FROM payments WHERE status = 'ACTIVE' GROUP BY 1) p ON p.customer_id = c.id
WHERE c.credit_balance <> coalesce(p.s, 0) OR c.credit_balance < 0;

INSERT INTO checks
SELECT 'invoice raised when it is dated', count(*) FROM invoices WHERE created_at <> invoice_date;

INSERT INTO checks
SELECT 'invoice number carries its own date, unique', count(*) FROM invoices
WHERE substr(invoice_number, 5, 8) <> to_char(invoice_date AT TIME ZONE 'UTC', 'YYYYMMDD')
   OR invoice_number IN (SELECT invoice_number FROM invoices GROUP BY 1 HAVING count(*) > 1);

INSERT INTO checks
SELECT 'nothing dated before the customer existed', count(*) FROM (
  SELECT i.id FROM invoices i JOIN customers c ON c.id = i.customer_id WHERE i.invoice_date < c.created_at
  UNION ALL SELECT p.id FROM payments p JOIN customers c ON c.id = p.customer_id WHERE p.paid_at < c.created_at
  UNION ALL SELECT p.id FROM payment_promises p JOIN customers c ON c.id = p.customer_id WHERE p.created_at < c.created_at
  UNION ALL SELECT d.id FROM disputes d JOIN customers c ON c.id = d.customer_id WHERE d.created_at < c.created_at
) x;

INSERT INTO checks
SELECT 'disputes resolved after they were opened', count(*) FROM disputes
WHERE (status = 'PENDING') <> (resolved_at IS NULL) OR resolved_at < created_at;

INSERT INTO checks
SELECT 'promised dates are real (no 2099 placeholders)', count(*) FROM payment_promises WHERE promised_date >= date '2090-01-01';

INSERT INTO checks
SELECT 'every record''s history runs forward in time', count(*) FROM (
  SELECT created_at < lag(created_at) OVER (PARTITION BY entity_type, entity_id ORDER BY id) AS backwards FROM audit_logs
) h WHERE h.backwards;

INSERT INTO checks
SELECT 'no promise marked broken before its date had gone', count(*)
FROM audit_logs a JOIN payment_promises p ON a.entity_type = 'PROMISE' AND a.entity_id = p.id
WHERE a.action = 'PROMISE_STATUS_CHANGED' AND convert_from(lo_get(a.after_json::oid), 'UTF8') = '"BROKEN"'
  AND a.created_at < ((p.promised_date + 1)::timestamp AT TIME ZONE 'UTC');

INSERT INTO checks
SELECT 'broken promises were paid nothing in time and were notified', count(*)
FROM payment_promises p
WHERE p.status = 'BROKEN' AND (p.broken_notified_at IS NULL
   OR NOT EXISTS (SELECT 1 FROM notifications n WHERE n.type = 'PROMISE_BROKEN' AND n.link = '/promises/' || p.id));

INSERT INTO checks
SELECT 'no timestamp in the future', count(*) FROM (
  SELECT created_at t FROM users UNION ALL SELECT created_at FROM customers UNION ALL SELECT created_at FROM customer_pocs
  UNION ALL SELECT created_at FROM products UNION ALL SELECT invoice_date FROM invoices UNION ALL SELECT paid_at FROM payments
  UNION ALL SELECT created_at FROM payment_promises UNION ALL SELECT updated_at FROM payment_promises
  UNION ALL SELECT created_at FROM disputes UNION ALL SELECT resolved_at FROM disputes
  UNION ALL SELECT created_at FROM notifications UNION ALL SELECT created_at FROM audit_logs
) x WHERE t > now();

-- The application itself leaves a superseded copy of a dispute's proposed change behind when it
-- saves the resolution, so those are reported below rather than counted as failures.
CREATE TEMP VIEW unreferenced_objects AS
SELECT l.oid, convert_from(lo_get(l.oid), 'UTF8') AS content
FROM pg_largeobject_metadata l
WHERE l.oid::text NOT IN (SELECT before_json FROM audit_logs WHERE before_json IS NOT NULL
                          UNION ALL SELECT after_json FROM audit_logs WHERE after_json IS NOT NULL
                          UNION ALL SELECT proposed_change_json FROM disputes WHERE proposed_change_json IS NOT NULL);

INSERT INTO checks
SELECT 'every large object is referenced (apart from superseded dispute proposals)', count(*)
FROM unreferenced_objects WHERE content !~ '^\{"action":"(cancel|void|replace_items|update_notes|update_amount|update_meta)"';

INSERT INTO checks
SELECT 'history snapshots name only existing invoice numbers', count(*) FROM (
  SELECT DISTINCT x[1] AS no FROM audit_logs a,
    LATERAL regexp_matches(coalesce(convert_from(lo_get(a.before_json::oid), 'UTF8'), '') || ' ' ||
                           coalesce(convert_from(lo_get(a.after_json::oid), 'UTF8'), ''), '(INV-[0-9]{8}-[0-9]{4})', 'g') AS x
) n WHERE n.no NOT IN (SELECT invoice_number FROM invoices);

INSERT INTO checks
SELECT 'history snapshots carry no placeholder promised date', count(*) FROM audit_logs a
WHERE a.entity_type = 'PROMISE' AND coalesce(convert_from(lo_get(a.after_json::oid), 'UTF8'), '') LIKE '%"promisedDate":"2099-%';

DO $$
BEGIN
  IF to_regclass('demo_gen.meta') IS NOT NULL THEN
    INSERT INTO checks
    SELECT 'no row keeps a generation-time timestamp', count(*) FROM (
      SELECT created_at t FROM users UNION ALL SELECT created_at FROM customers UNION ALL SELECT created_at FROM customer_pocs
      UNION ALL SELECT created_at FROM products UNION ALL SELECT created_at FROM invoices UNION ALL SELECT paid_at FROM payments
      UNION ALL SELECT created_at FROM payment_promises UNION ALL SELECT updated_at FROM payment_promises
      UNION ALL SELECT broken_notified_at FROM payment_promises
      UNION ALL SELECT created_at FROM disputes UNION ALL SELECT updated_at FROM disputes UNION ALL SELECT resolved_at FROM disputes
      UNION ALL SELECT created_at FROM notifications UNION ALL SELECT created_at FROM audit_logs
    ) x, demo_gen.meta m WHERE x.t > m.sim_end + interval '1 hour';

    INSERT INTO checks
    SELECT 'history snapshots carry no generation-time instant', count(*) FROM audit_logs a, demo_gen.meta m,
      LATERAL regexp_matches(coalesce(convert_from(lo_get(a.before_json::oid), 'UTF8'), '') || ' ' ||
                             coalesce(convert_from(lo_get(a.after_json::oid), 'UTF8'), ''),
                             '([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?Z)', 'g') AS x
    WHERE x[1]::timestamptz > m.sim_end + interval '1 hour';
  END IF;
END $$;

SELECT name AS "check", failures, CASE WHEN failures = 0 THEN 'ok' ELSE 'FAIL' END AS result FROM checks;

\echo
\echo '---- volumes ----'
SELECT 'users' t, count(*) FROM users UNION ALL SELECT 'customers', count(*) FROM customers
UNION ALL SELECT 'customer_pocs', count(*) FROM customer_pocs UNION ALL SELECT 'products', count(*) FROM products
UNION ALL SELECT 'invoices', count(*) FROM invoices UNION ALL SELECT 'invoice_items', count(*) FROM invoice_items
UNION ALL SELECT 'payments', count(*) FROM payments UNION ALL SELECT 'payment_allocations', count(*) FROM payment_allocations
UNION ALL SELECT 'payment_promises', count(*) FROM payment_promises UNION ALL SELECT 'disputes', count(*) FROM disputes
UNION ALL SELECT 'notifications', count(*) FROM notifications UNION ALL SELECT 'audit_logs', count(*) FROM audit_logs;

\echo '---- invoices by status ----'
SELECT status, count(*), round(sum(total) / 1e7, 1) AS crore FROM invoices GROUP BY 1 ORDER BY 2 DESC;
\echo '---- payments ----'
SELECT status, method, count(*), round(sum(amount) / 1e7, 1) AS crore FROM payments GROUP BY 1, 2 ORDER BY 1, 3 DESC;
\echo '---- promises ----'
SELECT status, count(*), count(*) FILTER (WHERE EXISTS (SELECT 1 FROM payment_promise_invoices i WHERE i.promise_id = p.id)) AS on_invoices
FROM payment_promises p GROUP BY 1 ORDER BY 2 DESC;
\echo '---- disputes ----'
SELECT target_type, status, count(*) FROM disputes GROUP BY 1, 2 ORDER BY 1, 2;
\echo '---- notifications ----'
SELECT type, count(*), count(*) FILTER (WHERE is_read) AS read FROM notifications GROUP BY 1 ORDER BY 2 DESC;
\echo '---- payments whose money only reached later invoices (refunded to credit, then spent) ----'
SELECT count(*) FROM payments p
WHERE EXISTS (SELECT 1 FROM payment_allocations a WHERE a.payment_id = p.id)
  AND p.paid_at < (SELECT min(i.invoice_date) FROM payment_allocations a JOIN invoices i ON i.id = a.invoice_id WHERE a.payment_id = p.id);
\echo '---- superseded dispute proposals left by the application ----'
SELECT count(*) FROM unreferenced_objects;
\echo '---- credit ----'
SELECT count(*) FILTER (WHERE credit_balance > 0) AS customers_in_credit, round(sum(credit_balance) / 1e5, 1) AS lakh FROM customers;
\echo '---- billed and collected per month (UTC) ----'
SELECT to_char(m, 'YYYY-MM') AS month,
       (SELECT count(*) FROM invoices WHERE date_trunc('month', invoice_date AT TIME ZONE 'UTC') = m) AS invoices,
       (SELECT round(coalesce(sum(total), 0) / 1e7, 1) FROM invoices WHERE status <> 'CANCELLED' AND date_trunc('month', invoice_date AT TIME ZONE 'UTC') = m) AS billed_cr,
       (SELECT round(coalesce(sum(amount), 0) / 1e7, 1) FROM payments WHERE status = 'ACTIVE' AND date_trunc('month', paid_at AT TIME ZONE 'UTC') = m) AS collected_cr
FROM generate_series(date '2025-09-01', date_trunc('month', now() AT TIME ZONE 'UTC'), interval '1 month') AS m;
