#!/usr/bin/env node
// Everyone has read what reached them more than a week before the end of the simulated year, so the
// notification bells show recent news rather than a year of backlog. Run after both SQL passes.
//
//   node mark-read.js --api http://localhost:8092 --out run

const fs = require('fs');
const path = require('path');

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : true]);
  return acc;
}, []));
const API = args.api || 'http://localhost:8092';
const OUT = path.resolve(args.out || 'run');
const meta = JSON.parse(fs.readFileSync(path.join(OUT, 'meta.json'), 'utf8'));
const CUTOFF = Date.parse(meta.simEnd) - 7 * 86400000;
const PASSWORDS = { admin: 'admin123', cashier: 'cashier123' };

async function api(method, p, { token, body } = {}) {
  const res = await fetch(API + p, {
    method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (res.status >= 300) throw new Error(`${method} ${p} -> ${res.status} ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : null;
}
const login = async (username) => (await api('POST', '/api/auth/login', { body: { username, password: PASSWORDS[username] || 'Demo1234!' } })).token;

async function all(pathBase, token) {
  const rows = [];
  for (let page = 0; ; page++) {
    const r = await api('GET', `${pathBase}${pathBase.includes('?') ? '&' : '?'}page=${page}&size=50`, { token });
    rows.push(...r.content);
    if (r.content.length < 50) return rows;
  }
}

(async () => {
  const admin = await login('admin');
  const users = (await all('/api/users', admin)).filter((u) => u.active !== false);
  let marked = 0;
  for (const u of users) {
    const token = await login(u.username);
    const ids = (await all('/api/notifications', token))
      .filter((n) => !n.read && Date.parse(n.createdAt) < CUTOFF).map((n) => n.id);
    for (let i = 0; i < ids.length; i += 200) {
      await api('POST', '/api/notifications/bulk', { token, body: { action: 'MARK_READ', ids: ids.slice(i, i + 200) } });
    }
    marked += ids.length;
  }
  console.log(`users: ${users.length}, notifications marked read: ${marked}`);
})().catch((e) => { console.error('MARK-READ FAILED:', e.message); process.exit(1); });
