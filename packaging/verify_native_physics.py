"""Smoke-test the actual packaged JAR using its bundled platform JRE."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path.cwd()
platform = os.environ["PLATFORM"]
if platform.startswith("macos"):
    app = root / "build/package/Mindustry.app/Contents"
    jar = app / "app/Mindustry.jar"
    java = app / "runtime/Contents/Home/bin/java"
else:
    app = root / "build/package/Mindustry"
    jar = app / "Mindustry.jar"
    java = app / ("jre/bin/java.exe" if platform == "windows" else "jre/bin/java")
for path in (jar, java):
    if not path.is_file():
        raise RuntimeError(f"Packaged file missing: {path}")
with tempfile.TemporaryDirectory() as tmp:
    subprocess.run(["javac", "-cp", str(jar), "-d", tmp, str(root / "packaging/NativePhysicsSmoke.java")], check=True)
    subprocess.run([str(java), "-Djava.library.path=" + tmp, "-cp", str(jar) + os.pathsep + tmp, "NativePhysicsSmoke", os.environ.get("EXPECTED_DISTRIBUTION_VERSION", "1.1")], cwd=tmp, check=True)
