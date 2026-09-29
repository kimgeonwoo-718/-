#!/usr/bin/env python3
"""
세로로 긴 테마 그림을 키보드용으로 **옆으로 넓힌다** — 그림은 자르지 않는다.

    python3 tools/theme/widen.py 원본.webp keyboard/src/main/res/drawable-nodpi/sealion_photo.webp

키보드는 가로로 넓고(약 1.2:1, 가로 모드면 3:1) 그림은 세로로 길다. 키보드가 그림을 **높이에 맞춰**
깔도록(KeyboardView.updateBackground 의 가운데 자르기) 그림을 3:1 로 넓혀 둔다. 넓힌 옆은 그림
가장자리의 물 색을 줄마다 이어 붙이고(바다사자 지느러미가 닿은 줄은 위아래에서 잇는다) 흐리게 해서
이음매를 감춘다. 그러면 어느 비율의 키보드에서도 잘리는 건 넓힌 옆뿐이고 그림 자체는 다 보인다.

원본은 저장소에 넣지 않는다. 사용자가 AI 로 만든 그림이다(HANDOFF '바다사자 테마 배경').
"""
import sys
import numpy as np
from PIL import Image, ImageFilter
src=Image.open(sys.argv[1]).convert('RGB')
H=1000
src=src.resize((round(src.width*H/src.height),H),Image.LANCZOS)
a=np.asarray(src).astype(np.float32); w=a.shape[1]
W=int(H*3.0); pad=(W-w)//2
def water(px):  # 파란 물 픽셀인가 (바다사자·지느러미 제외)
    r,g,b=px[...,0],px[...,1],px[...,2]
    return (b>r+40)|((r>200)&(g>200)&(b>200))
def side_colors(cols):
    out=np.zeros((H,3),np.float32); ok=np.zeros(H,bool)
    for y in range(H):
        px=a[y,cols]; m=water(px)
        if m.sum()>=5: out[y]=px[m].mean(0); ok[y]=True
    # 지느러미에 가린 줄은 위아래에서 잇는다
    idx=np.arange(H)
    for c in range(3): out[:,c]=np.interp(idx,idx[ok],out[ok,c])
    return out
L=side_colors(slice(0,25)); R=side_colors(slice(w-25,w))
canvas=np.zeros((H,W,3),np.float32)
canvas[:,:pad]=L[:,None,:]; canvas[:,pad+w:]=R[:,None,:]
canvas[:,pad:pad+w]=a
img=Image.fromarray(canvas.clip(0,255).astype(np.uint8))
# 옆을 흐리게(물결 느낌), 가운데 원본은 그대로. 이음매는 40px 에 걸쳐 섞는다.
blur=np.asarray(img.filter(ImageFilter.GaussianBlur(18))).astype(np.float32)
mask=np.zeros(W,np.float32); mask[pad:pad+w]=1
mask=np.asarray(Image.fromarray((mask[None,:].repeat(4,0)*255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(14))).astype(np.float32)[0]/255
mask=np.maximum(mask, 0); m=mask[None,:,None]
# 원본 영역 안쪽은 원본 100%
inner=np.zeros(W,bool); inner[pad+30:pad+w-30]=True; m=np.where(inner[None,:,None],1.0,m)
out=canvas*m+blur*(1-m)
Image.fromarray(out.clip(0,255).astype(np.uint8)).save(sys.argv[2], 'WEBP', quality=86, method=6)
print(W,H,pad,w)
