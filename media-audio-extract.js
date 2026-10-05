const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { spawn } = require('child_process');
const { pipeline } = require('stream/promises');
const ffmpegPath = require('ffmpeg-static');

const previousCreateServer = http.createServer.bind(http);
const MAX_UPLOAD_BYTES = 6 * 1024 * 1024 * 1024;

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

function safeExt(fileName) {
  const ext = path.extname(fileName || '').toLowerCase();
  return /^\.[a-z0-9]{1,8}$/.test(ext) ? ext : '.media';
}

function audioName(fileName, startSec, endSec) {
  const base = safeName(fileName).replace(/\.[^.]+$/, '') || 'media';
  const range = endSec > startSec
    ? `-${Math.round(startSec)}-${Math.round(endSec)}`
    : `-${Math.round(startSec)}-end`;
  return `${base}${range}.aac`;
}

async function unlinkQuiet(filePath) {
  if (!filePath) return;
  try { await fs.promises.unlink(filePath); } catch {}
}

function runFfmpeg(args) {
  return new Promise((resolve, reject) => {
    const child = spawn(ffmpegPath, args, { stdio: ['ignore', 'ignore', 'pipe'] });
    let stderr = '';
    child.stderr.on('data', chunk => {
      if (stderr.length < 12000) stderr += chunk.toString('utf8');
    });
    child.on('error', reject);
    child.on('close', code => {
      if (code === 0) resolve();
      else reject(new Error((stderr || `FFmpeg exited with code ${code}`).trim().slice(0, 1200)));
    });
  });
}

async function handleExtractAudio(req, res) {
  if (!ffmpegPath) return sendJson(res, 500, { ok: false, error: 'FFmpeg binary is not available on the server' });

  const fileName = safeName(req.headers['x-file-name']);
  const inputMime = String(req.headers['content-type'] || 'application/octet-stream').split(';')[0];
  const startSec = Math.max(0, numberHeader(req, 'x-start-sec', 0));
  const endRaw = numberHeader(req, 'x-end-sec', 0);
  const endSec = endRaw > 0 ? endRaw : 0;
  const declaredSize = Math.max(0, numberHeader(req, 'x-file-size', 0));

  if (!/^(video|audio)\//i.test(inputMime)) return sendJson(res, 400, { ok: false, error: 'Only audio/video files are supported' });
  if (endSec && endSec <= startSec) return sendJson(res, 400, { ok: false, error: 'End time must be after start time' });
  if (declaredSize > MAX_UPLOAD_BYTES) return sendJson(res, 413, { ok: false, error: 'Media file is too large for the current free server pipeline' });

  const token = crypto.randomBytes(12).toString('hex');
  const inputPath = path.join(os.tmpdir(), `english-tutor-${token}${safeExt(fileName)}`);
  const outputPath = path.join(os.tmpdir(), `english-tutor-${token}.aac`);

  console.log(`[audio-extract] upload start file=${JSON.stringify(fileName)} mime=${inputMime} size=${declaredSize || 'unknown'} range=${startSec}-${endSec || 'end'}`);

  try {
    let received = 0;
    req.on('data', chunk => {
      received += chunk.length;
      if (received > MAX_UPLOAD_BYTES) req.destroy(new Error('Media file exceeded server upload limit'));
    });
    await pipeline(req, fs.createWriteStream(inputPath));
    console.log(`[audio-extract] upload complete file=${JSON.stringify(fileName)} bytes=${received}`);

    const args = ['-hide_banner', '-loglevel', 'error', '-y'];
    if (startSec > 0) args.push('-ss', String(startSec));
    args.push('-i', inputPath);
    if (endSec > startSec) args.push('-t', String(endSec - startSec));
    args.push(
      '-map', '0:a:0',
      '-vn',
      '-ac', '1',
      '-ar', '16000',
      '-c:a', 'aac',
      '-b:a', '64k',
      '-f', 'adts',
      outputPath
    );

    await runFfmpeg(args);
    const stat = await fs.promises.stat(outputPath);
    if (!stat.size) throw new Error('No audio track was found in this file or selected interval');
    await unlinkQuiet(inputPath);

    res.writeHead(200, {
      'Content-Type': 'audio/aac',
      'Content-Length': String(stat.size),
      'Cache-Control': 'no-store',
      'X-Audio-File-Name': encodeURIComponent(audioName(fileName, startSec, endSec)),
      'X-Source-Start-Sec': String(startSec),
      'X-Source-End-Sec': endSec ? String(endSec) : ''
    });

    const stream = fs.createReadStream(outputPath);
    stream.on('error', err => res.destroy(err));
    stream.pipe(res);
    const cleanup = () => unlinkQuiet(outputPath);
    res.once('finish', cleanup);
    res.once('close', cleanup);
    console.log(`[audio-extract] complete file=${JSON.stringify(fileName)} outputBytes=${stat.size} range=${startSec}-${endSec || 'end'}`);
  } catch (err) {
    await unlinkQuiet(inputPath);
    await unlinkQuiet(outputPath);
    console.error(`[audio-extract] failed file=${JSON.stringify(fileName)}: ${err.message}`);
    sendJson(res, 422, { ok: false, error: err.message });
  }
}

http.createServer = function patchedCreateServer(listener) {
  return previousCreateServer((req, res) => {
    let pathname = '/';
    try { pathname = new URL(req.url, `http://${req.headers.host || 'localhost'}`).pathname; } catch {}

    if (req.method === 'POST' && pathname === '/api/extract-audio') {
      handleExtractAudio(req, res).catch(err => sendJson(res, 500, { ok: false, error: err.message }));
      return;
    }
    listener(req, res);
  });
};

console.log('FFmpeg audio extraction preload active — full media is decoded server-side, only extracted audio is sent to AI');
