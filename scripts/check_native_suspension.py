"""Run only Sable's cached native physics library. Never starts Minecraft or a game server."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
out = root / 'build/native-checks'
out.mkdir(parents=True, exist_ok=True)
lz4 = next((Path.home()/'.gradle/caches/modules-2/files-2.1/org.lz4/lz4-java').rglob('lz4-java-*.jar'))
jdk = Path(os.environ['JAVA_HOME']) / 'bin'
sources = [root/'src/nativeChecks/java/dev/ryanhcode/sable/physics/impl/rapier/Rapier3D.java',
           root/'src/main/java/dev/trackssimulate/physics/SuspensionSettings.java',
           root/'src/main/java/dev/trackssimulate/wheel/WheelGeometry.java']
sources += list((root/'src/main/java/dev/trackssimulate/physics/contact').glob('*.java'))
sources += [root/'src/main/java/dev/trackssimulate/track/TrackPath.java', root/'src/main/java/dev/trackssimulate/track/TrackCrossings.java']
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '-cp', str(lz4), '-d', str(out/'classes'), *map(str, sources)], check=True)
for version in ('2.0.0', '2.0.5'):
    print(f'Checking Sable {version} (no Minecraft)', flush=True)
    subprocess.run([str(jdk/'java.exe'), '-Xmx128m', '-cp', os.pathsep.join([str(out/'classes'), str(lz4)]),
                    'dev.ryanhcode.sable.physics.impl.rapier.Rapier3D', str(root/f'libs/sable-{version}.jar'),
                    str(out/version/'sable_rapier_x86_64_windows.dll')], check=True, timeout=60)
