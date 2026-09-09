"""Remove the generated gray grid after the user authorized local image editing.

The original navy outline and colored lettering are retained. Only the contour
is unmatted to remove the gray mixed into its antialiased pixels.
"""
from pathlib import Path
import argparse
import json
import numpy as np
from PIL import Image, ImageFilter


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('target', type=Path)
    args = parser.parse_args()
    source = Image.open(args.source).convert('RGB')
    pixels = np.asarray(source, dtype=np.float64)
    chroma = pixels.max(axis=2) - pixels.min(axis=2)
    foreground = (chroma > 18) | (pixels.max(axis=2) < 70)
    mask = Image.fromarray(foreground.astype(np.uint8) * 255)
    core = np.asarray(mask.filter(ImageFilter.MinFilter(5))) > 0
    band = np.asarray(mask.filter(ImageFilter.MaxFilter(7))) > 0

    # Extend the nearby solid outline into the narrow antialiasing band.
    # This estimate is never used to recolor the fully opaque interior.
    known = core.copy()
    estimate = pixels.copy()
    for _ in range(10):
        total = np.zeros_like(pixels)
        weight = np.zeros(foreground.shape)
        for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (-1, 1), (1, -1), (1, 1)):
            shifted = np.roll(np.roll(known, dy, axis=0), dx, axis=1)
            color = np.roll(np.roll(estimate, dy, axis=0), dx, axis=1)
            total += color * shifted[..., None]
            weight += shifted
        fill = band & ~known & (weight > 0)
        if not fill.any():
            break
        estimate[fill] = total[fill] / weight[fill, None]
        known |= fill

    # The background is neutral gray. Chromatic information gives coverage
    # independently of the alternating gray squares behind the outline.
    observed_chroma = pixels - pixels.mean(axis=2, keepdims=True)
    edge_chroma = estimate - estimate.mean(axis=2, keepdims=True)
    coverage = (observed_chroma * edge_chroma).sum(axis=2) / np.maximum((edge_chroma ** 2).sum(axis=2), 1)
    coverage = np.clip(coverage, 0, 1)
    coverage[coverage < .08] = 0
    coverage[~band] = 0
    coverage[core] = 1
    rgba = np.zeros((*foreground.shape, 4), dtype=np.uint8)
    rgba[..., :3] = np.clip(np.rint(estimate), 0, 255).astype(np.uint8)
    rgba[core, :3] = np.asarray(source)[core]
    rgba[..., 3] = np.rint(coverage * 255).astype(np.uint8)
    rgba[rgba[..., 3] == 0, :3] = 0
    args.target.parent.mkdir(parents=True, exist_ok=True)
    result = Image.fromarray(rgba, 'RGBA')
    result.save(args.target)
    assert np.array_equal(rgba[core, :3], np.asarray(source)[core])
    assert not rgba[0, :, 3].any() and not rgba[-1, :, 3].any()
    assert not rgba[:, 0, 3].any() and not rgba[:, -1, 3].any()
    report = {'source': str(args.source), 'target': str(args.target), 'size': result.size,
              'mode': result.mode, 'transparentPixels': int((rgba[..., 3] == 0).sum()),
              'preservedInteriorPixels': int(core.sum()), 'originalForegroundColorsPreserved': True}
    args.target.with_suffix('.json').write_text(json.dumps(report, indent=2) + '\n')
    preview = Image.new('RGB', (result.width, result.height * 2))
    for i, color in enumerate(('#f4f4f6', '#101722')):
        background = Image.new('RGBA', result.size, color)
        preview.paste(Image.alpha_composite(background, result).convert('RGB'), (0, i * result.height))
    preview.save(args.target.with_name('transparency-preview.png'))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
