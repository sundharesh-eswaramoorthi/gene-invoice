// Shared helpers for the regression testers.
// Test environment: backend http://localhost:8083 (Postgres DB geneinvoice_rt), web http://localhost:8084.
// Usage from a script in a sub-folder:  const rt = require('../lib.js');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { chromium } = require('playwright-core');

const API = 'http://localhost:8083';
const WEB = 'http://localhost:8084/';
const PASSWORD = 'Passw0rd!';
// Default dev secret from application.yml; the test backend runs without JWT_SECRET.
const JWT_SECRET = 'change-me-please-this-must-be-at-least-32-bytes-long-secret-key';

// ---- API -----------------------------------------------------------------------------

/** Calls the API. Returns { status, json, text, headers }. Never throws on HTTP status. */
async function api(method, p, { token, body, headers = {} } = {}) {
  const res = await fetch(API + p, {
    method,
    headers: {
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  return { status: res.status, json, text, headers: Object.fromEntries(res.headers) };
}

async function login(username, password) {
  const r = await api('POST', '/api/auth/login', { body: { username, password } });
  if (r.status !== 200) throw new Error(`login ${username} -> ${r.status} ${r.text}`);
  return r.json.token;
}

const adminToken = () => login('admin', 'admin123');
const cashierToken = () => login('cashier', 'cashier123');

/** A unique, prefixed name so concurrent testers never collide. */
function uniq(prefix) {
  return `${prefix}-${Date.now().toString(36)}${crypto.randomBytes(2).toString('hex')}`;
}

async function roleId(token, name) {
  const r = await api('GET', '/api/roles?size=50', { token });
  const role = (r.json?.content || []).find((x) => x.name === name);
  if (!role) throw new Error(`role ${name} not found (${r.status})`);
  return role.id;
}

/** Creates a staff login with the given role (e.g. SALES_POC). Returns the user plus its password. */
async function createStaff(token, roleName, prefix = 'rt') {
  const username = uniq(prefix);
  const r = await api('POST', '/api/users', {
    token,
    body: {
      username, email: `${username}@rt.local`, fullName: username.toUpperCase(),
      password: PASSWORD, roleId: await roleId(token, roleName), active: true,
    },
  });
  if (r.status >= 300) throw new Error(`create user ${roleName} -> ${r.status} ${r.text}`);
  return { ...r.json, username, password: PASSWORD };
}

/** Creates a customer together with its self-service login. Returns the customer plus the login. */
async function createCustomer(token, prefix = 'rt') {
  const username = uniq(prefix);
  const r = await api('POST', '/api/customers', {
    token,
    body: { name: username, phone: '555-0100', email: `${username}@rt.local`, address: '1 Test Way', username, password: PASSWORD },
  });
  if (r.status >= 300) throw new Error(`create customer -> ${r.status} ${r.text}`);
  return { ...r.json, username, password: PASSWORD };
}

/** A signed token for any username, with a chosen lifetime — for expiry tests only. */
function mintToken(username, ttlSeconds = 3600) {
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const now = Math.floor(Date.now() / 1000);
  const body = `${b64({ alg: 'HS256' })}.${b64({ sub: username, iat: now, exp: now + ttlSeconds })}`;
  return `${body}.${crypto.createHmac('sha256', Buffer.from(JWT_SECRET)).update(body).digest('base64url')}`;
}

// ---- UI (Flutter web, CanvasKit) -------------------------------------------------------

/**
 * Opens the app signed in with `token` (or at the login screen when omitted), waits for it to
 * boot, and turns on the accessibility tree so elements can be found. Collects every API
 * response >= 400 and every page error.
 */
async function openApp({ token, width = 1366, height = 900 } = {}) {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const ctx = await browser.newContext({ viewport: { width, height } });
  if (token) {
    await ctx.addInitScript((t) => localStorage.setItem('flutter.gene_invoice_token', JSON.stringify(t)), token);
  }
  const page = await ctx.newPage();
  const apiErrors = [];
  const pageErrors = [];
  page.on('response', (r) => {
    if (r.url().startsWith(API) && r.status() >= 400) {
      apiErrors.push({ status: r.status(), method: r.request().method(), url: decodeURIComponent(r.url().slice(API.length)) });
    }
  });
  page.on('pageerror', (e) => pageErrors.push(e.message));
  await page.goto(WEB);
  await page.waitForTimeout(6000);
  await enableSemantics(page);
  return { browser, ctx, page, apiErrors, pageErrors, close: () => browser.close() };
}

async function enableSemantics(page) {
  await page.evaluate(() => document.querySelector('flt-semantics-placeholder')?.click());
  await page.waitForTimeout(800);
}

/** In-app navigation. (A cold deep link lands on the dashboard — known and accepted.) */
async function go(page, hash, wait = 3500) {
  await page.evaluate((h) => { location.hash = h; }, hash);
  await page.waitForTimeout(wait);
  await enableSemantics(page);
}

/** Every labelled accessibility node with its role, label and on-screen centre. */
async function semantics(page) {
  return page.$$eval('flt-semantics', (els) => els.map((e, i) => {
    e.setAttribute('data-rt', String(i));
    const r = e.getBoundingClientRect();
    return {
      i, role: e.getAttribute('role'), label: e.getAttribute('aria-label'),
      text: e.querySelector('flt-semantics') ? '' : (e.textContent || '').trim(),
      x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2),
      w: Math.round(r.width), h: Math.round(r.height),
    };
  }).filter((n) => n.label || n.text));
}

function matches(n, m) {
  const s = `${n.label || ''} ${n.text || ''}`.trim();
  return m instanceof RegExp ? m.test(n.label || '') || m.test(n.text || '') : (n.label === m || n.text === m || s === m);
}

/** Finds a node by label/text (string or RegExp). Throws listing what is on screen when missing. */
async function find(page, matcher, { role } = {}) {
  const nodes = await semantics(page);
  const hit = nodes.find((n) => matches(n, matcher) && (!role || n.role === role));
  if (!hit) {
    throw new Error(`No element matching ${matcher}${role ? ` (role ${role})` : ''}. On screen: `
      + nodes.map((n) => `[${n.role}] ${n.label || n.text}`).join(' | ').slice(0, 1500));
  }
  return hit;
}

/** Taps a button, tab, chip or checkbox by its label/text. */
async function tap(page, matcher, opts = {}) {
  const n = await find(page, matcher, opts);
  await page.locator(`flt-semantics[data-rt="${n.i}"]`).dispatchEvent('click');
  await page.waitForTimeout(opts.wait ?? 1500);
  return n;
}

/** A real mouse click — needed for links inside merged tiles, whose semantics nodes are not separate. */
async function clickAt(page, x, y, wait = 1500) {
  await page.mouse.click(x, y);
  await page.waitForTimeout(wait);
}

/** Types into whatever field has focus. Click the field first (clickAt / tap). Select-all clears it. */
async function typeText(page, text, { clear = false } = {}) {
  if (clear) {
    await page.keyboard.press('Meta+A');
    await page.keyboard.press('Backspace');
  }
  await page.keyboard.type(text, { delay: 20 });
}

/** Saves a screenshot under <dir>/shots and returns its path — Read it to see the screen. */
async function shot(page, dir, name) {
  const d = path.join(dir, 'shots');
  fs.mkdirSync(d, { recursive: true });
  const file = path.join(d, `${name}.png`);
  await page.screenshot({ path: file });
  return file;
}

module.exports = {
  API, WEB, PASSWORD,
  api, login, adminToken, cashierToken, uniq, roleId, createStaff, createCustomer, mintToken,
  openApp, enableSemantics, go, semantics, find, tap, clickAt, typeText, shot,
};
