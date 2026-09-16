# Demo data: Indian cinema theme

Generates a year of demo data (Sep 2025 to the day it is run) with the same volumes as the demo
data it replaced, and swaps it into the one database. Staff are Indian film stars, customers are
film characters, products are cars, bikes and trucks.

| | Count | Notes |
|---|---|---|
| Staff | 13 | `admin` is Rajinikanth, `cashier` is Amitabh Bachchan; 1 viewer, 4 Sales, 4 Collection and 2 Customer Success POCs from Hindi, Tamil, Telugu, Malayalam, Kannada, Punjabi and Bengali cinema |
| Customers | 107 | Film characters, each with a self-service login |
| Products | 13 | Cars, bikes and trucks at approximate showroom prices; the Hindustan Ambassador is retired in December |
| Invoices | 1,013 | 1–4 lines, averaging about ₹1 Cr; about 46% fully paid, 26% unpaid, 23% partly paid, 5% cancelled |
| Payments | ~768 | Dated through the year; one returned cheque is voided and paid again by transfer |
| Promises | 156 | Kept, partly kept, broken (paid late or never), open and cancelled |
| Disputes | 83 | Pending, denied, and approved (cancellations, corrected lines, the voided cheque) |

Logins: `admin/admin123`, `cashier/cashier123`; every other account (staff and customers) uses
`Demo1234!`. Usernames are the name in lower case with dots, e.g. `shahrukh.khan`, `rocky.bhai`.

## How it works

Everything goes through the application's API, so allocation, credit, invoice status, POC rules,
disputes, history and notifications follow the application's own rules. `generate.js` plans the
year from a fixed seed and replays it in date order. The API stamps everything with "now", so the
data is then backdated:

1. `generate.js` records, for every action, its simulated time and the real-time window it ran in.
   Promises that must not be judged before their payments arrive are sent with a `2099-MM-DD`
   placeholder date; broken and open promises get their real date.
2. `postprocess.sql` moves every timestamp written inside a window to that action's simulated time,
   restores promised dates, numbers invoices by their own date (`INV-yyyyMMdd-NNNN`), and makes the
   history snapshots (Postgres large objects) and notification texts agree. It changes dates, times
   and invoice numbers only — never amounts or statuses.
3. The backend is restarted so its promise sweep and `POST /api/promises/recompute?apply=true`
   judge promises on their real dates (both find nothing to change in a clean run).
4. `postprocess-promises.sql` dates each broken promise's transition and notification to the
   morning after its promised date.
5. `mark-read.js` marks notifications older than a week as read.
6. `verify.sql` checks money, statuses, dates, numbering and history; every check must say `ok`.

## Running it

```bash
docs/demo-data/run.sh geneinvoice_theme 8092 /tmp/gene-invoice-demo-run
```

This builds the data in a scratch database behind a backend on :8092 and leaves that backend
running for review. It refuses to generate into `geneinvoice` and touches no other database or
port. Review in a browser with a web build pointed at it:

```bash
cd frontend && flutter build web -o /tmp/theme-web --dart-define=API_BASE_URL=http://localhost:8092
python3 -m http.server 8093 --directory /tmp/theme-web
```

## Swapping it in

Stop both backends so nothing is connected, then replace the live database with the checked copy:

```bash
docker exec gene-invoice-db pg_dump -U geneinvoice -d geneinvoice | gzip > ~/gene-invoice-db-backups/<date>/geneinvoice_before_theme.sql.gz
docker exec gene-invoice-db psql -U geneinvoice -d geneinvoice_theme -c "DROP SCHEMA demo_gen CASCADE;"
kill $(lsof -nP -iTCP:8092 -sTCP:LISTEN -t) $(lsof -nP -iTCP:8082 -sTCP:LISTEN -t)
docker exec gene-invoice-db psql -U geneinvoice -d postgres \
  -c "DROP DATABASE geneinvoice;" -c "ALTER DATABASE geneinvoice_theme RENAME TO geneinvoice;"
cd backend && SPRING_PROFILES_ACTIVE=dev DB_URL=jdbc:postgresql://localhost:5433/geneinvoice DB_USER=geneinvoice \
  DB_PASSWORD=geneinvoice nohup java -jar target/gene-invoice-backend-0.0.1-SNAPSHOT.jar \
  --server.port=8082 > /tmp/gene-invoice-8082.log 2>&1 &
docker exec -i gene-invoice-db psql -U geneinvoice -d geneinvoice < docs/demo-data/verify.sql
```

Run the whole thing on the same UTC day it is swapped in: the simulated year ends at that day's
close of business, and promise statuses are judged against "today".

## Things to know

- A few planned payments and promises find nothing to pay: when a dispute cancels a paid invoice the
  refund becomes credit, and the application spends it on the customer's next invoice. The run
  prints these as warnings; counts land a few short of the targets.
- The application leaves the previous copy of a dispute's proposed change behind as an unreferenced
  large object when it saves the resolution (seen in older data too). `verify.sql` reports these
  separately instead of failing.
- Amounts are in crores, so the figure tiles on the detail pages wrap for amounts of ₹10 lakh and
  above.
