/* Targeted local tint requested by the user. Preserves the native transition alpha
 * and detail, all current terrain bitmaps, and the existing Thoughtwell icon art.
 * Requires the bundled sharp package. Does not run any broad content generator. */
const fs=require('fs'),path=require('path'),crypto=require('crypto'),sharp=require('sharp');
const root=path.resolve(__dirname,'../..'),res=path.join(root,'src/main/resources'),common=path.join(res,'Common');
const native=path.resolve(root,'../HytaleSourceCode/hytale-shared-source/HytaleAssets/Common');
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,''));
const write=(p,data)=>{fs.mkdirSync(path.dirname(p),{recursive:true});fs.writeFileSync(p,JSON.stringify(data,null,2)+'\n')};
async function raw(p){return sharp(p).ensureAlpha().raw().toBuffer({resolveWithObject:true})}
async function main(){
  const grassTop=path.join(common,'BlockTextures/StrangeMatter/Anomalous_Grass_Top.png');
  const maskFile=path.join(native,'BlockTextures/Transition_Soil_Grass_GS.png');
  const top=await raw(grassTop),mask=await raw(maskFile);let alpha=0,mean=[0,0,0],maskAlpha=0,gray=0;
  for(let i=0;i<top.data.length;i+=4){let a=top.data[i+3]/255;alpha+=a;for(let c=0;c<3;c++)mean[c]+=top.data[i+c]*a}
  mean=mean.map(v=>v/alpha);
  for(let i=0;i<mask.data.length;i+=4){let a=mask.data[i+3]/255;maskAlpha+=a;gray+=(mask.data[i]+mask.data[i+1]+mask.data[i+2])/3*a}
  gray/=maskAlpha;const factors=mean.map(v=>v/gray),pixels=Buffer.from(mask.data);
  for(let i=0;i<pixels.length;i+=4){const light=(mask.data[i]+mask.data[i+1]+mask.data[i+2])/3;for(let c=0;c<3;c++)pixels[i+c]=Math.max(0,Math.min(255,Math.round(light*factors[c])))}
  const transition='BlockTextures/StrangeMatter/Anomalous_Grass_Transition.png';
  await sharp(pixels,{raw:{width:mask.info.width,height:mask.info.height,channels:4}}).png().toFile(path.join(common,transition));
  const itemPath=path.join(res,'Server/Item/Items/StrangeMatter/SM_Anomalous_Grass.json'),item=read(itemPath);
  item.BlockType.TransitionTexture=transition;write(itemPath,item);
  const iconSource='Icons/ItemsGenerated/SM_Anomaly_Thoughtwell.png',icon='UI/StatusEffects/SM_Cognitive_Dissonance.png';
  fs.mkdirSync(path.dirname(path.join(common,icon)),{recursive:true});fs.copyFileSync(path.join(common,iconSource),path.join(common,icon));
  const effectPath=path.join(res,'Server/Entity/Effects/StrangeMatter/SM_Cognitive_Dissonance.json'),effect=read(effectPath);
  effect.StatusEffectIcon=icon;write(effectPath,effect);
  const report={snapshot:'build/art-preservation/20260909T173156948611Z',transition,icon,iconSource,
    grassTopSha256:hash(fs.readFileSync(grassTop)),nativeTransitionSha256:hash(fs.readFileSync(maskFile)),
    transitionSha256:hash(fs.readFileSync(path.join(common,transition))),iconSha256:hash(fs.readFileSync(path.join(common,icon))),
    size:[mask.info.width,mask.info.height],grassMeanRGB:mean,nativeBorderMeanGray:gray,tintFactors:factors,
    nativeAlphaSha256:hash(Buffer.from(mask.data.filter((_,i)=>i%4===3))),
    unchangedTerrainTextures:['Anomalous_Grass_Top.png','Anomalous_Grass_Side.png','Anomalous_Grass_Soil.png']};
  write(path.join(root,'tools/assets/grass-icon-fix.json'),report);
  console.log(JSON.stringify({transition,icon,grassMeanRGB:mean,tintFactors:factors,nativeAlphaPreserved:true,iconCopiedWithoutChanges:true}));
}
main().catch(e=>{console.error(e);process.exitCode=1});
