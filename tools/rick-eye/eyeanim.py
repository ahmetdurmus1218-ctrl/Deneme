# Real photo -> layers for a living eye: pupil sizes (base), upper-lid and lower-lid overlays.
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter, median_filter, map_coordinates
from geom import load
a=load(); H,W=a.shape[:2]; g=a.mean(2)
PX,PY,PR=582.1,383.1,70.3; IR=255.0; RW=222.0
yy,xx=np.mgrid[0:H,0:W].astype(np.float32)

# ---- lid lines ----
u=np.load('u.npy')
CORNER=(1035.0,395.0)
x=np.arange(W,dtype=np.float32)
# lower lid margin (where the eyeball meets the lower lid rim)
LO=[(0,330),(120,345),(250,380),(350,640),(560,690),(800,665),(930,590),(1000,500),(1035,395)]
LO=[(0,566),(120,585),(250,622),(400,664),(580,690),(780,672),(900,618),(980,540),(1035,395)]
l=np.interp(x,[p[0] for p in LO],[p[1] for p in LO])
ux=u.copy(); blend=np.clip((x-860)/(CORNER[0]-860),0,1); ux=ux*(1-blend)+CORNER[1]*blend
ux=gaussian_filter(ux,6); l=gaussian_filter(l,6)
inside=np.clip((CORNER[0]-x)/40,0,1)            # no lid travel beyond the outer corner
gap=np.maximum(l-ux,0)*inside

# ---- lashes: dark thin strands in the band under the upper lid ----
band_lo=ux; band_hi=ux+130
inband=((yy>=band_lo[None,:]-6)&(yy<=band_hi[None,:])).astype(np.float32)
gb=gaussian_filter(g,5)
lashm=np.clip((gb-g)*9,0,1)*inband
lashm=gaussian_filter(lashm,0.7)
clean=a*(1-lashm[...,None])+gaussian_filter(a,(6,6,0))*lashm[...,None]

# ---- corneal reflection stays put when the pupil changes ----
lum=a.max(2); rd=np.hypot(xx-PX,yy-PY)
refl=gaussian_filter(((lum>0.70)&(rd<IR+40)&(xx>PX+60)).astype(np.float32),3)
refl=np.clip(refl*1.6,0,1)

def pupil_frame(scale):
    r1=PR*scale; r=rd
    src=np.where(r<r1, r*PR/r1, np.where(r<RW, PR+(r-r1)*(RW-PR)/(RW-r1), r))
    k=np.where(r>0, src/np.maximum(r,1e-6),1.0)
    sx=PX+(xx-PX)*k; sy=PY+(yy-PY)*k
    out=np.stack([map_coordinates(clean[...,c],[sy,sx],order=1,mode='nearest') for c in range(3)],-1)
    return out*(1-refl[...,None])+clean*refl[...,None]

skin_detail=a-gaussian_filter(a,(5,5,0))
# ---- a lid surface made from the photo's own skin (no stretching) ----
_patch=a[8:72, 330:980]                                   # clean skin above the lid
_pd=_patch-gaussian_filter(_patch,(4,4,0))                # its pores and fine lines
_ph,_pw=_pd.shape[:2]
def _tile(yv,xv):
    ty=np.abs(((yv/_ph)%2)-1); ty=(1-ty)*(_ph-1)          # mirrored tiling: no seams
    tx=np.abs(((xv/_pw)%2)-1); tx=(1-tx)*(_pw-1)
    return np.stack([map_coordinates(_pd[...,c],[ty,tx],order=1,mode='mirror') for c in range(3)],-1)
_tone=gaussian_filter(np.median(a[10:60],axis=0),(40,0))  # skin colour per column
_blur=gaussian_filter(a,(7,10,0))
_tone_mean=np.median(a[12:60,380:900].reshape(-1,3),axis=0)
def _sample(img,sy,sx):
    return np.stack([map_coordinates(img[...,c],[sy,sx],order=1,mode='nearest') for c in range(3)],-1)
RIM_UP,RIM_DN=18.0,22.0
def upper_overlay(b,skew=0.0):
    d=b*gap*(1+skew*(x/W-0.45)*2)               # skew>0: the outer end comes lower (sad)
    d=np.clip(d,0,gap); d=gaussian_filter(d,4)
    edge=ux+d
    # 1. the lid margin (real lash roots and rim from the photo) slides down unchanged
    rim_top=edge-RIM_UP
    in_rim=(yy>=rim_top[None,:])&(yy<edge[None,:]+RIM_DN)
    rim_rgb=_sample(a,yy-d[None,:],xx)
    # 2. the skin above it stretches; the top stays where it is
    y0=(0.45*(ux-RIM_UP))[None,:]
    top_src=(ux-RIM_UP)[None,:]; top_dst=rim_top[None,:]
    k=(top_src-y0)/np.maximum(top_dst-y0,1)
    sy=np.where(yy<y0,yy,y0+(yy-y0)*k)
    sharp=_sample(a,sy,xx); soft=_sample(_blur,sy,xx)
    stretch=np.clip(d/70,0,1)[None,:]*np.clip((yy-y0)/30,0,1)          # 0 = untouched, 1 = stretched a lot
    det=_tile(yy*0.9,xx*0.9)
    roundness=np.sin(np.pi*np.clip((yy-y0)/np.maximum(top_dst-y0,1),0,1))
    # a smooth, rounded lid (no smeared columns): one skin tone, shaded like a curved surface, with pores
    v=np.clip((yy-y0)/np.maximum(top_dst-y0,1),0,1)
    hx=np.clip((xx-520)/560,-1.2,1.2)
    shade=0.80+0.26*np.sin(np.pi*(0.15+0.75*v))*(1-0.30*hx**2)-0.10*v**4
    tone=_tone_mean*(1-0.10*v[...,None])+np.array([0.05,-0.01,-0.02])*v[...,None]
    lidskin=tone*shade[...,None]+det*1.15
    skin=sharp*(1-stretch[...,None])+lidskin*stretch[...,None]
    above=yy<rim_top[None,:]
    rgb=np.where(above[...,None],skin,rim_rgb)
    alpha=(above|in_rim).astype(np.float32)
    # 3. lashes hanging from the margin, and the lid's shadow on the eyeball
    sh_lash=map_coordinates(lashm,[yy-d[None,:],xx],order=1,mode='constant',cval=0)
    belowm=~(above|in_rim)
    shadow=np.clip(1-(yy-edge[None,:]-RIM_DN)/24,0,1)*0.45*np.clip(b*4,0,1)
    dark=np.where(belowm,np.clip(sh_lash*0.95+shadow,0,1),0.0)
    # inside the rim band only the rim's own dark/opaque pixels matter where it overlaps the eyeball below
    out_a=np.where(above|in_rim,1.0,dark)
    out_rgb=np.where((above|in_rim)[...,None],rgb,0.0)
    # the rim band below the edge is lashes over eyeball in the photo: keep it only where the lid has moved
    rim_below=(yy>=edge[None,:])&in_rim
    keep=np.clip(d/25,0,1)[None,:]
    out_a=np.where(rim_below,np.maximum(keep*0.0+ (sh_lash*0.95),0),out_a)
    out_rgb=np.where(rim_below[...,None],0.0,out_rgb)
    return out_rgb,out_a

def lower_overlay(e):
    rise=e*np.minimum(gap*0.22,90)
    edge=l-rise
    belowm=yy>edge[None,:]
    sy=H-(H-yy)*((H-l)/np.maximum(H-edge,1))[None,:]
    rgb=np.stack([map_coordinates(a[...,c],[sy,xx],order=1,mode='nearest') for c in range(3)],-1)
    feather=np.clip((yy-edge[None,:])/2.5,0,1)
    return rgb,np.where(belowm,feather,0.0)

def compose(scale,b=0.0,e=0.0,skew=0.0):
    out=pupil_frame(scale)
    if e>0:
        rgb,al=lower_overlay(e); out=out*(1-al[...,None])+rgb*al[...,None]
    rgb,al=upper_overlay(b,skew)
    out=out*(1-al[...,None])+rgb*al[...,None]
    return np.clip(out,0,1)

def vignette(img):
    cx,cy=W*0.47,H*0.48
    d=np.hypot((xx-cx)/(W*0.55),(yy-cy)/(H*0.60))
    v=np.clip(1.25-d,0,1)**1.6
    bg=np.array([0.016,0.024,0.047])
    return img*v[...,None]+bg*(1-v[...,None])

if __name__=="__main__":
    import sys
    shots=[("normal",1.0,0.06,0,0),("isik-kucuk",0.62,0.06,0,0),("karanlik-buyuk",1.85,0.06,0,0),
           ("kirpiyor",1.0,0.55,0,0),("kapali",1.0,1.0,0.15,0),("gulumsuyor",1.0,0.22,0.6,0),
           ("uzgun",1.15,0.33,0,0.9),("uykulu",1.3,0.62,0.1,0),("dinliyor",1.2,0.0,0,0)]
    frames=[]
    for name,sc,b,e,sk in shots:
        im=vignette(compose(sc,b,e,sk)); Image.fromarray((im*255).astype(np.uint8)).save(f"f_{name}.png"); print(name,flush=True)
