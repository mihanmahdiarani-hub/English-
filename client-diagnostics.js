const http = require('http');

const previousCreateServer = http.createServer.bind(http);

function readBody(req, maxBytes = 64 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    req.on('data', chunk => {
      total += chunk.length;
      if (total > maxBytes) {
        reject(new Error('diagnostic payload too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function clean(value, max = 4000) {
  return String(value ?? '')
    .replace(/[\u0000-\u001f\u007f]/g, ' ')
    .replace(/\s+/g, ' ')
    .slice(0, max);
}

http.createServer = function patchedCreateServer(listener) {
  return previousCreateServer(async (req, res) => {
    let pathname = '/';
    try { pathname = new URL(req.url, `http://${req.headers.host || 'localhost'}`).pathname; } catch {}

    if (req.method === 'POST' && pathname === '/api/client-diagnostic') {
      try {
        const raw = await readBody(req);
        let data = {};
        try { data = JSON.parse(raw || '{}'); } catch {}
        const type = clean(data.type || 'client', 120);
        const message = clean(data.message || '', 5000);
        const detail = clean(JSON.stringify(data.detail || {}), 5000);
        console.log(`[client-diagnostic] type=${JSON.stringify(type)} message=${JSON.stringify(message)} detail=${detail}`);
        res.writeHead(204, { 'Cache-Control': 'no-store' });
        res.end();
      } catch (err) {
        res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end('bad diagnostic');
      }
      return;
    }

    listener(req, res);
  });
};

console.log('Client diagnostics preload active');
