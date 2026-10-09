(() => {
'use strict';
const $ = id => document.getElementById(id);
const KEY = 'english-ai-tutor-gemini-chat-v1';
const CODE_KEY = 'english-ai-tutor-chat-code';
const BACKEND_KEY = 'english-ai-tutor-liara-server-v1';
const IS_ANDROID = location.hostname === 'appassets.androidplatform.net';
const backend = document.getElementById('gemBackend');
function validatedLiaraUrl(raw) {
 try {
  const url = new URL(String(raw||'').trim());
  if (url.protocol !== 'https:' || !url.hostname.endsWith('.liara.run') || url.hostname === 'liara.run' || url.username || url.password || url.port || (url.pathname !== '/' && url.pathname !== '') || url.search || url.hash) return '';
  return url.origin;
 } catch { return ''; }
}
let origin = '';
if (IS_ANDROID) {
 origin = validatedLiaraUrl(localStorage.getItem(BACKEND_KEY) || window.EnglishTutorConfig?.apiOrigin || '');
 backend.value = origin;
} else {
 backend.value = location.origin.endsWith('.liara.run') ? location.origin : '';
 backend.disabled = true;
}
const api = path => {
 if (IS_ANDROID && !origin) throw new Error('ابتدا نشانی HTTPS برنامه لیارا را در بخش «دسترسی» وارد و ذخیره کنید.');
 return (IS_ANDROID ? origin : '') + path;
};
const s = {threads:[],active:null,model:'gemini-3.8-flash',busy:false};
const input=$('gemInput'),list=$('gemThreads'),view=$('gemMessages'),model=$('gemModel'),send=$('gemSend'),code=$('gemCode'),history=$('gemHistory');
try {
 const x=JSON.parse(localStorage.getItem(KEY)||'{}');
 if(Array.isArray(x.threads))s.threads=x.threads.filter(t=>t&&typeof t.id==='string'&&Array.isArray(t.messages)).slice(0,16).map(t=>({
  id:t.id,title:String(t.title||'گفتگوی جدید').slice(0,60),
  model:String(t.model||s.model),
  messages:t.messages.slice(-50).filter(m=>m&&['user','model'].includes(m.role)&&typeof m.text==='string')
 }));
 s.active=s.threads.find(t=>t.id===x.active)?.id||s.threads[0]?.id||null;
 if(typeof x.model==='string')s.model=x.model;
 code.value=sessionStorage.getItem(CODE_KEY)||'';
}catch{}
function save(){try{localStorage.setItem(KEY,JSON.stringify({threads:s.threads.slice(0,16),active:s.active,model:s.model}));}catch{}}
function selected(){return s.threads.find(t=>t.id===s.active);}
function status(message,type){$('gemStatus').textContent=message;$('gemStatus').className='gem-status '+(type||'');}
function modelValue(){
 if(![...model.options].some(o=>o.value===s.model))model.add(new Option(s.model,s.model));
 model.value=s.model;
}
function closeHistory(){history.classList.remove('open');}
function newThread(){
 if(s.busy)return;
 const t={id:Date.now().toString(36)+Math.random().toString(36).slice(2,8),title:'گفتگوی جدید',model:s.model,messages:[]};
 s.threads.unshift(t);s.threads=s.threads.slice(0,16);s.active=t.id;save();render();closeHistory();input.focus();
}
function switchThread(id){
 if(s.busy)return;
 const t=s.threads.find(x=>x.id===id);if(!t)return;
 s.active=id;s.model=t.model||s.model;modelValue();save();render();closeHistory();
}
function bubble(role,text,pending){
 const article=document.createElement('article');article.className='gem-msg '+role+(pending?' pending':'');
 const info=document.createElement('div');info.className='gem-meta';
 const who=document.createElement('span');who.textContent=role==='user'?'شما':'✦ Gemini';info.append(who);
 if(!pending){
  const copy=document.createElement('button');copy.type='button';copy.className='gem-copy';copy.textContent='کپی';
  copy.addEventListener('click',async()=>{try{await navigator.clipboard.writeText(text);copy.textContent='کپی شد';}catch{copy.textContent='کپی نشد';}});
  info.append(copy);
 }
 const body=document.createElement('div');body.className='gem-bubble';body.textContent=text;
 article.append(info,body);return article;
}
function render(){
 list.replaceChildren();
 s.threads.forEach(t=>{
  const b=document.createElement('button');b.type='button';b.className='gem-thread'+(t.id===s.active?' active':'');
  b.textContent=t.title;b.title=t.title;b.onclick=()=>switchThread(t.id);list.append(b);
 });
 view.replaceChildren();
 const t=selected();
 if(!t?.messages.length){
  const welcome=document.createElement('div');welcome.className='gem-welcome';
  const icon=document.createElement('div');icon.className='gem-welcome-icon';icon.textContent='✦';
  const heading=document.createElement('h2');heading.textContent='هر چیزی خواستی بپرس';
  const note=document.createElement('p');note.textContent='اینجا می‌توانی آزادانه با Gemini پیام بدهی. این گفتگو به درس‌ها و صف ویدئو ارتباطی ندارد.';
  const suggestions=document.createElement('div');suggestions.className='gem-suggestions';
  ['با من انگلیسی تمرین کن','این جمله را اصلاح کن','یک موضوع را به فارسی توضیح بده'].forEach(text=>{
   const b=document.createElement('button');b.type='button';b.textContent=text;b.onclick=()=>{input.value=text;input.focus();};suggestions.append(b);
  });
  welcome.append(icon,heading,note,suggestions);view.append(welcome);
 }else t.messages.forEach(m=>view.append(bubble(m.role,m.text)));
 view.scrollTop=view.scrollHeight;
}
function headers(){
 const h={'Content-Type':'application/json'};
 if(code.value.trim())h['X-Gemini-Chat-Code']=code.value.trim();
 return h;
}
async function responseJson(response){
 let data;
 try{data=await response.json();}catch{throw new Error('پاسخ سرور JSON نبود. احتمالاً اتصال بک‌اند مشکل دارد.');}
 if(!response.ok||!data.ok)throw new Error(data.error||'خطای سرور '+response.status);
 return data;
}
async function loadModels(){
 status('در حال دریافت مدل‌های Gemini…');
 try{
  const r=await fetch(api('/api/gemini-chat/models'),{headers:headers(),cache:'no-store'});
  const result=await responseJson(r);
  const names=(result.models||[]).filter(n=>/^gemini-[a-z0-9.-]+$/.test(n));
  if(!names.length)throw new Error('برای این کلید مدل متنی در دسترس نیست.');
  model.replaceChildren(...names.map(name=>new Option(name,name)));
  if(!names.includes(s.model))s.model=result.defaultModel||names[0];
  modelValue();save();status('مدل‌ها بارگذاری شد؛ آماده گفتگو.','ok');
 }catch(err){modelValue();status(err.message+' می‌توانید ارسال با مدل انتخابی را امتحان کنید.','error');}
}
async function submit(event){
 event.preventDefault();
 const text=input.value.trim();
 if(!text||s.busy)return;
 if(!selected())newThread();
 const t=selected();
 const context=[...t.messages.slice(-23),{role:'user',text}];
 while(context[0]?.role==='model')context.shift();
 while(context.length>1&&context.reduce((n,m)=>n+m.text.length,0)>16000)context.shift();
 s.busy=true;input.disabled=true;send.disabled=true;$('gemNew').disabled=true;
 const draft=input.value;input.value='';
 if(!t.messages.length)view.replaceChildren();
 view.append(bubble('user',text),bubble('model','در حال نوشتن پاسخ…',true));view.scrollTop=view.scrollHeight;
 status('در حال دریافت پاسخ Gemini…');
 try{
  const r=await fetch(api('/api/gemini-chat'),{method:'POST',headers:headers(),body:JSON.stringify({model:s.model,messages:context})});
  const result=await responseJson(r);
  t.messages.push({role:'user',text},{role:'model',text:result.text});
  t.messages=t.messages.slice(-50);t.model=s.model;
  if(t.title==='گفتگوی جدید')t.title=text.slice(0,45);
  s.threads=[t,...s.threads.filter(x=>x.id!==t.id)];
  save();render();status('پاسخ دریافت شد.','ok');
 }catch(err){input.value=draft;render();status(err.message+' پیام شما ارسال نشده؛ دوباره امتحان کنید.','error');}
 finally{s.busy=false;input.disabled=false;send.disabled=false;$('gemNew').disabled=false;input.focus();}
}
$('gemForm').addEventListener('submit',submit);
input.addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();$('gemForm').requestSubmit();}});
$('gemNew').onclick=newThread;
$('gemOpenHistory').onclick=()=>history.classList.add('open');
$('gemCloseHistory').onclick=closeHistory;
$('gemReloadModels').onclick=loadModels;
$('gemSaveBackend').onclick=()=>{
 if(!IS_ANDROID){status('برای نسخه وب، آدرس سرور همان دامنه جاری است.','ok');return;}
 const updated=validatedLiaraUrl(backend.value);
 if(!updated){status('نشانی باید به شکل https://APP.liara.run و بدون مسیر اضافی باشد.','error');return;}
 origin=updated;
 try{localStorage.setItem(BACKEND_KEY,updated);}catch{}
 $('gemAccessDetails').open=false;
 status('نشانی لیارا ثبت شد: '+updated,'ok');
 loadModels();
};
$('gemSaveCode').onclick=()=>{
 try{sessionStorage.setItem(CODE_KEY,code.value.trim());}catch{}
 $('gemAccessDetails').open=false;loadModels();
};
$('gemDelete').onclick=()=>{
 if(s.busy||!selected()||!confirm('این گفتگو حذف شود؟'))return;
 s.threads=s.threads.filter(t=>t.id!==s.active);s.active=s.threads[0]?.id||null;save();render();status('گفتگو حذف شد.');
};
model.onchange=()=>{s.model=model.value;if(selected())selected().model=s.model;save();status('مدل '+s.model+' انتخاب شد.','ok');};
modelValue();render();loadModels();
})();
