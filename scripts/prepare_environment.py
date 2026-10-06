"""Build local-only dependency profiles from the supplied installation (Python 3.11+)."""
import hashlib
import io
import json
from pathlib import Path
import shutil
import tomllib
import zipfile
import argparse

ROOT = Path(__file__).resolve().parents[1]

def metadata(data):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        name = 'META-INF/neoforge.mods.toml'
        return tomllib.loads(jar.read(name).decode()) if name in jar.namelist() else {}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, default=Path(r'D:\.minecraft\versions\克莱星2.0L'))
    args = parser.parse_args()
    source = args.source.resolve()
    selected = {}
    def add(path, nested=None, key=None):
        data = path.read_bytes()
        if nested:
            with zipfile.ZipFile(io.BytesIO(data)) as jar:
                data = jar.read(nested)
        meta = metadata(data)
        mod = meta['mods'][0]
        name = f"{mod['modId']}-{mod['version']}.jar"
        dest = ROOT / 'libs' / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        selected[key or mod['modId']] = {'file': name, 'version': mod['version'],
            'sha256': hashlib.sha256(data).hexdigest(), 'source': str(path),
            'nested': nested, 'dependencies': meta.get('dependencies', {}),
            'license': meta.get('license', 'unspecified')}
    mods = source / 'mods'
    for pattern in ['*create-1.21.1-6.0.10.jar', 'sable-neoforge-1.21.1-2.0.5.jar',
                    'synaxis-1.5.1.jar', 'sable-photomancy-1.0.1.jar',
                    'ldlib2-neoforge-1.21.1-2.2.33-all.jar']:
        matches = list(mods.glob(pattern))
        if len(matches) != 1:
            raise RuntimeError(f'Expected exactly one {pattern}: {matches}')
        add(matches[0])
    bundled = list(mods.glob('*create-aeronautics-bundled-1.21.1-1.3.0.jar'))
    if len(bundled) != 1:
        raise RuntimeError('Expected one Aeronautics 1.3.0 bundle')
    with zipfile.ZipFile(bundled[0]) as jar:
        for mod_id in ['simulated', 'offroad']:
            names = [n for n in jar.namelist() if n.endswith('.jar') and f'.{mod_id}-neoforge-' in n]
            if len(names) != 1:
                raise RuntimeError(f'Expected one embedded {mod_id}')
            add(bundled[0], names[0])
    for path in sorted((ROOT / '参考模组').glob('*.jar')):
        add(path)
    profiles = {
        'base': ['create', 'sable'],
        'gearwork': ['create', 'sable', 'synaxis', 'sable_schematic_api', 'ldlib2', 'gearwork'],
        'tracks': ['create', 'sable', 'simulated', 'offroad', 'tracks'],
        'combined': list(selected),
    }
    baseline = ROOT / 'compatibility' / 'sable-2.0.0.jar'
    if baseline.exists():
        add(baseline, key='sable_2_0_0')
        profiles['base-2.0.0'] = ['create', 'sable_2_0_0']
    for profile, ids in profiles.items():
        target = ROOT / 'test-environment' / profile / 'mods'
        target.mkdir(parents=True, exist_ok=True)
        # Never delete user-added mods. Stale or duplicate jars are reported by audit_environment.py.
        for mod_id in ids:
            name = selected[mod_id]['file']
            shutil.copy2(ROOT / 'libs' / name, target / name)
    lock = {'minecraft': '1.21.1', 'neoforge': '21.1.248', 'source': str(source),
            'mods': selected, 'profiles': profiles}
    (ROOT / 'dependency-lock.json').write_text(json.dumps(lock, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Prepared profiles: ' + ', '.join(f'{k}={len(v)} jars' for k,v in profiles.items()))

if __name__ == '__main__':
    main()
