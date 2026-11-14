# The open eye (photo 1) closes with a real closed eyelid (closed-eye photo), lid margin following our lid line.
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter, map_coordinates
import eyeanim as E
a,H,W,xx,yy,ux,l,gap,lashm,x=E.a,E.H,E.W,E.xx,E.yy,E.ux,E.l,E.gap,E.lashm,E.x
C=np.asarray(Image.open('../closed/closed_w.png').convert('RGB')).astype(np.float32)/255
CH,CW=C.shape[:2]; m=np.load('../closed/m.npy')
# colour of the closed lid matched to this face's skin
src=C[120:370,150:950].reshape(-1,3); dst=a[12:70,330:980].reshape(-1,3)
mu_s,sd_s=src.mean(0),src.std(0); mu_d,sd_d=dst.mean(0),dst.std(0)
_Cs=gaussian_filter(C,(0.8,0.8,0)); _Cb=gaussian_filter(C,(5,5,0))
_Cs=_Cb+(_Cs-_Cb)*0.55                                            # calmer skin texture
_lum=_Cs.mean(2,keepdims=True); _Cs=np.where(_lum>0.78,_Cs-(_lum-0.78)*0.7,_Cs)   # tame the shiny flecks
Cm=np.clip((_Cs-mu_s)/sd_s*(sd_d*0.35+sd_s*0.65)+mu_d,0,1)*0.92
gc=gaussian_filter(C.mean(2),1.0)
lash_c=np.clip((gaussian_filter(gc,7)-gc)*7.5,0,1)
_r=np.arange(CH)[:,None]-m[None,:]
lash_c=lash_c*(_r>-4)*np.clip(1-(_r-150)/90,0,1)
INNER,OUTER=-80.0,1035.0; CIN,COUT=10.0,1110.0
xc=CIN+(x-INNER)*(COUT-CIN)/(OUTER-INNER)
mc=np.interp(xc,np.arange(CW),m)
LASH_RGB=np.array([0.035,0.022,0.018])
def smooth(e0,e1,v): t=np.clip((v-e0)/(e1-e0),0,1); return t*t*(3-2*t)

ROOT=7.0                                   # the lid edge sits a little into the lash roots: a real, dark margin
ux_s=gaussian_filter(ux,22)                # top of the lash band (where the lid skin meets the lashes)
_UP=[(0,240),(150,192),(300,168),(450,165),(600,165),(750,176),(850,205),(930,258),(990,318),(1035,395)]
U=gaussian_filter(np.interp(x,[p[0] for p in _UP],[p[1] for p in _UP]),12)   # top of the visible eyeball
from scipy.interpolate import PchipInterpolator
# Clean arcs for the lid, anchored at the outer corner: where the moving edge starts (open) and ends (closed).
_OPEN=[(-80,262),(0,215),(150,160),(300,120),(450,100),(600,95),(750,108),(860,150),(950,230),(1005,320),(1035,395)]
_SHUT=[(-80,548),(0,566),(120,585),(250,622),(400,664),(580,690),(780,672),(900,618),(980,540),(1035,395)]
def _arc(pts): return PchipInterpolator([p[0] for p in pts],[p[1] for p in pts],extrapolate=False)(np.clip(x,-80,1035))
E0=_arc(_OPEN); L1=_arc(_SHUT)
E0=np.where(x>1035,395.0,E0); L1=np.where(x>1035,395.0,L1)
# Lashes of the photo around the outer corner (over the skin, outside the lash band): they belong to the lid,
# so they go away as it closes, and the closed lid's own lashes take their place.
_g=gaussian_filter(a.mean(2),1.0); _gb=gaussian_filter(_g,6)
corner_lash=np.clip((_gb-_g)*8,0,1)*smooth(40,80,yy)*smooth(L1[None,:]+90,L1[None,:]+20,yy)
corner_lash=gaussian_filter(corner_lash,1.0)*smooth(820,900,xx)
from scipy.ndimage import grey_closing
_clos=np.stack([grey_closing(a[...,c],size=(11,11)) for c in range(3)],-1)
_soft=gaussian_filter(_clos,(2.5,2.5,0))
_hair=np.clip((_soft.mean(2)-_g)*9,0,1)
corner_lash=np.maximum(corner_lash,gaussian_filter(_hair,1.2)*smooth(40,80,yy)*smooth(L1[None,:]+90,L1[None,:]+20,yy)*smooth(900,1000,xx))
def frame(scale,b,e_low=0.0,skew=0.0,extra_dim=0.0,base=None,ref=None):
    base=E.pupil_frame(scale) if base is None else base
    ref=base if ref is None else ref
    if e_low>0:
        rgb,al=E.lower_overlay(e_low); base=base*(1-al[...,None])+rgb*al[...,None]
    travel=np.maximum(L1-E0,0)
    d=np.clip(b*travel*(1+skew*(x/W-0.45)*2),0,travel)
    edge=E0+d
    _sm=gaussian_filter(np.pad(edge,60,mode='edge'),18)[60:-60]
    edge=np.clip(edge+(_sm-edge)*float(4*b*(1-b)),E0,L1)
    d=edge-E0
    wc=float(smooth(0.15,0.65,b))
    # the skin above the lid comes down a little with it (the crease deepens)
    top0=ux-40; top=top0+gaussian_filter(np.minimum(0.30*d,0.45*np.maximum(top0,0)),30)   # smooth along x: no seams
    ys_above=np.where(yy<top[None,:],yy*(top0/np.maximum(top,1))[None,:],yy)
    moved=np.stack([map_coordinates(ref[...,c],[ys_above,xx],order=1,mode='nearest') for c in range(3)],-1)
    out=np.where((yy<top[None,:])[...,None],moved,base)
    cw=float(smooth(0.20,0.80,b))*corner_lash
    out=out*(1-cw[...,None])+_soft*cw[...,None]
    # original lashes ride down with the lid, fading as the closed lid's own lashes take over
    # the lid: skin of the closed lid, its margin on our edge line
    msrc=mc+ROOT
    k=np.minimum(1.0,(msrc-25)/np.maximum(edge-top,1))[None,:]
    sy=msrc[None,:]-(edge[None,:]-yy)*k; sx=np.broadcast_to(xc[None,:],yy.shape)
    lid=np.stack([map_coordinates(Cm[...,c],[sy,sx],order=1,mode='nearest') for c in range(3)],-1)
    v=np.clip((yy-top[None,:])/np.maximum(edge-top,1)[None,:],0,1)
    lid=lid*(0.84+0.20*np.sin(np.pi*(0.1+0.8*v)))[...,None]
    ti=np.clip(top-6,0,H-1).astype(int); tj=np.clip(top+22,0,H-1).astype(int); cols=np.arange(W)
    skin_above=gaussian_filter(moved[ti,cols],(18,0)); lid_top=gaussian_filter(lid[tj,cols],(18,0))
    gain=np.clip(skin_above/np.maximum(lid_top,1e-3),0.35,1.6)
    gw=np.clip(1-(yy-top[None,:])/np.maximum((edge-top)*0.55,1)[None,:],0,1)**1.5
    lid=lid*(1+(gain[None,:,:]-1)*gw[...,None])
    lid_a=(yy<edge[None,:]).astype(np.float32)*smooth(-45,25,yy-top[None,:])
    lid_a=lid_a*np.clip(d/6,0,1)[None,:]*smooth(OUTER+60,OUTER+20,x)[None,:]
    lid_a=np.minimum(lid_a,np.clip((edge[None,:]-yy)/1.5,0,1)+(yy<edge[None,:]-1.5))
    # above the eye's opening the lid ends softly at the corner (inside the opening it must cover all)
    lid_a=np.where(yy>=E0[None,:],lid_a,lid_a*smooth(1048,985,x)[None,:])
    shadow=np.clip(1-(yy-edge[None,:])/30,0,1)*(yy>=edge[None,:])*0.5*smooth(0.0,0.25,b)*np.clip(travel/25,0,1)[None,:]
    out=out*(1-shadow[...,None])
    out=out*(1-lid_a[...,None])+lid*lid_a[...,None]
    # the photo's own lashes stay rooted on the margin and point up over the lid, shortening as they turn down
    fs=1.0-0.45*b
    ol=map_coordinates(lashm,[E0[None,:]-(edge[None,:]-yy)/fs,xx],order=1,mode='constant',cval=0)
    ol=ol*smooth(edge[None,:]+3,edge[None,:]-2,yy)*(1-wc)
    out=out*(1-ol[...,None]*0.95)+LASH_RGB*ol[...,None]*0.95
    # the closed lid's own lashes hang from the edge
    ly=msrc[None,:]+(yy-edge[None,:])*1.35
    la=map_coordinates(lash_c,[ly,sx],order=1,mode='constant',cval=0)*(yy>=edge[None,:]-3)*wc*smooth(OUTER-15,OUTER-120,x)[None,:]
    out=out*(1-la[...,None]*0.95)+LASH_RGB*la[...,None]*0.95
    out=out*(1-extra_dim)
    return np.clip(out,0,1)

if __name__=="__main__":
    shots=[("b0",1.0,0.0),("b30",1.0,0.3),("b60",1.0,0.6),("b85",1.0,0.85),("b100",1.0,1.0)]
    ims=[]
    for n,sc,b in shots:
        im=E.vignette(frame(sc,b)); Image.fromarray((im*255).astype(np.uint8)).save(f"k_{n}.png"); ims.append(n); print(n,flush=True)
