"""Package the signed APK and reproducible sources without local tools or signing secrets."""
from pathlib import Path
import hashlib
import shutil
import zipfile

root = Path(__file__).resolve().parent.parent
artifacts = root / "artifacts"
artifacts.mkdir(exist_ok=True)
apk = artifacts / "盘库助手-0.2.4.apk"
shutil.copy2(root / "app/build/outputs/apk/release/app-release.apk", apk)
source = artifacts / "盘库助手-0.2.4-源码.zip"
entries = [root / name for name in (
    "README.md", ".gitignore", "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "app/build.gradle.kts"
)]
for folder in ("app/src", "gradle", "docs", "tools"):
    entries += [path for path in (root / folder).rglob("*") if path.is_file() and "__pycache__" not in path.parts]
with zipfile.ZipFile(source, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(set(entries)):
        archive.write(path, path.relative_to(root).as_posix())
with zipfile.ZipFile(source) as archive:
    assert archive.testzip() is None
    assert all(not any(part in {".toolchain", ".signing", "local.properties"} for part in Path(name).parts) for name in archive.namelist())
with (artifacts / "SHA256SUMS.txt").open("w", encoding="utf-8") as output:
    for path in (apk, source):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        output.write(f"{digest}  {path.name}\n")
        print(f"{path.name}: {path.stat().st_size:,} bytes; SHA256 {digest}")
