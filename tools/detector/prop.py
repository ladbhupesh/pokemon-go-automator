"""High-recall candidate proposer + feature extraction (prototype for the Kotlin port)."""
import numpy as np
from PIL import Image
from scipy import ndimage as ndi
from det import hsv, ui_mask

F = 3


def load(path):
    img = Image.open(path).convert('RGB')
    W0, H0 = img.size
    a = np.asarray(img).astype(np.float32)
    H, W = H0 // F, W0 // F
    a = a[:H * F, :W * F].reshape(H, F, W, F, 3).mean((1, 3))
    return img, np.floor(a) / 255


def planes(a):
    h, s, v = hsv(a)
    H, W = h.shape
    ui = ui_mask(W, H)
    stop = (h >= 172) & (h <= 215) & (s > 0.58) & (v > 0.80)
    bluish = (h >= 185) & (h <= 240) & (s < 0.62)
    hb = (h / 20).astype(int) % 18
    sb = np.minimum((s * 4).astype(int), 3)
    vb = np.minimum((v * 4).astype(int), 3)
    code = hb * 16 + sb * 4 + vb
    hist = np.bincount(code[~ui], minlength=288).astype(float)
    hist /= max(hist.sum(), 1)
    rare = hist[code] < 0.006
    white = (s < 0.18) & (v > 0.80)
    gray = (s < 0.16) & (v <= 0.80) & (v > 0.18)
    fg = rare & ~stop & ~ui & (v > 0.20) & ~(bluish & ~gray) & ~white
    return dict(h=h, s=s, v=v, ui=ui, stop=stop, fg=fg, white=white, gray=gray, rare=rare)


def propose(a):
    P = planes(a)
    fg = ndi.binary_opening(P["fg"], np.ones((3, 3)))
    grp = ndi.binary_dilation(fg, np.ones((5, 5)))
    lab, n = ndi.label(grp)
    H, W = fg.shape
    out = []
    for i, sl in enumerate(ndi.find_objects(lab), 1):
        m = (lab[sl] == i) & fg[sl]
        area = int(m.sum())
        if area < 20:
            continue
        ys, xs = np.nonzero(m)
        y0, y1 = ys.min() + sl[0].start, ys.max() + sl[0].start + 1
        x0, x1 = xs.min() + sl[1].start, xs.max() + sl[1].start + 1
        if x1 - x0 > 90 or y1 - y0 > 110:
            continue
        out.append(dict(box=(x0, y0, x1, y1), area=area, cx=float(xs.mean() + sl[1].start), cy=float(ys.mean() + sl[0].start)))
    return P, out


def features(P, c):
    """Feature vector for one candidate (all in downscaled pixels)."""
    H, W = P['h'].shape
    x0, y0, x1, y1 = c['box']
    bw, bh = x1 - x0, y1 - y0
    m = np.zeros((H, W), bool)
    m[y0:y1, x0:x1] = P['fg'][y0:y1, x0:x1]
    h, s, v = P['h'][m], P['s'][m], P['v'][m]
    n = max(len(h), 1)
    hist = np.histogram(h, bins=12, range=(0, 360))[0] / n
    gray = (P['gray'] & m).sum() / n
    # context ring around the box
    pad = 12
    X0, Y0, X1, Y1 = max(0, x0 - pad), max(0, y0 - pad), min(W, x1 + pad), min(H, y1 + pad)
    ctx = np.zeros((H, W), bool); ctx[Y0:Y1, X0:X1] = True; ctx[y0:y1, x0:x1] = False
    carea = max(ctx.sum(), 1)
    inbox = np.zeros((H, W), bool); inbox[y0:y1, x0:x1] = True
    stop_in = (P['stop'] & inbox).sum() / max(bw * bh, 1)
    stop_ctx = (P['stop'] & ctx).sum() / carea
    white_ctx = (P['white'] & ctx).sum() / carea
    white_in = (P['white'] & inbox).sum() / max(bw * bh, 1)
    fg_ctx = (P['fg'] & ctx).sum() / carea
    gray_ctx = (P['gray'] & ctx).sum() / carea
    PAD2 = 25
    Z0, W0_, Z1, W1_ = max(0, x0 - PAD2), max(0, y0 - PAD2), min(W, x1 + PAD2), min(H, y1 + PAD2 * 2)
    big = np.zeros((H, W), bool); big[W0_:W1_, Z0:Z1] = True; big[y0:y1, x0:x1] = False
    barea = max(big.sum(), 1)
    dark = (P['s'] < 0.15) & (P['v'] < 0.36) & (P['v'] > 0.08)
    dark_big = (dark & big).sum() / barea
    below = np.zeros((H, W), bool); below[y1:min(H, y1 + 2 * bh + 10), max(0, x0 - 10):min(W, x1 + 10)] = True
    dark_below = (dark & below).sum() / max(below.sum(), 1)
    red = ((P['h'] < 15) | (P['h'] > 345)) & (P['s'] > 0.45) & (P['v'] > 0.5)
    red_in = (red & m).sum() / n
    yellow = ((P['h'] > 38) & (P['h'] < 60) & (P['s'] > 0.85) & (P['v'] > 0.85))
    yel = (yellow & ctx).sum() / carea + (yellow & inbox).sum() / max(bw * bh, 1)
    dx = (c['cx'] - W * 0.5) / W
    dy = (c['cy'] - H * 0.62) / H
    f = [
        c['area'], bw, bh, c['area'] / max(bw * bh, 1), bw / max(bh, 1),
        np.mean(s), np.std(s), np.mean(v), np.std(v), gray,
        stop_in, stop_ctx, white_in, white_ctx, fg_ctx, gray_ctx, yel,
        c['cy'] / H, dx, dy, dark_big, dark_below, red_in,
    ] + list(hist)
    return np.array(f, np.float32)


FEATURE_NAMES = ['area', 'bw', 'bh', 'fill', 'aspect', 's_mean', 's_std', 'v_mean', 'v_std', 'gray',
                 'stop_in', 'stop_ctx', 'white_in', 'white_ctx', 'fg_ctx', 'gray_ctx', 'yellow', 'y', 'dx', 'dy', 'dark_big', 'dark_below', 'red_in'] + \
                [f'hue{i}' for i in range(12)]
