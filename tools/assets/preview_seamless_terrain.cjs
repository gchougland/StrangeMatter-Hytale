/* Read actual model UVs and painted images. Creates a QA image, not game art. */
const fs = require('fs'), path = require('path'), {execFileSync} = require('child_process');
const {createCanvas, loadImage} = require('@napi-rs/canvas');
const root = path.resolve(__dirname, '../..');
const data = JSON.parse(execFileSync(process.env.PYTHON || 'python', [path.join(__dirname, 'preview_seamless_terrain.py')], {encoding: 'utf8', maxBuffer: 10e6}));
const canvas = createCanvas(1100, 1200), ctx = canvas.getContext('2d');
const dot = (a, b) => a.reduce((sum, v, i) => sum + v * b[i], 0);
const sub = (a, b) => a.map((v, i) => v - b[i]);
const cross = (a, b) => [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]];
function triangle(img, p, u) {
  const [a,b,c]=u,[A,B,C]=p,d=(b[0]-a[0])*(c[1]-a[1])-(c[0]-a[0])*(b[1]-a[1]);
  if (Math.abs(d)<1e-8) return;
  const m00=((B[0]-A[0])*(c[1]-a[1])-(C[0]-A[0])*(b[1]-a[1]))/d;
  const m01=((C[0]-A[0])*(b[0]-a[0])-(B[0]-A[0])*(c[0]-a[0]))/d;
  const m10=((B[1]-A[1])*(c[1]-a[1])-(C[1]-A[1])*(b[1]-a[1]))/d;
  const m11=((C[1]-A[1])*(b[0]-a[0])-(B[1]-A[1])*(c[0]-a[0]))/d;
  ctx.save();ctx.beginPath();ctx.moveTo(...A);ctx.lineTo(...B);ctx.lineTo(...C);ctx.closePath();ctx.clip();
  ctx.transform(m00,m10,m01,m11,A[0]-m00*a[0]-m01*a[1],A[1]-m10*a[0]-m11*a[1]);ctx.drawImage(img,0,0);ctx.restore();
}
function aboveGround(face) {
  const output=[];
  const input=face.p.map((p,i)=>({p,uv:face.uv[i]}));
  for(let i=0;i<input.length;i++){
    const a=input[i],b=input[(i+1)%input.length];
    if(a.p[1]>=0) output.push(a);
    if((a.p[1]<0)!==(b.p[1]<0)){
      const t=-a.p[1]/(b.p[1]-a.p[1]);
      output.push({p:a.p.map((v,j)=>v+(b.p[j]-v)*t),uv:a.uv.map((v,j)=>v+(b.uv[j]-v)*t)});
    }
  }
  return output;
}
async function main(){
  ctx.fillStyle='#0b1423';ctx.fillRect(0,0,1100,1200);ctx.imageSmoothingEnabled=false;
  ctx.fillStyle='#bfeef4';ctx.font='28px sans-serif';ctx.fillText('Anomalous terrain and crystal placement',42,42);
  ctx.fillStyle='#8fa9bf';ctx.font='16px sans-serif';ctx.fillText('Repeated actual textures. Crystal panels use current UVs and a surface at Y zero.',42,71);
  for(let row=0;row<2;row++){
    const name=['Top','Soil'][row],y=120+row*355;
    for(let col=0;col<2;col++){
      const key=['before','current'][col],x=55+col*530;
      const im=await loadImage(data.terrain[name][key]);
      ctx.fillStyle='#c2d7e5';ctx.font='19px sans-serif';ctx.fillText((name==='Top'?'Grass top':'Anomalous dirt')+'  '+(col?'After':'Before'),x,y-16);
      for(let a=0;a<4;a++)for(let b=0;b<4;b++)ctx.drawImage(im,x+a*76,y+b*76,76,76);
    }
  }
  const images={};for(const[k,v]of Object.entries(data.images)) images[k]=await loadImage(v);
  const cam=[Math.sin(.45)*Math.cos(.19),Math.sin(.19),Math.cos(.45)*Math.cos(.19)],right=[Math.cos(.45),0,-Math.sin(.45)],up=cross(cam,right);
  for(let col=0;col<2;col++){
    const key=['before','current'][col],cx=260+col*530,cy=1090,scale=225;
    const project=p=>[cx+dot(p,right)*scale,cy-dot(p,up)*scale];
    ctx.fillStyle='#c2d7e5';ctx.font='19px sans-serif';ctx.fillText(col?'Crystal  After  0.1 block lower':'Crystal  Before',55+col*530,848);
    for(let a=-1;a<1;a++)for(let b=-1;b<1;b++){
      const ps=[[a,0,b],[a+1,0,b],[a+1,0,b+1],[a,0,b+1]].map(project),uv=[[0,0],[32,0],[32,32],[0,32]];
      triangle(images.grass,[ps[0],ps[1],ps[2]],[uv[0],uv[1],uv[2]]);triangle(images.grass,[ps[0],ps[2],ps[3]],[uv[0],uv[2],uv[3]]);
    }
    const faces=data.models[key].filter(f=>dot(cross(sub(f.p[1],f.p[0]),sub(f.p[2],f.p[0])),cam)>0).sort((a,b)=>dot(a.p[0],cam)-dot(b.p[0],cam));
    for(const face of faces){const points=aboveGround(face);for(let i=1;i<points.length-1;i++){
      const tri=[points[0],points[i],points[i+1]];triangle(images.crystal,tri.map(v=>project(v.p)),tri.map(v=>v.uv));
    }}
  }
  ctx.fillStyle='#8fa9bf';ctx.font='15px sans-serif';ctx.fillText('Painted interiors, colored grass fringe, crystal textures and collision boxes are unchanged.',42,1180);
  const target=path.join(root,'docs/art/seamless-terrain-preview.png');fs.mkdirSync(path.dirname(target),{recursive:true});fs.writeFileSync(target,canvas.toBuffer('image/png'));console.log(target);
}
main().catch(e=>{console.error(e);process.exitCode=1;});
