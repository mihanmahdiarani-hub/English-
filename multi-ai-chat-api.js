'use strict';
// English AI Tutor text-only Groq/OpenRouter gateway. Provider keys never leave Liara.
const http = require('http');
const crypto = require('crypto');
const previousCreateServer = http.createServer.bind(http);
const endpoint = '/api/ai/chat';
const providers = Object.freeze({
  groq: {
    url: 'https://api.groq.com/openai/v1/chat/completions',
    model: 'openai/gpt-oss-120b',
    key: 'GROQ_API_KEY'
  },
  openrouter: {
    url: 'https://openrouter.ai/api/v1/chat/completions',
    model: 'openrouter/free', // Always routes to no-cost models, never paid ones.
    key: 'OPENROUTER_API_KEY'
  }
});
const requests = new Map();
const ALLOWED_ORIGINS = new Set([
  'https://appassets.androidplatform.net', 'http://localhost:3000',
  'http://127.0.0.1:3000'
]);

function reply(res, status, object) {
  if (res.headersSent || res.writableEnded) return;
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff'
  });
  res.end(JSON.stringify(object));
}
function authorized(value) {
  const expected = process.env.AI_TUTOR_ACCESS_CODE || process.env.GEMINI_CHAT_ACCESS_CODE || '';
  if (!expected || !value || typeof value !== 'string') return false;
  const actualBytes = Buffer.from(value, 'utf8');
  const expectedBytes = Buffer.from(expected, 'utf8');
  return actualBytes.length === expectedBytes.length &&
    crypto.timingSafeEqual(actualBytes, expectedBytes);
}
function originAllowed(req) {
  const origin = String(req.headers.origin || '');
  if (!origin) return true; // Android's native HTTPS stack sends no Origin header.
  const host = String(req.headers.host || '').toLowerCase();
  return ALLOWED_ORIGINS.has(origin) ||
    (/^https:\/\/[a-z0-9-]+\.liara\.run$/.test(origin) &&
      origin.slice('https://'.length) === host);
}
function dailyLimit(req, provider) {
  const now = Date.now();
  // App is personal; cap OpenRouter below the documented no-credits 50/day.
  const max = provider === 'openrouter' ? 40 : 100;
  const day = Math.floor(now / 86400000);
  const ip = String(req.socket.remoteAddress || 'unknown');
  const key = day + ':' + ip + ':' + provider;
  if (requests.size > 1500) requests.clear();
  const count = (requests.get(key) || 0) + 1;
  requests.set(key, count);
  return count <= max;
}
function readJson(req, maxBytes = 32000) {
  return new Promise((resolve, reject) => {
    let raw = '';
    let size = 0;
    let done = false;
    req.on('data', chunk => {
      if (done) return;
      size += chunk.length;
      if (size > maxBytes) {
        done = true;
        reject(Object.assign(new Error('too_large'), {code:'TOO_LARGE'}));
        req.pause();
        return;
      }
      raw += chunk.toString('utf8');
    });
    req.on('end', () => {
      if (done) return;
      try { resolve(JSON.parse(raw)); }
      catch { reject(Object.assign(new Error('invalid_json'), {code:'INVALID_JSON'})); }
    });
    req.on('error', reject);
  });
}
function cleanHistory(items) {
  if (!Array.isArray(items)) return [];
  const safe = [];
  for (const item of items.slice(-12)) {
    if (typeof item !== 'object' || item === null) continue;
    if (item.role !== 'user' && item.role !== 'assistant') continue;
    if (typeof item.content !== 'string') continue;
    const content = item.content.trim().slice(0, 1600);
    if (content) safe.push({role:item.role,content});
  }
  return safe;
}
function validate(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) return null;
  const provider = input.provider;
  if (!Object.prototype.hasOwnProperty.call(providers, provider)) return null;
  // Refuse client-specified model, URL, or paid routing.
  if ('model' in input || 'url' in input || 'baseUrl' in input ||
      'fallback' in input) return null;
  if (typeof input.question !== 'string' || typeof input.dialogue !== 'string') return null;
  const question = input.question.trim();
  const dialogue = input.dialogue.trim();
  if (!question || question.length > 4000 || dialogue.length > 1600) return null;
  return {provider, question, dialogue, history:cleanHistory(input.history)};
}
const systemPrompt = [
  'You are the English AI Tutor teaching assistant. Explain in clear, natural Persian.',
  'Focus exclusively on the supplied current English movie dialogue and the learner question.',
  'Explain accurate meaning, idioms, collocations, grammar and natural pronunciation as requested.',
  'Never invent unseen movie context, and do not imply you watched or heard the film.',
  'No unrelated greetings, compliments or filler. Answer concise questions concisely.'
].join(' ');
async function handle(req,res) {
  if (!originAllowed(req))
    return reply(res,403,{ok:false,code:'ORIGIN_NOT_ALLOWED',error:'مبدأ اتصال مجاز نیست.'});
  if (!process.env.AI_TUTOR_ACCESS_CODE && !process.env.GEMINI_CHAT_ACCESS_CODE)
    return reply(res,503,{ok:false,code:'NOT_CONFIGURED',error:'کد دسترسی سرویس روی Liara تنظیم نشده است.'});
  if (!authorized(req.headers['x-ai-tutor-code']))
    return reply(res,401,{ok:false,code:'ACCESS_DENIED',error:'کد دسترسی سرویس درست نیست.'});
  if (req.method !== 'POST')
    return reply(res,405,{ok:false,code:'METHOD_NOT_ALLOWED',error:'فقط POST مجاز است.'});
  if (!String(req.headers['content-type'] || '').toLowerCase().startsWith('application/json'))
    return reply(res,415,{ok:false,code:'JSON_REQUIRED',error:'فرمت درخواست باید JSON باشد.'});
  let input;
  try { input = validate(await readJson(req)); }
  catch (e) {
    return reply(res,e.code==='TOO_LARGE'?413:400,
      {ok:false,code:e.code==='TOO_LARGE'?'TOO_LARGE':'INVALID_JSON',error:'درخواست معتبر نیست.'});
  }
  if (!input) return reply(res,400,{ok:false,code:'INVALID_INPUT',error:'سرویس یا متن درخواست معتبر نیست.'});
  const provider = providers[input.provider];
  const apiKey = process.env[provider.key];
  if (!apiKey) return reply(res,503,{ok:false,code:'PROVIDER_NOT_CONFIGURED',
    error:'کلید این سرویس هنوز در تنظیمات امن Liara ثبت نشده است.'});
  if (!dailyLimit(req,input.provider)) return reply(res,429,
    {ok:false,code:'LOCAL_DAILY_LIMIT',error:'سهمیه محافظتی روزانه این سرویس تمام شده است.'});
  const userContent = 'Current English dialogue (only this line):\n' +
    (input.dialogue || '(none)') + '\n\nLearner request:\n' + input.question;
  const body = {
    model:provider.model,
    stream:false,
    temperature:0.35,
    max_tokens:1100,
    messages:[
      {role:'system',content:systemPrompt},
      ...input.history,
      {role:'user',content:userContent}
    ]
  };
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 32000);
  try {
    const response = await fetch(provider.url,{
      method:'POST', redirect:'error',signal:controller.signal,
      headers:{
        'Authorization':'Bearer ' + apiKey,
        'Content-Type':'application/json'
      },
      body:JSON.stringify(body)
    });
    if (!response.ok) {
      // Upstream error bodies may echo secrets or private prompts; never log/forward them.
      const isLimit = response.status===429;
      return reply(res,isLimit?429:502,{
        ok:false,code:isLimit?'PROVIDER_RATE_LIMIT':'PROVIDER_UNAVAILABLE',
        error:isLimit?'سهمیه رایگان سرویس تمام شده است؛ بعداً امتحان کن.':
          'پاسخ سرویس هوش مصنوعی ناموفق بود؛ تنظیمات یا دسترسی آن را بررسی کن.'
      });
    }
    const raw = await response.text();
    if (raw.length > 300000) throw new Error('oversized_reply');
    const data = JSON.parse(raw);
    const content = data && data.choices && data.choices[0] &&
      data.choices[0].message && data.choices[0].message.content;
    const text = typeof content === 'string' ? content.trim().slice(0, 18000) : '';
    if (!text) throw new Error('empty_reply');
    return reply(res,200,{ok:true,provider:input.provider,model:provider.model,text});
  } catch (err) {
    return reply(res,err && err.name==='AbortError'?504:502,{
      ok:false,code:err && err.name==='AbortError'?'TIMEOUT':'PROVIDER_UNAVAILABLE',
      error:'ارتباط با سرویس هوش مصنوعی برقرار نشد. بعداً امتحان کن.'
    });
  } finally { clearTimeout(timer); }
}
http.createServer = function englishTutorMultiAiCreateServer(listener) {
  return previousCreateServer((req,res) => {
    let path = '';
    try { path = new URL(req.url,'http://localhost').pathname; } catch {}
    if (path !== endpoint) return listener(req,res);
    handle(req,res).catch(() => reply(res,502,{
      ok:false,code:'GATEWAY_ERROR',error:'خطای موقت سرویس هوش مصنوعی.'
    }));
  });
};
module.exports = {validate, cleanHistory, providers, endpoint};
