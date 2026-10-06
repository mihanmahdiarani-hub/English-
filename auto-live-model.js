'use strict';

// English AI Tutor: choose the Gemini Live model from Gemini's own Models API.
// The browser may send any model name (or models/auto); before the setup frame
// reaches Gemini, this preload rewrites it to the best currently listed general
// Live model. No API key is ever sent to the browser.

const wsModule = require('ws');
const BaseWebSocket = wsModule.WebSocket || wsModule;
const nativeFetch = global.fetch.bind(global);

const API_KEYS = [
  process.env.GEMINI_API_KEY || '',
  process.env.GEMINI_API_KEY_2 || ''
].filter(Boolean);

const FALLBACK_MODEL = String(process.env.GEMINI_LIVE_MODEL || 'gemini-3.8-live')
  .replace(/^models\//, '')
  .trim();
const CACHE_MS = Math.max(60_000, Number(process.env.GEMINI_LIVE_MODEL_CACHE_MS || 30 * 60 * 1000));

let cachedSelection = null;
let cachedAt = 0;
let inFlight = null;

function actionsOf(model) {
  const raw = model?.supportedGenerationMethods || model?.supportedActions || model?.supported_actions || [];
  return Array.isArray(raw) ? raw.map(x => String(x).toLowerCase()) : [];
}

function liveCandidate(model) {
  const name = String(model?.name || '').toLowerCase();
  if (!name.includes('gemini') || !name.includes('live')) return false;
  if (/(tts|text-to-speech|translate|translation|embedding|embed|imagen|veo)/i.test(name)) return false;
  return true;
}

function versionWeight(name) {
  const m = String(name).match(/gemini[-_](\d+)(?:\.(\d+))?/i);
  if (!m) return 0;
  return Number(m[1] || 0) * 100 + Number(m[2] || 0) * 10;
}

function scoreModel(model) {
  const name = String(model?.name || '');
  const text = `${name} ${model?.displayName || ''} ${model?.description || ''}`.toLowerCase();
  const actions = actionsOf(model);
  let score = versionWeight(name);

  if (actions.some(a => a.includes('bidigeneratecontent') || a === 'live')) score += 1000;
  if (/default live api model|low-latency voice|real-time voice|realtime voice/.test(text)) score += 250;
  if (/native[- ]audio/.test(text)) score += 80;
  if (/preview|experimental|exp\b/.test(text)) score -= 35;
  if (/extended[- ]thinking|thinking/.test(text)) score -= 20; // tutor favors low latency by default
  return score;
}

async function listModels(apiKey) {
  const models = [];
  let pageToken = '';
  for (let page = 0; page < 4; page++) {
    const url = new URL('https://generativelanguage.googleapis.com/v1beta/models');
    url.searchParams.set('pageSize', '1000');
    if (pageToken) url.searchParams.set('pageToken', pageToken);

    const response = await nativeFetch(url, {
      headers: { 'x-goog-api-key': apiKey, 'accept': 'application/json' }
    });
    if (!response.ok) {
      const text = await response.text().catch(() => '');
      throw new Error(`models.list HTTP ${response.status}: ${text.slice(0, 180)}`);
    }
    const body = await response.json();
    if (Array.isArray(body?.models)) models.push(...body.models);
    pageToken = String(body?.nextPageToken || '');
    if (!pageToken) break;
  }
  return models;
}

async function discoverSelection() {
  if (!API_KEYS.length) {
    return { name: `models/${FALLBACK_MODEL}`, label: FALLBACK_MODEL, source: 'fallback-no-key' };
  }

  let lastError = null;
  for (let keyIndex = 0; keyIndex < API_KEYS.length; keyIndex++) {
    try {
      const models = await listModels(API_KEYS[keyIndex]);
      const candidates = models.filter(liveCandidate).sort((a, b) => scoreModel(b) - scoreModel(a));
      if (!candidates.length) throw new Error('Gemini Models API returned no general Live candidates');

      const picked = candidates[0];
      const name = String(picked.name || '').startsWith('models/') ? String(picked.name) : `models/${picked.name}`;
      const selection = {
        name,
        label: String(picked.displayName || name.replace(/^models\//, '')),
        source: `models.list:key#${keyIndex + 1}`,
        score: scoreModel(picked),
        candidates: candidates.slice(0, 6).map(m => String(m.name || ''))
      };
      console.log(`[auto-live-model] selected ${selection.name} from Gemini Models API (${selection.source})`);
      console.log(`[auto-live-model] top candidates: ${selection.candidates.join(' | ')}`);
      return selection;
    } catch (err) {
      lastError = err;
      console.warn(`[auto-live-model] discovery with key#${keyIndex + 1} failed: ${err.message}`);
    }
  }

  const fallback = { name: `models/${FALLBACK_MODEL}`, label: FALLBACK_MODEL, source: 'fallback-after-discovery-error' };
  console.warn(`[auto-live-model] using fallback ${fallback.name}: ${lastError?.message || 'unknown error'}`);
  return fallback;
}

async function getSelection() {
  if (cachedSelection && Date.now() - cachedAt < CACHE_MS) return cachedSelection;
  if (!inFlight) {
    inFlight = discoverSelection()
      .then(selection => {
        cachedSelection = selection;
        cachedAt = Date.now();
        return selection;
      })
      .finally(() => { inFlight = null; });
  }
  return inFlight;
}

// Warm the selection before the first user starts a class.
getSelection().catch(() => {});

class AutoLiveModelWebSocket extends BaseWebSocket {
  constructor(address, protocols, options) {
    if (options !== undefined) super(address, protocols, options);
    else if (protocols !== undefined) super(address, protocols);
    else super(address);
    this.__englishTutorGeminiLive = /generativelanguage\.googleapis\.com\/ws\/.*BidiGenerateContent/i.test(String(address || ''));
  }

  send(data, ...args) {
    if (!this.__englishTutorGeminiLive || typeof data !== 'string') {
      return BaseWebSocket.prototype.send.call(this, data, ...args);
    }

    let setup;
    try {
      const parsed = JSON.parse(data);
      if (!parsed?.setup) return BaseWebSocket.prototype.send.call(this, data, ...args);
      setup = parsed;
    } catch {
      return BaseWebSocket.prototype.send.call(this, data, ...args);
    }

    const sendSetup = (selection) => {
      if (this.readyState !== BaseWebSocket.OPEN) return;
      setup.setup.model = selection.name;
      const payload = JSON.stringify(setup);
      console.log(`[auto-live-model] rewriting setup model -> ${selection.name}`);
      BaseWebSocket.prototype.send.call(this, payload, ...args);
    };

    const fresh = cachedSelection && Date.now() - cachedAt < CACHE_MS;
    if (fresh) {
      sendSetup(cachedSelection);
      return;
    }

    getSelection()
      .then(sendSetup)
      .catch(err => {
        console.warn(`[auto-live-model] selection failed during setup: ${err.message}`);
        sendSetup({ name: `models/${FALLBACK_MODEL}`, label: FALLBACK_MODEL, source: 'send-fallback' });
      });
  }
}

wsModule.WebSocket = AutoLiveModelWebSocket;
console.log('Gemini Live automatic model selection active (Gemini Models API -> server-side setup rewrite)');
