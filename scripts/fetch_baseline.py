"""Download the official Sable 2.0.0 NeoForge release and verify Modrinth's SHA-512."""
import hashlib
import json
from pathlib import Path
import urllib.request
import winreg

ROOT = Path(__file__).resolve().parents[1]
key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Software\Microsoft\Windows\CurrentVersion\Internet Settings')
enabled = winreg.QueryValueEx(key, 'ProxyEnable')[0]
proxies = {}
if enabled:
    raw = winreg.QueryValueEx(key, 'ProxyServer')[0]
    if '=' in raw:
        proxies = dict(entry.split('=', 1) for entry in raw.split(';') if '=' in entry)
    else:
        proxies = {'http': raw, 'https': raw}
    proxies = {k: v if '://' in v else 'http://' + v for k, v in proxies.items()}
opener = urllib.request.build_opener(urllib.request.ProxyHandler(proxies))
url = 'https://api.modrinth.com/v2/version/NGuyFOeE'
with opener.open(urllib.request.Request(url, headers={'User-Agent': 'TracksSimulate-local-development/0.1'}), timeout=45) as response:
    release = json.load(response)
assert release['version_number'] == '2.0.0+mc1.21.1' and 'neoforge' in release['loaders']
artifact = next(f for f in release['files'] if f['primary'])
dest = ROOT / 'compatibility' / 'sable-2.0.0.jar'
dest.parent.mkdir(exist_ok=True)
data = dest.read_bytes() if dest.exists() else b''
if hashlib.sha512(data).hexdigest() != artifact['hashes']['sha512']:
    with opener.open(artifact['url'], timeout=90) as response:
        data = response.read()
    if hashlib.sha512(data).hexdigest() != artifact['hashes']['sha512']:
        raise RuntimeError('Sable SHA-512 verification failed')
    dest.write_bytes(data)
(ROOT / 'docs').mkdir(exist_ok=True)
(ROOT / 'docs' / 'sable-baseline-source.json').write_text(json.dumps({
    'api': url, 'url': artifact['url'], 'sha512': artifact['hashes']['sha512']
}, indent=2), encoding='utf-8')
print('Verified official Sable 2.0.0 NeoForge release')
