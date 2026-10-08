#!/usr/bin/env python3
"""Build this small module directly with official Android SDK tools.

No Gradle dependency resolution is needed. The API AAR is compile-only.
The debug keystore is local to the build directory and is never distributed.
"""
import argparse
import os
from pathlib import Path
import subprocess
import zipfile
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--sdk', type=Path, required=True)
p.add_argument('--java-home', type=Path, required=True)
p.add_argument('--api-aar', type=Path, required=True)
p.add_argument('--build-tools', default='34.0.0')
args = p.parse_args()
root = Path(__file__).resolve().parents[1]
sdk, jdk, aar = (x.resolve() for x in (args.sdk, args.java_home, args.api_aar))
work = root / 'app/build/sdk-direct'
work.mkdir(parents=True, exist_ok=True)
tmp = work / 'tmp'
tmp.mkdir(exist_ok=True)
env = os.environ.copy()
env['LD_LIBRARY_PATH'] = os.pathsep.join(map(str, (
    jdk / 'lib', jdk / 'lib/server', sdk / 'build-tools' / args.build_tools / 'lib64')))
env['JAVA_TOOL_OPTIONS'] = '-Djava.io.tmpdir=' + str(tmp)
java = str(jdk / 'bin/java')
bt = sdk / 'build-tools' / args.build_tools
android = sdk / 'platforms/android-35/android.jar'

def run(*cmd):
    subprocess.run(list(map(str, cmd)), env=env, check=True)

with zipfile.ZipFile(aar) as z:
    (work / 'api.jar').write_bytes(z.read('classes.jar'))
classes = work / 'classes'
classes.mkdir(exist_ok=True)
run(java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '--release', '17',
    '-cp', str(android) + os.pathsep + str(work / 'api.jar'), '-d', classes,
    *sorted((root / 'app/src/main/java').rglob('*.java')))
with zipfile.ZipFile(work / 'module.jar', 'w') as z:
    for f in sorted(classes.rglob('*.class')):
        z.write(f, f.relative_to(classes).as_posix())
dex = work / 'dex'
dex.mkdir(exist_ok=True)
run(java, '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '26',
    '--lib', android, '--classpath', work / 'api.jar', '--output', dex, work / 'module.jar')
run(bt / 'aapt2', 'compile', '--dir', root / 'app/src/main/res', '-o', work / 'resources.zip')
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml')
manifest.getroot().set('package', 'dev.local.applemusicstrict')
ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
manifest.write(work / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
unsigned = work / 'unsigned.apk'
run(bt / 'aapt2', 'link', '-I', android, '--manifest', work / 'AndroidManifest.xml',
    '--min-sdk-version', '26', '--target-sdk-version', '35', '--version-code', '2',
    '--version-name', '0.1.1-lifecycle', '-o', unsigned, work / 'resources.zip')
with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as z:
    for f in sorted(dex.glob('*.dex')):
        z.write(f, f.name)
    resources = root / 'app/src/main/resources'
    for f in sorted(resources.rglob('*')):
        if f.is_file():
            z.write(f, f.relative_to(resources).as_posix())
aligned = work / 'aligned.apk'
run(bt / 'zipalign', '-f', '-p', '4', unsigned, aligned)
key = work / 'debug.keystore'
if not key.exists():
    run(jdk / 'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'android',
        '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA',
        '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Android Debug,O=Android,C=US')
    key.chmod(0o600)
output = root / 'app/build/outputs/apk/sdk-direct/AppleMusicStrict-0.1.1-lifecycle.apk'
output.parent.mkdir(parents=True, exist_ok=True)
signer = bt / 'lib/apksigner.jar'
run(java, '-jar', signer, 'sign', '--ks', key, '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--out', output, aligned)
run(java, '-jar', signer, 'verify', '--verbose', '--print-certs', output)
run(bt / 'zipalign', '-c', '-v', '4', output)
run(bt / 'aapt2', 'dump', 'badging', output)
print('Signed APK:', output)
