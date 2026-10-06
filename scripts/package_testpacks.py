"""Assemble local test ZIPs without changing the source Minecraft instance."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
subprocess.run([sys.executable, str(ROOT / 'scripts/audit_environment.py')], check=True)
lock = json.loads((ROOT / 'dependency-lock.json').read_text(encoding='utf-8'))
version = next(line.split('=', 1)[1].strip() for line in (ROOT / 'gradle.properties').read_text().splitlines() if line.startswith('mod_version='))
artifact = ROOT / f'build/libs/trackssimulate-{version}.jar'
if not artifact.exists():
    raise RuntimeError('Run scripts/gradle.ps1 build first')
dest = ROOT / 'dist'
dest.mkdir(exist_ok=True)
for profile in ['base', 'combined']:
    manifest = {'minecraft': lock['minecraft'], 'neoforge': lock['neoforge'], 'profile': profile, 'files': {}}
    files = [ROOT / 'test-environment' / profile / 'mods' / lock['mods'][mid]['file'] for mid in lock['profiles'][profile]]
    files.append(artifact)
    output = dest / f'TracksSimulate-{profile}-testpack-{version}.zip'
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_STORED) as archive:
        for path in files:
            data = path.read_bytes()
            target = 'mods/' + path.name
            archive.writestr(target, data)
            manifest['files'][target] = hashlib.sha256(data).hexdigest()
        archive.writestr('manifest.json', json.dumps(manifest, indent=2))
        archive.writestr('README.txt',
            'Tracks Simulate local test pack\n'
            f'Profile: {profile}\nMinecraft 1.21.1 / NeoForge 21.1.248 / Java 21\n\n'
            'Create a NEW isolated Minecraft 1.21.1 + NeoForge 21.1.248 instance in your launcher, '
            'then extract this mods folder into its game directory. Do not merge with an existing modpack.\n'
            'This archive includes the TracksSimulate wheel and belt assembly prototype.\n'
            'Do not install this compiled foundation into Gradle test-environment profiles: '
            'those already load project sources.\n'
            'For local testing only. Original mod licenses apply. No game files, login data or worlds included.\n'
            'The combined profile has upstream model errors documented in VALIDATION.md.\n')
        archive.write(ROOT / 'docs/VALIDATION.md', 'VALIDATION.md')
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None
        for name, expected in manifest['files'].items():
            assert hashlib.sha256(archive.read(name)).hexdigest() == expected
    print(f'{output.name}: {len(files)} jars; ZIP and SHA-256 verified')
