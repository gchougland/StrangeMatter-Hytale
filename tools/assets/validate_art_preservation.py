"""Verify hand-edited art outside the explicit revision targets remains byte-identical."""
from pathlib import Path
import argparse, hashlib, json

ROOT=Path(__file__).resolve().parents[2]
PREFIX='src/main/resources/Common/'
TARGETS={'Items/StrangeMatter/raw_resonite.blockymodel','Items/StrangeMatter/raw_resonite.png',
         'Items/StrangeMatter/tinfoil_hat_worn.blockymodel','Items/StrangeMatter/shade_shard.png'}
for family in ('gravitic','chrono','energetic','spatial','shade','insight'):
    TARGETS.update(f'Blocks/StrangeMatter/{family}_shard_lamp.{extension}' for extension in ('png','blockymodel'))
TARGETS.update(f'Blocks/StrangeMatter/shade_shard_{kind}.png' for kind in ('crystal','ore','lantern'))


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--snapshot',type=Path,required=True);args=parser.parse_args()
    manifest=json.loads((args.snapshot/'sha256.json').read_text());unchanged=[];requested=[];other_owner=[];unexpected=[]
    for relative,expected in manifest['files'].items():
        p=ROOT/relative;short=relative.removeprefix(PREFIX)
        changed=not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest()!=expected
        if not changed:unchanged.append(short)
        elif short in TARGETS or short.startswith('Icons/'):requested.append(short)
        elif short.startswith(('Particles/','Sounds/','UI/')):other_owner.append(short)
        else:unexpected.append(short)
    report={'status':'FAIL' if unexpected else 'PASS','snapshot':str(args.snapshot),
      'unchangedOriginalFiles':len(unchanged),'requestedChanges':requested,'separateRuntimeUiChanges':other_owner,'unexpectedChanges':unexpected}
    (ROOT/'tools/assets/manual-art-preservation.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2));raise SystemExit(bool(unexpected))


if __name__=='__main__':main()
