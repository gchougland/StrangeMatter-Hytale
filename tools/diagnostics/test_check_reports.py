"""Small synthetic reports exercise failure gates without running the server or touching assets."""
import contextlib
import copy
import io
import json
import math
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock
import zlib

import check_reports as checks


def png(width, height):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    header = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    pixels = (b'\0' + bytes([30, 70, 110, 255]) * width) * height
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')


class ReportChecks(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='sm-report-checks-')
        self.root = Path(self.temporary.name).resolve()
        self.addCleanup(self.temporary.cleanup)
        self.patch = mock.patch.object(checks, 'ROOT', self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.output = contextlib.redirect_stdout(io.StringIO())
        self.output.__enter__()
        self.addCleanup(self.output.__exit__, None, None, None)
        self.budgets = {'sourceUniquePixelLimits': {'Blocks': 4, 'Icons': 4},
                        'nativePaddedPixelLimits': {'Model/Entity': 128, 'Blocks': 64}}

    def write(self, name, value):
        path = self.root / name
        path.write_text(json.dumps(value), encoding='utf-8')
        return path

    def atlas(self):
        return {'atlases': [dict(name=name, width=8192, predictedHeight=256,
                                textureCount=11, sourceError=False, unreadableCount=0, estimated=False,
                                packs=[dict(pack='Hytale:Hytale', paddedArea=2000, count=10),
                                       dict(pack='HexVane:Strange Matter', paddedArea=limit, count=1)])
                            for name, limit in self.budgets['nativePaddedPixelLimits'].items()]}

    def performance(self, micros=100., allocation=2048.):
        return dict(schema=1, java='25.0.1', os='Windows 11', arch='amd64', processors=8,
                    cpu='Synthetic test CPU', maximumHeapBytes=3 * 1024**3, serverSha256='a' * 64,
                    scope='Synthetic validation input, not a measured benchmark.',
                    results=[dict(name='reservation', operation='One reservation plan', operationsPerSample=8,
                                  warmupOperations=200, p50Micros=micros, p95Micros=micros,
                                  p99Micros=micros, maximumMicros=micros, cpuMicrosPerOperation=micros,
                                  allocatedBytesPerOperation=allocation, samplesMicros=[micros] * 400)])

    def run_performance(self, report=None, baseline=None, strict=False):
        current = self.write('current.json', self.performance() if report is None else report)
        old = None if baseline is None else self.write('baseline.json', baseline)
        checks.check_performance(current, old, strict)
        return current.with_suffix('.md').read_text(encoding='utf-8')

    def test_source_budget_deduplicates_exact_files_and_writes_report(self):
        blocks = self.root / 'src/main/resources/Common/Blocks'
        blocks.mkdir(parents=True)
        for name in ('one.png', 'same.png'):
            (blocks / name).write_bytes(png(2, 2))
        checks.check_static(self.budgets)
        report = json.loads((self.root / 'build/reports/texture-footprint.json').read_text())
        self.assertEqual(report['Blocks'], dict(files=2, pixels=8, uniquePixels=4))

    def test_source_budget_rejects_growth_and_unreviewed_category(self):
        for folder, size in [('Blocks', (4, 2)), ('Unexpected', (1, 1))]:
            with self.subTest(folder=folder):
                path = self.root / 'src/main/resources/Common' / folder / 'texture.png'
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(png(*size))
                with self.assertRaises(ValueError):
                    checks.check_static(self.budgets)
                path.unlink()

    def test_source_budget_rejects_non_png_content(self):
        path = self.root / 'src/main/resources/Common/Blocks/broken.png'
        path.parent.mkdir(parents=True)
        path.write_bytes(b'not a PNG')
        with self.assertRaisesRegex(ValueError, 'Invalid PNG'):
            checks.footprint()

    def test_native_atlas_boundary_accepts_normalized_mod_pack_and_estimate(self):
        report = self.atlas()
        report['atlases'][0]['estimated'] = True
        report['atlases'][0]['predictedHeight'] = 8192
        checks.check_atlas(self.write('atlas.json', report), self.budgets)

    def test_native_atlas_budget_sums_multiple_mod_packs(self):
        report = self.atlas()
        report['atlases'][0]['packs'].append(dict(pack='Addon:StrangeMatter', paddedArea=1, count=1))
        report['atlases'][0]['textureCount'] += 1
        with self.assertRaisesRegex(ValueError, 'padded pixels'):
            checks.check_atlas(self.write('atlas.json', report), self.budgets)

    def test_native_atlas_rejects_incomplete_unloaded_and_overlarge_reports(self):
        changes = [lambda r: r['atlases'].pop(),
                   lambda r: r['atlases'][0].update(name='Unexpected'),
                   lambda r: r['atlases'][0].update(sourceError=True),
                   lambda r: r['atlases'][0].update(unreadableCount=1),
                   lambda r: r['atlases'][0].update(width=8193),
                   lambda r: r['atlases'][0].update(predictedHeight=8193),
                   lambda r: [a.update(textureCount=1, packs=[dict(pack='Hytale:Hytale', paddedArea=100, count=1)]) for a in r['atlases']]]
        for index, change in enumerate(changes):
            with self.subTest(case=index):
                report = self.atlas(); change(report)
                with self.assertRaises(ValueError):
                    checks.check_atlas(self.write('atlas.json', report), self.budgets)

    def test_native_atlas_rejects_duplicate_categories(self):
        report = self.atlas(); report['atlases'].append(copy.deepcopy(report['atlases'][0]))
        with self.assertRaises(ValueError):
            checks.check_atlas(self.write('atlas.json', report), self.budgets)

    def test_native_atlas_three_mpx_ceiling_cannot_be_removed_by_raising_budget(self):
        report = self.atlas()
        self.budgets['nativePaddedPixelLimits']['Model/Entity'] = 3_000_000
        report['atlases'][0]['packs'][1]['paddedArea'] = 3_000_000
        checks.check_atlas(self.write('atlas.json', report), self.budgets)
        report['atlases'][0]['packs'][1]['paddedArea'] += 1
        with self.assertRaisesRegex(ValueError, '3 MPx'):
            checks.check_atlas(self.write('atlas.json', report), self.budgets)
        report['atlases'][0]['packs'][1]['paddedArea'] = 1
        self.budgets['nativePaddedPixelLimits']['Model/Entity'] += 1
        with self.assertRaisesRegex(ValueError, 'budget exceeds.*3 MPx'):
            checks.check_atlas(self.write('atlas.json', report), self.budgets)

    def test_native_atlas_rejects_negative_owned_area(self):
        report = self.atlas(); report['atlases'][0]['packs'][1]['paddedArea'] = -1
        with self.assertRaises(ValueError):
            checks.check_atlas(self.write('atlas.json', report), self.budgets)

    def test_matching_baseline_produces_readable_component_report(self):
        markdown = self.run_performance(baseline=self.performance(), strict=True)
        self.assertIn('| reservation | 100.00 us |', markdown)
        self.assertIn('not client FPS', markdown)

    def test_native_nearest_rank_percentiles_from_unsorted_samples(self):
        report = self.performance()
        result = report['results'][0]
        result.update(samplesMicros=list(reversed([1.] * 199 + [2.] * 180 + [3.] * 20 + [100.])),
                      p50Micros=2., p95Micros=3., p99Micros=3., maximumMicros=100.)
        self.run_performance(report, copy.deepcopy(report), strict=True)
        for side in ('current', 'baseline'):
            with self.subTest(side=side):
                current, old = copy.deepcopy(report), copy.deepcopy(report)
                (current if side == 'current' else old)['results'][0]['p95Micros'] = 2.
                with self.assertRaises(ValueError):
                    self.run_performance(current, old, strict=True)

    def test_strict_comparison_requires_a_baseline(self):
        with self.assertRaisesRegex(ValueError, 'requires a baseline'):
            self.run_performance(strict=True)

    def test_cli_rejects_ignored_comparison_arguments_before_static_scan(self):
        for arguments in (['--baseline', 'previous.json'], ['--strict']):
            with self.subTest(arguments=arguments), mock.patch('sys.argv', ['check_reports.py'] + arguments), \
                    mock.patch.object(checks, 'check_static') as static, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error:
                    checks.main()
                self.assertEqual(error.exception.code, 2)
                static.assert_not_called()

    def test_invalid_operation_count_or_short_warmup_rejected(self):
        for key, value in [('operationsPerSample', 0), ('operationsPerSample', 1.5), ('warmupOperations', 199)]:
            with self.subTest(key=key, value=value):
                report = self.performance(); report['results'][0][key] = value
                with self.assertRaises(ValueError):
                    self.run_performance(report)

    def test_regression_is_reported_without_strict_and_rejected_with_strict(self):
        markdown = self.run_performance(self.performance(160.), self.performance(), strict=False)
        self.assertIn('Changes to investigate', markdown)
        self.assertIn('reservation p95', markdown)
        with self.assertRaisesRegex(ValueError, 'regression thresholds'):
            self.run_performance(self.performance(160.), self.performance(), strict=True)

    def test_threshold_requires_both_absolute_and_proportional_increase(self):
        self.run_performance(self.performance(20.), self.performance(10.), strict=True)
        self.run_performance(self.performance(260.), self.performance(200.), strict=True)
        self.run_performance(self.performance(150.), self.performance(100.), strict=True)

    def test_allocation_threshold_and_unavailable_allocation(self):
        with self.assertRaisesRegex(ValueError, 'regression thresholds'):
            self.run_performance(self.performance(allocation=4096), self.performance(), strict=True)
        self.run_performance(self.performance(allocation=3072), self.performance(), strict=True)
        self.run_performance(self.performance(allocation=None), self.performance(), strict=True)

    def test_environment_mismatch_rejected_even_without_strict(self):
        for key in ('schema', 'java', 'os', 'arch', 'processors', 'cpu', 'maximumHeapBytes', 'serverSha256'):
            with self.subTest(key=key):
                old = self.performance(); old[key] = 'different'
                with self.assertRaises(ValueError):
                    self.run_performance(baseline=old)

    def test_workload_and_definition_mismatch_rejected(self):
        for key in ('name', 'operation', 'operationsPerSample'):
            with self.subTest(key=key):
                old = self.performance(); old['results'][0][key] = 4 if key == 'operationsPerSample' else 'different'
                with self.assertRaises(ValueError):
                    self.run_performance(baseline=old)

    def test_empty_current_and_invalid_raw_samples_rejected(self):
        report = self.performance(); report['results'] = []
        with self.assertRaises(ValueError):
            self.run_performance(report)
        for samples in ([], [100.] * 399, [100.] * 399 + [math.nan], [100.] * 399 + [-1.]):
            with self.subTest(last=samples[-1:] if samples else []):
                report = self.performance(); report['results'][0]['samplesMicros'] = samples
                with self.assertRaises(ValueError):
                    self.run_performance(report)

    def test_empty_baseline_cannot_disable_comparison(self):
        with self.assertRaises((ValueError, KeyError)):
            self.run_performance(baseline={}, strict=True)

    def test_baseline_raw_samples_are_validated_too(self):
        old = self.performance(); old['results'][0]['samplesMicros'] = []
        with self.assertRaises(ValueError):
            self.run_performance(baseline=old, strict=True)

    def test_nonfinite_summary_or_allocation_cannot_mask_regression(self):
        for side in ('current', 'baseline'):
            for key in ('p50Micros', 'p95Micros', 'p99Micros', 'maximumMicros', 'allocatedBytesPerOperation'):
                with self.subTest(side=side, key=key):
                    report, old = self.performance(), self.performance()
                    (report if side == 'current' else old)['results'][0][key] = math.nan
                    with self.assertRaises(ValueError):
                        self.run_performance(report, old, strict=True)

    def test_missing_environment_fields_cannot_match_each_other(self):
        for key in ('schema', 'serverSha256', 'maximumHeapBytes'):
            with self.subTest(key=key):
                report, old = self.performance(), self.performance()
                del report[key]; del old[key]
                with self.assertRaises((ValueError, KeyError)):
                    self.run_performance(report, old, strict=True)

    def test_duplicate_workloads_cannot_hide_in_set_comparison(self):
        report, old = self.performance(), self.performance()
        report['results'].append(copy.deepcopy(report['results'][0]))
        old['results'].append(copy.deepcopy(old['results'][0]))
        with self.assertRaises(ValueError):
            self.run_performance(report, old, strict=True)


if __name__ == '__main__':
    unittest.main(verbosity=2)
