import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {makeRoute,tilesFor,tileUrl} from './model.mjs';
const gpx=await readFile(new URL('./assets/rigi.gpx',import.meta.url),'utf8');
const route=makeRoute([...gpx.matchAll(/<trkpt lat="([^"]+)" lon="([^"]+)"/g)].map(match=>({lat:+match[1],lon:+match[2]})));
const tiles=tilesFor(route);
console.log(tiles.length,'terrain tiles');
const dir=new URL('./assets/dem/',import.meta.url);
await mkdir(dir,{recursive:true});
for(let i=0;i<tiles.length;i+=10) {
  await Promise.all(tiles.slice(i,i+10).map(async ({z,x,y})=>{
    const res=await fetch(tileUrl(z,x,y));
    if(!res.ok) throw Error(`Terrain ${z}/${x}/${y}: ${res.status}`);
    await writeFile(new URL(`${z}-${x}-${y}.png`,dir),Buffer.from(await res.arrayBuffer()));
  }));
}
