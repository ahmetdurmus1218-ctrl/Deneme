import numpy as np
from PIL import Image, ImageDraw
S=0.576   # displayed(2000 wide) -> working(1152 wide)
UP=[(0,420),(200,330),(600,300),(1000,290),(1400,330),(1650,430),(1795,690)]
LO=[(0,980),(200,1020),(600,1150),(1000,1195),(1400,1160),(1650,1000),(1795,690)]
def curve(pts,W):
    xs=np.array([p[0] for p in pts])*S; ys=np.array([p[1] for p in pts])*S
    x=np.arange(W); y=np.interp(x,xs,ys)   # flat beyond the last point
    # smooth
    k=np.ones(41)/41; yp=np.pad(y,20,mode='edge'); return np.convolve(yp,k,'valid')
def load():
    im=Image.open('src.jpg').convert('RGB'); W=1152; H=round(im.height*W/im.width)
    return np.asarray(im.resize((W,H),Image.LANCZOS)).astype(np.float32)/255
def pupil(a):
    g=a.mean(2); H,W=g.shape
    cy,cx=int(663*S),int(1015*S); r=90
    sub=g[cy-r:cy+r,cx-r:cx+r]; m=sub<0.12
    ys,xs=np.nonzero(m); c=(xs.mean()+cx-r, ys.mean()+cy-r); rad=np.sqrt(m.sum()/np.pi)
    return c,rad
if __name__=="__main__":
    a=load(); H,W=a.shape[:2]; u=curve(UP,W); l=curve(LO,W); (px,py),pr=pupil(a)
    print("size",W,H,"pupil",round(px,1),round(py,1),round(pr,1))
    im=Image.fromarray((a*255).astype(np.uint8)); d=ImageDraw.Draw(im)
    d.line(list(zip(range(W),u)),fill=(0,255,0),width=2); d.line(list(zip(range(W),l)),fill=(0,160,255),width=2)
    d.ellipse([px-pr,py-pr,px+pr,py+pr],outline=(255,0,0),width=2)
    for R in (257,220): d.ellipse([px-R,py-R,px+R,py+R],outline=(255,255,0),width=1)
    im.save('geom.png')
