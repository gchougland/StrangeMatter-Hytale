"""Original tileable panes and ceiling lights for the recovered laboratory prefab."""
import json
from PIL import Image, ImageDraw
import build_assets as art

def main():
    original=art.paint
    def paint(w,h,mat,seed):
        if mat=='lab_diffuser':
            image=original(w,h,'cyan',seed);d=ImageDraw.Draw(image)
            if w>6 and h>6:
                for x in (w//4,3*w//4):d.line((x,0,x,h-1),fill=art.P['edge']+(255,),width=1)
            return image
        if mat!='lab_glass':return original(w,h,mat,seed)
        image=Image.new('RGBA',(w,h),(48,161,174,72));d=ImageDraw.Draw(image)
        if w>4 and h>4:
            d.line((2,h-4,w-4,2),fill=(143,242,233,125),width=1)
            d.line((4,h-4,w-4,4),fill=(102,212,224,84),width=1)
        return image
    art.paint=paint
    try:
        pane=art.Model('lab_cyan_glass',True)
        pane.box('cyan_laminated_glass',(0,16,0),(32,32,1.3),'lab_glass')
        for x in (-15.5,15.5):pane.box('vertical_lead_seam',(x,16,0),(1,32,2),'edge')
        for y in (.5,31.5):pane.box('horizontal_lead_seam',(0,y,0),(30,1,2),'edge')
        lamp=art.Model('lab_lamp',True)
        lamp.box('navy_ceiling_light',(0,16,0),(32,31.8,32),'navy')
        # Opposed luminous faces make either ceiling or wall placement readable.
        for y in (.05,31.95):
            lamp.box('diffuser',(0,y,0),(26,.1,26),'lab_diffuser',glow=True)
        entries=[pane.save(),lamp.save()]
        for entry in entries:art.render(entry,256).resize((64,64),Image.Resampling.LANCZOS).save(art.COMMON/entry['icon'])
        (art.OUT/'lab-details-catalog.json').write_text(json.dumps({'items':entries},indent=2)+'\n')
        art.contact(entries,art.ART/'laboratory-details.png',cols=2,cell=280)
        art.validate(entries)
    finally:art.paint=original

if __name__=='__main__':main()
