# Exports the raised lower lid (a smile reaching the eyes) as overlay frames for the app: assets/rick-eye/eye_low_N.webp.
import json, numpy as np
from PIL import Image
import eyeanim as E, lower as Lo
OUT="/home/user/Deneme/assets/rick-eye"
S=864/E.W; OW,OH=864,round(E.H*S)
def save_rgba(rgb,alpha,name,q=88):
    im=np.dstack([np.clip(rgb,0,1),np.clip(alpha,0,1)])
    Image.fromarray((im*255+0.5).astype(np.uint8),"RGBA").resize((OW,OH),Image.LANCZOS).save(f"{OUT}/{name}",quality=q,method=6)
LOW=[0.0,0.35,0.7,1.0]
for i,e in enumerate(LOW):
    if i==0: continue
    rgb,al,sh=Lo.lower(e)
    o0=rgb*al[...,None]                                   # over black
    mul=(1-sh)*(1-al)                                      # what is left of the eye under it
    a=1-mul
    col=np.where(a[...,None]>0.004,o0/np.maximum(a[...,None],1e-3),0)
    save_rgba(col,a,f"eye_low_{i}.webp"); print("low",i,e,flush=True)
p=f"{OUT}/eye.json"; g=json.load(open(p)); g["lows"]=LOW; json.dump(g,open(p,"w"),ensure_ascii=False,indent=1)
