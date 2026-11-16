from pathlib import Path
import os, shutil, subprocess, hashlib, urllib.request, sys

ROOT = Path(__file__).resolve().parents[2]
JDK = os.environ.get('MARCOMOVIE_JDK') or os.environ.get('JAVA_HOME')


def java_tool(name):
    if JDK:
        return str(Path(JDK) / 'bin' / (name + '.exe' if os.name == 'nt' else name))
    found = shutil.which(name)
    if not found:
        raise RuntimeError('Set JAVA_HOME or MARCOMOVIE_JDK to JDK 17')
    return found


test = ROOT / 'android/tests'
folder = ROOT / 'android/build/ad-filter-check'
folder.mkdir(parents=True, exist_ok=True)
jar = test / 'lib/json-20240303.jar'
if not jar.exists():
    jar.parent.mkdir(parents=True, exist_ok=True)
    jar.write_bytes(urllib.request.urlopen(
        'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',
        timeout=30).read())
if hashlib.sha256(jar.read_bytes()).hexdigest() != '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':
    raise RuntimeError('json library digest mismatch')

src = ROOT / 'android/src/main/java/com/pilipala/marcomovie'
sources = [src / n for n in (
    'AdFilterRules.java', 'M3u8AdFilter.java', 'HeuristicAdDetector.java',
    'RulesImporter.java', 'AdFilterStats.java')]
subprocess.run([
    java_tool('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
    '-cp', str(jar), '-d', str(folder),
    *map(str, sources), str(test / 'AdFilterChecks.java')], check=True)
result = subprocess.run([
    java_tool('java'), '-cp', str(folder) + os.pathsep + str(jar),
    'com.pilipala.marcomovie.AdFilterChecks'], check=True, text=True, encoding='utf8',
    capture_output=True)
print(result.stdout)
