export const R = 6371008.8, RAD = Math.PI / 180, WORLD = 2 * Math.PI * 6378137, REACH = 45000;
export const clamp = (x,a,b) => Math.min(b,Math.max(a,x));
export const lerp = (a,b,t) => a+(b-a)*t;
export const mx = lon => lon * WORLD / 360;
export const my = lat => 6378137 * Math.asinh(Math.tan(clamp(lat,-85.05,85.05)*RAD));
export const latitude = y => Math.atan(Math.sinh(y/6378137))/RAD;
export const longitude = x => x/WORLD*360;
export const wrapLon = x => ((x+180)%360+360)%360-180;

export function distance(a,b) {
  const h = Math.sin((b.lat-a.lat)*RAD/2)**2 + Math.cos(a.lat*RAD)*Math.cos(b.lat*RAD)*Math.sin((b.lon-a.lon)*RAD/2)**2;
  return 2*R*Math.asin(Math.min(1,Math.sqrt(h)));
}
export function makeRoute(points) {
  const clean = [];
  let total = 0;
  for (let p of points) {
    if (!Number.isFinite(p.lat) || !Number.isFinite(p.lon) || Math.abs(p.lat)>83 || Math.abs(p.lon)>180) throw Error('This route contains invalid coordinates or goes beyond terrain coverage (83° latitude).');
    p = {...p};
    const last=clean.at(-1);
    if (last) {
      p.lon = last.lon+wrapLon(p.lon-last.lon);
      const d=distance(last,p);
      if (d<.5) continue;
      total+=d;
    }
    clean.push({...p,d:total,x:mx(p.lon),y:my(p.lat)});
  }
  if (total<30) throw Error('The GPX needs a route at least 30 metres long.');
  if (total>180000) throw Error('This preview supports routes up to 180 km. Split longer tracks and analyse each part separately.');
  let xmin=Infinity,xmax=-Infinity,ymin=Infinity,ymax=-Infinity;
  for (const p of clean) { xmin=Math.min(xmin,p.x); xmax=Math.max(xmax,p.x); ymin=Math.min(ymin,p.y); ymax=Math.max(ymax,p.y); }
  const box={xmin,xmax,ymin,ymax};
  const center={x:(xmin+xmax)/2,y:(ymin+ymax)/2};
  center.lat=latitude(center.y); center.lon=wrapLon(longitude(center.x)); center.sec=1/Math.cos(center.lat*RAD);
  return {points:clean,total,box,center};
}
export function sampleRoute(route, terrain) {
  const points=route.points, slices=clamp(Math.ceil(route.total/30),14,1650), edges=[], steps=[];
  const at=(d,cursor)=>{
    while(cursor.i<points.length-2 && points[cursor.i+1].d<d) cursor.i++;
    const a=points[cursor.i],b=points[cursor.i+1],f=(d-a.d)/(b.d-a.d);
    const x=lerp(a.x,b.x,f),y=lerp(a.y,b.y,f),lat=latitude(y),lon=wrapLon(longitude(x));
    const ground=terrain.height(x,y);
    return {d,x,y,lat,lon,sec:1/Math.cos(lat*RAD),ground,ele:Number.isFinite(a.ele)&&Number.isFinite(b.ele)?lerp(a.ele,b.ele,f):ground,cache:new Map()};
  };
  const ci={i:0}; let ic=0;
  for(let j=0;j<=slices;j++) {
    edges.push(at(route.total*j/slices,ci));
    if(j<slices) {
      const d0=route.total*j/slices,d1=route.total*(j+1)/slices;
      const mid=at((d0+d1)/2,ci);
      const path=[edges[j]];
      while(ic<points.length && points[ic].d<=d0) ic++;
      while(ic<points.length && points[ic].d<d1) path.push(points[ic++]);
      steps.push({...mid,path});
    }
  }
  for(let j=0;j<steps.length;j++) steps[j].path.push(edges[j+1]);
  // A short weighted filter avoids turning vertical GPS jitter into ascent.
  const elev=edges.map(p=>p.ele);
  edges.forEach((p,j)=>p.ele=(elev[Math.max(0,j-1)]+2*elev[j]+elev[Math.min(j+1,edges.length-1)])/4);
  return {edges,steps};
}

const tileX=(x,z)=>(x/WORLD+.5)*2**z;
const tileY=(y,z)=>(.5-y/WORLD)*2**z;
const wrapTile=(x,z)=>((x%2**z)+2**z)%2**z;
export const tileUrl=(z,x,y)=>`https://s3.amazonaws.com/elevation-tiles-prod/terrarium/${z}/${x}/${y}.png`;
export function tilesFor(route) {
  const set=new Map();
  const part=(z,pad)=>{
    const {xmin,xmax,ymin,ymax}=route.box;
    const p=pad*route.center.sec*1.035;
    for(let y=Math.max(0,Math.floor(tileY(ymax+p,z)));y<=Math.min(2**z-1,Math.floor(tileY(ymin-p,z)));y++) {
      for(let x=Math.floor(tileX(xmin-p,z));x<=Math.floor(tileX(xmax+p,z));x++) {
        const item={z,x:wrapTile(x,z),y}; const name=`${z}/${item.x}/${y}`;
        set.set(name,{...item,name});
      }
    }
  };
  part(11,REACH+1500); part(13,3600);
  if(set.size>195) throw Error('This track covers too large an area for one analysis. Try a shorter section of the route.');
  return [...set.values()];
}
export class Terrain {
  constructor(tiles){this.tiles=tiles;}
  pixel(x,y,z){
    const ix=Math.floor(x/256),iy=Math.floor(y/256);
    const tile=this.tiles.get(z)?.get(wrapTile(ix,z))?.get(iy);
    return tile ? tile[(y-iy*256)*256+(x-ix*256)] : NaN;
  }
  at(x,y,z) {
    const u=tileX(x,z)*256-.5,v=tileY(y,z)*256-.5,ix=Math.floor(u),iy=Math.floor(v);
    const a=this.pixel(ix,iy,z),b=this.pixel(ix+1,iy,z),c=this.pixel(ix,iy+1,z),d=this.pixel(ix+1,iy+1,z);
    return lerp(lerp(a,b,u-ix),lerp(c,d,u-ix),v-iy);
  }
  height(x,y) { const near=this.at(x,y,13); return Number.isFinite(near)?near:this.at(x,y,11); }
}
const distances=[];
for(let d=27;d<REACH;d+=Math.max(d<1800?20:55,d*.036)) distances.push([d,d*d/(2*R)]);
distances.push([REACH,REACH*REACH/(2*R)]);
export function skyline(point, az, terrain, fast=false, stopAbove=Infinity) {
  const z=point.ground+1.6,x=point.x,y=point.y;
  if (!Number.isFinite(z)) return NaN;
  const dx=Math.sin(az*RAD)*point.sec,dy=Math.cos(az*RAD)*point.sec;
  let best=-Infinity, missing=false;
  for(let i=0;i<distances.length;i+=fast?3:1){
    const [d,bend]=distances[i];
    const h=terrain.height(x+dx*d,y+dy*d);
    if(!Number.isFinite(h)){missing=true;continue;}
    best=Math.max(best,(h-z-bend)/d);
    if(best>stopAbove) return best;
  }
  return missing?NaN:best;
}
export function solar(ms,lat,lon) {
  const t=new Date(ms),year=t.getUTCFullYear();
  const g=2*Math.PI*((ms-Date.UTC(year,0,1))/86400000-.5)/((Date.UTC(year+1,0,1)-Date.UTC(year,0,1))/86400000);
  const eq=229.18*(.000075+.001868*Math.cos(g)-.032077*Math.sin(g)-.014615*Math.cos(2*g)-.040849*Math.sin(2*g));
  const dec=.006918-.399912*Math.cos(g)+.070257*Math.sin(g)-.006758*Math.cos(2*g)+.000907*Math.sin(2*g)-.002697*Math.cos(3*g)+.00148*Math.sin(3*g);
  const ha=(t.getUTCHours()*15+t.getUTCMinutes()/4+t.getUTCSeconds()/240+eq/4+lon-180)*RAD,la=lat*RAD;
  const altitude=Math.asin(clamp(Math.sin(la)*Math.sin(dec)+Math.cos(la)*Math.cos(dec)*Math.cos(ha),-1,1))/RAD;
  const az=(Math.atan2(Math.sin(ha),Math.cos(ha)*Math.sin(la)-Math.tan(dec)*Math.cos(la))/RAD+180)%360;
  return {altitude,az};
}
const utcParts=new Map();
export function localToUTC(date,time,zone) {
  let fmt=utcParts.get(zone);
  if(!fmt) {
    fmt=new Intl.DateTimeFormat('en-GB',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'});
    utcParts.set(zone,fmt);
  }
  const wanted=Date.parse(`${date}T${time}:00Z`);
  if(!Number.isFinite(wanted)) return NaN;
  const shown=ms=>{
    const a=Object.fromEntries(fmt.formatToParts(ms).map(v=>[v.type,v.value]));
    return Date.UTC(+a.year,+a.month-1,+a.day,+a.hour,+a.minute,+a.second);
  };
  const offsets=[wanted-864e5,wanted,wanted+864e5].map(ms=>shown(ms)-ms);
  const candidates=offsets.map(offset=>wanted-offset).filter(ms=>shown(ms)===wanted);
  return candidates.length?Math.min(...candidates):NaN;
}
export function itinerary(samples, speed, penalty) {
  let seconds=0, gain=0;
  for(let i=0;i<samples.steps.length;i++) {
    const a=samples.edges[i],b=samples.edges[i+1],s=samples.steps[i];
    const ups=Math.max(0,b.ele-a.ele);
    const span=(b.d-a.d)*3.6/speed + (Number.isFinite(ups)?ups*penalty:0);
    gain+=Number.isFinite(ups)?ups:0;
    s.t0=seconds; s.span=span;
    seconds+=span;
  }
  return {seconds,gain};
}
export function lightAt(point,ms,terrain) {
  const sun=solar(ms,point.lat,point.lon);
  if(sun.altitude<=0) return {kind:'dark',sun};
  const key=Math.round(sun.az*2)%720;
  if(!point.cache.has(key)) point.cache.set(key,skyline(point,key/2,terrain));
  const horizon=point.cache.get(key);
  return {kind:!Number.isFinite(horizon)?'unknown':Math.tan(sun.altitude*RAD)>horizon?'sun':'shade',sun,horizon};
}
export function analyse(samples,start,terrain) {
  const totals={sun:0,shade:0,dark:0,unknown:0};
  const types=samples.steps.map(point=>{
    const light=lightAt(point,start+(point.t0+point.span/2)*1000,terrain);
    totals[light.kind]+=point.span;
    return light.kind;
  });
  return {types,totals};
}
