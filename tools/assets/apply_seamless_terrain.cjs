/* Targeted local art repair. Requires sharp and a stdlib Python for ZIP reading.
 * Read the preservation snapshot, remove only its known one pixel bevel, and
 * translate the shared placed crystal. Never regenerate painted art or icons.
 * A later manual edit makes this script stop instead of overwriting it.
 */
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const {execFileSync} = require('child_process'), sharp = require('sharp');
const root = path.resolve(__dirname, '../..'), res = path.join(root, 'src/main/resources');
const snapshot = 'build/art-preservation/20260909T175943153203Z';
const terrain = ['Top', 'Soil'].map(name => `Common/BlockTextures/StrangeMatter/Anomalous_Grass_${name}.png`);
const crystal = 'Common/Blocks/StrangeMatter/Shared/shard_crystal_84dcace58934.blockymodel';
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const read = name => JSON.parse(fs.readFileSync(name, 'utf8').replace(/^\uFEFF/, ''));
const mean = pixels => [0, 1, 2].map(c => {
  let sum = 0, weight = 0;
  for (let i = 0; i < pixels.length; i += 4) {sum += pixels[i + c] * pixels[i + 3]; weight += pixels[i + 3];}
  return sum / weight;
});

async function main() {
  const names = [...terrain, crystal];
  const old = JSON.parse(execFileSync(process.env.PYTHON || 'python', ['-c',
    'import sys,zipfile,json,base64\nwith zipfile.ZipFile(sys.argv[1]) as z: print(json.dumps({p:base64.b64encode(z.read("src/main/resources/"+p)).decode() for p in sys.argv[2:]}))',
    path.join(root, snapshot, 'Resources.zip'), ...names], {encoding: 'utf8'}));
  const pending = new Map(), textures = {};
  for (let t = 0; t < terrain.length; t++) {
    const rel = terrain[t], before = Buffer.from(old[rel], 'base64');
    const {data: pixels, info} = await sharp(before).ensureAlpha().raw().toBuffer({resolveWithObject: true});
    if (info.width !== 32 || info.height !== 32) throw Error('Unexpected terrain size: ' + rel);
    const original = Buffer.from(pixels), deltas = t ? [26, 23] : [-54, 56];
    let changed = 0;
    for (let y = 0; y < 32; y++) for (let x = 0; x < 32; x++) {
      const i = (y * 32 + x) * 4;
      const bevel = Math.min(x, y) === 0 ? 10 : (x === 31 || y === 31) ? -12 : 0;
      // The original painter adds the bevel only to the base pigment. Grass
      // blades, mineral flecks and later brush marks have different chroma.
      if (!bevel || pixels[i] - pixels[i + 1] !== deltas[0] || pixels[i + 1] - pixels[i + 2] !== deltas[1]) continue;
      for (let c = 0; c < 3; c++) pixels[i + c] = Math.min(255, Math.max(0, pixels[i + c] - bevel));
      changed++;
    }
    const after = await sharp(pixels, {raw: {width: 32, height: 32, channels: 4}}).png().toBuffer();
    pending.set(rel, after);
    textures[rel] = {beforeSha256: hash(before), afterSha256: hash(after), changedPixels: changed,
      untouchedInteriorPixels: 900, baseChannelDifferences: deltas,
      originalMeanRGB: mean(original), currentMeanRGB: mean(pixels)};
  }
  const beforeModel = Buffer.from(old[crystal], 'base64');
  const model = JSON.parse(beforeModel.toString('utf8'));
  for (const node of model.nodes) node.position.y -= 3.2;
  const afterModel = Buffer.from(JSON.stringify(model, null, 2) + '\n');
  pending.set(crystal, afterModel);
  // Check every destination before the first write. Repeated runs are harmless.
  for (const [rel, after] of pending) {
    const current = fs.readFileSync(path.join(res, rel));
    if (!current.equals(Buffer.from(old[rel], 'base64')) && !current.equals(after)) {
      throw Error('Later manual edit detected. Review before applying this repair: ' + rel);
    }
  }
  for (const [rel, after] of pending) fs.writeFileSync(path.join(res, rel), after);
  const report = {snapshot, textures, crystal: {path: crystal, rootCount: model.nodes.length,
    deltaModelUnitsY: -3.2, modelUnitsPerBlock: 32, deltaBlocksY: -.1,
    beforeSha256: hash(beforeModel), afterSha256: hash(afterModel), collisionBoxesUnchanged: true},
    preservation: 'Only two texture borders and placed crystal root Y positions change. All UVs, texture assignments, child transforms, shard and ore models, icons, grass sides, grass transition and hitboxes remain unchanged.'};
  fs.writeFileSync(path.join(root, 'tools/assets/seamless-terrain.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report));
}
main().catch(error => {console.error(error); process.exitCode = 1;});
