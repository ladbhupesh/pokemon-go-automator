"""Replay the on-device target selection on a frame (full-res path or 0.5x thumbnail)."""
import sys, pickle, numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage as ndi
import prop
m = pickle.load(open('gb.pkl', 'rb'))

def run(path, out=None):
    img = Image.open(path).convert('RGB')
    if img.width < 1000: img = img.resize((img.width * 2, img.height * 2), Image.BILINEAR)
    tmp = '/tmp/_replay.png'; img.save(tmp)
    img, a = prop.load(tmp)
    P = prop.planes(a)
    H, W = P['h'].shape
    fg = ndi.binary_opening(P['fg'], np.ones((3, 3)))
    grp = ndi.binary_dilation(fg, np.ones((5, 5)))
    lab, n = ndi.label(grp)
    cands, structs = [], []
    for i, sl in enumerate(ndi.find_objects(lab), 1):
        mm = (lab[sl] == i) & fg[sl]; area = int(mm.sum())
        if area < 20: continue
        ys, xs = np.nonzero(mm)
        y0, y1 = ys.min() + sl[0].start, ys.max() + sl[0].start + 1
        x0, x1 = xs.min() + sl[1].start, xs.max() + sl[1].start + 1
        if x1 - x0 > 90 or y1 - y0 > 110:
            if area / ((x1 - x0) * (y1 - y0)) > 0.2: structs.append((x0, y0, x1, y1))
            continue
        cands.append(dict(box=(x0, y0, x1, y1), area=area, cx=float(xs.mean() + sl[1].start), cy=float(ys.mean() + sl[0].start)))
    res = []
    for c in cands:
        x0, y0, x1, y1 = c['box']
        why = None
        if c['area'] < 40: why = 'small'
        elif x0 <= 2 or y0 <= 2 or x1 >= W - 2 or y1 >= H - 2: why = 'edge'
        elif any(x1 > s[0] - 10 and x0 < s[2] + 10 and y1 > s[1] - 10 and y0 < s[3] + 10 for s in structs): why = 'struct'
        p = m.predict_proba([prop.features(P, c)])[0, 1]
        res.append((c, p, why))
    if out:
        d = ImageDraw.Draw(img)
        for s in structs: d.rectangle([v * 3 for v in s], outline=(255, 255, 0), width=6)
        for c, p, why in res:
            col = (0, 255, 0) if (why is None and p >= 0.85) else (255, 0, 0) if why is None else (120, 120, 255)
            if p < 0.5 and why is None: continue
            d.rectangle([v * 3 for v in c['box']], outline=col, width=5)
            d.text((c['box'][0] * 3, c['box'][1] * 3 - 34), f"{p:.2f} {why or ''}", fill=col, font_size=30)
        img.resize((540, 1200)).save(out)
    return structs, res

if __name__ == '__main__':
    for f in sys.argv[1:]:
        structs, res = run(f, f.rsplit('.', 1)[0] + '_replay.png')
        print(f.split('/')[-1], 'structs', structs, 'picked', [(int(c['cx'] * 3), int(c['cy'] * 3), round(p, 2)) for c, p, w in res if w is None and p >= 0.85])
