#pragma once
#include <Arduino.h>

// ---------------------------------------------------------------- shared CSS
static const char STYLE_CSS[] PROGMEM = R"rawliteral(
:root{--bg:#1a1410;--card:#2a211a;--fg:#f5ede4;--mut:#b8a898;--acc:#ff8a00;--ok:#3ecf6e;--bad:#e5484d;--line:#4a3c30}
*{box-sizing:border-box}
body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--fg)}
header{padding:14px 16px;background:linear-gradient(90deg,#ff8a00,#e0561b);color:#fff}
header h1{margin:0;font-size:20px}header small{opacity:.9}
main{max-width:760px;margin:auto;padding:16px;display:grid;gap:14px}
.card{background:var(--card);border-radius:12px;padding:14px}
.card h3{margin:0 0 8px}
.who{font-size:26px;font-weight:700;margin-top:2px}
.big{font-size:56px;font-weight:700;color:var(--acc);line-height:1.1;margin:4px 0 12px}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}
.lbl{font-size:12px;color:var(--mut);text-transform:uppercase;letter-spacing:.05em}
.hint{font-size:14px;color:var(--mut);margin:0 0 10px}
.val{font-size:20px;font-weight:600;overflow-wrap:anywhere}
.bar{position:relative;height:14px;background:#000;border-radius:7px;overflow:hidden;margin-top:6px}
.bar i{position:absolute;left:0;top:0;bottom:0;background:#2aa7c9;transition:width .15s}
.bar b{position:absolute;top:0;bottom:0;width:2px;background:var(--bad)}
.row{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-top:12px}
button{background:#3a2e24;color:var(--fg);border:1px solid var(--line);border-radius:8px;padding:9px 14px;font-size:15px;cursor:pointer}
button.pri{background:var(--acc);border-color:var(--acc);color:#1a1410;font-weight:600}
button.dan{border-color:var(--bad);color:var(--bad);background:transparent}
button.sm{padding:6px 10px;font-size:14px}
button.net{display:block;width:100%;text-align:left;margin-bottom:6px}
label{display:block;margin:12px 0 4px}
input[type=range]{width:100%}
input[type=text],input[type=password],input[type=number]{width:100%;padding:9px;font-size:16px;background:var(--bg);color:var(--fg);border:1px solid var(--line);border-radius:6px}
table{width:100%;border-collapse:collapse}th,td{padding:6px 4px;border-bottom:1px solid #3a2e24;text-align:right}th:first-child,td:first-child{text-align:left}
th{font-size:12px;color:var(--mut);font-weight:600}
.scroll{overflow-x:auto}
a{color:var(--acc)}
.banner{background:#5a3a00;color:#ffd79a;padding:10px 12px;border-radius:8px}
.person{border:1px solid var(--line);border-radius:10px;padding:10px 12px;margin-bottom:10px}
.person.cur{border-color:var(--acc);box-shadow:0 0 0 1px var(--acc)}
.pname{font-size:18px;font-weight:700}
.pv{font-size:13px;color:var(--mut);margin-left:8px}
.pv.ok{color:var(--ok)}
.stats{margin-top:4px;color:var(--mut)}.stats b{color:var(--fg)}
.add{display:flex;gap:8px}.add input{flex:1}
.hide{display:none}
)rawliteral";

// ----------------------------------------------------------------- dashboard
static const char DASHBOARD_HTML[] PROGMEM = R"rawliteral(<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Naam Jap Counter</title><link rel="stylesheet" href="/style.css"></head><body>
<header><h1>Naam Jap Counter</h1><small id="clock">--</small></header>
<main>
<div id="apBanner" class="banner hide">Device is not connected to WiFi. <a href="/setup" style="color:#fff">Set up WiFi &rarr;</a></div>
<div id="micBanner" class="banner hide" style="background:#5a1010;color:#ffc9c9"></div>

<section class="card">
  <div class="lbl">Now chanting <span id="pz" style="color:var(--bad)"></span></div>
  <div class="who" id="who">&mdash;</div>
  <div class="lbl" id="conf">&nbsp;</div>
  <div class="big" id="today">0</div>
  <div class="grid">
    <div><div class="lbl">All today</div><div class="val" id="tAll">0</div></div>
    <div><div class="lbl">Grand total</div><div class="val" id="gAll">0</div></div>
    <div><div class="lbl">Previous day</div><div class="val" id="pAll">0</div></div>
  </div>
  <div id="trainBox" class="banner hide" style="margin-top:12px">
    Training <b id="trName"></b>: <b id="trDone">0</b> / <b id="trTarget">0</b> chants.
    Only this person should chant now, one naam at a time with a short pause after each.
    <div class="row"><button class="sm" onclick="person(0,'stop')">Stop training</button></div>
  </div>
  <div id="fastBox" class="banner hide" style="margin-top:12px">
    Learning fast chanting from <b id="fsName"></b>: <b id="fsDone">0</b> / <b id="fsTarget">0</b> words.
    Chant continuously at your normal fast speed, no pauses needed.
    <div class="row"><button class="sm" onclick="person(0,'stop')">Stop &amp; keep what was learnt</button></div>
  </div>
  <div class="lbl" style="margin-top:14px">Microphone level (red line = trigger)</div>
  <div class="bar"><i id="lv"></i><b id="th"></b></div>
  <div class="row"><button class="pri" id="pauseBtn" onclick="post('/api/pause')">Pause</button></div>
</section>

<section class="card">
  <h3>Word counting</h3>
  <p class="hint" id="wHint"></p>
  <label>Word or mantra</label>
  <input type="text" id="wname" maxlength="16">
  <label>Word matching strictness: <b id="wstrictV"></b> <small>(higher = fewer false counts, but may miss some)</small></label>
  <input type="range" id="wstrict" min="1" max="10">
  <div class="lbl" style="margin-top:12px">Closest match in the last second &mdash; counts when the bar ends left of the red line</div>
  <div class="bar"><i id="wlv"></i><b id="wth" style="left:50%"></b></div>
  <div class="row"><button class="pri" onclick="saveWord()">Save word settings</button><span id="wsaved"></span></div>
</section>

<section class="card">
  <h3>People</h3>
  <p class="hint">Add up to <span id="maxP">2</span> people. Then press <b>Erase voice &amp; retrain</b>: that person says the word alone, with a short pause after each one. To start a person again from scratch use <b>Erase voice &amp; retrain</b>.</p>
  <div id="people"></div>
  <div class="add" id="addBox"><input type="text" id="newName" maxlength="16" placeholder="Name (English letters)"><button class="pri" onclick="addPerson()">Add person</button></div>
</section>

<section class="card">
  <h3>Detection settings</h3>
  <label>Sensitivity: <b id="sensV"></b> <small>(higher = picks up softer chanting)</small></label>
  <input type="range" id="sens" min="1" max="10">
  <label>Minimum chant length (ms)</label>
  <input type="number" id="minv" min="40" max="1000" step="10">
  <label>Silence between two japs (ms)</label>
  <input type="number" id="gap" min="100" max="3000" step="10">
  <h3 style="margin-top:18px">Voice recognition</h3>
  <label>Strictness: <b id="strictV"></b> <small>(higher = fewer wrong names, more Guest)</small></label>
  <input type="range" id="strict" min="1" max="10">
  <label>Chants recorded per training</label>
  <input type="number" id="tchants" min="5" max="40" step="1">
  <label><input type="checkbox" id="smooth"> When unsure, give the jap to whoever chanted the previous japs</label>
  <h3 style="margin-top:18px">Screen</h3>
  <label>Brightness: <b id="brightV"></b>%</label>
  <input type="range" id="bright" min="5" max="100">
  <div class="row"><button class="pri" onclick="saveSet()">Save settings</button><span id="saved"></span></div>
</section>

<section class="card">
  <h3>Totals</h3>
  <p class="hint">Each year and month, including today so far.</p>
  <div class="lbl">By year</div>
  <div class="scroll"><table id="years"></table></div>
  <div class="lbl" style="margin-top:12px">By month (last 24)</div>
  <div class="scroll"><table id="months"></table></div>
  <h3 style="margin-top:18px">Daily history</h3>
  <div class="scroll"><table id="hist"></table></div>
  <p><a href="/history.csv">Download full history (CSV)</a></p>
</section>

<section class="card">
  <h3>Device</h3>
  <div id="dev" style="color:var(--mut)"></div>
  <div class="row">
    <a href="/setup"><button>Change WiFi</button></a>
    <button class="dan" onclick="if(confirm('Forget WiFi and restart in setup mode?'))post('/api/forget')">Forget WiFi</button>
    <button class="dan" onclick="if(confirm('Erase ALL counts and history of everyone? Names and trained voices are kept. This cannot be undone.'))post('/api/reset',{what:'all'})">Reset all counts</button>
  </div>
</section>

<section class="card">
  <h3>Firmware update</h3>
  <p class="hint">Installed: <b id="fwVer">-</b> for <b id="fwBoard">-</b>. Counts, names, voices and WiFi are kept.
  Every update needs the PIN shown on the counter's screen. If an update fails, the old firmware keeps running.</p>
  <div class="lbl">From GitHub</div>
  <div class="add" style="margin-top:6px"><input type="text" id="otaRepo" placeholder="owner/repository"><input type="text" id="otaBranch" placeholder="main" style="max-width:110px"></div>
  <div class="row">
    <button onclick="otaSaveSource()">Save</button>
    <button class="pri" onclick="otaCheck()">Check for update</button>
    <button id="otaInstall" class="pri hide" onclick="otaInstall()">Install</button>
  </div>
  <p id="otaGh" class="hint" style="margin-top:8px"></p>
  <div class="lbl" style="margin-top:12px">From a file</div>
  <div class="row"><input type="file" id="otaFile" accept=".bin"><button class="pri" onclick="otaUpload()">Upload firmware</button></div>
  <div id="otaBox" class="hide" style="margin-top:12px">
    <div class="bar"><i id="otaBar" style="background:#3ecf6e"></i></div>
    <p id="otaMsg" class="hint" style="margin-top:6px"></p>
  </div>
</section>
</main>
<script>
const $=id=>document.getElementById(id);
const fmt=n=>Number(n).toLocaleString('en-IN');
const esc=t=>{const d=document.createElement('div');d.textContent=t;return d.innerHTML};
const pct=db=>Math.max(0,Math.min(100,(db+80)/60*100));
let S=null,loaded=false,sig='';
function post(u,d){return fetch(u,{method:'POST',body:new URLSearchParams(d||{})}).then(r=>r.json()).then(j=>{if(j.error)alert(j.error);poll();hist();return j})}
function person(slot,action,extra){return post('/api/person',Object.assign({slot:slot,action:action},extra||{}))}
const G=()=>S.people.length-1;  // Guest is the last slot
function label(i){return i==G()?'Guest':(S.people[i].n||'Person '+(i+1))}

function renderPeople(){
  let h='';
  S.people.forEach((p,i)=>{
    if(i<G()&&!p.a)return;
    const g=i==G();
    h+='<div class="person" id="pc'+i+'"><span class="pname">'+esc(label(i))+'</span><span class="pv" id="pv'+i+'"></span>'+
      '<div class="stats">Today <b id="pt'+i+'">0</b> &middot; Total <b id="pg'+i+'">0</b> &middot; Prev day <b id="pp'+i+'">0</b></div><div class="row">'+
      (g?'':'<button class="pri sm" onclick="retrain('+i+')">Erase voice &amp; retrain</button><button class="sm" onclick="train('+i+',1)">Add chants</button><button class="sm" onclick="fastTrain('+i+')">Teach fast chanting</button>')+
      '<button class="sm" onclick="adj('+i+',1)">+1</button><button class="sm" onclick="adj('+i+',-1)">&minus;1</button><button class="sm" onclick="setCnt('+i+')">Set today&hellip;</button>'+
      (g?'':'<button class="sm" onclick="rename('+i+')">Rename</button><button class="sm" onclick="clearVoice('+i+')">Erase voice only</button><button class="dan sm" onclick="removeP('+i+')">Remove</button>')+
      '</div></div>';
  });
  $('people').innerHTML=h;
  $('addBox').classList.toggle('hide',S.people.slice(0,G()).every(p=>p.a));
}
function poll(){fetch('/api/status').then(r=>r.json()).then(s=>{
  S=s;
  const ns=s.people.map(p=>p.a+':'+p.n).join('|');
  if(ns!==sig){sig=ns;renderPeople();hist();$('maxP').textContent=G()}
  const cur=s.train.p>=0?s.train.p:s.cur;
  const per=cur>=0&&(s.people.slice(0,G()).some(p=>p.a)||cur!=G());
  $('who').textContent=per?label(cur):'Everyone';
  $('conf').innerHTML=s.train.p>=0?'training':(per&&cur!=G()&&s.conf?'match '+s.conf+'%':'&nbsp;');
  $('today').textContent=fmt(per?s.people[cur].t:s.tAll);
  $('tAll').textContent=fmt(s.tAll);$('gAll').textContent=fmt(s.gAll);$('pAll').textContent=fmt(s.pAll);
  s.people.forEach((p,i)=>{
    if(!$('pc'+i))return;
    $('pt'+i).textContent=fmt(p.t);$('pg'+i).textContent=fmt(p.g);$('pp'+i).textContent=fmt(p.p);
    $('pc'+i).classList.toggle('cur',i==cur);
    if(i<G()){const v=$('pv'+i);const w=s.word.t[i];
      v.textContent=(p.s>=5?'voice trained ('+p.s+' chants)':(p.s?'voice: '+p.s+' chants, needs 5+':'voice not trained'))+(w?' · word: '+w+' patterns'+(s.word.f[i]?' ('+s.word.f[i]+' fast)':''):'');
      v.classList.toggle('ok',p.s>=5)}
  });
  const W=s.word,taught=W.t.map((n,i)=>i).filter(i=>W.t[i]>0).map(i=>esc(label(i))+' ('+W.t[i]+')');
  $('wHint').innerHTML=(!W.ready?'<b style="color:#ffd79a">Not counting yet</b> &mdash; the word has not been taught. Add a person below and press <b>Erase voice &amp; retrain</b>; they say <b>'+esc(W.name)+'</b> about 30 times with a short pause after each one. After that only that word is counted, also when chanted without pauses.'
    :(W.active?'<b>Counting only &ldquo;'+esc(W.name)+'&rdquo;</b>, also in continuous chanting. Taught by '+taught.join(', ')+'.'
    :'Paused while training.'))+'<br><small>Ignored noises (taps, claps, clicks): '+s.rej+'</small>';
  const wf=W.score<0||W.thr<=0?0:Math.max(0,Math.min(100,W.score/(W.thr*2)*100));
  $('wlv').style.width=(W.score<0?0:wf)+'%';$('wlv').style.background=W.score>=0&&W.score<=W.thr?'#3ecf6e':'#2aa7c9';
  $('trainBox').classList.toggle('hide',s.train.p<0);
  if(s.train.p>=0){$('trName').textContent=label(s.train.p);$('trDone').textContent=s.train.d;$('trTarget').textContent=s.train.t}
  const F=s.word.fast;$('fastBox').classList.toggle('hide',F.p<0);
  if(F.p>=0){$('fsName').textContent=label(F.p);$('fsDone').textContent=F.d;$('fsTarget').textContent=F.t}
  $('clock').textContent=s.timeOk?s.time+' IST':'Time not synced yet';
  $('pz').textContent=s.paused?'· PAUSED':'';$('pauseBtn').textContent=s.paused?'Resume':'Pause';
  $('lv').style.width=pct(s.level)+'%';$('lv').style.background=s.voice?'#3ecf6e':'#2aa7c9';$('th').style.left=pct(s.thr)+'%';
  $('apBanner').classList.toggle('hide',s.wifi);
  const micBad=s.mic=='nosignal'||s.mic=='stuckhigh';
  $('micBanner').classList.toggle('hide',!micBad);
  if(micBad)$('micBanner').innerHTML='<b>Microphone not working:</b> '+(s.mic=='stuckhigh'?'its data line is stuck high. Check the SD wire, and that VDD goes to 3V3 (not 5V).':'no signal on either channel. Check the mic wires (SD, SCK, WS, VDD to 3V3, GND, and L/R to GND) &mdash; see the wiring table.');
  $('dev').innerHTML='WiFi: '+(s.wifi?esc(s.ssid)+' ('+s.rssi+' dBm) &middot; '+s.ip+' &middot; http://japcounter.local':'not connected')+
    '<br>Microphone: '+s.mic+' ('+s.micCh+' channel, raw '+s.raw[0]+'..'+s.raw[1]+')'+
    '<br>SD card: '+(s.sd?'inserted':'not found')+' &middot; Up '+Math.floor(s.uptime/3600)+'h '+Math.floor(s.uptime%3600/60)+'m';
  if(!loaded){loaded=true;['sens','minv','gap','bright','strict','tchants'].forEach(k=>$(k).value=s[k]);$('smooth').checked=s.smooth;
    $('wname').value=s.word.name;$('wstrict').value=s.word.strict;upd()}
}).catch(()=>{})}
function upd(){$('sensV').textContent=$('sens').value;$('brightV').textContent=$('bright').value;$('strictV').textContent=$('strict').value;$('wstrictV').textContent=$('wstrict').value}
['sens','bright','strict','wstrict'].forEach(k=>$(k).oninput=upd);
function saveWord(){post('/api/settings',{wname:$('wname').value.trim(),wmode:1,wstrict:$('wstrict').value})
  .then(j=>{if(j.ok){$('wsaved').textContent='Saved';setTimeout(()=>$('wsaved').textContent='',2000)}})}
function saveSet(){post('/api/settings',{sens:$('sens').value,minv:$('minv').value,gap:$('gap').value,bright:$('bright').value,
  strict:$('strict').value,tchants:$('tchants').value,smooth:$('smooth').checked?1:0})
  .then(()=>{$('saved').textContent='Saved';setTimeout(()=>$('saved').textContent='',2000)})}
function adj(i,n){post('/api/count',{slot:i,op:'add',n:n})}
function setCnt(i){const v=prompt('Set today\'s count for '+label(i)+' to:');if(v!==null&&/^\d+$/.test(v.trim()))post('/api/count',{slot:i,op:'set',n:v.trim()})}
function addPerson(){const n=$('newName').value.trim();if(!n)return;person(0,'add',{name:n}).then(()=>$('newName').value='')}
function rename(i){const n=prompt('New name for '+label(i)+':',S.people[i].n);if(n&&n.trim())person(i,'name',{name:n.trim()})}
function train(i,more){
  const t=$('tchants').value||20;
  if(!more&&S.people[i].s&&!confirm('Replace the trained voice of '+label(i)+' with new chants?'))return;
  if(confirm(label(i)+' should now say "'+S.word.name+'" '+t+' times, alone, at the usual distance, with a short pause after each one. Start?'))person(i,more?'more':'train')}
// ---- firmware update
let otaPoll=null;
function otaShow(p,msg){$('otaBox').classList.remove('hide');$('otaBar').style.width=p+'%';$('otaMsg').textContent=msg}
function otaLoad(){fetch('/api/ota/status').then(r=>r.json()).then(o=>{$('fwVer').textContent=o.version;$('fwBoard').textContent=o.board;
  if(!$('otaRepo').value)$('otaRepo').value=o.repo;if(!$('otaBranch').value)$('otaBranch').value=o.branch})}
function otaSaveSource(){return post('/api/ota/source',{repo:$('otaRepo').value.trim(),branch:$('otaBranch').value.trim()||'main'})}
function otaCheck(){$('otaGh').textContent='Checking GitHub…';$('otaInstall').classList.add('hide');
  otaSaveSource().then(()=>fetch('/api/ota/check',{method:'POST'})).then(r=>r.json()).then(a=>{
    $('otaGh').textContent=a.message+(a.notes?' — '+a.notes:'');$('otaInstall').classList.toggle('hide',!a.newer)})}
// Asks the counter to show a PIN, then asks the user to type it.
function otaAskPin(){return fetch('/api/ota/pin',{method:'POST'}).then(()=>{
  const p=prompt('Enter the 4-digit PIN now shown on the counter\'s screen:');return p&&/^\d{4}$/.test(p.trim())?p.trim():null})}
function otaWatch(){clearInterval(otaPoll);otaPoll=setInterval(()=>fetch('/api/ota/status').then(r=>r.json()).then(o=>{
  otaShow(o.progress,o.message+' ('+o.progress+'%)');
  if(o.state=='done'){clearInterval(otaPoll);otaShow(100,'Installed. The counter is restarting; this page reloads in 20 s.');setTimeout(()=>location.reload(),20000)}
  if(o.state=='failed'){clearInterval(otaPoll)}}).catch(()=>{}),1000)}
function otaInstall(){otaAskPin().then(p=>{if(!p)return;
  post('/api/ota/install',{pin:p}).then(j=>{if(j.ok){otaShow(0,'Downloading…');otaWatch()}})})}
function otaUpload(){const f=$('otaFile').files[0];if(!f){alert('Choose the firmware .bin file first');return}
  if(!confirm('Install '+f.name+' ('+Math.round(f.size/1024)+' KB) on the counter?'))return;
  otaAskPin().then(p=>{if(!p)return;
    const x=new XMLHttpRequest(),fd=new FormData();fd.append('firmware',f);
    x.upload.onprogress=e=>{if(e.lengthComputable)otaShow(Math.round(e.loaded*100/e.total),'Uploading…')};
    x.onload=()=>{let j={};try{j=JSON.parse(x.responseText)}catch(e){}
      if(j.ok){otaShow(100,'Installed. The counter is restarting; this page reloads in 20 s.');setTimeout(()=>location.reload(),20000)}
      else otaShow(0,'Update failed: '+(j.error||x.status))};
    x.onerror=()=>otaShow(0,'Upload interrupted');
    x.open('POST','/api/ota/upload?pin='+p);x.send(fd)})}
otaLoad();

function retrain(i){
  const t=$('tchants').value||30;
  if(!confirm('Erase the voice model and word patterns of '+label(i)+' and record '+t+' new examples now?

'+label(i)+' then says "'+S.word.name+'" '+t+' times, alone, with a short pause after each one. Counts are kept.'))return;
  person(i,'clearvoice').then(()=>person(i,'train'))}
function fastTrain(i){
  if(!(S.word.t[i]>0)){alert('First teach the word with pauses (Erase voice & retrain), then fast chanting.');return}
  if(confirm(label(i)+' should now chant "'+S.word.name+'" continuously at their usual fast speed (up to 3 per second) for about 10 seconds. The counter cuts out each word and learns it. Start?'))person(i,'fast')}
function clearVoice(i){if(confirm('Erase the voice model and word patterns of '+label(i)+'? Counts are kept. '+label(i)+' will not be counted until trained again.'))person(i,'clearvoice')}
function removeP(i){if(confirm('Remove '+label(i)+'? Their counts and trained voice will be erased.'))person(i,'remove')}
function hist(){if(!S)return;fetch('/api/history').then(r=>r.json()).then(h=>{
  const cols=S.people.map((p,i)=>i).filter(i=>i==G()||S.people[i].a||h.some(e=>e.c[i]>0));
  let t='<tr><th>Date</th>'+cols.map(i=>'<th>'+esc(label(i))+'</th>').join('')+'<th>Total</th></tr>';
  t+=h.length?h.map(e=>'<tr><td>'+e.d.split('-').reverse().join('-')+'</td>'+cols.map(i=>'<td>'+fmt(e.c[i]||0)+'</td>').join('')+
    '<td><b>'+fmt(e.c.reduce((a,b)=>a+b,0))+'</b></td></tr>').join(''):'<tr><td colspan="9">No finished days yet</td></tr>';
  $('hist').innerHTML=t});
  fetch('/api/summary').then(r=>r.json()).then(sm=>{
    const MONTHS=['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    const cols=S.people.map((p,i)=>i).filter(i=>i==G()||S.people[i].a||sm.years.some(e=>e.c[i]>0));
    const table=(rows,key,fmtKey)=>'<tr><th></th>'+cols.map(i=>'<th>'+esc(label(i))+'</th>').join('')+'<th>Total</th></tr>'+
      (rows.length?rows.map(e=>'<tr><td>'+fmtKey(e[key])+'</td>'+cols.map(i=>'<td>'+fmt(e.c[i]||0)+'</td>').join('')+
        '<td><b>'+fmt(e.c.reduce((a,b)=>a+b,0))+'</b></td></tr>').join(''):'<tr><td colspan="9">No data yet</td></tr>');
    $('years').innerHTML=table(sm.years,'y',y=>y);
    $('months').innerHTML=table(sm.months,'m',m=>MONTHS[+m.slice(5,7)-1]+' '+m.slice(0,4));
  })}
poll();setInterval(poll,700);
</script></body></html>)rawliteral";

// ---------------------------------------------------------------- WiFi setup
static const char SETUP_HTML[] PROGMEM = R"rawliteral(<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>WiFi Setup - Naam Jap Counter</title><link rel="stylesheet" href="/style.css"></head><body>
<header><h1>WiFi Setup</h1><small>Naam Jap Counter</small></header>
<main>
<section class="card">
  <h3>1. Choose your WiFi</h3>
  <div id="nets">Scanning&hellip;</div>
  <button type="button" onclick="scan(1)">Rescan</button>
</section>
<section class="card">
  <h3>2. Enter the password</h3>
  <form onsubmit="return save()">
    <label>WiFi name (SSID)</label><input type="text" id="ssid" required maxlength="32" autocomplete="off">
    <label>Password</label><input type="password" id="pass" maxlength="64">
    <label><input type="checkbox" onclick="$('pass').type=this.checked?'text':'password'"> Show password</label>
    <div class="row"><button class="pri" type="submit">Save &amp; connect</button></div>
  </form>
  <p id="msg"></p>
</section>
<section class="card"><a href="/">Skip &ndash; use the counter without WiFi &rarr;</a></section>
</main>
<script>
const $=id=>document.getElementById(id);
function scan(fresh){fetch('/api/scan'+(fresh?'?fresh=1':'')).then(r=>r.json()).then(j=>{
  const box=$('nets');
  if(j.scanning){box.textContent='Scanning…';setTimeout(()=>scan(0),1500);return}
  box.innerHTML=j.nets.length?'':'No networks found';
  j.nets.sort((a,b)=>b.r-a.r).forEach(n=>{const b=document.createElement('button');b.type='button';b.className='net';
    b.textContent=n.s+'   '+n.r+' dBm'+(n.e?'  🔒':'');b.onclick=()=>{$('ssid').value=n.s;$('pass').focus()};box.appendChild(b)})
}).catch(()=>setTimeout(()=>scan(0),2000))}
function save(){
  $('msg').textContent='Saving…';
  fetch('/api/wifi',{method:'POST',body:new URLSearchParams({ssid:$('ssid').value,pass:$('pass').value})}).then(r=>r.json()).then(j=>{
    $('msg').textContent=j.ok?'Saved! The counter is restarting and will join "'+$('ssid').value+'". Its new address will be shown on the screen.':(j.error||'Error')})
  .catch(()=>$('msg').textContent='Saved. The counter is restarting.');
  return false}
scan(0);
</script></body></html>)rawliteral";
