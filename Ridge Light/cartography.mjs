import {clamp,lerp,latitude,longitude,wrapLon,RAD,skyline,solar} from './model.mjs';
export const colors={sun:'#efad39',shade:'#527583',dark:'#30334a',unknown:'#92988b'};
const makeCanvas=()=>document.createElement('canvas');
function size(canvas){
  const w=canvas.clientWidth,h=canvas.clientHeight,dpr=Math.min(devicePixelRatio||1,2);
  if(canvas.width!==Math.round(w*dpr)||canvas.height!==Math.round(h*dpr)){canvas.width=Math.round(w*dpr);canvas.height=Math.round(h*dpr);}
  const c=canvas.getContext('2d'); c.setTransform(dpr,0,0,dpr,0,0);
  return {w,h,c};
}
const smooth=x=>clamp(x,0,1)**2*(3-2*clamp(x,0,1));
export class TrailMap {
  constructor(base,overlay,select) {
    Object.assign(this,{base,overlay,select,bg:makeCanvas(),shadeCanvas:makeCanvas(),shadows:false,view:null});
    new ResizeObserver(()=>this.resize()).observe(overlay);
    const xy=e=>{const r=overlay.getBoundingClientRect();return [e.clientX-r.left,e.clientY-r.top];};
    overlay.addEventListener('pointerdown',e=>{
      if(!this.view||e.button!==0) return;
      const [x,y]=xy(e);
      this.drag={x,y,bx:this.view.x,by:this.view.y,moved:false};
      overlay.setPointerCapture(e.pointerId);
    });
    overlay.addEventListener('pointermove',e=>{
      if(!this.drag) return;
      const [x,y]=xy(e),dx=x-this.drag.x,dy=y-this.drag.y;
      if(Math.hypot(dx,dy)<4&&!this.drag.moved)return;
      this.drag.moved=true;
      this.view.x=this.drag.bx-dx*this.view.scale;
      this.view.y=this.drag.by+dy*this.view.scale;
      this.bound(); this.changed();
    });
    overlay.addEventListener('pointerup',e=>{
      if(this.drag&&!this.drag.moved){
        const [x,y]=xy(e); let min=24,index=-1,end=0;
        for(let i=0;i<this.samples.steps.length;i++){
          const path=this.samples.steps[i].path;
          for(let j=1;j<path.length;j++){
            const [ax,ay]=this.toScreen(path[j-1]),[bx,by]=this.toScreen(path[j]);
            const dx=bx-ax,dy=by-ay,len=dx*dx+dy*dy;
            const f=len?clamp(((x-ax)*dx+(y-ay)*dy)/len,0,1):0;
            const d=Math.hypot(x-ax-dx*f,y-ay-dy*f);
            if(d<min){min=d;index=i;end=lerp(path[j-1].d,path[j].d,f);}
          }
        }
        if(index>=0){
          const s=this.samples.steps[index],a=this.samples.edges[index],b=this.samples.edges[index+1];
          this.select(s.t0+s.span*clamp((end-a.d)/(b.d-a.d),0,1));
        }
      }
      this.drag=null;
    });
    overlay.addEventListener('pointercancel',()=>{this.drag=null;});
    overlay.addEventListener('keydown',e=>{
      if(e.key==='+'||e.key==='='||e.key==='-'){e.preventDefault();this.zoom(e.key==='-'?1.32:.76);}
      if(e.key==='Home'){e.preventDefault();this.fit();}
    });
  }
  set(route,terrain,samples){Object.assign(this,{route,terrain,samples});this.result=null;this.current=null;this.fit();}
  resize(){ if(!this.route)return; if(!this.view||this.fitted)this.fit();else this.changed(); }
  fit(){
    if(!this.route)return;
    const {box,center}=this.route, w=this.overlay.clientWidth,h=this.overlay.clientHeight;
    if(w<1||h<1)return;
    const scale=Math.max((box.xmax-box.xmin)/(w*.71),(box.ymax-box.ymin)/(h*.60),2500*center.sec/w);
    this.view={x:center.x,y:center.y,scale};this.fitScale=scale;this.changed();this.fitted=true;
  }
  bound(){
    const {x,y,sec}=this.route.center,limit=14000*sec;
    this.view.x=clamp(this.view.x,x-limit,x+limit);
    this.view.y=clamp(this.view.y,y-limit,y+limit);
  }
  zoom(value){
    if(!this.view)return;
    this.view.scale=clamp(this.view.scale*value,this.fitScale*.14,this.fitScale*4);
    this.changed();
  }
  changed(){
    this.fitted=false;this.baseDirty=true;this.shadowKey=null;this.drawOverlay();
    if(!this.pending)this.pending=requestAnimationFrame(()=>{this.pending=0;this.drawBase();});
  }
  setShadows(checked){this.shadows=checked;this.shadowKey=null;this.drawOverlay();}
  toScreen(p){
    const v=this.view;
    return [(p.x-v.x)/v.scale+this.overlay.clientWidth/2,(v.y-p.y)/v.scale+this.overlay.clientHeight/2];
  }
  drawBase(){
    if(!this.view)return;
    const {w,h,c}=size(this.base),v=this.view;
    const pre=Math.min(w,860),hei=Math.max(1,Math.round(pre*h/w));
    if(!w||!h)return;
    this.bg.width=pre;this.bg.height=hei;
    const ctx=this.bg.getContext('2d'), image=ctx.createImageData(pre,hei),rgb=image.data;
    const delta=v.scale*w/pre;
    const hgrid=new Float32Array((pre+2)*(hei+2));
    for(let j=-1;j<=hei;j++)for(let i=-1;i<=pre;i++){
      hgrid[(j+1)*(pre+2)+i+1]=this.terrain.height(v.x+(i+.5-pre/2)*delta,v.y-(j+.5-hei/2)*delta);
    }
    const relief=delta/this.route.center.sec;
    for(let j=0;j<hei;j++)for(let i=0;i<pre;i++){
      const id=(j+1)*(pre+2)+i+1,z=hgrid[id],k=(j*pre+i)*4;
      if(!Number.isFinite(z)){rgb[k]=225;rgb[k+1]=228;rgb[k+2]=217;rgb[k+3]=255;continue;}
      const ex=(hgrid[id+1]-hgrid[id-1])/(2*relief), ny=(hgrid[id-(pre+2)]-hgrid[id+(pre+2)])/(2*relief);
      const dx=Number.isFinite(ex)?ex:0,dy=Number.isFinite(ny)?ny:0;
      const illum=clamp((.44*dx-.50*dy+.75)/Math.sqrt(1+dx*dx+dy*dy),-.33,1);
      const high=smooth((z-800)/1300),alpine=smooth((z-2100)/950);
      const lowland=smooth((z-250)/670);
      const baseR=lerp(lerp(219,193,lowland),235,high);
      const baseG=lerp(lerp(227,212,lowland),226,high);
      const baseB=lerp(lerp(207,181,lowland),201,high);
      const interval=relief<25?40:relief<65?100:200;
      const contourDist=Math.abs(z-Math.round(z/interval)*interval);
      const weight=clamp((Math.abs(dx)+Math.abs(dy))*relief*.59,1,11);
      const contour=1-smooth(contourDist/weight);
      const wash=(illum-.70)*70-contour*(Math.round(z/interval)%5===0?21:13);
      rgb[k]=lerp(baseR,242,alpine)+wash;
      rgb[k+1]=lerp(baseG,240,alpine)+wash*.84;
      rgb[k+2]=lerp(baseB,225,alpine)+wash*.57;
      rgb[k+3]=255;
    }
    ctx.putImageData(image,0,0);
    c.drawImage(this.bg,0,0,w,h);
  }
  drawShadow(c,w,h){
    const {x,y,scale}=this.view, t=Math.floor(this.current.ms/180000), key=[t,x,y,scale,w,h].join();
    if(this.shadowKey!==key){
      this.shadowKey=key;
      const iw=Math.min(194,w),ih=Math.max(1,Math.round(iw*h/w)), delta=scale*w/iw;
      this.shadeCanvas.width=iw;this.shadeCanvas.height=ih;
      const sc=this.shadeCanvas.getContext('2d'), im=sc.createImageData(iw,ih);
      const sun=solar(t*180000,this.route.center.lat,this.route.center.lon), limit=Math.tan(sun.altitude*RAD);
      for(let j=0;j<ih;j++)for(let i=0;i<iw;i++){
        const gx=x+(i+.5-iw/2)*delta,gy=y-(j+.5-ih/2)*delta;
        const lat=latitude(gy),ground=this.terrain.height(gx,gy);
        const shade=sun.altitude<=0||skyline({x:gx,y:gy,ground,sec:1/Math.cos(lat*RAD)},sun.az,this.terrain,true,limit)>limit;
        if(shade){const k=(j*iw+i)*4; im.data[k]=41;im.data[k+1]=66;im.data[k+2]=92;im.data[k+3]=sun.altitude<=0?107:92;}
      }
      sc.putImageData(im,0,0);
    }
    c.drawImage(this.shadeCanvas,0,0,w,h);
  }
  setResult(result){this.result=result;this.drawOverlay();}
  setCurrent(cur){this.current=cur;this.drawOverlay();}
  label(c,p,title,dx,dy,align='left'){
    const [x,y]=this.toScreen(p);
    c.textAlign=align;c.textBaseline='middle';c.font='800 12px Satoshi, sans-serif';
    c.lineWidth=3.5;c.strokeStyle='#f9f8ec';c.lineJoin='round';c.strokeText(title,x+dx,y+dy);
    c.fillStyle='#2b4134';c.fillText(title,x+dx,y+dy);
  }
  drawOverlay(){
    if(!this.view||!this.samples)return;
    const {w,h,c}=size(this.overlay); c.clearRect(0,0,w,h);
    if(this.shadows&&this.current)this.drawShadow(c,w,h);
    if(this.route.demo){
      // Geographic anchor: peak label is drawn only when it fits.
      const p=this.route.labels?.peak;
      if(p){const [x,y]=this.toScreen(p);if(x>50&&x<w-60&&y>29&&y<h-33){
        c.beginPath();c.moveTo(x,y-4);c.lineTo(x-4,y+3);c.lineTo(x+4,y+3);c.closePath();c.fillStyle='#596146';c.fill();this.label(c,p,'Rigi Kulm',8,0);
      }}
    }
    const steps=this.samples.steps;
    const draw=(start,end)=>{
      c.beginPath(); c.moveTo(...this.toScreen(steps[start].path[0]));
      for(let i=start;i<end;i++) for(let j=1;j<steps[i].path.length;j++)c.lineTo(...this.toScreen(steps[i].path[j]));
      c.stroke();
    };
    c.lineCap='round';c.lineJoin='round';c.strokeStyle='#fffdef';c.lineWidth=8;draw(0,steps.length);
    let i=0;
    while(i<steps.length){
      const key=this.result?.types[i]||'unknown';let j=i+1;
      while(j<steps.length&&(this.result?.types[j]||'unknown')===key)j++;
      c.strokeStyle=colors[key];c.lineWidth=4.5;draw(i,j);i=j;
    }
    const [a,b]=[this.samples.edges[0],this.samples.edges.at(-1)];
    [a,b].forEach((p,j)=>{
      const [x,y]=this.toScreen(p);
      c.beginPath();c.arc(x,y,8,0,2*Math.PI);c.fillStyle='#fffcf3';c.fill();
      c.beginPath();c.arc(x,y,5.6,0,2*Math.PI);c.fillStyle='#345641';c.fill();
      if(j){c.fillStyle='#fffcf3';c.fillRect(x-2.2,y-2.2,4.4,4.4);}
    });
    this.label(c,a,this.route.demo?'Rigi Scheidegg':'Start',0,19,'center');
    this.label(c,b,this.route.demo?'Rigi Kaltbad':'Finish',0,19,'center');
    if(this.current){
      const [x,y]=this.toScreen(this.current);
      const az=this.current.light.sun.az*RAD;
      if(this.current.light.kind!=='dark'){
        c.beginPath();c.moveTo(x+Math.sin(az)*11,y-Math.cos(az)*11);
        c.lineTo(x+Math.sin(az)*33,y-Math.cos(az)*33);c.strokeStyle='#634a22';c.lineWidth=1.5;c.setLineDash([2,3]);c.stroke();c.setLineDash([]);
      }
      c.beginPath();c.arc(x,y,9,0,2*Math.PI);c.fillStyle='#fffdfa';c.fill();c.lineWidth=2;c.strokeStyle='#283e32';c.stroke();
      c.beginPath();c.arc(x,y,5,0,2*Math.PI);c.fillStyle=colors[this.current.light.kind];c.fill();
    }
    const metresPerPx=this.view.scale/this.route.center.sec;
    const ideal=metresPerPx*58, power=10**Math.floor(Math.log10(ideal));
    const span=[1,2,5,10].map(i=>i*power).find(i=>i>=ideal), len=span/metresPerPx;
    const x=w-len-14,y=h-34;
    c.strokeStyle='#fcfbef';c.lineWidth=4;c.beginPath();c.moveTo(x,y-3);c.lineTo(x,y);c.lineTo(x+len,y);c.lineTo(x+len,y-3);c.stroke();
    c.strokeStyle='#405643';c.lineWidth=1.3;c.stroke();c.fillStyle='#263a2e';c.textAlign='center';c.textBaseline='bottom';c.font='800 10px Satoshi,sans-serif';
    const txt=span>=1000?`${span/1000} km`:`${span} m`;
    c.strokeStyle='#fcfbef';c.lineWidth=2.7;c.strokeText(txt,x+len/2,y-4);c.fillText(txt,x+len/2,y-4);
  }
}
export function profile(canvas,samples,result,cur){
  if(!samples)return;
  const {w,h,c}=size(canvas);c.clearRect(0,0,w,h);
  if(w<100||h<70)return;
  const es=samples.edges, maxD=es.at(-1).d, valid=es.filter(p=>Number.isFinite(p.ele));
  if(!valid.length){c.fillStyle='#526252';c.fillText('No elevation data available.',16,32);return;}
  const min=Math.min(...valid.map(p=>p.ele)),max=Math.max(...valid.map(p=>p.ele));
  const top=10,bottom=h-24,left=49,right=w-14, spread=Math.max(26,max-min);
  const y=v=>bottom-(bottom-top)*(.09+.82*(v-min)/spread),x=d=>left+(right-left)*d/maxD;
  c.font='700 11px Satoshi,sans-serif'; c.textBaseline='middle';
  for(let n=0;n<3;n++){
    const v=min+spread*n/2,yy=y(v);
    c.textAlign='right';c.fillStyle='#596658';c.fillText(`${Math.round(v)} m`,left-7,yy);
    c.beginPath();c.moveTo(left,yy);c.lineTo(right,yy);c.strokeStyle='#e8ebe1';c.lineWidth=1;c.stroke();
  }
  for(let i=0;i<es.length-1;i++){
    const p=es[i],q=es[i+1];if(!Number.isFinite(p.ele)||!Number.isFinite(q.ele))continue;
    const hue=colors[result?.types[i]||'unknown'];
    c.beginPath();c.moveTo(x(p.d),bottom);c.lineTo(x(p.d),y(p.ele));c.lineTo(x(q.d),y(q.ele));c.lineTo(x(q.d),bottom);c.closePath();
    c.fillStyle=hue+'3a';c.fill();
    c.beginPath();c.moveTo(x(p.d),y(p.ele));c.lineTo(x(q.d),y(q.ele));c.strokeStyle=hue;c.lineWidth=2.4;c.lineCap='round';c.stroke();
  }
  for(let i=0;i<=4;i++){
    const d=maxD*i/4;c.textAlign=i===0?'left':i===4?'right':'center';c.fillStyle='#596658';c.fillText(`${(d/1000).toFixed(i===0?0:1)} km`,x(d),h-8);
  }
  if(cur&&Number.isFinite(cur.ele)){
    const cx=x(cur.d),cy=y(cur.ele);
    c.beginPath();c.moveTo(cx,top);c.lineTo(cx,bottom);c.lineWidth=1;c.strokeStyle='#385444';c.setLineDash([3,2]);c.stroke();c.setLineDash([]);
    c.beginPath();c.arc(cx,cy,4.8,0,2*Math.PI);c.fillStyle='#fffefa';c.fill();c.strokeStyle='#385444';c.lineWidth=2;c.stroke();
  }
}
