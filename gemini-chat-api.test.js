'use strict';
const assert = require('node:assert/strict');
const http = require('node:http');
const normalFetch = global.fetch;
process.env.GEMINI_API_KEY = 'mock-key-do-not-use';
process.env.GEMINI_CHAT_ACCESS_CODE = 'mock-access';
let upstreamMode = 'success';
global.fetch = async (url, options = {}) => {
  const target = String(url);
  if (!target.startsWith('https://generativelanguage.googleapis.com/')) return normalFetch(url, options);
  assert.equal(options.headers['x-goog-api-key'], 'mock-key-do-not-use');
  if (upstreamMode === '403') return {ok:false,status:403,text:async()=>'<html>Error 403 (Forbidden)</html>'};
  if (target.includes('/models?')) return {ok:true,status:200,text:async()=>JSON.stringify({
    models:[{name:'models/gemini-3.8-flash',supportedGenerationMethods:['generateContent']}]
  })};
  return {ok:true,status:200,text:async()=>JSON.stringify({
    candidates:[{content:{parts:[{text:'Hi from mocked Gemini'}]}}]
  })};
};
require('./gemini-chat-api');
const server = http.createServer((req,res)=>{res.writeHead(200);res.end('other routes unaffected');});
server.listen(0, '127.0.0.1', async ()=>{
  const url='http://127.0.0.1:'+server.address().port;
  const headers={'Content-Type':'application/json','Origin':'https://appassets.androidplatform.net','X-Gemini-Chat-Code':'mock-access'};
  const payload=JSON.stringify({model:'gemini-3.8-flash',messages:[{role:'user',text:'Hello'}]});
  try {
    let r=await normalFetch(url+'/api/gemini-chat/models',{headers});
    assert.equal(r.status,200);
    assert.deepEqual((await r.json()).models,['gemini-3.8-flash']);
    r=await normalFetch(url+'/api/gemini-chat',{method:'POST',headers,body:payload});
    assert.equal(r.status,200);
    assert.equal((await r.json()).text,'Hi from mocked Gemini');
    r=await normalFetch(url+'/api/gemini-chat',{method:'POST',headers:{...headers,'X-Gemini-Chat-Code':'incorrect'},body:payload});
    assert.equal(r.status,401);
    r=await normalFetch(url+'/api/gemini-chat',{method:'POST',headers:{...headers,Origin:'https://untrusted.example'},body:payload});
    assert.equal(r.status,403);
    r=await normalFetch(url+'/api/gemini-chat',{method:'POST',headers,body:JSON.stringify({model:'../secret',messages:[{role:'user',text:'hello'}]})});
    assert.equal(r.status,400);
    upstreamMode='403';
    r=await normalFetch(url+'/api/gemini-chat',{method:'POST',headers,body:payload});
    assert.equal(r.status,502);
    const denied=await r.json();
    assert.equal(denied.code,'GEMINI_ACCESS_DENIED');
    assert(!JSON.stringify(denied).includes('<html>'));
    r=await normalFetch(url+'/health');
    assert.equal(await r.text(),'other routes unaffected');
    console.log('PASS: Gemini chat models, chat response, access guard, origin guard, model check, HTML 403 handling, route passthrough');
  }catch(err){console.error(err);process.exitCode=1;}
  finally{server.close();}
});
