# Third-party code in this folder

`lfm3_vision.py`, `export_vision.py`, `build_vl_bundle.py`, `vl_template.py` and `lfm3_preprocess.py` are copied from
[john-rocky/hf-to-litertlm](https://github.com/john-rocky/hf-to-litertlm) (`lfm3vl_work/tools/`, commit `a395b4967fa9e879a5de548d83399b4be2487b1e`,
Apache-2.0).

| File | Change here |
|---|---|
| `lfm3_vision.py` | The vision tower and projector take a fixed **H × W** grid (`g` = side or `(h, w)`), not only G × G |
| `export_vision.py` | `--g` accepts `HxW` (e.g. `1280x576`) |
| `build_vl_bundle.py` | `--g HxW` writes `image_tensor_height` / `image_tensor_width` separately |
| `vl_template.py`, `lfm3_preprocess.py` | Unchanged |
