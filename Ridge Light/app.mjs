import {Terrain,makeRoute,tilesFor,tileUrl,sampleRoute,itinerary,analyse,localToUTC,lightAt,clamp,lerp,latitude,longitude,wrapLon,RAD,mx,my} from './model.mjs';
import {TrailMap,profile} from './cartography.mjs';

const $=id=>document.getElementById(id);
const icon=name=>`<svg class="icon" aria-hidden="true"><use href="#i-${name}"/></svg>`;
const el={}; for(const id of ['date','time','activity','speed','climb','zone','calculate','notice','file','scrub','play','shadows','profile','departures'])el[id]=$(id);
const presets={hike:[4,6],ski:[3,10],run:[9,3.6],custom:[4,6]};
const kinds={sun:'Direct sun',shade:'Terrain shade',dark:'Below horizon',unknown:'Unknown terrain'};
const duration=(seconds)=>{
  const m=Math.round(seconds/60),h=Math.floor(m/60);
  return h?`${h}h ${String(m%60).padStart(2,'0')}m`:`${m} min`;
};
const place=window.tzlookup;
const state={zone:'Europe/Zurich',route:null,config:null,result:null,token:0,t:0,running:false};
const topo=new TrailMap($('terrain'),$('overlay'),t=>{pause();move(t);});
new ResizeObserver(()=>profile(el.profile,state.samples,state.result,state.cursor)).observe(el.profile);
const clockFmt=new Map();
function clock(ms){
  const z=state.zone;
  if(!clockFmt.has(z))clockFmt.set(z,new Intl.DateTimeFormat('en-GB',{timeZone:z,hour:'2-digit',minute:'2-digit',hourCycle:'h23'}));
  return clockFmt.get(z).format(ms);
}
function notice(msg='',error=false){
  el.notice.textContent=msg;
  el.notice.classList.toggle('error',error);
}
function busy(on){
  state.loading=on;
  el.calculate.disabled=on||!state.route;
  $('import').disabled=on;
  $('sample').disabled=on;
  el.calculate.setAttribute('aria-busy',String(on));
}
function readGPX(text){
  const doc=new DOMParser().parseFromString(text,'application/xml');
  if(doc.querySelector('parsererror')||doc.documentElement.localName!=='gpx')throw Error('Could not read this GPX file. Export a GPX track or route and try again.');
  const get=(target,name)=>[...target.children].find(n=>n.localName===name);
  const tracks=[...doc.getElementsByTagNameNS('*','trkseg')].map(n=>[...n.children].filter(p=>p.localName==='trkpt')).filter(s=>s.length);
  const routes=[...doc.getElementsByTagNameNS('*','rte')].map(n=>[...n.children].filter(p=>p.localName==='rtept')).filter(s=>s.length);
  const groups=tracks.length?tracks:routes;
  if(!groups.length)throw Error('No track or route points were found. Waypoints alone do not make a route.');
  if(groups.length>1) {
    // Only stitch segments that actually meet, so missing trail is never implied.
    for(let i=0;i<groups.length-1;i++){
      const a=groups[i].at(-1),b=groups[i+1][0];
      const dy=(+a.getAttribute('lat')-+b.getAttribute('lat'))*111200;
      const dx=wrapLon(+a.getAttribute('lon')-+b.getAttribute('lon'))*111200*Math.cos(+a.getAttribute('lat')*RAD);
      if(Math.hypot(dx,dy)>50)throw Error('This GPX has separate tracks or large gaps. Export one continuous route and try again.');
    }
  }
  const nodes=groups.flat();
  if(nodes.length>100000)throw Error('This GPX has too many points. Simplify it before importing.');
  const parseNum=x=>x!=null&&x.trim()!==''?Number(x):NaN;
  const points=nodes.map(p=>({lat:parseNum(p.getAttribute('lat')),lon:parseNum(p.getAttribute('lon')),ele:parseNum(get(p,'ele')?.textContent)}));
  const name=get(get(doc.documentElement,tracks.length?'trk':'rte')||doc.documentElement,'name')?.textContent?.trim()||get(get(doc.documentElement,'metadata')||doc.documentElement,'name')?.textContent?.trim();
  return {points,name:name?.slice(0,100)};
}
const tileCache=new Map();
async function pixels(url){
  const img=new Image();
  img.crossOrigin='anonymous';
  await new Promise((yes,no)=>{
    const timeout=setTimeout(()=>no(Error('Terrain request timed out')),14000);
    img.onload=()=>{clearTimeout(timeout);yes();};
    img.onerror=()=>{clearTimeout(timeout);no(Error('Terrain tile unavailable'));};
    img.src=url;
  });
  const c=document.createElement('canvas');c.width=256;c.height=256;
  const ctx=c.getContext('2d',{willReadFrequently:true});ctx.drawImage(img,0,0,256,256);
  const rgba=ctx.getImageData(0,0,256,256).data,a=new Float32Array(65536);
  for(let i=0,j=0;i<a.length;i++,j+=4)a[i]=rgba[j+3]===0?NaN:rgba[j]*256+rgba[j+1]+rgba[j+2]/256-32768;
  return a;
}
async function loadTerrain(route){
  const tiles=tilesFor(route), byZ=new Map();
  let finished=0, missed=0,index=0;
  const fetchNext=async()=>{
    while(index<tiles.length){
      const {z,x,y,name}=tiles[index++];
      let p=tileCache.get(name);
      if(!p){
        if(tileCache.size>350)tileCache.delete(tileCache.keys().next().value);
        const remote=()=>pixels(tileUrl(z,x,y));
        p=route.demo?pixels(`assets/dem/${z}-${x}-${y}.png`).catch(remote):remote();
        tileCache.set(name,p);
      }
      try{
        const tile=await p;
        if(!byZ.has(z))byZ.set(z,new Map());
        const bx=byZ.get(z);
        if(!bx.has(x))bx.set(x,new Map());
        bx.get(x).set(y,tile);
      }catch{tileCache.delete(name);missed++;}
      finished++; if(finished%8===0||finished===tiles.length)notice(`Loading elevation tiles · ${finished}/${tiles.length}`);
    }
  };
  await Promise.all(Array.from({length:12},fetchNext));
  if(missed===tiles.length)throw Error('Terrain data could not load. Check your connection and try again.');
  return {terrain:new Terrain(byZ),missed};
}
async function loadRoute(file){
  if(state.loading)return;
  pause();busy(true);notice('Reading route…');
  try{
    if(file&&file.size>20e6)throw Error('This GPX file exceeds the 20 MB limit. Simplify it before importing.');
    const raw=file?await file.text():await fetch('assets/rigi.gpx').then(r=>{if(!r.ok)throw Error('The sample route could not be loaded.');return r.text();});
    const gpx=readGPX(raw),route=makeRoute(gpx.points);
    route.demo=!file;
    route.name=file?(gpx.name||file.name.replace(/\.gpx$/i,'')).slice(0,100):'Rigi-Panoramaweg';
    if(route.demo)route.labels={peak:{x:mx(8.485313),y:my(47.056663)}};
    const p=route.points[0];
    const zone=route.demo?'Europe/Zurich':place(p.lat,wrapLon(p.lon));
    const {terrain,missed}=await loadTerrain(route);
    const samples=sampleRoute(route,terrain);
    if(!samples.steps.some(p=>Number.isFinite(p.ground))) throw Error('No ground elevations were found along this route.');
    state.token++; Object.assign(state,{route,zone,terrain,samples,missed,result:null,cursor:null,config:null,t:0});
    $('route-tag').innerHTML=`<span></span> ${route.demo?'SAMPLE ROUTE':'IMPORTED GPX'}`;
    $('route-name').textContent=route.name;
    $('route-subtitle').textContent=route.demo?'Rigi Scheidegg to Rigi Kaltbad · Switzerland':`${Math.abs(p.lat).toFixed(3)}° ${p.lat>=0?'N':'S'} · ${Math.abs(wrapLon(p.lon)).toFixed(3)}° ${wrapLon(p.lon)>=0?'E':'W'}`;
    el.zone.textContent=`Trailhead time · ${zone.replaceAll('_',' ')}`;
    topo.set(route,terrain,samples);
    clearResult();
    notice();
    busy(false);
    calculate();
  }catch(e){notice(e?.message||'The route could not be loaded.',true);}
  finally{busy(false);el.file.value='';}
}
function clearResult(){
  el.play.disabled=true;el.scrub.disabled=true;el.scrub.value='0';
  $('whole-day').disabled=true;
  $('cursor-time').textContent='–';$('cursor-distance').textContent='Scrub to explore your route';
  $('cursor-light').innerHTML='<span class="dot unknown"></span><span>No analysis yet</span>';
  $('facts').textContent='';
  $('sun-total').textContent='–';$('sun-pct').textContent='Estimated direct sun';
  for(const id of ['summary-bar','exposure-list','replay-bar','replay-ticks','departures'])$(id).textContent='';
  $('compare-caption').textContent='Try other departures for the same route and pace.';
  profile(el.profile,state.samples,null,null);
}
function segments(totals,total){
  return Object.keys(kinds).filter(k=>totals[k]>0).map(k=>`<span class="${k}" style="width:${100*totals[k]/total}%"></span>`).join('');
}
function runSegments(types){
  const runs=[];
  types.forEach((k,i)=>{
    const span=state.samples.steps[i].span,a=runs.at(-1);
    if(a?.kind===k)a.span+=span;else runs.push({kind:k,span});
  });
  return runs.map(r=>`<span class="${r.kind}" style="width:${100*r.span/state.it.seconds}%"></span>`).join('');
}
function render(){
  const {result,it,config,route}=state,{totals}=result;
  $('facts').innerHTML=`<span class="fact">${icon('route')}${(route.total/1000).toFixed(1)} km</span><span class="fact">${icon('mountain')}↑ ${Math.round(it.gain)} m</span><span class="fact">${icon('clock')}${duration(it.seconds)}</span>`;
  $('sun-total').textContent=duration(totals.sun);
  $('sun-pct').textContent=`${Math.round(totals.sun/it.seconds*100)}% of the tour in direct sun`;
  $('summary-bar').innerHTML=segments(totals,it.seconds);
  $('exposure-list').innerHTML=Object.keys(kinds).filter(k=>k!=='unknown'||totals[k]>0).map(k=>`<div class="exposure-line"><span class="dot ${k}"></span>${kinds[k]}<b>${duration(totals[k])}</b></div>`).join('');
  $('replay-bar').innerHTML=runSegments(result.types);
  $('replay-ticks').innerHTML=Array.from({length:5},(_,i)=>`<span>${clock(config.start+it.seconds*1000*i/4)}</span>`).join('');
  topo.setResult(result);
  el.scrub.disabled=false;el.play.disabled=false;
  $('whole-day').disabled=false;
  move(0);
}
function departOptions(time){
  if(state.fullDay){
    const slots=Array.from({length:12},(_,i)=>`${String(i*2).padStart(2,'0')}:00`);
    return [...new Set([...slots,time])].sort();
  }
  const [h,m]=time.split(':').map(Number), v=h*60+m;
  const around=[-120,-60,0,60,120].map(d=>v+d).filter(n=>n>=0&&n<1440);
  return around.map(n=>`${String(Math.floor(n/60)).padStart(2,'0')}:${String(n%60).padStart(2,'0')}`);
}
function compare(token){
  const {config,it}=state, list=departOptions(config.time);
  $('compare-caption').textContent=state.fullDay?'Departures every two hours, plus your selected time.':'Same route and pace. Select a time to replay.';
  el.departures.innerHTML=list.map((t,i)=>`<button class="departure${t===config.time?' active':''}" data-i="${i}" aria-pressed="${t===config.time}"><b>${t}</b><small>Calculating…</small><span class="mini-bar"></span></button>`).join('');
  const buttons=[...el.departures.querySelectorAll('button')];
  let i=0;
  function next(){
    if(token!==state.token||i>=list.length)return;
    const time=list[i],b=buttons[i++];
    const start=localToUTC(config.date,time,state.zone);
    if(!Number.isFinite(start)){b.disabled=true;b.querySelector('small').textContent='Skipped by clocks';}
    else{
      const {totals}=time===config.time?state.result:analyse(state.samples,start,state.terrain);
      b.querySelector('small').textContent=`${Math.round(totals.sun/it.seconds*100)}% sun`;
      b.querySelector('.mini-bar').innerHTML=segments(totals,it.seconds);
      b.onclick=()=>{applySettings(config);el.time.value=time;calculate();};
    }
    setTimeout(next,10);
  }
  setTimeout(next,10);
}
function applySettings(conf){
  el.date.value=conf.date; el.time.value=conf.time;
  el.activity.value=conf.activity; el.speed.value=conf.speed; el.climb.checked=conf.climb;
}
function calculate(){
  if(!state.route||state.loading)return;
  for(const input of [el.date,el.time,el.speed])if(!input.checkValidity()){
    notice('Enter a valid date, start time and speed (0.5–60 km/h).',true);input.reportValidity();return;
  }
  const date=el.date.value,time=el.time.value,speed=+el.speed.value,activity=el.activity.value,climb=el.climb.checked;
  const start=localToUTC(date,time,state.zone);
  if(!Number.isFinite(start)){notice('That local time is skipped when the clocks change. Try a different start time.',true);return;}
  pause();
  const token=++state.token;
  state.config={date,time,speed,activity,climb,start};
  state.it=itinerary(state.samples,speed,climb?presets[activity][1]:0);
  state.result=analyse(state.samples,start,state.terrain);
  const unknown=state.result.totals.unknown;
  notice(unknown?'Some terrain is missing. Unknown time is not counted as sun.':state.missed?'Some surrounding tiles were unavailable.':'');
  render(); compare(token);
}
function stepIndex(t){
  let a=0,b=state.samples.steps.length-1;
  while(a<b){const m=(a+b)>>1; if(state.samples.steps[m].t0+state.samples.steps[m].span<t)a=m+1;else b=m;}
  return a;
}
function move(t){
  if(!state.result)return;
  t=clamp(t,0,state.it.seconds);state.t=t;
  const i=stepIndex(t),s=state.samples.steps[i],a=state.samples.edges[i],b=state.samples.edges[i+1];
  const f=clamp((t-s.t0)/s.span,0,1),d=lerp(a.d,b.d,f),ms=state.config.start+t*1000;
  const path=s.path;let j=0;while(j<path.length-2&&path[j+1].d<d)j++;
  const pa=path[j],pb=path[j+1], frac=clamp((d-pa.d)/(pb.d-pa.d),0,1);
  const x=lerp(pa.x,pb.x,frac),y=lerp(pa.y,pb.y,frac),lat=latitude(y),lon=wrapLon(longitude(x));
  const p={x,y,lat,lon,d,ele:lerp(a.ele,b.ele,f),ms,ground:state.terrain.height(x,y),sec:1/Math.cos(lat*RAD),cache:new Map()};
  const light=lightAt(p,ms,state.terrain);p.light=light;
  state.cursor=p;
  $('cursor-time').textContent=clock(ms);
  $('cursor-distance').textContent=`${(d/1000).toFixed(2)} km of ${(state.route.total/1000).toFixed(2)} km${Number.isFinite(p.ele)?` · ${Math.round(p.ele)} m`:''}`;
  const detail=light.sun.altitude>0?`<small>${light.sun.altitude.toFixed(1)}° sun elevation</small>`:'';
  $('cursor-light').innerHTML=`<span class="dot ${light.kind}"></span><span>${kinds[light.kind]}</span>${detail}`;
  el.scrub.value=String(Math.round(t/state.it.seconds*1000));
  el.scrub.setAttribute('aria-valuetext',`${clock(ms)}, ${kinds[light.kind]}, ${(d/1000).toFixed(2)} kilometres`);
  topo.setCurrent(p);profile(el.profile,state.samples,state.result,p);
}
let anim=0;
function pause(){
  state.running=false;if(anim)cancelAnimationFrame(anim);anim=0;
  el.play.innerHTML=icon('play');el.play.setAttribute('aria-label','Play tour replay');
}
function play(){
  if(!state.result)return;
  if(state.running){pause();return;}
  const begin=performance.now(),offset=state.t>=state.it.seconds-.1?0:state.t;
  state.running=true;el.play.innerHTML=icon('pause');el.play.setAttribute('aria-label','Pause tour replay');
  let last=0;
  function frame(now){
    if(!state.running)return;
    const t=offset+(now-begin)/19000*state.it.seconds;
    if(now-last>45||t>=state.it.seconds){move(t);last=now;}
    if(t>=state.it.seconds){pause();return;}
    anim=requestAnimationFrame(frame);
  }
  anim=requestAnimationFrame(frame);
}
el.file.addEventListener('change',()=>{if(el.file.files?.[0])loadRoute(el.file.files[0]);});
$('import').addEventListener('click',()=>el.file.click());
$('sample').addEventListener('click',()=>loadRoute());
el.calculate.addEventListener('click',calculate);
el.activity.addEventListener('change',()=>{if(el.activity.value!=='custom')el.speed.value=presets[el.activity.value][0];});
for(const field of [el.activity,el.speed,el.date,el.time,el.climb])field.addEventListener('change',()=>{
  if(state.config&&!state.loading)notice('Settings changed. Analyse sunlight to update.');
});
el.scrub.addEventListener('input',()=>{pause();move(+el.scrub.value/1000*state.it.seconds);});
el.play.addEventListener('click',play);
$('zoom-in').onclick=()=>topo.zoom(.76);$('zoom-out').onclick=()=>topo.zoom(1.32);$('fit').onclick=()=>topo.fit();
$('whole-day').onclick=()=>{
  if(!state.result)return;
  state.fullDay=!state.fullDay;
  $('whole-day').setAttribute('aria-pressed',String(state.fullDay));
  compare(++state.token);
};
el.shadows.onclick=()=>{
  const on=el.shadows.getAttribute('aria-pressed')!=='true';
  el.shadows.setAttribute('aria-pressed',String(on));topo.setShadows(on);
};
const dialog=$('about');
for(const id of ['help','footer-help'])$(id).onclick=()=>dialog.showModal();
$('close-help').onclick=()=>dialog.close();
dialog.addEventListener('click',e=>{if(e.target===dialog){const b=dialog.getBoundingClientRect();if(e.clientX<b.left||e.clientX>b.right||e.clientY<b.top||e.clientY>b.bottom)dialog.close();}});
if(document.fonts)document.fonts.ready.then(()=>{topo.drawOverlay();profile(el.profile,state.samples,state.result,state.cursor);});
loadRoute();
