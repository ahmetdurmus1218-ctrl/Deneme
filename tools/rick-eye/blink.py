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
E0=ux_s*(1-smooth(780,960,x))+(U-8)*smooth(780,960,x)   # where the moving lid edge starts: calm, into the corner
def frame(scale,b,e_low=0.0,skew=0.0,extra_dim=0.0,base=None,ref=None):
    base=E.pupil_frame(scale) if base is None else base
    ref=base if ref is None else ref
    if e_low>0:
        rgb,al=E.lower_overlay(e_low); base=base*(1-al[...,None])+rgb*al[...,None]
    travel=np.maximum(l-E0,0)*smooth(OUTER+20,OUTER-40,x)
    d=np.clip(b*travel*(1+skew*(x/W-0.45)*2),0,travel); d=gaussian_filter(d,6)
    edge=E0+d
    wc=float(smooth(0.30,0.80,b))
    # the skin above the lid comes down a little with it (the crease deepens)
    top0=ux-40; top=top0+np.minimum(0.30*d,0.45*np.maximum(top0,0))
    ys_above=np.where(yy<top[None,:],yy*(top0/np.maximum(top,1))[None,:],yy)
    moved=np.stack([map_coordinates(ref[...,c],[ys_above,xx],order=1,mode='nearest') for c in range(3)],-1)
    out=np.where((yy<top[None,:])[...,None],moved,base)
    # original lashes ride down with the lid, fading as the closed lid's own lashes take over
    ol=map_coordinates(lashm,[yy-d[None,:],xx],order=1,mode='constant',cval=0)*(1-wc)
    out=out*(1-ol[...,None]*0.95)+LASH_RGB*ol[...,None]*0.95
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
    shadow=np.clip(1-(yy-edge[None,:])/30,0,1)*(yy>=edge[None,:])*0.5*smooth(0.0,0.25,b)
    out=out*(1-shadow[...,None])
    out=out*(1-lid_a[...,None])+lid*lid_a[...,None]
    # the closed lid's own lashes hang from the edge
    ly=msrc[None,:]+(yy-edge[None,:])*1.35
    la=map_coordinates(lash_c,[ly,sx],order=1,mode='constant',cval=0)*(yy>=edge[None,:]-3)*wc*smooth(OUTER-10,OUTER-170,x)[None,:]
    out=out*(1-la[...,None]*0.95)+LASH_RGB*la[...,None]*0.95
    out=out*(1-extra_dim)
    return np.clip(out,0,1)

if __name__=="__main__":
    shots=[("b0",1.0,0.0),("b30",1.0,0.3),("b60",1.0,0.6),("b85",1.0,0.85),("b100",1.0,1.0)]
    ims=[]
    for n,sc,b in shots:
        im=E.vignette(frame(sc,b)); Image.fromarray((im*255).astype(np.uint8)).save(f"k_{n}.png"); ims.append(n); print(n,flush=True)
