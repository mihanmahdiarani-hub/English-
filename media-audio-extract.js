const http = require('http');
const { spawn } = require('child_process');
const ffmpegPath = require('ffmpeg-static');

const previousCreateServer = http.createServer.bind(http);

function sendJson(res, status, body) {
  if (res.headersSent) return;
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store'
  });
  res.end(JSON.stringify(body));
}

function safeName(value) {
  try { value = decodeURIComponent(String(value || 'media')); } catch {}
  return String(value || 'media').replace(/[\r\n\t]/g, ' ').slice(0, 180);
}

function numberHeader(req, name, fallback = 0) {
  const value = Number(req.headers[name]);
  return Number.isFinite(value) ? value : fallback;
}

function audioName(fileName, startSec, endSec) {
  const base = safeName(fileName).replace(/\.[^.]+$/, '') || 'media';
  const range = endSec > startSec
    ? `-${Math.round(startSec)}-${Math.round(endSec)}`
    : `-${Math.round(startSec)}-end`;
  return `${base}${range}.aac`;
}

function handleExtractAudio(req, res) {
  if (!ffmpegPath) {
    sendJson(res, 500, { ok: false, error: 'FFmpeg binary is not available on the server' });
    return;
  }

  const fileName = safeName(req.headers['x-file-name']);
  const inputMime = String(req.headers['content-type'] || 'application/octet-stream').split(';')[0];
  const startSec = Math.max(0, numberHeader(req, 'x-start-sec', 0));
  const endRaw = numberHeader(req, 'x-end-sec', 0);
  const endSec = endRaw > 0 ? endRaw : 0;
  const declaredSize = Math.max(0, numberHeader(req, 'x-file-size', 0));

  if (!/^(video|audio)\//i.test(inputMime)) {
    sendJson(res, 400, { ok: false, error: 'Only audio/video files are supported' });
    return;
  }
  if (endSec && endSec <= startSec) {
    sendJson(res, 400, { ok: false, error: 'End time must be after start time' });
    return;
  }

  const args = ['-hide_banner', '-loglevel', 'error', '-i', 'pipe:0'];
  if (startSec > 0) args.push('-ss', String(startSec));
  if (endSec > startSec) args.push('-t', String(endSec - startSec));
  args.push(
    '-map', '0:a:0',
    '-vn',
    '-ac', '1',
    '-ar', '16000',
    '-c:a', 'aac',
    '-b:a', '64k',
    '-f', 'adts',
    'pipe:1'
  );

  console.log(`[audio-extract] start file=${JSON.stringify(fileName)} mime=${inputMime} size=${declaredSize || 'unknown'} range=${startSec}-${endSec || 'end'}`);

  const ffmpeg = spawn(ffmpegPath, args, { stdio: ['pipe', 'pipe', 'pipe'] });
  let stderr = '';
  let sentHeaders = false;
  let outputBytes = 0;
  let settled = false;

  const fail = (message) => {
    if (settled) return;
    settled = true;
    console.error(`[audio-extract] failed file=${JSON.stringify(fileName)}: ${message}`);
    if (!sentHeaders) sendJson(res, 422, { ok: false, error: message });
    else res.destroy(new Error(message));
  };

  req.on('aborted', () => {
    try { ffmpeg.kill('SIGKILL'); } catch {}
  });
  req.on('error', err => fail(`Upload stream failed: ${err.message}`));
  ffmpeg.stdin.on('error', err => {
    if (err.code !== 'EPIPE') fail(`FFmpeg input failed: ${err.message}`);
  });
  ffmpeg.stderr.on('data', chunk => {
    if (stderr.length < 12000) stderr += chunk.toString('utf8');
  });

  ffmpeg.stdout.on('data', chunk => {
    if (settled) return;
    if (!sentHeaders) {
      sentHeaders = true;
      res.writeHead(200, {
        'Content-Type': 'audio/aac',
        'Cache-Control': 'no-store',
        'X-Audio-File-Name': encodeURIComponent(audioName(fileName, startSec, endSec)),
        'X-Source-Start-Sec': String(startSec),
        'X-Source-End-Sec': endSec ? String(endSec) : ''
      });
    }
    outputBytes += chunk.length;
    res.write(chunk);
  });

  ffmpeg.on('error', err => fail(`Could not start FFmpeg: ${err.message}`));
  ffmpeg.on('close', code => {
    if (settled) return;
    if (code !== 0) {
      fail((stderr || `FFmpeg exited with code ${code}`).trim().slice(0, 1200));
      return;
    }
    if (!outputBytes) {
      fail('No audio track was found in this file or selected interval');
      return;
    }
    settled = true;
    res.end();
    console.log(`[audio-extract] complete file=${JSON.stringify(fileName)} outputBytes=${outputBytes} range=${startSec}-${endSec || 'end'}`);
  });

  req.pipe(ffmpeg.stdin);
}

http.createServer = function patchedCreateServer(listener) {
  return previousCreateServer((req, res) => {
    let pathname = '/';
    try { pathname = new URL(req.url, `http://${req.headers.host || 'localhost'}`).pathname; } catch {}

    if (req.method === 'POST' && pathname === '/api/extract-audio') {
      handleExtractAudio(req, res);
      return;
    }
    listener(req, res);
  });
};

console.log('FFmpeg audio extraction preload active — video frames are discarded before AI analysis');
