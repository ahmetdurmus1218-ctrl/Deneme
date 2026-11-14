# Rick's eyes from real video frames (Mixkit free licence, clip 17467: a man's eyes over a surgical mask).
#
# Input: the clip's frames at 15 fps (frames/0001.jpg …, made with: ffmpeg -i clip.mp4 -vf fps=15 f/%04d.jpg).
# Output: assets/rick-eyes/eyes_NNN.webp (RGBA: only brows and eyes, mask and cap removed, edges faded) and
#         assets/rick-eyes/eyes.json (which frames are the living loop, a blink, closed).
#
# Steps: the head's small movements are measured (phase correlation on the brows and forehead) and undone, so
# the eyes stay still on screen; the operating-room teal light is turned into warm skin (in Lab: chroma moved
# toward skin in the lit skin, gently in the shadows; a little brighter); the band from the brows to the lower
# lids is cut out; the mask below and the cap at the sides are faded away.
#
# Usage: python video_eyes.py <frames dir> <out dir>
import json, os, sys
import numpy as np
from PIL import Image, ImageCms
from numpy.fft import fft2, ifft2
from scipy.ndimage import shift as nshift, gaussian_filter

SRC, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
files = sorted(f for f in os.listdir(SRC) if f.endswith(".jpg"))

IDLE = list(range(90, 126, 2))     # no blink, gentle life: played forth and back (7.5 frames a second)
BLINK = list(range(84, 91))        # 85 lid coming down … 87 closed … 89 open
CLOSED = 87
KEEP = sorted(set(IDLE + BLINK))

REF = 0
TRACK = (slice(60, 330), slice(300, 1000))      # brows + forehead: steady texture
CROP = (120, 135, 1200, 490)                     # x0, y0, x1, y1 in the reference frame
OUT_W = 720

def gray(i):
    return np.asarray(Image.open(os.path.join(SRC, files[i])).convert("L")).astype(np.float32)

def displacement(a, b):
    A = fft2(a - a.mean()); B = fft2(b - b.mean())
    r = np.real(ifft2(A * np.conj(B) / (np.abs(A * np.conj(B)) + 1e-6)))
    y, x = np.unravel_index(np.argmax(r), r.shape)
    if y > r.shape[0] // 2: y -= r.shape[0]
    if x > r.shape[1] // 2: x -= r.shape[1]
    return y, x

srgb = ImageCms.createProfile("sRGB"); lab = ImageCms.createProfile("LAB")
TO = ImageCms.buildTransformFromOpenProfiles(srgb, lab, "RGB", "LAB")
BACK = ImageCms.buildTransformFromOpenProfiles(lab, srgb, "LAB", "RGB")

def warm(rgb):
    raw = np.asarray(ImageCms.applyTransform(Image.fromarray(rgb), TO))
    L = raw[..., 0].astype(float); A = raw[..., 1].view(np.int8).astype(float); B = raw[..., 2].view(np.int8).astype(float)
    w = np.clip((L - 20) / 90, 0, 1)
    A2 = A + 6 * w + 1 * (1 - w)       # the eyes (dark, low weight) keep their own blue-grey
    B2 = B + 15 * w + 4 * (1 - w)
    L2 = np.clip(255 * ((L / 255) ** (1 / 1.25)), 0, 255)
    out = np.dstack([L2.astype(np.uint8), np.clip(A2, -127, 127).astype(np.int8).view(np.uint8),
                     np.clip(B2, -127, 127).astype(np.int8).view(np.uint8)])
    return np.asarray(ImageCms.applyTransform(Image.fromarray(out, "LAB"), BACK)).astype(np.float32)

def alpha_for(rgb):
    """Brows and eyes only: the mask (saturated light blue, below) and the cap (teal, at the sides) go."""
    h, w = rgb.shape[:2]
    R, G, Bc = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    # the mask: clearly blue and green over red, and joined to the bottom edge (a bluish eye white is not)
    from scipy.ndimage import label, binary_dilation
    blue = ((Bc - R) > 40) & ((G - R) > 30) & (yy > h * 0.45)
    lab_, n = label(binary_dilation(blue, iterations=2))
    touching = set(np.unique(lab_[-3:, :])) - {0}
    mask_px = np.isin(lab_, list(touching)) & (yy > h * 0.45)
    # the cap: teal, only in the upper part and joined to the top or side edges (an eye corner is not)
    teal = ((Bc - R) > 22) & ((G - R) > 18) & (yy < h * 0.42)
    clab, _ = label(binary_dilation(teal, iterations=2))
    edge_ids = set(np.unique(np.concatenate([clab[0, :], clab[:, 0], clab[:, -1]]))) - {0}
    cap_px = np.isin(clab, list(edge_ids)) & teal
    away = gaussian_filter((mask_px | cap_px).astype(np.float32), 6)
    keep = np.clip(1 - away * 2.2, 0, 1)
    # soft frame: an ellipse around the eyes, plus faded sides and bottom
    e = np.hypot((xx - w * 0.5) / (w * 0.56), (yy - h * 0.47) / (h * 0.62))
    frame = np.clip((1.0 - e) / 0.22, 0, 1)
    sides = np.clip(xx / (w * 0.07), 0, 1) * np.clip((w - 1 - xx) / (w * 0.07), 0, 1)
    tb = np.clip(yy / (h * 0.10), 0, 1) * np.clip((h - 1 - yy) / (h * 0.14), 0, 1)
    return keep * frame * sides * tb

ref = gray(REF)
x0, y0, x1, y1 = CROP
scale = OUT_W / (x1 - x0)
out_h = round((y1 - y0) * scale)
names = {}
for n, i in enumerate(KEEP):
    im = np.asarray(Image.open(os.path.join(SRC, files[i])).convert("RGB")).astype(np.float32)
    dy, dx = displacement(ref[TRACK], gray(i)[TRACK])
    al = np.stack([nshift(im[..., c], (dy, dx), order=1, mode="nearest") for c in range(3)], -1)
    crop = al[y0:y1, x0:x1]
    a = alpha_for(crop)
    col = warm(np.clip(crop, 0, 255).astype(np.uint8))
    rgba = np.dstack([np.clip(col, 0, 255), a * 255]).astype(np.uint8)
    name = f"eyes_{n:03d}.webp"
    Image.fromarray(rgba, "RGBA").resize((OUT_W, out_h), Image.LANCZOS).save(os.path.join(OUT, name), quality=86, method=6)
    names[i] = n
    print(i, "->", name, (dy, dx), flush=True)

meta = {
    "width": OUT_W, "height": out_h, "fps": 15, "idle_fps": 7.5,
    "idle": [names[i] for i in IDLE],
    "blink": [names[i] for i in BLINK],
    "closed": names[CLOSED],
    "credits": ["Gözler: Mixkit, ücretsiz video (clip 17467, Mixkit Free License); renk ve kırpma Rick için uyarlandı"],
}
json.dump(meta, open(os.path.join(OUT, "eyes.json"), "w"), ensure_ascii=False, indent=1)
print(meta["width"], meta["height"], len(KEEP), "frames")
