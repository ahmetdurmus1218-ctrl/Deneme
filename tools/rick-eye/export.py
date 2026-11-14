# Exports the living-eye layers for the app (assets/rick-eye): base, corneal reflection, lid frames, geometry.
import numpy as np, json, os
from PIL import Image
from scipy.ndimage import zoom
import blink as Bk, eyeanim as E
OUT="/home/user/Deneme/assets/rick-eye"; os.makedirs(OUT,exist_ok=True)
S=864/E.W; OW,OH=864,round(E.H*S)
def save_rgb(arr,name,q=92):
    Image.fromarray((np.clip(arr,0,1)*255+0.5).astype(np.uint8)).resize((OW,OH),Image.LANCZOS).save(f"{OUT}/{name}",quality=q)
def save_rgba(rgb,alpha,name,q=88):
    im=np.dstack([np.clip(rgb,0,1),np.clip(alpha,0,1)])
    Image.fromarray((im*255+0.5).astype(np.uint8),"RGBA").resize((OW,OH),Image.LANCZOS).save(f"{OUT}/{name}",quality=q,method=6)
ref=E.pupil_frame(1.0)
save_rgb(E.clean,"eye_base.jpg")
r=E.refl; save_rgba(E.clean,r,"eye_reflect.webp",q=92)
B=[0.0,0.05,0.10,0.17,0.24,0.30,0.36,0.42,0.48,0.54,0.60,0.66,0.72,0.78,0.83,0.88,0.92,0.955,0.98,1.0]
zero=np.zeros_like(ref); one=np.ones_like(ref)
for i,b in enumerate(B):
    o0=Bk.frame(1.0,b,base=zero,ref=ref); o1=Bk.frame(1.0,b,base=one,ref=ref)
    mul=np.clip((o1-o0).mean(2),0,1); a=1-mul
    rgb=np.where(a[...,None]>0.004,o0/np.maximum(a[...,None],1e-3),0)
    save_rgba(rgb,a,f"eye_lid_{i:02d}.webp"); print("lid",i,b,flush=True)
# vignette that blends the photo into Rick's dark background (alpha = how much background)
cx,cy=E.W*0.47,E.H*0.48
d=np.hypot((E.xx-cx)/(E.W*0.55),(E.yy-cy)/(E.H*0.60)); v=np.clip(1.25-d,0,1)**1.6
geo=dict(width=OW,height=OH,pupil_x=E.PX*S,pupil_y=E.PY*S,pupil_r=E.PR*S,warp_r=E.RW*S,lids=B,
         vignette=dict(cx=cx*S,cy=cy*S,rx=E.W*0.55*S,ry=E.H*0.60*S),
         credits=["Açık göz: Ylem, Wikimedia Commons, kamu malı (030608 Pupil.jpg)",
                  "Kapalı göz kapağı: Rapidreflex, Wikimedia Commons, CC BY-SA 4.0 (Closed human eye, superior view.jpg); Rick için renk ve şekil uyarlandı"])
json.dump(geo,open(f"{OUT}/eye.json","w"),ensure_ascii=False,indent=1)
# check: rebuild a frame from the exported layers, the way the app draws it
print(json.dumps({k:v for k,v in geo.items() if k!="credits"}))
