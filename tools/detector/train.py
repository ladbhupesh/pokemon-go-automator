import json, numpy as np
from collections import defaultdict
import prop, labels
from sklearn.ensemble import GradientBoostingClassifier, RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import GroupKFold
items=json.load(open('lab/items.json'))
F=prop.F
byframe=defaultdict(list)
for i,it in enumerate(items): byframe[it['frame']].append(i)
X=[];y=[];g=[];idx=[]
for fi,(f,ids) in enumerate(sorted(byframe.items())):
    img,a=prop.load(f); P,cs=prop.propose(a)
    # match proposals to item ids by box
    def iou(a,b):
        ix=max(0,min(a[2],b[2])-max(a[0],b[0])); iy=max(0,min(a[3],b[3])-max(a[1],b[1])); I=ix*iy
        return I/((a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-I+1e-6)
    for c in cs:
        bb=[v*F for v in c['box']]
        best=max(ids,key=lambda i: iou(bb,items[i]['box']))
        if iou(bb,items[best]['box'])<0.5 or best in labels.SKIP: continue
        X.append(prop.features(P,c)); y.append(int(best in labels.POS)); g.append(fi//6); idx.append(best)
X=np.array(X);y=np.array(y);g=np.array(g);idx=np.array(idx)
np.savez('train.npz',X=X,y=y,g=g,idx=idx)
print(X.shape, y.sum())
for name,mk in [('lr',lambda: LogisticRegression(max_iter=5000,C=1.0)),('rf',lambda: RandomForestClassifier(200,min_samples_leaf=2,random_state=0)),('gb',lambda: GradientBoostingClassifier(n_estimators=150,max_depth=3,random_state=0))]:
    p=np.zeros(len(y))
    for tr,te in GroupKFold(5).split(X,y,g):
        m=mk(); 
        Xt=X[tr]; 
        if name=='lr':
            mu,sd=Xt.mean(0),Xt.std(0)+1e-6; m.fit((Xt-mu)/sd,y[tr]); p[te]=m.predict_proba((X[te]-mu)/sd)[:,1]
        else:
            m.fit(Xt,y[tr]); p[te]=m.predict_proba(X[te])[:,1]
    for th in (0.5,0.7,0.85):
        tp=((p>=th)&(y==1)).sum(); fp=((p>=th)&(y==0)).sum(); fn=((p<th)&(y==1)).sum()
        print(f'{name} th={th} prec={tp/max(tp+fp,1):.3f} rec={tp/max(tp+fn,1):.3f} fp={fp} fn={fn}')
    np.save(f'cv_{name}.npy',p)
