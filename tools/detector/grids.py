import glob, json, os, sys
from PIL import Image, ImageDraw
import prop
F=prop.F
frames=sorted(glob.glob(sys.argv[1]))
outdir=sys.argv[2]; os.makedirs(outdir,exist_ok=True)
items=[]
for f in frames:
    img,a=prop.load(f)
    P,cs=prop.propose(a)
    for c in cs:
        x0,y0,x1,y1=[v*F for v in c['box']]
        items.append(dict(frame=f,box=[int(x0),int(y0),int(x1),int(y1)],cx=float(c["cx"]*F),cy=float(c["cy"]*F)))
print(len(items),'candidates from',len(frames),'frames')
json.dump(items,open(os.path.join(outdir,'items.json'),'w'))
T=150; C=8; R=5
cache={}
for g in range(0,len(items),C*R):
    sheet=Image.new('RGB',(C*T,R*T),(0,0,0)); d=ImageDraw.Draw(sheet)
    for k,it in enumerate(items[g:g+C*R]):
        img=cache.get(it['frame']) or Image.open(it['frame']).convert('RGB'); cache={it['frame']:img}
        x0,y0,x1,y1=it['box']; cx,cy=(x0+x1)/2,(y0+y1)/2; side=max(x1-x0,y1-y0,60)*1.8
        crop=img.crop((int(cx-side/2),int(cy-side/2),int(cx+side/2),int(cy+side/2))).resize((T-4,T-4))
        cd=ImageDraw.Draw(crop); s=(T-4)/side
        cd.rectangle(((x0-cx)*s+(T-4)/2,(y0-cy)*s+(T-4)/2,(x1-cx)*s+(T-4)/2,(y1-cy)*s+(T-4)/2),outline=(0,255,0),width=2)
        px,py=(k%C)*T,(k//C)*T
        sheet.paste(crop,(px+2,py+2))
        d.rectangle((px+2,py+2,px+40,py+26),fill=(0,0,0)); d.text((px+5,py+3),str(g+k),fill=(255,255,0),font_size=20)
    sheet.save(os.path.join(outdir,f'grid{g//(C*R):03d}.png'))
