#!/usr/bin/env python3
"""Build an auditable cutout rig from the approved reference, never from a generic avatar.

Only source pixels are used for character textures. Narrow facial feature beds are
inpainted so independently controlled eyes/brows/mouth do not leave doubled features.
The small front/back reference poses are identified as scene-distance assets, not HD.
No model, credential, conversation, or renderer binary is read or changed.
"""
import argparse, hashlib, json
from pathlib import Path
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

REFERENCE_SHA = '3fd9526712304ab6c9411a4be90dbb7ed7e548d485215944b811694b2c272f40'

def build(source: Path, destination: Path):
    raw = source.read_bytes()
    if hashlib.sha256(raw).hexdigest() != REFERENCE_SHA:
        raise ValueError('This rig requires the exact approved reference bytes')
    im = Image.open(source).convert('RGB')
    # Remove only the white reference-sheet letter lying across the hair silhouette.
    pixels=np.array(im); lettering=np.zeros(pixels.shape[:2],dtype=np.uint8)
    region=pixels[106:120,200:216];lettering[106:120,200:216]=np.where(np.min(region,axis=2)>180,255,0).astype(np.uint8)
    im=Image.fromarray(cv2.inpaint(pixels,lettering,3,cv2.INPAINT_TELEA))
    if im.size != (1024, 1536): raise ValueError('Reference dimensions changed')
    destination.mkdir(parents=True, exist_ok=True)
    entries = []
    W,H=im.size
    def mask(points, feather=.7):
        m=Image.new('L',(W,H)); ImageDraw.Draw(m).polygon(points, fill=255)
        return m.filter(ImageFilter.GaussianBlur(feather)) if feather else m
    def layer(name, points, pivot, parent='', order=0, image=None, scale=1, space='hero', feather=.7):
        m=mask(points,feather); box=m.getbbox()
        if box is None:raise ValueError(name)
        texture=(image or im).convert('RGBA');texture.putalpha(m)
        p=destination/(name+'.png');texture.crop(box).save(p,optimize=True)
        entries.append(dict(name=name,file=p.name,bounds=list(box),pivot=list(pivot),parent=parent,
                            order=order,space=space,sourcePixels=True,sha256=hashlib.sha256(p.read_bytes()).hexdigest()))
    # Character-only silhouettes. Menu pixels and old buttons are outside these masks.
    layer('hair_back',[(313,54),(371,67),(419,109),(447,167),(463,198),(467,257),(480,340),(488,398),(504,509),(513,569),(532,627),(541,679),(538,696),(526,683),(506,626),(492,576),(488,634),(496,686),(484,695),(465,648),(454,566),(458,481),(438,421),(392,365),(310,355),(251,368),(245,411),(223,482),(198,512),(180,566),(166,624),(170,676),(185,711),(169,704),(146,675),(139,626),(141,586),(116,640),(115,674),(106,657),(105,617),(126,563),(162,504),(190,456),(197,405),(217,355),(222,295),(198,251),(184,201),(186,159),(200,116),(245,74)],(327,343),order=0)
    layer('hair_left_tip',[(192,443),(177,502),(145,553),(119,618),(115,670),(140,697),(165,711),(148,682),(142,639),(159,590),(179,568),(202,507),(220,463)],(203,451),'hair_back',order=1)
    layer('hair_right_tip',[(469,415),(489,445),(501,513),(509,573),(530,640),(538,691),(528,687),(507,631),(494,592),(500,655),(493,691),(485,682),(479,624),(471,565)],(473,417),'hair_back',order=2)
    layer('leg_left',[(177,803),(224,876),(243,964),(260,1082),(84,1082),(107,968),(137,871)],(179,804),order=3)
    layer('leg_right',[(464,800),(502,854),(519,931),(529,1082),(348,1082),(391,989),(424,889)],(463,803),order=4)
    layer('dress_lower',[(169,779),(198,752),(277,737),(441,744),(479,773),(502,816),(516,868),(530,956),(546,1082),(71,1082),(103,980),(143,873),(166,806)],(332,764),order=5)
    # Shoulder/arm are real separate textures, overlapping at their joints.
    layer('arm_left_upper',[(191,350),(212,336),(240,341),(263,355),(278,380),(278,445),(280,514),(282,581),(270,646),(228,661),(205,630),(198,570),(201,501),(188,447),(177,405),(178,375)],(227,362),order=6)
    layer('arm_right_upper',[(449,405),(470,399),(491,409),(502,428),(502,484),(498,551),(493,595),(475,645),(443,643),(449,598),(459,548),(459,497),(447,442)],(475,421),order=7)
    # Reconstruct only the small forearm region hidden by the old speech bubble,
    # using skin tones sampled from the adjoining original upper forearm.
    restored=im.copy();rp=np.array(restored);left=np.array(im.getpixel((452,664)),dtype=float);right=np.array(im.getpixel((475,662)),dtype=float)
    for yy in range(684,779):
        for xx in range(433,487):
            t=max(0,min(1,(xx-440)/44));v=left*(1-t)+right*t;rp[yy,xx]=np.uint8(np.clip(v*(1-(yy-684)*.0004),0,255))
    restored=Image.fromarray(rp)
    layer('arm_right_lower',[(458,584),(489,579),(483,639),(483,679),(473,716),(451,754),(440,778),(431,775),(442,737),(447,700),(447,655)],(476,592),'arm_right_upper',order=8,image=restored)
    entries[-1]['sourcePixels']=False;entries[-1]['restoration']='Covered forearm region reconstructed from adjoining source skin tones; original upper region retained'
    layer('torso',[(276,363),(299,339),(317,339),(352,353),(378,347),(397,366),(430,396),(451,416),(474,470),(500,536),(500,585),(486,628),(452,682),(437,746),(482,799),(188,813),(202,765),(274,739),(288,676),(275,612),(272,533),(257,454),(259,399)],(339,590),order=9)
    layer('neck',[(300,309),(322,323),(350,330),(377,316),(394,291),(395,345),(398,367),(378,387),(350,394),(323,369),(300,348)],(345,356),'torso',order=10)
    # Erase only the source feature beds; each extracted feature remains original-pixel art.
    features={
      'eye_left':([(245,228),(263,223),(282,224),(302,233),(305,249),(296,261),(269,263),(250,254),(240,241)],(273,241)),
      'eye_right':([(343,216),(363,208),(385,208),(399,214),(397,233),(381,248),(360,247),(345,237),(337,228)],(370,227)),
      'brow_left':([(255,225),(271,222),(288,223),(301,226),(299,229),(282,227),(264,228)],(281,225)),
      'brow_right':([(325,193),(350,194),(371,191),(381,192),(381,197),(357,201),(337,201),(324,198)],(352,196)),
      'mouth_closed':([(318,296),(331,299),(345,297),(355,291),(361,292),(359,299),(342,304),(320,303)],(338,299))
    }
    erase=Image.new('L',(W,H));d=ImageDraw.Draw(erase)
    for pts,_ in features.values():d.polygon(pts,fill=255)
    erased=cv2.inpaint(np.array(im),cv2.dilate(np.array(erase),np.ones((5,5),np.uint8)),9,cv2.INPAINT_TELEA)
    clean=Image.fromarray(erased)
    head=[(193,151),(210,114),(251,76),(296,57),(324,54),(358,59),(391,77),(416,103),(434,130),(443,154),(443,178),(438,197),(432,227),(422,253),(413,279),(398,301),(375,320),(351,334),(322,328),(293,315),(271,302),(245,289),(223,273),(205,254),(194,227),(187,198),(189,173)]
    layer('head',head,(341,334),'neck',order=11,image=clean)
    layer('hair_side_left',[(220,268),(243,285),(248,318),(246,373),(236,420),(215,444),(183,474),(177,490),(181,457),(207,428),(218,391),(225,345),(220,301)],(228,282),'head',order=12)
    layer('hair_side_right',[(414,248),(435,220),(443,245),(447,290),(455,334),(470,381),(480,397),(465,397),(449,367),(435,323),(420,287)],(425,263),'head',order=13)
    for i,(name,(pts,pivot)) in enumerate(features.items()):layer(name,pts,pivot,'head',14+i,feather=1.0)
    layer('hand_left',[(204,626),(248,631),(277,651),(285,688),(288,725),(298,800),(291,830),(279,844),(260,823),(254,803),(239,820),(229,842),(217,838),(209,824),(209,785),(196,768),(173,785),(145,808),(128,810),(126,801),(146,770),(163,739),(176,714),(173,700),(159,688),(162,677),(184,673),(199,656)],(239,648),'arm_left_upper',order=20)
    # Authored front/back views from the actual reference, for short scene-distance actions.
    # These are deliberately not advertised as high-definition full-body textures.
    front=[(853,646),(857,624),(866,607),(880,603),(891,612),(895,631),(894,645),(902,662),(905,688),(907,705),(924,725),(921,731),(904,718),(896,694),(891,718),(900,747),(901,784),(895,822),(891,844),(882,846),(879,805),(875,782),(871,817),(867,846),(858,846),(857,823),(858,791),(850,805),(844,831),(839,840),(835,836),(842,809),(845,778),(842,747),(847,719),(850,692),(844,713),(833,733),(827,732),(831,721),(844,693),(846,671)]
    layer('walk_base',front,(876,716),space='walk',order=0)
    layer('walk_leg_left',[(861,749),(876,760),(871,795),(868,824),(867,847),(858,847),(857,827),(858,797)],(867,754),'walk_base',space='walk',order=1)
    layer('walk_leg_right',[(880,748),(895,740),(899,780),(890,818),(890,847),(882,847),(879,825),(875,801),(878,775)],(888,751),'walk_base',space='walk',order=2)
    # Remove stationary source legs from the base texture before bone animation.
    base=destination/'walk_base.png';bb=next(e for e in entries if e['name']=='walk_base')['bounds'];bi=Image.open(base).convert('RGBA');ba=np.array(bi)
    for nm in ('walk_leg_left','walk_leg_right'):
        e=next(e for e in entries if e['name']==nm);a=Image.open(destination/e['file']).getchannel('A');cut=Image.new('L',bi.size);cut.paste(a,(e['bounds'][0]-bb[0],e['bounds'][1]-bb[1]));ba[:,:,3]=np.minimum(ba[:,:,3],255-np.array(cut))
    Image.fromarray(ba).save(base,optimize=True);next(e for e in entries if e['name']=='walk_base')['sha256']=hashlib.sha256(base.read_bytes()).hexdigest()
    back=[(952,652),(953,630),(960,611),(973,602),(989,606),(1000,620),(1000,649),(995,677),(997,694),(1000,713),(1000,763),(997,795),(996,847),(943,847),(947,817),(944,793),(937,771),(936,749),(943,725),(939,708),(936,698),(940,681)]
    layer('turn_back',back,(973,717),space='turn',order=0)
    layer('turn_hair',[(953,628),(962,609),(978,603),(992,610),(999,625),(999,651),(994,678),(998,700),(985,714),(964,729),(950,733),(939,726),(946,701),(948,678)],(974,637),'turn_back',space='turn',order=1)
    doc={'schema':1,'referenceSha256':REFERENCE_SHA,'referenceSize':[W,H], 'heroDesignSize':[594,1082],
         'representation':'Independent original-art cutout layers with joint transforms; scene-distance authored front/back views',
         'faceTiming':'PCM-derived articulation estimates; no phoneme claim',
         'sceneDistanceSourceSizes':{'walking':[98,244],'turning':[64,245]},
         'limitations':['Single-reference cutout rig, not volumetric 3-D','Small scene-distance views are not HD full-body textures','Physical-phone visual acceptance required'],
         'layers':entries}
    (destination/'rig.json').write_text(json.dumps(doc,indent=2))
    print(f'Authored {len(entries)} independently addressable layers from verified original art')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('reference',type=Path);p.add_argument('output',type=Path);a=p.parse_args();build(a.reference,a.output)
