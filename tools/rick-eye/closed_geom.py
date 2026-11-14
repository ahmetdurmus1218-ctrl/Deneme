import numpy as np
from PIL import Image, ImageDraw
from scipy.ndimage import gaussian_filter, median_filter
c=np.asarray(Image.open('closed_w.png').convert('RGB')).astype(np.float32)/255
H,W=c.shape[:2]; g=gaussian_filter(c.mean(2),2)
m=np.full(W,np.nan)
for x in range(W):
    col=g[250:560,x]; skin=np.median(g[200:300,x])
    idx=np.nonzero(col<skin*0.55)[0]
    if len(idx): m[x]=250+idx[0]
ok=~np.isnan(m); xs=np.arange(W)
m=np.interp(xs,xs[ok],m[ok]); m=median_filter(m,41); m=gaussian_filter(m,10)
np.save('m.npy',m); print([int(m[x]) for x in range(0,W,96)])
im=Image.fromarray((c*255).astype(np.uint8)); d=ImageDraw.Draw(im); d.line(list(zip(xs,m)),fill=(0,255,0),width=2); im.save('cgeom.png')
