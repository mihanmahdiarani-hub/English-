const http = require('http');
const fs = require('fs');
const path = require('path');

const previousCreateServer = http.createServer.bind(http);
const UNSIGNED_APK = path.join(__dirname, 'android-dist', 'english-ai-tutor-unsigned.apk');
let signedApkPromise = null;

function sendJson(res, status, body) {
  if (res.headersSent) return;
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store'
  });
  res.end(JSON.stringify(body));
}

async function buildSignedApk() {
  if (!fs.existsSync(UNSIGNED_APK)) throw new Error('Android APK has not been built yet');
  const keyB64 = process.env.ANDROID_SIGNING_KEYSTORE_B64 || '';
  const keyPassword = process.env.ANDROID_SIGNING_STORE_PASSWORD || '';
  if (!keyB64 || !keyPassword) throw new Error('Android signing key is not configured');

  const { ApkSigner, SigningKey, convertToPEM } = await import('apk_sign_ts');
  const keystore = new Uint8Array(Buffer.from(keyB64, 'base64'));
  const { privateKey, certificate } = await convertToPEM(keystore, keyPassword, 'jks');
  const signingKey = SigningKey.fromPEM(privateKey, certificate);
  const signer = new ApkSigner({ signingKey });
  const unsigned = new Uint8Array(await fs.promises.readFile(UNSIGNED_APK));
  const result = await signer.sign(unsigned);
  const signed = Buffer.from(result.signedApk);
  console.log(`[android-apk] signed APK prepared bytes=${signed.length}`);
  return signed;
}

function getSignedApk() {
  if (!signedApkPromise) {
    signedApkPromise = buildSignedApk().catch(err => {
      signedApkPromise = null;
      throw err;
    });
  }
  return signedApkPromise;
}

async function handleLatestApk(req, res) {
  try {
    const apk = await getSignedApk();
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': String(apk.length),
      'Content-Disposition': 'attachment; filename="EnglishAITutor.apk"',
      'Cache-Control': 'no-store'
    });
    res.end(apk);
  } catch (err) {
    console.error(`[android-apk] unavailable: ${err.message}`);
    sendJson(res, 503, { ok: false, error: err.message });
  }
}

http.createServer = function patchedCreateServer(listener) {
  return previousCreateServer((req, res) => {
    let pathname = '/';
    try { pathname = new URL(req.url, `http://${req.headers.host || 'localhost'}`).pathname; } catch {}

    if (req.method === 'GET' && pathname === '/android/latest.apk') {
      handleLatestApk(req, res);
      return;
    }
    listener(req, res);
  });
};

console.log('Android APK host preload active — APK is signed server-side from protected Render environment');
