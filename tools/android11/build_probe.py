"""Build a tiny independent Android 11 location client with installed SDK tools.

Usage: python tools/android11/build_probe.py --sdk PATH --java-home PATH
"""
import argparse
from pathlib import Path
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--sdk", required=True, type=Path)
parser.add_argument("--java-home", required=True, type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
src = Path(__file__).resolve().parent
out = root / "build/android11-validation/probe"
out.mkdir(parents=True, exist_ok=True)
tools = args.sdk / "build-tools/36.0.0"
android = args.sdk / "platforms/android-31/android.jar"
java = args.java_home / "bin/java.exe"

def run(*command):
    subprocess.run([str(item) for item in command], check=True)

run(args.java_home / "bin/javac.exe", "-source", "8", "-target", "8", "-classpath", android,
    "-d", out, *src.glob("*.java"))
run(java, "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "30", "--lib", android,
    "--output", out, *out.rglob("*.class"))
unsigned = out / "unsigned.apk"
run(tools / "aapt.exe", "package", "-f", "-M", src / "AndroidManifest.xml", "-I", android, "-F", unsigned)
with zipfile.ZipFile(unsigned, "a") as archive:
    archive.write(out / "classes.dex", "classes.dex")
key = out / "probe.jks"
if not key.exists():
    run(args.java_home / "bin/keytool.exe", "-genkeypair", "-keystore", key, "-storepass", "android",
        "-keypass", "android", "-alias", "probe", "-keyalg", "RSA", "-dname", "CN=Android11Probe", "-validity", "3650")
apk = out / "android11-probe.apk"
run(java, "-jar", tools / "lib/apksigner.jar", "sign", "--ks", key, "--ks-pass", "pass:android",
    "--out", apk, unsigned)
print(apk)
