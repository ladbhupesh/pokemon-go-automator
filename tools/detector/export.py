import pickle, json, numpy as np, sys
from scipy.special import expit
import prop
m=pickle.load(open('gb.pkl','rb'))
init=float(m._raw_predict_init(np.zeros((1,m.n_features_in_),np.float32))[0,0])
lines=[f'gb {m.n_features_in_} {len(m.estimators_)} {float(m.learning_rate)!r} {float(init)!r}']
for est in m.estimators_[:,0]:
    t=est.tree_
    lines.append(f'tree {t.node_count}')
    for i in range(t.node_count):
        lines.append(f'{int(t.feature[i])} {float(t.threshold[i])!r} {int(t.children_left[i])} {int(t.children_right[i])} {float(t.value[i][0][0])!r}')
open(sys.argv[1],'w').write('\n'.join(lines)+'\n')
# self-check the exported evaluator
def ev(x):
    raw=init; k=1
    trees=[]
    for est in m.estimators_[:,0]:
        t=est.tree_; n=0
        while t.children_left[n]!=-1:
            n=t.children_left[n] if np.float32(x[t.feature[n]])<=t.threshold[n] else t.children_right[n]
        raw+=m.learning_rate*t.value[n][0][0]
    return expit(raw)
d=np.load('train.npz'); X=d['X'][:50].astype(np.float32)
print('max diff', max(abs(ev(x)-p) for x,p in zip(X,m.predict_proba(X)[:,1])))
# parity fixtures
fx=[]
for f in sys.argv[2:]:
    img,a=prop.load(f); P,cs=prop.propose(a)
    fx.append(dict(frame=f, cands=[dict(box=[int(v) for v in c['box']],area=c['area'],cx=c['cx'],cy=c['cy'],features=[float(v) for v in prop.features(P,c)],p=float(m.predict_proba([prop.features(P,c)])[0,1])) for c in cs]))
json.dump(fx,open('parity.json','w'))
print('fixtures',[len(x['cands']) for x in fx])
