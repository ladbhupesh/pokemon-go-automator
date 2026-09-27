import json, numpy as np
from collections import defaultdict
import prop, labels3
items=json.load(open('lab3/items.json'))
F=prop.F
byframe=defaultdict(list)
for i,it in enumerate(items): byframe[it['frame']].append(i)
X=[];y=[];g=[];idx=[]
def iou(a,b):
    ix=max(0,min(a[2],b[2])-max(a[0],b[0])); iy=max(0,min(a[3],b[3])-max(a[1],b[1])); I=ix*iy
    return I/((a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-I+1e-6)
for fi,(f,ids) in enumerate(sorted(byframe.items())):
    img,a=prop.load(f); P,cs=prop.propose(a)
    for c in cs:
        bb=[v*F for v in c['box']]
        best=max(ids,key=lambda i: iou(bb,items[i]['box']))
        if iou(bb,items[best]['box'])<0.5 or best in labels3.SKIP: continue
        X.append(prop.features(P,c)); y.append(int(best in labels3.POS)); g.append(300+fi//5); idx.append(best)
np.savez("train3.npz",X=np.array(X),y=np.array(y),g=np.array(g),idx=np.array(idx))
print(len(y), sum(y))
