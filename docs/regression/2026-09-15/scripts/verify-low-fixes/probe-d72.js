// D-72 probe: what the accessibility tree actually holds for row selection on a list page.
// Prints every checkbox node with its label, plus how the surrounding row is exposed, at 1366
// (data table) and 400 (cards). Read-only — changes nothing. Run: node probe-d72.js
const rt = require('../lib.js');

const lab = (n) => `${n.label || ''} ${n.text || ''}`.trim().replace(/\n/g, ' / ');

(async () => {
  const admin = await rt.adminToken();
  for (const width of [1366, 400]) {
    const app = await rt.openApp({ token: admin, width, height: 900 });
    try {
      await rt.go(app.page, '#/invoices', 5000);
      await rt.enableSemantics(app.page);
      const nodes = await rt.semantics(app.page);
      const boxes = nodes.filter((n) => n.role === 'checkbox');
      console.log(`\n=== ${width}px: ${nodes.length} semantic nodes, ${boxes.length} checkbox nodes`);
      boxes.slice(0, 8).forEach((b, i) =>
        console.log(`  checkbox ${i}: label=${JSON.stringify(lab(b))} @${b.x},${b.y} ${b.w}x${b.h}`));
      const roles = {};
      for (const n of nodes) roles[n.role] = (roles[n.role] || 0) + 1;
      console.log('  roles:', JSON.stringify(roles));
    } finally {
      await app.close();
    }
  }
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
