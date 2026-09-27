"""Mine hard negatives from on-device failed taps.

Each `miss-NNNN-<outcome>.jpg` is the full-resolution map frame a failed tap was chosen
from; events.log has the tap point. The candidate containing that point becomes a
negative (for gym/stop/rocket/other outcomes). Appends to extra.npz.
"""
import glob, os, re, sys
import numpy as np
from PIL import Image
import prop

rows, labels = [], []
for run in sys.argv[1:]:
    taps = {}
    for line in open(os.path.join(run, 'events.log')):
        t, kind, detail = line.rstrip('\n').split('\t', 2)
        if kind == 'tap':
            m = re.match(r'#(\d+) (\d+),(\d+)', detail)
            taps[int(m.group(1))] = (int(m.group(2)), int(m.group(3)))
    for f in sorted(glob.glob(os.path.join(run, 'miss-*.jpg'))):
        n, outcome = re.match(r'.*miss-(\d+)-(\w+)\.jpg', f).groups()
        if outcome not in ('blocked', 'pokestop'):
            continue
        x, y = taps[int(n)]
        count = 0
        for rot in (0, 120, 240):
            path = f
            if rot:
                hsv = np.asarray(Image.open(f).convert('HSV')).copy()
                hsv[..., 0] = (hsv[..., 0].astype(int) + rot * 256 // 360) % 256
                path = '/tmp/_rot.png'; Image.fromarray(hsv, 'HSV').convert('RGB').save(path)
            img, a = prop.load(path)
            P, cs = prop.propose(a)
            # every candidate within 120 px of the tapped structure is part of it
            hit = [c for c in cs if abs(c['cx'] * 3 - x) < 120 and abs(c['cy'] * 3 - y) < 120]
            for c in hit:
                rows.append(prop.features(P, c)); labels.append(0)
            count += len(hit)
        print(os.path.basename(f), outcome, (x, y), 'negatives', count)

old = dict(np.load('extra.npz')) if os.path.exists('extra.npz') else {'X': np.zeros((0, len(prop.FEATURE_NAMES)), np.float32), 'y': np.zeros(0, int)}
X = np.vstack([old['X']] + ([np.array(rows)] if rows else []))
y = np.concatenate([old['y'], np.array(labels, int)])
np.savez('extra.npz', X=X, y=y)
print('extra negatives total', len(y))
