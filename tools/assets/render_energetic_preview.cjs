/* Render our own canvas artifact through native canvas, without browser automation
 * or modifying any production bitmap. Requires bundled @napi-rs/canvas. */
const fs=require('fs'),path=require('path'),vm=require('vm');
const {createCanvas,Image}=require('@napi-rs/canvas');
const root=path.resolve(__dirname,'../..'),file=path.join(root,'docs/art/energetic-rift-preview.html');
const html=fs.readFileSync(file,'utf8'),script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
const canvas=createCanvas(1200,690),elements={view:canvas,play:{textContent:'Pause motion'},vfx:{checked:true},angle:{value:'25'}};
let frame;
const sandbox={Image,document:{getElementById:id=>elements[id],createElement:tag=>{if(tag!=='canvas')throw Error(tag);return createCanvas(128,128)}},
  requestAnimationFrame:callback=>{frame=callback},console};
(async()=>{
  await vm.runInNewContext(script,sandbox,{filename:'energetic-rift-preview.html',timeout:30000});
  if(!frame)throw Error('Preview did not initialize its animation frame');
  frame(1000);frame(1700);
  fs.writeFileSync(path.join(root,'docs/art/energetic-rift-preview.png'),canvas.toBuffer('image/png'));
  elements.vfx.checked=false;elements.angle.value='36';frame(1700);
  fs.writeFileSync(path.join(root,'docs/art/energetic-rift-geometry.png'),canvas.toBuffer('image/png'));
  console.log('Rendered actual preview code and geometry toggle using native canvas. Production bitmaps untouched.');
})().catch(error=>{console.error(error);process.exitCode=1});
