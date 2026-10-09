'use strict';
// Separate Gemini text-chat API. API keys stay on the server, never in APK/web assets.
const http = require('http');
const createServer = http.createServer.bind(http);
const API = 'https://generativelanguage.googleapis.com/v1beta/';
const DEFAULT_MODEL = process.env.GEMINI_CHAT_MODEL || process.env.GEMINI_ANALYSIS_MODEL || 'gemini-3.8-flash';
const origins = new Set(['https://appassets.androidplatform.net', 'https://english-ai-tutor-2vki.onrender.com', 'http://localhost:3000', 'http://127.0.0.1:3000']);
const requestCounts = new Map();
let modelCache = { at: 0, models: [] };
function json(res, status, data) {
  res.writeHead(status, {'Content-Type': 'application/json; charset=utf-8', 'Cache-Control':'no-store'});
  res.end(JSON.stringify(data));
}
function apiKey() { return process.env.GEMINI_API_KEY || process.env.GEMINI_API_KEY_2 || ''; }
function error(res, err) {
  const status = Number(err.status) || 502;
  if (status === 401 || status === 403) return json(res, 502, {ok:false,code:'GEMINI_ACCESS_DENIED',error:'اتصال به Gemini با خطای دسترسی 403 رد شد. کلید، مجوز یا محدودیت منطقه‌ای را بررسی کنید.'});
  if (status === 429) return json(res,429,{ok:false,code:'RATE_LIMIT',error:'سقف درخواست‌های Gemini پر شده؛ بعداً دوباره تلاش کنید.'});
  if (status === 404) return json(res,502,{ok:false,code:'MODEL_NOT_FOUND',error:'این مدل برای کلید شما موجود نیست؛ مدل دیگری انتخاب کنید.'});
  if (err.name === 'AbortError') return json(res,504,{ok:false,code:'TIMEOUT',error:'زمان انتظار پاسخ Gemini تمام شد.'});
  return json(res,502,{ok:false,code:'UPSTREAM_ERROR',error:'پاسخ معتبری از Gemini دریافت نشد. اتصال و تنظیمات سرور را بررسی کنید.'});
}
function limited(req) {
  const ip = req.socket.remoteAddress || 'unknown', now = Date.now();
  if(requestCounts.size > 5000) for (const [k,v] of requestCounts) if(now-v.at>60000) requestCounts.delete(k);
  let r=requestCounts.get(ip);
  if(!r || now-r.at>60000) r={at:now,count:0};
  r.count++; requestCounts.set(ip,r);
  return r.count <= 15;
}
function readBody(req) {
  return new Promise((resolve,reject)=>{
    let raw='',size=0,failed=false;
    req.on('data',chunk=>{size+=chunk.length;if(size>90000){failed=true;reject(new Error('too_large'));req.pause();return;}if(!failed)raw+=chunk.toString('utf8');});
    req.on('end',()=>{if(failed)return;try{resolve(JSON.parse(raw||'{}'));}catch{reject(new Error('invalid_json'));}});
    req.on('error',reject);
  });
}
async function google(path,body,signal) {
  const response=await fetch(API+path,{
    method:body?'POST':'GET',
    headers:Object.assign({'x-goog-api-key':apiKey()},body?{'Content-Type':'application/json'}:{}),
    body:body?JSON.stringify(body):undefined,signal
  });
  const raw=await response.text();
  let data=null;
  try{data=JSON.parse(raw);}catch{}
  if(!response.ok){const e=new Error('upstream_http_error');e.status=response.status;throw e;}
  if(!data)throw new Error('invalid_upstream_json');
  return data;
}
async function route(req,res,path) {
  const origin=String(req.headers.origin||'');
  if(origin && !origins.has(origin))return json(res,403,{ok:false,error:'Origin not allowed'});
  const secret=process.env.GEMINI_CHAT_ACCESS_CODE;
  if(secret && req.headers['x-gemini-chat-code']!==secret)return json(res,401,{ok:false,code:'ACCESS_CODE_REQUIRED',error:'کد دسترسی چت لازم است یا درست نیست.'});
  if(!apiKey())return json(res,503,{ok:false,code:'API_KEY_MISSING',error:'کلید GEMINI_API_KEY در سرور تنظیم نشده است.'});
  if(!limited(req))return json(res,429,{ok:false,code:'LOCAL_RATE_LIMIT',error:'تعداد درخواست زیاد است؛ یک دقیقه دیگر تلاش کنید.'});
  const controller=new AbortController();
  const timer=setTimeout(()=>controller.abort(),35000);
  try {
    if(path==='/api/gemini-chat/models') {
      if(req.method!=='GET')return json(res,405,{ok:false,error:'Method not allowed'});
      if(Date.now()-modelCache.at>300000||!modelCache.models.length){
        const data=await google('models?pageSize=100',null,controller.signal);
        const models=(data.models||[]).filter(x=>(x.supportedGenerationMethods||x.supportedActions||[]).includes('generateContent') && /^models\/gemini-[a-z0-9.-]+$/.test(String(x.name||'')))
          .map(x=>x.name.slice(7)).slice(0,100);
        modelCache={at:Date.now(),models};
      }
      const models=modelCache.models;
      return json(res,200,{ok:true,models,defaultModel:models.includes(DEFAULT_MODEL)?DEFAULT_MODEL:(models[0]||DEFAULT_MODEL)});
    }
    if(req.method!=='POST')return json(res,405,{ok:false,error:'Method not allowed'});
    const input=await readBody(req);
    const model=String(input.model||DEFAULT_MODEL);
    if(!/^gemini-[a-z0-9.-]{1,75}$/.test(model))return json(res,400,{ok:false,code:'INVALID_MODEL',error:'نام مدل صحیح نیست.'});
    if(!Array.isArray(input.messages)||!input.messages.length||input.messages.length>24)return json(res,400,{ok:false,error:'هر درخواست باید حداکثر ۲۴ پیام داشته باشد.'});
    let count=0;
    const contents=input.messages.map(m=>{
      const role=m && m.role, text=typeof m?.text==='string'?m.text.trim():'';
      if(!['user','model'].includes(role)||!text||text.length>4000)throw new Error('invalid_messages');
      count+=text.length;
      return {role,parts:[{text}]};
    });
    if(count>18000||contents[contents.length-1].role!=='user')return json(res,400,{ok:false,error:'متن یا ترتیب پیام‌ها معتبر نیست.'});
    const data=await google('models/'+encodeURIComponent(model)+':generateContent',{contents,generationConfig:{maxOutputTokens:2048}},controller.signal);
    const answer=(data.candidates?.[0]?.content?.parts||[]).map(p=>p.text||'').join('\n').trim();
    if(!answer)return json(res,502,{ok:false,code:'EMPTY_RESPONSE',error:'Gemini پاسخی متنی برنگرداند.'});
    return json(res,200,{ok:true,model,text:answer});
  }catch(err) {
    if(err.message==='invalid_json'||err.message==='invalid_messages')return json(res,400,{ok:false,error:'ساختار درخواست معتبر نیست.'});
    if(err.message==='too_large')return json(res,413,{ok:false,error:'پیام بیش از حد طولانی است.'});
    console.warn('[gemini-chat] request failed status='+String(err.status||'network')+' reason='+err.message);
    return error(res,err);
  }finally{clearTimeout(timer);}
}
http.createServer=function(listener){
  return createServer((req,res)=>{
    let path='';
    try{path=new URL(req.url,'http://localhost').pathname;}catch{}
    if(path==='/api/gemini-chat'||path==='/api/gemini-chat/models'){
      route(req,res,path).catch(err=>{if(!res.headersSent)error(res,err);});
      return;
    }
    listener(req,res);
  });
};
