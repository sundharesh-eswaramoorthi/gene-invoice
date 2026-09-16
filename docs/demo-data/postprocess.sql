-- Pass 1, run once right after generate.js on the generated database (see README.md).
--
-- Expects demo_gen.meta, demo_gen.win (run/windows.csv) and demo_gen.promise_dates
-- (run/promise_dates.csv) to be loaded. Moves every timestamp written during an action's real-time
-- window to that action's simulated time, puts the real promised dates back, numbers invoices by
-- their own date, and makes the history snapshots (large objects) and notification texts agree.
-- Only dates, times and invoice numbers change; amounts and statuses are the application's own.

\set ON_ERROR_STOP on
BEGIN;

CREATE INDEX IF NOT EXISTS win_real_start ON demo_gen.win (real_start);

-- The simulated time of the action that was running at real time ts; null outside the run.
CREATE OR REPLACE FUNCTION demo_gen.sim_of(ts timestamptz) RETURNS timestamptz
LANGUAGE sql STABLE AS $$
  SELECT w.sim FROM demo_gen.win w
  WHERE w.real_start <= ts AND ts < w.real_end
  ORDER BY w.real_start DESC
  LIMIT 1
$$;

-- 1. Row timestamps.
DO $$
DECLARE
  m demo_gen.meta%ROWTYPE;
  c record;
  n bigint;
BEGIN
  SELECT * INTO m FROM demo_gen.meta;
  FOR c IN SELECT * FROM (VALUES
      ('users', 'created_at'), ('customers', 'created_at'), ('customer_pocs', 'created_at'),
      ('products', 'created_at'), ('invoices', 'created_at'), ('payments', 'paid_at'),
      ('payment_promises', 'created_at'), ('payment_promises', 'updated_at'),
      ('payment_promises', 'broken_notified_at'), ('payment_promises', 'overridden_at'),
      ('disputes', 'created_at'), ('disputes', 'updated_at'), ('disputes', 'resolved_at'),
      ('notifications', 'created_at'), ('audit_logs', 'created_at')) AS t(tbl, col)
  LOOP
    EXECUTE format('UPDATE %I SET %I = coalesce(demo_gen.sim_of(%I), %I) WHERE %I >= %L AND %I < %L',
                   c.tbl, c.col, c.col, c.col, c.col, m.gen_start, c.col, m.gen_end);
    GET DIAGNOSTICS n = ROW_COUNT;
    RAISE NOTICE '%.%: % row(s) moved', c.tbl, c.col, n;
    -- Written by the application on start-up, before the first action (admin and cashier).
    EXECUTE format('UPDATE %I SET %I = %L WHERE %I < %L AND %I > %L',
                   c.tbl, c.col, m.sim_setup, c.col, m.gen_start, c.col, m.sim_end + interval '1 hour');
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n > 0 THEN RAISE NOTICE '%.%: % start-up row(s) dated to the setup day', c.tbl, c.col, n; END IF;
  END LOOP;
END $$;

-- 2. Promised dates: the real ones instead of the 2099 placeholders.
UPDATE payment_promises p SET promised_date = d.real_date
FROM demo_gen.promise_dates d WHERE d.promise_id = p.id;

-- 3. Invoice numbers from the invoice's own UTC day, in the order they were raised that day.
CREATE TABLE demo_gen.renum AS
SELECT id, invoice_number AS old_no,
       'INV-' || to_char(invoice_date AT TIME ZONE 'UTC', 'YYYYMMDD') || '-' ||
       lpad(row_number() OVER (PARTITION BY (invoice_date AT TIME ZONE 'UTC')::date
                               ORDER BY invoice_date, id)::text, 4, '0') AS new_no
FROM invoices;
CREATE UNIQUE INDEX ON demo_gen.renum (old_no);
CREATE UNIQUE INDEX ON demo_gen.renum (id);
UPDATE invoices SET invoice_number = 'TMP-' || id;
UPDATE invoices i SET invoice_number = r.new_no FROM demo_gen.renum r WHERE r.id = i.id;
-- The next invoice counts today's numbers afresh (InvoiceNumbers#next).
UPDATE invoice_number_sequence SET issued_day = NULL, last_number = 0;

-- 4. Text that repeats numbers, instants or promised dates.
CREATE OR REPLACE FUNCTION demo_gen.fix_text(t text, promise_year text) RETURNS text
LANGUAGE plpgsql STABLE AS $$
DECLARE
  r text := t;
  m text;
  rid bigint;
  s timestamptz;
  meta demo_gen.meta%ROWTYPE;
BEGIN
  IF t IS NULL THEN RETURN NULL; END IF;
  SELECT * INTO meta FROM demo_gen.meta;
  -- Old numbers become tokens first, so a new number is never mistaken for an old one.
  FOR m IN SELECT DISTINCT x[1] FROM regexp_matches(t, '(INV-[0-9]{8}-[0-9]{4})', 'g') AS x LOOP
    SELECT id INTO rid FROM demo_gen.renum WHERE old_no = m;
    IF rid IS NOT NULL THEN r := replace(r, m, chr(1) || rid || chr(2)); END IF;
  END LOOP;
  FOR rid IN SELECT DISTINCT (x[1])::bigint FROM regexp_matches(r, chr(1) || '([0-9]+)' || chr(2), 'g') AS x LOOP
    r := replace(r, chr(1) || rid || chr(2), (SELECT new_no FROM demo_gen.renum WHERE id = rid));
  END LOOP;
  FOR m IN SELECT DISTINCT x[1] FROM regexp_matches(r, '([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?Z)', 'g') AS x LOOP
    s := m::timestamptz;
    IF s >= meta.gen_start AND s < meta.gen_end THEN
      r := replace(r, m, to_char(demo_gen.sim_of(s) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'));
    ELSIF s < meta.gen_start AND s > meta.sim_end + interval '1 hour' THEN
      r := replace(r, m, to_char(meta.sim_setup AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'));
    END IF;
  END LOOP;
  IF promise_year IS NOT NULL THEN
    r := replace(r, '"promisedDate":"2099-', '"promisedDate":"' || promise_year || '-');
  END IF;
  RETURN r;
END $$;

-- Audit snapshots are large objects: write a fixed copy and drop the old one when anything changed.
DO $$
DECLARE
  a record;
  yr text;
  txt text;
  fixed text;
  rewritten int := 0;
BEGIN
  FOR a IN SELECT id, entity_type, entity_id, before_json, after_json FROM audit_logs ORDER BY id LOOP
    yr := NULL;
    IF a.entity_type = 'PROMISE' THEN
      SELECT to_char(real_date, 'YYYY') INTO yr FROM demo_gen.promise_dates WHERE promise_id = a.entity_id;
    END IF;
    IF a.before_json IS NOT NULL THEN
      txt := convert_from(lo_get(a.before_json::oid), 'UTF8');
      fixed := demo_gen.fix_text(txt, yr);
      IF fixed IS DISTINCT FROM txt THEN
        UPDATE audit_logs SET before_json = lo_from_bytea(0, convert_to(fixed, 'UTF8'))::text WHERE id = a.id;
        PERFORM lo_unlink(a.before_json::oid);
        rewritten := rewritten + 1;
      END IF;
    END IF;
    IF a.after_json IS NOT NULL THEN
      txt := convert_from(lo_get(a.after_json::oid), 'UTF8');
      fixed := demo_gen.fix_text(txt, yr);
      IF fixed IS DISTINCT FROM txt THEN
        UPDATE audit_logs SET after_json = lo_from_bytea(0, convert_to(fixed, 'UTF8'))::text WHERE id = a.id;
        PERFORM lo_unlink(a.after_json::oid);
        rewritten := rewritten + 1;
      END IF;
    END IF;
  END LOOP;
  RAISE NOTICE 'audit snapshots rewritten: %', rewritten;
END $$;

UPDATE audit_logs SET reason = demo_gen.fix_text(reason, NULL) WHERE reason ~ 'INV-[0-9]{8}';
UPDATE notifications SET title = demo_gen.fix_text(title, NULL), message = demo_gen.fix_text(message, NULL)
WHERE title ~ 'INV-[0-9]{8}' OR message ~ 'INV-[0-9]{8}';

COMMIT;
