"""Snapshot current edited art before a targeted revision; never generates art."""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import zipfile
import argparse

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', action='store_true', help='Include all resource files, including item definitions and translations.')
    args = parser.parse_args()
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    output = ROOT / 'build/art-preservation' / stamp
    output.mkdir(parents=True, exist_ok=False)
    source = ROOT / 'src/main/resources' if args.resources else ROOT / 'src/main/resources/Common'
    files = sorted(source.rglob('*'))
    files = [path for path in files if path.is_file()]
    hashes = {}
    with zipfile.ZipFile(output / ('Resources.zip' if args.resources else 'Common.zip'), 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            relative = path.relative_to(ROOT).as_posix()
            data = path.read_bytes()
            hashes[relative] = hashlib.sha256(data).hexdigest()
            archive.writestr(relative, data)
    manifest = {'createdUtc': stamp, 'files': hashes, 'fileCount': len(files)}
    (output / 'sha256.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({'snapshot': str(output), 'files': len(files)}))


if __name__ == '__main__':
    main()
