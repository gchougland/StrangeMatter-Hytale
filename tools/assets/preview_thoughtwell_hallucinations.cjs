/* Reference sheet from the exact native creatures used by hallucination models. */
const fs=require('fs'),path=require('path');
const {createCanvas,loadImage}=require('@napi-rs/canvas');
const root=path.resolve(__dirname,'../..');
const native=path.resolve(root,'../HytaleSourceCode/hytale-shared-source/HytaleAssets/Common');
async function main(){
  const canvas=createCanvas(1200,730),g=canvas.getContext('2d');
  g.fillStyle='#0c1526';g.fillRect(0,0,1200,730);
  g.fillStyle='#bde9ef';g.font='30px sans-serif';g.fillText('Thoughtwell apparitions',42,49);
  g.fillStyle='#91b2cc';g.font='17px sans-serif';g.fillText('Recognizable native creatures with their running animations',42,82);
  const entries=[['Wolf_Black','False wolf','Approaches on four legs'],['Spectre_Void','Floating spectre','Glides and sways toward you'],['Crawler_Void','False crawler','Moves on its arms and legs']];
  for(let i=0;i<entries.length;i++){
    const[id,title,detail]=entries[i],x=40+i*390;
    g.fillStyle='#20314a';g.fillRect(x,112,350,310);
    const image=await loadImage(path.join(native,`Icons/ModelsGenerated/${id}.png`));
    g.imageSmoothingEnabled=false;g.drawImage(image,x+62,127,226,226);
    g.fillStyle='#e3f0f5';g.font='22px sans-serif';g.fillText(title,x+20,373);
    g.fillStyle='#a8bed6';g.font='15px sans-serif';g.fillText(detail,x+20,399);
  }
  g.fillStyle='#bde9ef';g.font='23px sans-serif';g.fillText('One apparition at a time',42,476);
  const stages=[['Appears','Beside the forward view'],['Approaches','Stops short of the player'],['Vanishes','Dissolves after 2.15 seconds']];
  for(let i=0;i<3;i++){
    const x=65+i*390;g.fillStyle=i===2?'#9e82ca':'#60c5d4';g.beginPath();g.arc(x,526,8,0,Math.PI*2);g.fill();
    if(i<2){g.strokeStyle='#3a5472';g.lineWidth=2;g.beginPath();g.moveTo(x+16,526);g.lineTo(x+350,526);g.stroke();}
    g.fillStyle='#d4e7f1';g.font='20px sans-serif';g.fillText(stages[i][0],x-10,566);
    g.fillStyle='#96b0c8';g.font='16px sans-serif';g.fillText(stages[i][1],x-10,593);
  }
  g.fillStyle='#aac3d6';g.font='16px sans-serif';g.fillText('Visible only to the affected player. Blue haze and hat protection remain.',42,660);
  g.fillStyle='#6e8aa5';g.font='14px sans-serif';g.fillText('Native artwork reference and scripted sequence. Live gameplay appearance requires a client playtest.',42,695);
  const target=path.join(root,'docs/art/thoughtwell-hallucinations-preview.png');fs.writeFileSync(target,canvas.toBuffer('image/png'));console.log(target);
}
main().catch(e=>{console.error(e);process.exitCode=1});
