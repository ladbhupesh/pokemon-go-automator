# Map Pokémon detector — training tools

The app's `MapScanner` scores map candidates with gradient-boosted trees stored in
`app/src/main/assets/pokemon_gb.txt`. These scripts produced that model.

1. Collect map screenshots: `adb exec-out screencap -p > ds/fNN.png` (1080×2400).
2. `python grids.py "ds/*.png" lab` — proposes candidates (`prop.py`) and writes numbered
   contact sheets plus `lab/items.json`.
3. Record positive ids (and ambiguous ones to skip) in a `labels*.py` file.
4. `python train.py` / `python build2.py` — extract features (`prop.features`, mirrored
   exactly by `MapScanner.features`).
5. `python mine.py <run dir>` — hard negatives from on-device failed taps
   (`files/runs/<time>/miss-*.jpg` + `events.log`).
6. Train, then `python export.py <assets>/pokemon_gb.txt <frames…>`; it also writes parity
   fixtures. Run `PARITY_FILE=parity.txt ./gradlew testDebugUnitTest --rerun` to check the
   Kotlin port matches Python exactly.

Needs `pillow numpy scipy scikit-learn`.
