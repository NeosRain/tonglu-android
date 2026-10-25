"""Export the fixed Tomato mascot launcher icon. Requires Pillow; never changes components.

Source artwork: app/src/main/artwork/tomato-icon.png (built-in imagegen, original character refs).
Adaptive foreground/background remain separate. Foreground is normalized into the centered
66dp safe circle in a 108dp canvas; legacy Android24/25 gets the same artwork in five densities.
"""
from pathlib import Path
import argparse,json,math
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1]
MAIN=ROOT/'apps/android/app/src/main'
RES=MAIN/'res'
DENSITIES={'mdpi':48,'hdpi':72,'xhdpi':96,'xxhdpi':144,'xxxhdpi':192}
BACKGROUND=(255,240,220,255)

def foreground():
    source=Image.open(MAIN/'artwork/tomato-icon.png').convert('RGBA')
    alpha=source.getchannel('A');bounds=alpha.getbbox();assert bounds
    source=source.crop(bounds)
    w,h=source.size;cx=w/2;cy=h/2
    points=[(x,y) for y in range(h) for x in range(w) if source.getpixel((x,y))[3]>=16]
    radius=max(math.hypot(x-cx,y-cy) for x,y in points)
    scale=128/radius
    size=(round(w*scale),round(h*scale))
    mark=source.resize(size,Image.Resampling.LANCZOS)
    layer=Image.new('RGBA',(432,432));layer.alpha_composite(mark,((432-size[0])//2,(432-size[1])//2))
    return layer

def icon(layer,size,round_mask=False,store=False):
    # Adaptive launchers display the center 72dp of a 108dp layer.
    portrait=layer.crop((72,72,360,360)).resize((size,size),Image.Resampling.LANCZOS)
    image=Image.new('RGBA',(size,size),BACKGROUND);image.alpha_composite(portrait)
    if not store:
        mask=Image.new('L',(size*4,size*4));d=ImageDraw.Draw(mask)
        if round_mask:d.ellipse((0,0,size*4-1,size*4-1),fill=255)
        else:d.rounded_rectangle((0,0,size*4-1,size*4-1),radius=round(size*.22*4),fill=255)
        image.putalpha(mask.resize((size,size),Image.Resampling.LANCZOS))
    return image

def main():
    p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');p.add_argument('--preview',type=Path);args=p.parse_args()
    layer=foreground()
    if not args.check:layer.save(RES/'drawable-nodpi/ic_launcher_portrait.png')
    for density,size in DENSITIES.items():
        for rounded in [False,True]:
            name='ic_launcher_round.png' if rounded else 'ic_launcher.png'
            path=RES/('mipmap-'+density)/name
            if not args.check:icon(layer,size,rounded).save(path)
            else:assert Image.open(path).size==(size,size)
            print(f'{density}/{name}: {size}px')
    if args.preview:icon(layer,512).save(args.preview)
    if not args.check:icon(layer,512,store=True).save(MAIN/'artwork/store-icon-512.png')
    print('Fixed portrait, safe-circle radius <=33dp; no app-managed day/night variants')
if __name__=='__main__':main()
