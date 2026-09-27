"""Prototype map Pokémon detector. Works on a 1/3-scale frame."""
import sys, glob, os
import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage as ndi

F = 3  # downscale factor


def hsv(im):
    r, g, b = im[..., 0], im[..., 1], im[..., 2]
    mx = im.max(-1); mn = im.min(-1); d = mx - mn
    s = np.where(mx > 0, d / np.maximum(mx, 1e-6), 0)
    h = np.zeros_like(mx)
    m = d > 1e-6
    rr = (mx == r) & m; gg = (mx == g) & m & ~rr; bb = m & ~rr & ~gg
    h[rr] = (60 * ((g - b)[rr] / d[rr])) % 360
    h[gg] = 60 * ((b - r)[gg] / d[gg]) + 120
    h[bb] = 60 * ((r - g)[bb] / d[bb]) + 240
    return h, s, mx


def ui_mask(W, H):
    """True where UI lives (fractions of the full screen)."""
    m = np.zeros((H, W), bool)
    def rect(x0, y0, x1, y1):
        m[int(y0 * H):int(y1 * H), int(x0 * W):int(x1 * W)] = True
    rect(0, 0, 1, 0.075)          # status bar + timer row
    rect(0, 0, 0.24, 0.16)        # menu/timer, star
    rect(0, 0.10, 0.10, 0.15)
    rect(0.84, 0.03, 1, 0.20)     # weather, compass, side tab
    rect(0.78, 0.15, 1, 0.20)     # ball badge
    rect(0.80, 0.20, 1, 0.30)     # joystick
    rect(0.92, 0.32, 1, 0.39)     # side tab "<"
    rect(0, 0.78, 0.22, 1)        # egg/buddy + avatar
    rect(0, 0.90, 1, 1)           # bottom bar
    rect(0.40, 0.89, 0.60, 1)     # main ball
    rect(0.85, 0.78, 1, 0.92)     # calendar, binoculars
    rect(0.70, 0.91, 1, 1)        # nearby
    rect(0, 0.51, 0.08, 0.58)     # left pokeball tab
    return m


def detect(path, debug=None):
    img = Image.open(path).convert('RGB')
    W0, H0 = img.size
    sm = img.resize((W0 // F, H0 // F), Image.BILINEAR)
    a = np.asarray(sm).astype(np.float32) / 255
    H, W = a.shape[:2]
    h, s, v = hsv(a)
    ui = ui_mask(W, H)

    stop = (h >= 178) & (h <= 222) & (s > 0.50) & (v > 0.55)
    # adaptive background: frequent (hue,sat,val) bins over the playfield
    hb = (h / 20).astype(int) % 18
    sb = np.minimum((s * 4).astype(int), 3)
    vb = np.minimum((v * 4).astype(int), 3)
    code = hb * 16 + sb * 4 + vb
    field = ~ui
    hist = np.bincount(code[field], minlength=18 * 16).astype(float)
    hist /= hist.sum()
    common = hist > 0.006
    rare = ~common[code]

    white = (s < 0.18) & (v > 0.80)
    fg = rare & ~stop & ~ui & (v > 0.22) & ~white
    # remove thin streaks (rain): require local density
    dens = ndi.uniform_filter(fg.astype(float), 3)
    fg = fg & (dens > 0.45)
    fg = ndi.binary_closing(fg, np.ones((3, 3)))

    stop_d = ndi.binary_dilation(stop & ~ui, np.ones((9, 9)))

    lab, n = ndi.label(fg, np.ones((3, 3)))
    objs = ndi.find_objects(lab)
    cands = []
    px, py = W * 0.5, H * 0.62  # player
    for i, sl in enumerate(objs, 1):
        ys, xs = sl
        bw, bh = xs.stop - xs.start, ys.stop - ys.start
        area = int((lab[sl] == i).sum())
        cx = (xs.start + xs.stop) / 2; cy = (ys.start + ys.stop) / 2
        why = None
        if area < 40: why = 'small'
        elif bw > 75 or bh > 90: why = 'big'
        elif area / (bw * bh) < 0.18: why = 'sparse'
        else:
            # structure next to it?
            y0, y1 = max(0, ys.start - 4), min(H, ys.stop + 4)
            x0, x1 = max(0, xs.start - 4), min(W, xs.stop + 4)
            if stop[y0:y1, x0:x1].sum() > 6: why = 'stop'
            elif abs(cx - px) < 12 and abs(cy - py) < 22: why = 'player'
        cands.append(dict(x=cx * F, y=cy * F, box=(xs.start * F, ys.start * F, xs.stop * F, ys.stop * F), area=area, why=why))
    if debug:
        d = img.copy(); dr = ImageDraw.Draw(d)
        for c in cands:
            if c['why'] in ('small',):
                continue
            col = (0, 255, 0) if c['why'] is None else (255, 0, 0)
            dr.rectangle(c['box'], outline=col, width=5)
            if c['why']:
                dr.text((c['box'][0], c['box'][1] - 30), c['why'], fill=col, font_size=30)
        m = np.zeros((H, W, 3), np.uint8) + 30
        m[fg] = [255, 140, 0]; m[stop] = [0, 120, 255]; m[ui] = m[ui] // 2 + 40
        mi = Image.fromarray(m).resize((W0, H0))
        out = Image.new('RGB', (W0 * 2, H0)); out.paste(d, (0, 0)); out.paste(mi, (W0, 0))
        out.resize((W0, H0 // 2)).save(debug)
    return [c for c in cands if c['why'] is None]


if __name__ == '__main__':
    os.makedirs(sys.argv[2], exist_ok=True)
    for p in sorted(glob.glob(sys.argv[1])):
        r = detect(p, os.path.join(sys.argv[2], os.path.basename(p)))
        print(os.path.basename(p), [(int(c['x']), int(c['y'])) for c in r])
