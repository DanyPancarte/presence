import sys
from PIL import Image, ImageDraw, ImageFont
out, *items = sys.argv[1:]
ims = [Image.open(p).convert('RGB') for p in items[::2]]
labels = items[1::2]
w, h = ims[0].size
crop = (0, int(h*0.22), w, int(h*0.78))
ims = [im.crop(crop) for im in ims]
cw, ch = ims[0].size
sheet = Image.new('RGB', (cw*len(ims), ch+60), 'black')
d = ImageDraw.Draw(sheet)
try: font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 34)
except: font = None
for i, (im, lb) in enumerate(zip(ims, labels)):
    sheet.paste(im, (i*cw, 60))
    d.text((i*cw+20, 12), lb, fill=(255,183,77), font=font)
sheet.save(out, quality=90)
