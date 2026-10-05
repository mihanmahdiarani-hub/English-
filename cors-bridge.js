const http = require('http');

const previousCreateServer = http.createServer.bind(http);
const ALLOWED_ORIGINS = new Set([
  'https://appassets.androidplatform.net'
]);

http.createServer = function corsAwareCreateServer(listener) {
  return previousCreateServer((req, res) => {
    const origin = String(req.headers.origin || '');
    if (ALLOWED_ORIGINS.has(origin)) {
      res.setHeader('Access-Control-Allow-Origin', origin);
      res.setHeader('Vary', 'Origin');
      res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
      res.setHeader(
        'Access-Control-Allow-Headers',
        req.headers['access-control-request-headers'] ||
          'Content-Type, X-File-Name, X-File-Size, X-Clip-Start, X-Clip-End'
      );
      res.setHeader('Access-Control-Max-Age', '86400');
    }

    if (req.method === 'OPTIONS' && ALLOWED_ORIGINS.has(origin)) {
      res.statusCode = 204;
      res.end();
      return;
    }

    listener(req, res);
  });
};

console.log('Local Android shell CORS bridge active');
