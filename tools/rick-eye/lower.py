# Lower lid rising (a smile reaches the eyes): the skin under the eye slides up over the eyeball.
import numpy as np
from scipy.ndimage import gaussian_filter, map_coordinates
import eyeanim as E
from blink import smooth
a,H,W,xx,yy,x,l=E.a,E.H,E.W,E.xx,E.yy,E.x,E.l
X0,X1=-40.0,1035.0
_t=np.clip((x-X0)/(X1-X0),0,1)
TAPER=np.clip(np.sin(np.pi*_t),0,1)**0.8                      # nothing at the corners, most under the iris
RMAX=95.0
def lower(e):
    """RGB and alpha of the lower lid raised by e (0 … 1)."""
    rise=e*RMAX*TAPER
    edge=gaussian_filter(l-rise,8)
    sy=H-(H-yy)*((H-l)/np.maximum(H-edge,1))[None,:]
    rgb=np.stack([map_coordinates(a[...,c],[sy,xx],order=1,mode='nearest') for c in range(3)],-1)
    d=yy-edge[None,:]
    # a soft wet margin: slightly dark right at the edge, then the lid's skin
    rgb=rgb*(1-0.35*np.exp(-np.maximum(d,0)/3.0))[...,None]
    alpha=np.clip(d/3.0+0.5,0,1)*(rise[None,:]>0.5)
    # the eyeball just above the rising lid is a little shaded
    shade=np.clip(1+d/14,0,1)*(d<0)*0.22*min(1,e*3)*np.clip(rise/20,0,1)[None,:]
    return rgb,alpha,shade
