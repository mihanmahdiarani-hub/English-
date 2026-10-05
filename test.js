const { spawn } = require('child_process');
const assert = require('assert');
const path = require('path');
const fs = require('fs');

(async () => {
  ['public/index.html','public/app.js','public/styles.css','public/manifest.webmanifest','server.js'].forEach(f=>assert(fs.existsSync(path.join(__dirname,f)),`missing ${f}`));
  const port = 31337;
  const child = spawn(process.execPath,['server.js'],{cwd:__dirname,env:{...process.env,PORT:String(port)},stdio:'ignore'});
  try {
    await new Promise(r=>setTimeout(r,500));
    const h = await fetch(`http://127.0.0.1:${port}/health`); assert.equal(h.status,200); const j=await h.json(); assert.equal(j.ok,true);
    const index = await fetch(`http://127.0.0.1:${port}/`); assert.equal(index.status,200); const html=await index.text(); assert(html.includes('English AI Tutor'));
    const token = await fetch(`http://127.0.0.1:${port}/api/live-token`,{method:'POST'}); assert.equal(token.status,400); const tj=await token.json(); assert(/GEMINI_API_KEY/.test(tj.error));
    console.log('PASS: static app, health route, and missing-key guard all work.');
  } finally { child.kill('SIGTERM'); }
})().catch(e=>{console.error(e);process.exit(1)});
