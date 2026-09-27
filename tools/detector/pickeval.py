"""Cross-validated 'first pick' accuracy: per frame, apply the on-device ordering to CV scores."""
import numpy as np, sys
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.model_selection import GroupKFold
a=np.load('train.npz'); b=np.load('train2.npz'); e=np.load('extra.npz')
X=np.vstack([a['X'],b['X'],e['X']]); y=np.concatenate([a['y'],b['y'],e['y']])
src=np.concatenate([np.zeros(len(a['y'])),np.ones(len(b['y'])),np.full(len(e['y']),2)])
g=np.concatenate([a['g'],b['g'],100+np.arange(len(e['y']))//20])
# frame id: groups are frame//6 (area1) and frame//5 (area2); rebuild exact frame ids from feature order
w=np.where(src==2,2.0,1.0); w[y==1]*=float(sys.argv[1]) if len(sys.argv)>1 else 2
p=np.zeros(len(y))
for tr,te in GroupKFold(8).split(X,y,g):
    m=GradientBoostingClassifier(n_estimators=250,max_depth=3,random_state=0).fit(X[tr],y[tr],sample_weight=w[tr]); p[te]=m.predict_proba(X[te])[:,1]
np.save('cvp.npy',p)
for th in (0.85,0.9,0.95,0.98):
    ok=(X[:,0]>=40)&(X[:,17]>0.12)&(X[:,17]<0.855)&(p>=th)
    for s,n in ((0,'area1'),(1,'area2')):
        k=(src==s)&ok
        print(f'th={th} {n}: accepted={k.sum()} wrong={int((k&(y==0)).sum())}  positives kept={int((k&(y==1)).sum())}/{int(((src==s)&(y==1)).sum())}')
