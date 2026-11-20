#!/usr/bin/env python3
"""Build three Java 17 executable jars separately from the main Mod."""
from pathlib import Path
import subprocess, zipfile
root = Path(__file__).resolve().parents[2]
subprocess.run([str(root/'gradlew'), ':java-sdk:macroRuntimeJar', '--offline', '--console=plain'], cwd=root, check=True)
runtime = root/'java-sdk/build/libs/macro-runtime.jar'
classes = root/'build/cooperation/classes'; classes.mkdir(parents=True, exist_ok=True)
subprocess.run(['javac', '--release', '17', '-cp', str(runtime), '-d', str(classes), *map(str, (Path(__file__).parent/'macros').glob('*.java'))], check=True)
out = root/'build/macros/cooperation'; out.mkdir(parents=True, exist_ok=True)
for kind in ['Detector', 'Executor', 'Scheduler']:
    path = out/f'cooperation-{kind.lower()}.jar'
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as jar:
        jar.writestr('META-INF/MANIFEST.MF', f'Manifest-Version: 1.0\nMain-Class: Cooperation{kind}\n\n')
        with zipfile.ZipFile(runtime) as sdk:
            for name in sdk.namelist():
                if name.endswith('.class'): jar.writestr(name, sdk.read(name))
        for file in classes.glob('*.class'):
            if file.name.startswith(('CooperationSupport', f'Cooperation{kind}')): jar.write(file, file.name)
    print(path)
