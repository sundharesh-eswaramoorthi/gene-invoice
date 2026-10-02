// Infrastructure for the migration suite: restore the user's dev-database dump into gi-e2e-db (e2e_* databases
// only), boot the baseline jar and the new jar against the copies, stop only what this suite started.
//
// Isolation: the ONLY interaction with gene-invoice-db is the read-only pg_dump in dumpDev(). Everything else
// touches container gi-e2e-db (databases e2e_migration / e2e_migration_base) and ports 18107 / 18108.
const fs = require('fs');
const path = require('path');
const { execFileSync, spawn } = require('child_process');

const E2E = path.resolve(__dirname, '..', '..');
const AREA = 'migration';
const RT = path.join(E2E, 'runtime', AREA);
const PIDS = path.join(RT, 'pids');
const EXTRA_PIDS = path.join(RT, 'pids-extra');
const DATA = path.join(__dirname, 'data');
const NEW_JAR = path.join(E2E, 'dist', 'backend.jar');
const BASE_JAR = path.join(E2E, 'baseline-src', 'backend', 'target', 'gene-invoice-backend-0.0.1-SNAPSHOT.jar');
const DB_NEW = 'e2e_migration';
const DB_BASE = 'e2e_migration_base';
const PORT_NEW = 18107;
const PORT_BASE = 18108;
const PORT_H2 = 18108; // used only while the baseline is not running
const DUMP = path.join(DATA, 'dev.sql');
const DUMP_FILTERED = path.join(DATA, 'dev.filtered.sql');

function assertDb(db) {
  if (!/^e2e_migration(_base|_seed|_seedup|_legacy2)?$/.test(db)) throw new Error(`refusing database ${db}`);
}
function assertPort(p) {
  if (!(p >= 18100 && p <= 18199)) throw new Error(`refusing port ${p}`);
}

function psql(db, sqlText) {
  assertDb(db === 'postgres' ? 'e2e_migration' : db);
  const out = execFileSync('docker', ['exec', '-i', 'gi-e2e-db', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'e2e', '-d', db,
    '-At', '-F', '\t', '-c', sqlText], { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
  return out.split('\n').filter((l) => l.length).map((l) => l.split('\t'));
}
const one = (db, q) => { const r = psql(db, q); return r.length ? r[0][0] : null; };

/** Read-only dump of the user's dev database (the only permitted interaction with gene-invoice-db). */
function dumpDev({ force = false } = {}) {
  fs.mkdirSync(DATA, { recursive: true });
  if (!force && fs.existsSync(DUMP) && fs.statSync(DUMP).size > 0) return DUMP;
  const out = execFileSync('docker', ['exec', 'gene-invoice-db', 'pg_dump', '-U', 'geneinvoice', 'geneinvoice'],
    { maxBuffer: 1024 * 1024 * 1024 });
  fs.writeFileSync(DUMP, out);
  return DUMP;
}

/** The dump without owner changes (role geneinvoice does not exist in gi-e2e-db). */
function filteredDump() {
  const src = fs.readFileSync(DUMP, 'utf8');
  const lines = src.split('\n').filter((l) => !/^ALTER .* OWNER TO /.test(l) && !/^(GRANT|REVOKE) /.test(l));
  fs.writeFileSync(DUMP_FILTERED, lines.join('\n'));
  return DUMP_FILTERED;
}

/** Tables and row counts in the dump (from its COPY blocks). */
function dumpCounts() {
  const counts = {};
  let t = null; let n = 0;
  for (const l of fs.readFileSync(DUMP, 'utf8').split('\n')) {
    if (t === null) {
      const m = /^COPY public\.(\w+) /.exec(l);
      if (m) { t = m[1]; n = 0; }
    } else if (l === '\\.') { counts[t] = n; t = null; } else n++;
  }
  return counts;
}

function restore(db) {
  assertDb(db);
  psql('postgres', `DROP DATABASE IF EXISTS ${db} WITH (FORCE)`);
  psql('postgres', `CREATE DATABASE ${db}`);
  const f = filteredDump();
  execFileSync('bash', ['-c', `docker exec -i gi-e2e-db psql -q -v ON_ERROR_STOP=1 -U e2e -d ${db} < '${f}' > /dev/null`],
    { maxBuffer: 64 * 1024 * 1024 });
}
function dropDb(db) {
  assertDb(db);
  try { psql('postgres', `DROP DATABASE IF EXISTS ${db} WITH (FORCE)`); } catch (e) { /* container gone */ }
}

// ---- processes ----------------------------------------------------------------------------------

function cmdline(pid) {
  try { return execFileSync('ps', ['-p', String(pid), '-o', 'command='], { encoding: 'utf8' }).trim(); } catch { return ''; }
}
function alive(pid) { try { process.kill(pid, 0); return true; } catch { return false; } }

function readPids(file) {
  if (!fs.existsSync(file)) return [];
  return fs.readFileSync(file, 'utf8').split('\n').filter(Boolean).map((l) => l.split(/\s+/));
}

/** Stops a pid only when its command line contains `needle` (never a process we did not start). */
async function stopPid(pid, needle) {
  if (!pid || !alive(pid)) return false;
  if (!cmdline(pid).includes(needle)) return false;
  process.kill(pid, 'SIGTERM');
  for (let i = 0; i < 60 && alive(pid); i++) await new Promise((r) => setTimeout(r, 500));
  if (alive(pid)) process.kill(pid, 'SIGKILL');
  return true;
}

function portBusy(port) {
  try { execFileSync('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN'], { stdio: 'pipe' }); return true; } catch { return false; }
}

/**
 * Starts a backend jar. kind: 'backend' (the new jar, recorded in up.sh's pids file so down.sh stops it),
 * 'baseline' or 'h2' (recorded in pids-extra; stopped by stopExtras()).
 */
function startBackend({ kind, jar, port, db, profile = 'dev', log, docs, extraEnv = {} }) {
  assertPort(port);
  if (db) assertDb(db);
  if (portBusy(port)) throw new Error(`port ${port} busy`);
  fs.mkdirSync(docs, { recursive: true });
  const env = {
    ...process.env, SPRING_PROFILES_ACTIVE: profile, SERVER_PORT: String(port),
    SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE: '5', MAIL_TRANSPORT: 'none', DOCUMENT_ROOT: docs,
  };
  Object.assign(env, extraEnv);
  if (db) Object.assign(env, { DB_URL: `jdbc:postgresql://localhost:18432/${db}`, DB_USER: 'e2e', DB_PASSWORD: 'e2e' });
  else { delete env.DB_URL; delete env.DB_USER; delete env.DB_PASSWORD; }
  const fd = fs.openSync(log, 'a');
  const child = spawn('java', ['-Xmx512m', `-De2e.area=${AREA}`, `-De2e.kind=${kind}`, '-jar', jar],
    { env, detached: true, stdio: ['ignore', fd, fd] });
  child.unref();
  const pid = child.pid;
  if (kind === 'backend') {
    const rows = readPids(PIDS).filter((r) => r[0] !== 'backend');
    rows.push(['backend', String(pid)]);
    fs.writeFileSync(PIDS, rows.map((r) => r.join(' ')).join('\n') + '\n');
  } else {
    fs.appendFileSync(EXTRA_PIDS, `${kind} ${pid}\n`);
  }
  return pid;
}

async function waitLogin(port, { username = 'admin', password = 'admin123', timeoutMs = 240000, pid } = {}) {
  const end = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < end) {
    if (pid && !alive(pid)) return { ok: false, reason: 'process exited' };
    try {
      const r = await fetch(`http://localhost:${port}/api/auth/login`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username, password }),
      });
      if (r.status === 200) return { ok: true, json: await r.json() };
      // 401 right after the web server starts: DataSeeder (a CommandLineRunner) has not made the user yet.
      last = { ok: false, status: r.status, reason: await r.text(), up: true };
      if (Date.now() > end - timeoutMs + 45000) return last;
    } catch { /* not yet */ }
    await new Promise((r) => setTimeout(r, 1500));
  }
  return last || { ok: false, reason: 'timeout' };
}

/** Stops the new backend recorded in up.sh's pids file (up.sh started it for this area). */
async function stopNewBackend() {
  const row = readPids(PIDS).find((r) => r[0] === 'backend');
  if (!row) return false;
  return stopPid(Number(row[1]), NEW_JAR);
}

async function stopExtras(kinds = null) {
  const rows = readPids(EXTRA_PIDS);
  const keep = [];
  for (const [kind, pid] of rows) {
    if (kinds && !kinds.includes(kind)) { keep.push([kind, pid]); continue; }
    const needle = kind === 'baseline' ? BASE_JAR : NEW_JAR;
    await stopPid(Number(pid), needle);
  }
  if (keep.length) fs.writeFileSync(EXTRA_PIDS, keep.map((r) => r.join(' ')).join('\n') + '\n');
  else if (fs.existsSync(EXTRA_PIDS)) fs.unlinkSync(EXTRA_PIDS);
}

function grepLog(file, re) {
  if (!fs.existsSync(file)) return [];
  return fs.readFileSync(file, 'utf8').split('\n').filter((l) => re.test(l));
}

module.exports = {
  E2E, AREA, RT, PIDS, EXTRA_PIDS, DATA, NEW_JAR, BASE_JAR, DB_NEW, DB_BASE, PORT_NEW, PORT_BASE, PORT_H2, DUMP,
  psql, one, dumpDev, dumpCounts, restore, dropDb, startBackend, waitLogin, stopNewBackend, stopExtras, stopPid,
  alive, cmdline, readPids, grepLog, portBusy,
};
