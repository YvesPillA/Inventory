"""Package current sources and verified APKs without local tools or signing secrets."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
ARTIFACTS = ROOT / "artifacts"
RELEASE_CERT = "3ee4c1ed3baf150a09c96096a7950c3a226e1448c77bfa9a67c72c085c0c68ba"


def android_tool(name):
    executable = name + (".bat" if name == "apksigner" else ".exe") if os.name == "nt" else name
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    candidates = [Path(sdk)] if sdk else []
    candidates.append(ROOT / ".toolchain/sdk")
    properties = ROOT / "local.properties"
    if properties.exists():
        match = re.search(r"^sdk.dir=(.+)$", properties.read_text(encoding="utf-8"), re.M)
        if match:
            candidates.append(Path(match.group(1).strip().replace("\\:", ":").replace("\\\\", "\\")))
    for sdk_root in candidates:
        tool = sdk_root / "build-tools/34.0.0" / executable
        if tool.is_file():
            return str(tool)
    found = shutil.which(name)
    if found:
        return found
    raise RuntimeError(f"Cannot locate {name}; configure Android SDK Build Tools 34.0.0.")


def checked_output(args):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or "Command failed")
    return result.stdout


def verified_apk(variant, version, version_code):
    folder = ROOT / "app/build/outputs/apk" / variant
    metadata_file = folder / "output-metadata.json"
    if not metadata_file.is_file():
        raise RuntimeError(f"Build assemble{variant.capitalize()} first.")
    metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
    elements = metadata["elements"]
    if metadata.get("applicationId") != "com.fieldstock.app" or metadata.get("variantName") != variant or len(elements) != 1:
        raise RuntimeError("Unexpected APK application, variant or split count.")
    element = elements[0]
    if element["versionName"] != version or element["versionCode"] != version_code:
        raise RuntimeError("APK metadata does not match the current Gradle version; rebuild first.")
    apk = folder / element["outputFile"]
    signature = checked_output([android_tool("apksigner"), "verify", "--print-certs", str(apk)])
    if variant == "release" and f"certificate SHA-256 digest: {RELEASE_CERT}" not in signature:
        raise RuntimeError("Expected customer release certificate required; keep the same key for future updates.")
    badging = checked_output([android_tool("aapt"), "dump", "badging", str(apk)])
    if not re.search(r"package: name='com\.fieldstock\.app' versionCode='" + str(version_code) + r"' versionName='" + re.escape(version) + "'", badging):
        raise RuntimeError("APK manifest does not match the current app version.")
    if variant == "release" and "application-debuggable" in badging:
        raise RuntimeError("A debuggable APK cannot be packaged as a release.")
    return apk


def package_sources(version):
    entries = [ROOT / name for name in (
        "README.md", "README.en.md", ".gitignore", "settings.gradle.kts", "build.gradle.kts",
        "gradle.properties", "gradlew", "gradlew.bat", "app/build.gradle.kts",
    )]
    for folder in ("app/src", "gradle", "docs", "tools"):
        entries += [path for path in (ROOT / folder).rglob("*") if path.is_file() and "__pycache__" not in path.parts]
    source = ARTIFACTS / f"盘库助手-{version}-源码.zip"
    with zipfile.ZipFile(source, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(set(entries)):
            archive.writestr(path.relative_to(ROOT).as_posix(), path.read_bytes())
    with zipfile.ZipFile(source) as archive:
        if archive.testzip() is not None:
            raise RuntimeError("Source archive failed its integrity check.")
        if any(any(part in {".toolchain", ".signing", "local.properties"} for part in Path(name).parts) for name in archive.namelist()):
            raise RuntimeError("Source archive contains a local file.")
    return source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--test", action="store_true", help="Package a signed Debug APK explicitly labelled as a test build.")
    mode.add_argument("--source-only", action="store_true", help="Package source files without requiring a signing key.")
    args = parser.parse_args()
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    version = re.search(r'versionName\s*=\s*"([0-9.]+)"', gradle).group(1)
    version_code = int(re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1))
    # Verify before writing or replacing any deliverables.
    apk = None if args.source_only else verified_apk("debug" if args.test else "release", version, version_code)
    ARTIFACTS.mkdir(exist_ok=True)
    if apk:
        suffix = "-测试版" if args.test else ""
        shutil.copy2(apk, ARTIFACTS / f"盘库助手-{version}{suffix}.apk")
    package_sources(version)
    deliverables = sorted(list(ARTIFACTS.glob("*.apk")) + list(ARTIFACTS.glob("*-源码.zip")))
    with (ARTIFACTS / "SHA256SUMS.txt").open("w", encoding="utf-8", newline="\n") as output:
        for path in deliverables:
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            output.write(f"{digest}  {path.name}\n")
            print(f"{path.name}: {path.stat().st_size:,} bytes; SHA256 {digest}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, AttributeError) as error:
        raise SystemExit(f"Packaging stopped: {error}")
