# Bakes the eye's edge fade into the alpha of the lid layers (eye_lid_NN, eye_low_N), so the app need not erase
# the edges on every frame (it fades the eye picture once per pupil size). Run once after export.py and
# export_lower.py. The fade is the same as the app's: an elliptical gradient plus 14 % top/bottom and 10 % sides.
import json, sys, glob, numpy as np
from PIL import Image
D=sys.argv[1] if len(sys.argv)>1 else "assets/rick-eye"
g=json.load(open(f"{D}/eye.json")); W,H=g["width"],g["height"]; v=g["vignette"]
yy,xx=np.mgrid[0:H,0:W]+0.5
R=v["rx"]*1.25; Ry=R*v["ry"]/v["rx"]
r=np.hypot((xx-v["cx"])/R,(yy-v["cy"])/Ry)
a_r=np.interp(r,[0,.2,.4,.6,.8,1],[0,0,.37,.67,.89,1])
ex0,ey0,ew,eh=-1.0,-1.0,W+2.0,H+2.0
fade,side=eh*0.14,ew*0.10
a_t=np.clip(1-(yy-ey0)/fade,0,1); a_b=np.clip(1-(ey0+eh-yy)/fade,0,1)
a_l=np.clip(1-(xx-ex0)/side,0,1); a_rt=np.clip(1-(ex0+ew-xx)/side,0,1)
M=(1-a_r)*(1-a_t)*(1-a_b)*(1-a_l)*(1-a_rt)
files=sorted(glob.glob(f"{D}/eye_lid_*.webp")+glob.glob(f"{D}/eye_low_*.webp"))
for f in files:
    im=np.asarray(Image.open(f).convert("RGBA")).astype(np.float32)
    im[...,3]*=M
    Image.fromarray((im+0.5).clip(0,255).astype(np.uint8),"RGBA").save(f,quality=88,method=6)
    print("faded",f)
g["faded"]=True; json.dump(g,open(f"{D}/eye.json","w"),ensure_ascii=False,indent=1)
