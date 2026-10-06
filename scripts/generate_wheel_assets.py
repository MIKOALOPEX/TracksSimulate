"""Convert the supplied textured Blockbench cubes, preserving face UVs and rotations.

Only generated resources are written. The original bbmodels remain untouched.
No image libraries or downloaded assets are required.
"""
import hashlib
import itertools
import json
import math
from pathlib import Path
import struct
import zlib
import argparse
import shutil

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/main/resources/assets/trackssimulate'
INPUTS = [('导轮.bbmodel', 'drive_wheel'), ('负重轮.bbmodel', 'road_wheel'),
          ('托带轮.bbmodel', 'return_wheel')]
FACES = {
    'west': [(0,1,0),(0,0,0),(0,0,1),(0,1,1)],
    'east': [(1,1,1),(1,0,1),(1,0,0),(1,1,0)],
    'down': [(0,0,1),(0,0,0),(1,0,0),(1,0,1)],
    'up': [(0,1,0),(0,1,1),(1,1,1),(1,1,0)],
    'north': [(1,1,0),(1,0,0),(0,0,0),(0,1,0)],
    'south': [(0,1,1),(0,0,1),(1,0,1),(1,1,1)],
}

def rotate(p, origin, angles):
    x,y,z = [p[i]-origin[i] for i in range(3)]
    for axis, a in enumerate(angles):
        c,s = math.cos(math.radians(a)), math.sin(math.radians(a))
        if axis == 0: y,z = c*y-s*z, s*y+c*z
        if axis == 1: x,z = c*x+s*z, -s*x+c*z
        if axis == 2: x,y = c*x-s*y, s*x+c*y
    return [x+origin[0],y+origin[1],z+origin[2]]

def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':'))+'\n', encoding='utf-8')

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--source',type=Path,default=ROOT.parent/'assets/Tracks')
    source=parser.parse_args().source
    manifest = []
    for filename, name in INPUTS:
        src = source / filename
        data = json.loads(src.read_text(encoding='utf-8'))
        textures=[Path(t['name']).stem for t in data['textures']]
        if any(t not in ('copycat_base','belt','rx') for t in textures):
            raise ValueError('Unknown texture slots: '+str(textures))
        groups = {g['uuid']: g for g in data.get('groups', [])}
        parents = {}
        def walk(items, chain):
            for item in items:
                if isinstance(item, str): parents[item] = chain
                else: walk(item.get('children', []), chain + [groups.get(item['uuid'], item)])
        walk(data.get('outliner', []), [])
        quads = []
        for e in data['elements']:
            if not e.get('export', True): continue
            chain = parents.get(e['uuid'], [])
            if any(not g.get('export', True) or not g.get('visibility', True) for g in chain): continue
            if e.get('type', 'cube') != 'cube' or e.get('rescale', False):
                raise ValueError('Unsupported element: '+e['name'])
            for face, corners in FACES.items():
                if face not in e['faces'] or e['faces'][face].get('texture') is None: continue
                face_data=e['faces'][face]
                points = []
                for corner in corners:
                    p = [e['to'][i] if corner[i] else e['from'][i] for i in range(3)]
                    p = rotate(p, e.get('origin',[0,0,0]), e.get('rotation',[0,0,0]))
                    for g in reversed(chain): p = rotate(p, g.get('origin',[0,0,0]), g.get('rotation',[0,0,0]))
                    # Shared source axle X, radial centre Y=9.3/Z=8; centre on shaft.
                    points.append([(p[0]-8)/16,(p[1]-9.3)/16,(p[2]-8)/16])
                a = [points[1][i]-points[0][i] for i in range(3)]
                b = [points[2][i]-points[0][i] for i in range(3)]
                n = [a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]]
                length = math.sqrt(sum(x*x for x in n))
                if length < 1e-10: continue
                uv=face_data['uv'];rotation=face_data.get('rotation',0)
                if rotation not in (0,90,180,270): raise ValueError('Invalid UV rotation')
                tex=data['textures'][face_data['texture']]
                uvs=[]
                for i in range(4):
                    j=(i+rotation//90)%4
                    uvs.extend([uv[0 if j in (0,1) else 2]/tex['width'],uv[1 if j in (0,3) else 3]/tex['height']])
                quads.append([round(x/length,7) for x in n]+[round(x,7) for p in points for x in p]+uvs+[face_data['texture']])
        radius = max(math.hypot(q[i+1],q[i+2]) for q in quads for i in (3,6,9,12))
        width = max(q[i] for q in quads for i in (3,6,9,12))-min(q[i] for q in quads for i in (3,6,9,12))
        write_json(OUT/'meshes'/f'{name}.json', dict(format=2,textures=textures,radius=radius,width=width,quads=quads))
        write_json(OUT/'models/item'/f'{name}.json', {
            'parent':'builtin/entity','textures':{'particle':'create:block/copycat_base'},
            'display':{
                'gui':{'rotation':[20,45,0],'translation':[0,0,0],'scale':[0.65,0.65,0.65]},
                'ground':{'translation':[0,3,0],'scale':[0.3,0.3,0.3]},
                'fixed':{'rotation':[0,90,0],'scale':[0.6,0.6,0.6]},
                'thirdperson_righthand':{'rotation':[75,45,0],'scale':[0.35,0.35,0.35]},
                'firstperson_righthand':{'rotation':[0,45,0],'scale':[0.5,0.5,0.5]}}})
        write_json(OUT/'blockstates'/f'{name}.json', {'variants':{'':{'model':'trackssimulate:block/mount'}}})
        loot={'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'trackssimulate:'+name}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]}
        write_json(ROOT/'src/main/resources/data/trackssimulate/loot_table/blocks'/f'{name}.json',loot)
        manifest.append(dict(source=str(src),sha256=hashlib.sha256(src.read_bytes()).hexdigest(),textures=textures,quads=len(quads),radius=radius,width=width))
    write_json(OUT/'models/block/mount.json', {'textures':{'particle':'create:block/copycat_base'},'elements':[]})
    for texture in ('belt','rx'):
        target=OUT/'textures/block'/f'{texture}.png';target.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(source/f'{texture}.png',target)
    def chunk(t,b): return struct.pack('>I',len(b))+t+b+struct.pack('>I',zlib.crc32(t+b))
    png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>2I5B',1,1,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b'\0\xff\xff\xff\xff'))+chunk(b'IEND',b'')
    path=OUT/'textures/block/white.png'; path.parent.mkdir(parents=True,exist_ok=True); path.write_bytes(png)
    write_json(ROOT/'docs/wheel-assets.json',manifest)
    print(json.dumps(manifest,ensure_ascii=True,indent=2))

if __name__ == '__main__': main()
