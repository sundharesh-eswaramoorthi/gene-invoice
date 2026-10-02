// Minimal static server for a Flutter web build: `node serve.js <root> <port>`.
// No caching (every round may bring a new build); unknown paths fall back to index.html.
const http = require('http');
const fs = require('fs');
const path = require('path');
const root = path.resolve(process.argv[2]);
const port = Number(process.argv[3]);
const types = {
  '.html': 'text/html; charset=utf-8', '.js': 'application/javascript', '.mjs': 'application/javascript',
  '.json': 'application/json', '.css': 'text/css', '.wasm': 'application/wasm', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.otf': 'font/otf',
  '.ttf': 'font/ttf', '.woff': 'font/woff', '.woff2': 'font/woff2', '.map': 'application/json',
  '.frag': 'application/octet-stream', '.bin': 'application/octet-stream',
};
http.createServer((req, res) => {
  let p = decodeURIComponent((req.url || '/').split('?')[0]);
  let file = path.join(root, p);
  if (!file.startsWith(root)) { res.writeHead(403); return res.end(); }
  fs.stat(file, (err, st) => {
    if (err || st.isDirectory()) file = err ? path.join(root, 'index.html') : path.join(file, 'index.html');
    fs.readFile(file, (e2, buf) => {
      if (e2) { res.writeHead(404); return res.end('not found'); }
      res.writeHead(200, {
        'Content-Type': types[path.extname(file).toLowerCase()] || 'application/octet-stream',
        'Cache-Control': 'no-store',
      });
      res.end(buf);
    });
  });
}).listen(port, '127.0.0.1', () => console.log(`serving ${root} on http://localhost:${port}/`));
