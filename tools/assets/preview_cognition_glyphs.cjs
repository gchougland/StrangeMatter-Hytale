/* Render the exact native Group stroke rectangles at native size and enlarged. */
const fs=require('fs'),path=require('path');
const{createCanvas}=require('@napi-rs/canvas');
const root=path.resolve(__dirname,'../..'),data=JSON.parse(fs.readFileSync(path.join(__dirname,'cognition-glyphs.json'),'utf8'));
const canvas=createCanvas(1120,690),g=canvas.getContext('2d');
function glyph(index,x,y,scale,lit=false){
  g.fillStyle=lit?data.cue.Background:'#1c2a43';g.fillRect(x,y,37*scale,29*scale);
  for(const r of data.glyphs[index].rectangles){g.fillStyle=(lit?data.cue:data.normal)[r.role];g.fillRect(x+r.x*scale,y+r.y*scale,r.w*scale,r.h*scale);}
}
g.fillStyle='#0b1423';g.fillRect(0,0,1120,690);g.imageSmoothingEnabled=false;
g.fillStyle='#b9eff3';g.font='29px sans-serif';g.fillText('Cognition glyphs',40,46);
g.fillStyle='#8fa9c1';g.font='16px sans-serif';g.fillText('Nine distinct shapes. Original mouse controls and memory sequence.',40,78);
g.fillStyle='#c3d8e7';g.font='18px sans-serif';g.fillText('Enlarged symbol shapes',42,120);
for(let i=0;i<9;i++){
  const x=48+(i%3)*154,y=148+Math.floor(i/3)*130;
  glyph(i,x,y,3);
  g.fillStyle='#8ba6c3';g.font='15px sans-serif';g.fillText(data.glyphs[i].name,x,y+111);
}
g.fillStyle='#c3d8e7';g.font='18px sans-serif';g.fillText('Actual native control size',620,120);
g.fillStyle='#263553';g.fillRect(620,144,310,234);g.fillStyle='#101b2f';g.fillRect(621,145,308,232);
g.fillStyle='#8a9dc5';g.font='12px sans-serif';g.fillText('COGNITION / MNEMONIC MATRIX',649,168);
g.fillStyle='#e89abd';g.font='10px sans-serif';g.fillText('UNSTABLE',845,189);
for(let i=0;i<9;i++)glyph(i,631+76+(i%3)*42,198+Math.floor(i/3)*34,1,i===4);
g.fillStyle='#c0abd9';g.font='12px sans-serif';g.fillText('Watch the glowing symbols',699,322);
g.fillStyle='#8197b9';g.font='11px sans-serif';g.fillText('Watch the glowing symbols, then repeat their order.',640,351);
g.fillText('A new pattern emerges after 30 seconds.',672,367);
g.fillStyle='#c3d8e7';g.font='18px sans-serif';g.fillText('Matching memory cue',620,430);
glyph(0,642,455,3);glyph(0,822,455,3,true);
g.fillStyle='#8fa9c1';g.font='15px sans-serif';g.fillText('Normal',660,565);g.fillText('Lit cue',845,565);
g.fillStyle='#9fb7cc';g.font='16px sans-serif';g.fillText('The same geometry is used in both states. Decorative strokes never intercept clicks.',40,635);
g.fillStyle='#69849e';g.font='14px sans-serif';g.fillText('Drawn from the shipped native UI rectangles. Client layout still needs a playtest.',40,665);
const target=path.join(root,'docs/art/cognition-glyphs-preview.png');fs.writeFileSync(target,canvas.toBuffer('image/png'));console.log(target);
