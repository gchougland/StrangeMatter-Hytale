# Texture packing

`optimize_textures.py` packs the current authored model atlases. It does not run the art generators. Both output dimensions are powers of two, at least 32 pixels, with width at least height. It never changes model geometry, texture density, colors, transparency, UV mirrors, rotations or animation tracks.

Faces reuse a painted patch only when its pixels and one pixel sampling border match across every texture variant. Existing overlapping UV swatches stay shared. Shared models, doors, conduit shapes, mounted items and working textures are packed together. Icons, particle sheets, UI images and terrain textures retain their original files.

Use Python with Pillow for packing. The independent verifier and regular validation tests only need the Python standard library.

```powershell
python tools/assets/optimize_textures.py --work-dir build/my-texture-pass
python tools/assets/verify_texture_repack.py --baseline build/my-texture-pass/before-resources.zip --manifest build/my-texture-pass/packing-plan.json --resources build/my-texture-pass/staged
python tools/assets/optimize_textures.py --work-dir build/my-texture-pass --apply-plan
```

The first command leaves source assets unchanged and writes a full original resource ZIP, packing report and staged resources. The apply command verifies every model face and all texture variants against that ZIP again, and rejects changes made to the source or staged files since planning. Use a new work directory for another pass after editing assets. `--resume` replans only when the original source snapshot still matches.

PNG palettes are used only when every original RGBA color can be represented exactly. No color quantization or resampling is performed. Appearance verification includes the original neighboring texels used for filtering; distant mip rendering still belongs to an in game visual check.

The optimizer is deliberately an explicit editing step. Normal Gradle builds and asset synchronization do not rewrite your models or textures. Keep model UV edits and their atlas edits together when saving from Blockbench.
