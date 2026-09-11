"""Repeatable asset budgets and opt-in timing comparisons; standard library only."""
from pathlib import Path
import argparse
import hashlib
import json
import math
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]
BUDGETS = Path(__file__).with_name('budgets.json')
MAX_MOD_ATLAS_PIXELS = 3_000_000


def footprint():
    groups = {}
    for path in sorted((ROOT / 'src/main/resources/Common').rglob('*.png')):
        data = path.read_bytes()
        if data[:8] != b'\x89PNG\r\n\x1a\n':
            raise ValueError(f'Invalid PNG: {path}')
        width, height = struct.unpack('>II', data[16:24])
        relative = path.relative_to(ROOT / 'src/main/resources/Common').as_posix()
        group = relative.split('/')[0]
        item = groups.setdefault(group, {'files': 0, 'pixels': 0, 'uniquePixels': 0, 'hashes': set()})
        item['files'] += 1
        item['pixels'] += width * height
        digest = hashlib.sha256(data).digest()
        if digest not in item['hashes']:
            item['uniquePixels'] += width * height
            item['hashes'].add(digest)
    return {key: {k: v for k, v in value.items() if k != 'hashes'} for key, value in groups.items()}


def check_static(budgets):
    actual = footprint()
    for group, values in actual.items():
        limit = budgets['sourceUniquePixelLimits'].get(group, 0)
        if values['uniquePixels'] > limit:
            raise ValueError(f'{group} source textures use {values["uniquePixels"]:,} unique pixels; reviewed budget {limit:,}. Inspect growth before updating tools/diagnostics/budgets.json.')
    output = ROOT / 'build/reports/texture-footprint.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(actual, indent=2) + '\n', encoding='utf-8')
    print('PASS texture source footprint budgets. Native atlas membership and padding are checked in the isolated server.')


def check_atlas(path, budgets):
    atlases = json.loads(path.read_text(encoding='utf-8-sig'))['atlases']
    if len(atlases) != len(budgets['nativePaddedPixelLimits']) or {a['name'] for a in atlases} != set(budgets['nativePaddedPixelLimits']):
        raise ValueError('Missing or unexpected atlas report categories')
    for atlas in atlases:
        for key in ('width', 'predictedHeight', 'textureCount', 'unreadableCount'):
            if type(atlas[key]) is not int or atlas[key] < (1 if key in ('width', 'predictedHeight') else 0):
                raise ValueError('Invalid atlas ' + key)
        for pack in atlas['packs']:
            if type(pack['paddedArea']) is not int or pack['paddedArea'] < 0 or type(pack['count']) is not int or pack['count'] < 0:
                raise ValueError('Invalid atlas pack usage')
        if atlas['sourceError'] or atlas['unreadableCount']:
            raise ValueError(f'Incomplete atlas audit: {atlas["name"]}')
        if max(atlas['width'], atlas['predictedHeight']) > 8192:
            raise ValueError(f'Combined atlas exceeds minimum GPU dimension: {atlas["name"]}')
        own = [pack for pack in atlas['packs'] if 'strangematter' in pack['pack'].lower().replace(' ', '')]
        area = sum(pack['paddedArea'] for pack in own)
        limit = budgets['nativePaddedPixelLimits'][atlas['name']]
        if limit > MAX_MOD_ATLAS_PIXELS:
            raise ValueError(f'{atlas["name"]}: reviewed budget exceeds the 3 MPx per atlas ceiling')
        if area > MAX_MOD_ATLAS_PIXELS:
            raise ValueError(f'{atlas["name"]}: Strange Matter exceeds the 3 MPx per atlas ceiling ({area:,} padded pixels)')
        if area > limit:
            raise ValueError(f'{atlas["name"]}: Strange Matter uses {area:,} padded pixels; reviewed budget {limit:,}')
        print(f'{atlas["name"]}: combined {atlas["width"]} x {atlas["predictedHeight"]}; Strange Matter {area / 1_000_000:.2f} MPx ({area:,} / {limit:,} padded pixels)' + (' (packing estimate)' if atlas['estimated'] else ''))
    if not any('strangematter' in p['pack'].lower().replace(' ', '') for a in atlases for p in a['packs']):
        raise ValueError('Strange Matter was not loaded in the audited asset packs')


def validate_performance(report):
    required = ('schema', 'java', 'os', 'arch', 'processors', 'cpu', 'maximumHeapBytes', 'serverSha256', 'results', 'scope')
    if not isinstance(report, dict) or any(key not in report or report[key] is None for key in required):
        raise ValueError('Incomplete benchmark environment or results')
    if report['schema'] != 1 or not report['results']:
        raise ValueError('Unsupported or empty benchmark report')
    names = set()
    for result in report['results']:
        if result['name'] in names:
            raise ValueError('Duplicate benchmark workload: ' + result['name'])
        names.add(result['name'])
        samples = result['samplesMicros']
        if len(samples) != 400 or not all(isinstance(x, (float, int)) and math.isfinite(x) and x >= 0 for x in samples):
            raise ValueError('Missing or invalid timing samples: ' + result['name'])
        if not isinstance(result['operationsPerSample'], int) or result['operationsPerSample'] < 1 or result['warmupOperations'] < 200:
            raise ValueError('Invalid benchmark workload count')
        ordered = sorted(samples)
        for key, rank in (('p50Micros', .5), ('p95Micros', .95), ('p99Micros', .99), ('maximumMicros', 1)):
            value = result[key]
            if not isinstance(value, (float, int)) or not math.isfinite(value) or not math.isclose(value, ordered[math.ceil(len(ordered) * rank) - 1], rel_tol=1e-7, abs_tol=1e-7):
                raise ValueError('Invalid or inconsistent ' + key + ': ' + result['name'])
        for key in ('allocatedBytesPerOperation', 'cpuMicrosPerOperation'):
            value = result.get(key)
            if value is not None and (not isinstance(value, (float, int)) or not math.isfinite(value) or value < 0):
                raise ValueError('Invalid ' + key + ': ' + result['name'])


def check_performance(path, baseline, strict):
    report = json.loads(path.read_text(encoding='utf-8-sig'))
    validate_performance(report)
    if strict and not baseline:
        raise ValueError('Strict regression checking requires a baseline')
    old = json.loads(baseline.read_text(encoding='utf-8-sig')) if baseline else None
    if old is not None:
        validate_performance(old)
        keys = ('schema', 'java', 'os', 'arch', 'processors', 'cpu', 'maximumHeapBytes', 'serverSha256')
        differences = [key for key in keys if report.get(key) != old.get(key)]
        if differences:
            raise ValueError('Baseline environment differs: ' + ', '.join(differences))
        if {r['name'] for r in report['results']} != {r['name'] for r in old['results']}:
            raise ValueError('Baseline workloads differ')
    regressions = []
    lines = ['Strange Matter server component benchmarks', '', report['scope'], '',
             '| Operation | Median | p95 | p99 | Allocation per operation |', '| :--- | ---: | ---: | ---: | ---: |']
    for result in report['results']:
        if len(result['samplesMicros']) != 400 or not all(math.isfinite(x) and x >= 0 for x in result['samplesMicros']):
            raise ValueError('Missing or invalid timing samples: ' + result['name'])
        allocation = result.get('allocatedBytesPerOperation')
        lines.append(f'| {result["name"]} | {result["p50Micros"]:.2f} us | {result["p95Micros"]:.2f} us | {result["p99Micros"]:.2f} us | {allocation:.0f} bytes |' if allocation is not None else f'| {result["name"]} | {result["p50Micros"]:.2f} us | {result["p95Micros"]:.2f} us | {result["p99Micros"]:.2f} us | unavailable |')
        if old:
            previous = next(r for r in old['results'] if r['name'] == result['name'])
            if any(previous[k] != result[k] for k in ('operation', 'operationsPerSample')):
                raise ValueError('Baseline definition differs: ' + result['name'])
            # Both a proportional and absolute increase avoid failing tiny timings on scheduler noise.
            if result['p95Micros'] > max(previous['p95Micros'] * 1.5, previous['p95Micros'] + 50):
                regressions.append(result['name'] + ' p95 rose by over 50 percent and 50 us')
            if allocation is not None and previous.get('allocatedBytesPerOperation') is not None and allocation > max(previous['allocatedBytesPerOperation'] * 1.2, previous['allocatedBytesPerOperation'] + 1024):
                regressions.append(result['name'] + ' allocation rose by over 20 percent and 1 KiB')
    lines += ['', 'Run on: ' + report['java'], 'CPU: ' + str(report.get('cpu')), '', 'These measurements are component timings, not client FPS or a promise of whole server tick times.']
    if regressions:
        lines += ['', 'Changes to investigate:'] + ['* ' + r for r in regressions]
    path.with_suffix('.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print('\n'.join(lines))
    if regressions and strict:
        raise ValueError('Performance regression thresholds exceeded; repeat on an idle host before investigating')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--atlas', type=Path)
    parser.add_argument('--performance', type=Path)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--strict', action='store_true')
    args = parser.parse_args()
    if (args.baseline or args.strict) and not args.performance:
        parser.error('--baseline and --strict require --performance')
    budgets = json.loads(BUDGETS.read_text(encoding='utf-8'))
    check_static(budgets)
    if args.atlas:
        check_atlas(args.atlas, budgets)
    if args.performance:
        check_performance(args.performance, args.baseline, args.strict)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        sys.exit(1)
