"""Validate required/optional/incompatible version ranges, nested jars, duplicates and lock hashes."""
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def version(value):
    # Selected dependency versions are numeric releases with optional MC/build suffixes.
    release = value.split('+')[0]
    if not re.fullmatch(r'\d+(\.\d+)*', release):
        raise ValueError(f'Unsupported version qualifier; needs Maven comparison: {value}')
    parts = list(map(int, release.split('.')))
    while parts and parts[-1] == 0:
        parts.pop()
    return tuple(parts)

def accepts(value, spec):
    if not spec or spec == '*':
        return True
    if spec.startswith('[') and spec.endswith(']') and ',' not in spec:
        return version(value) == version(spec[1:-1])
    if ',' not in spec:
        raise ValueError(f'Unsupported version range: {spec}')
    low, high = spec[1:-1].split(',')
    v = version(value)
    return ((not low) or v > version(low) or (spec[0] == '[' and v == version(low))) and \
           ((not high) or v < version(high) or (spec[-1] == ']' and v == version(high)))

def scan(data, origin, mods, errors):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        if 'META-INF/neoforge.mods.toml' in jar.namelist():
            meta = tomllib.loads(jar.read('META-INF/neoforge.mods.toml').decode())
            for m in meta.get('mods', []):
                mid = m['modId']
                if mid in mods:
                    errors.append(f'Duplicate {mid}: {origin} and {mods[mid][2]}')
                mods[mid] = (m['version'], meta.get('dependencies', {}).get(mid, []), origin)
        if 'META-INF/jarjar/metadata.json' in jar.namelist():
            nested = json.loads(jar.read('META-INF/jarjar/metadata.json'))
            for entry in nested.get('jars', []):
                path = entry['path']
                scan(jar.read(path), origin + '!' + path, mods, errors)

def audit(profile, lock):
    mods = {'minecraft': (lock['minecraft'], [], 'platform'), 'neoforge': (lock['neoforge'], [], 'platform')}
    errors = []
    folder = ROOT / 'test-environment' / profile / 'mods'
    expected = {lock['mods'][mid]['file']: lock['mods'][mid]['sha256'] for mid in lock['profiles'][profile]}
    present = {p.name for p in folder.glob('*.jar')}
    for name in expected.keys() - present:
        errors.append(f'Missing locked jar: {name}')
    for path in sorted(folder.glob('*.jar')):
        data = path.read_bytes()
        if path.name in expected and hashlib.sha256(data).hexdigest() != expected[path.name]:
            errors.append(f'Hash mismatch: {path.name}')
        if path.name not in expected and not path.name.startswith('trackssimulate-'):
            errors.append(f'Unexpected jar outside locked profile: {path.name}')
        scan(data, path.name, mods, errors)
    for mid, (_, deps, _) in list(mods.items()):
        for dep in deps:
            other, kind = dep['modId'], dep.get('type', 'required')
            spec = dep.get('versionRange', '')
            if other not in mods:
                if kind == 'required':
                    errors.append(f'{mid} requires missing {other} {spec}')
                continue
            # Only compare ranges for installed mods; optional absent mods are irrelevant.
            good = accepts(mods[other][0], spec)
            if (kind in ['required', 'optional'] and not good) or (kind == 'incompatible' and good):
                errors.append(f'{mid}: {other} {mods[other][0]} violates {kind} {spec}')
    return {'profile': profile, 'passed': not errors, 'errors': errors,
            'mods': {mid: {'version': m[0], 'origin': m[2]} for mid,m in mods.items()}}

def main():
    lock = json.loads((ROOT / 'dependency-lock.json').read_text(encoding='utf-8'))
    results = [audit(p, lock) for p in lock['profiles']]
    out = ROOT / 'docs' / 'dependency-audit.json'
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    for result in results:
        print(result['profile'], 'PASS' if result['passed'] else 'FAIL', f"({len(result['mods'])} mod IDs including platform)")
        for error in result['errors']:
            print('  ' + error)
    return 0 if all(r['passed'] for r in results) else 1

if __name__ == '__main__':
    sys.exit(main())
