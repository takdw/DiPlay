#!/usr/bin/env python3
"""Build and optionally run a one-shot, read-only BYD lamp diagnostic over authorized ADB."""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
MAIN = "com.shilapi.xcertplay.tools.BydAmbientLightProbe"
HEADER = "DIPLAY_BYD_AMBIENT_PROBE_V1"
REPORT_PREFIXES = (
    "android.sdk=", "constant.", "id.", "read.", "method.", "pr345.",
    "manager.error=", "probe.", "capability_encoding=", "write_authorization=",
    "lamp_writes_performed=", "vehicle_compatibility=",
)


def run(command, *, env=None, timeout=60):
    return subprocess.run(command, check=True, text=True, capture_output=True,
                          env=env, timeout=timeout).stdout


def newest(paths):
    return max(paths, key=lambda path: tuple(map(int, re.findall(r"\d+", path.parent.name))))


def toolchain(args):
    sdk_value = args.sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk_value:
        sdk = Path(sdk_value).expanduser().resolve()
    else:
        candidates = [Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk"]
        sdk = next((path for path in candidates if path.is_dir()), candidates[0])
    jars = list((sdk / "platforms").glob("android-*/android.jar"))
    compilers = list((sdk / "build-tools").glob("*/d8"))
    if not jars or not compilers:
        raise RuntimeError("Android SDK platform and build-tools are required; pass --sdk.")
    env = os.environ.copy()
    java_home = args.java_home or env.get("JAVA_HOME")
    if java_home:
        env["JAVA_HOME"] = str(Path(java_home).expanduser().resolve())
        env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
    javac = shutil.which("javac", path=env.get("PATH"))
    if not javac:
        raise RuntimeError("A JDK is required; pass --java-home or set JAVA_HOME.")
    return sdk, newest(jars), newest(compilers), javac, env


def build(args):
    sdk, android_jar, d8, javac, env = toolchain(args)
    directory = ROOT / "build/probes/byd-ambient"
    classes, dex = directory / "classes", directory / "dex"
    for path in (classes, dex):
        if path.exists():
            shutil.rmtree(path)
        path.mkdir(parents=True)
    source = ROOT / "scripts/probes/BydAmbientLightProbe.java"
    run([javac, "--release", "8", "-d", str(classes), str(source)], env=env)
    run([str(d8), "--min-api", "28", "--lib", str(android_jar), "--output", str(dex),
         *map(str, sorted(classes.rglob("*.class")))], env=env)
    artifact = dex / "classes.dex"
    if not artifact.is_file():
        raise RuntimeError("DEX compilation did not produce classes.dex.")
    return artifact, sdk, env


def select_device(adb, serial, env):
    output = run([adb, "devices"], env=env, timeout=10)
    devices = {}
    for line in output.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1] in {"device", "offline", "unauthorized"}:
            devices[parts[0]] = parts[1]
    if serial:
        if devices.get(serial) != "device":
            raise RuntimeError("The selected ADB device is not connected and authorized. Connect it first.")
        return serial
    ready = [name for name, state in devices.items() if state == "device"]
    if len(ready) != 1:
        raise RuntimeError("Expected one authorized ADB device; connect the car or select it with --serial.")
    return ready[0]


def diagnostic_report(stdout):
    # SDK startup can print unrelated messages; retain only this diagnostic's records.
    lines = [line.strip() for line in stdout.splitlines()]
    if HEADER not in lines or "probe.complete=true" not in lines or "probe.complete=false" in lines:
        raise RuntimeError("The probe did not return a complete diagnostic; compatibility remains unknown.")
    records = [line for line in lines if line == HEADER or line.startswith(REPORT_PREFIXES)]
    return "\n".join(records) + "\n"


def probe(args, artifact, sdk, env):
    adb_path = sdk / "platform-tools/adb"
    adb = str(adb_path) if adb_path.is_file() else shutil.which("adb", path=env.get("PATH"))
    if not adb:
        raise RuntimeError("ADB platform-tools are required.")
    serial = select_device(adb, args.serial, env)
    command = [adb, "-s", serial]
    # Only this uniquely named temporary directory is created and removed on the head unit.
    remote = "/data/local/tmp/diplay-ambient-probe-" + uuid.uuid4().hex
    remote_dex = remote + "/probe.dex"
    run([*command, "shell", "mkdir", "-p", remote], env=env, timeout=10)
    try:
        run([*command, "push", str(artifact), remote_dex], env=env, timeout=20)
        try:
            stdout = run([*command, "shell", f"CLASSPATH={remote_dex} app_process /system/bin {MAIN}"],
                         env=env, timeout=25)
        except subprocess.CalledProcessError as error:
            raise RuntimeError("The device-side probe failed; no complete diagnostic was recorded.") from error
        result = diagnostic_report(stdout)
        destination = Path(args.output).expanduser().resolve() if args.output else (
            ROOT / "build/probes/byd-ambient/report.txt")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(result, encoding="utf-8")
        print(result, end="" if result.endswith("\n") else "\n")
        print(f"Report saved: {destination}")
    finally:
        try:
            run([*command, "shell", "rm", "-rf", remote], env=env, timeout=10)
        except (subprocess.SubprocessError, OSError):
            print("Temporary probe cleanup was not confirmed: " + remote, file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-only", action="store_true", help="Compile without using ADB.")
    parser.add_argument("--serial", help="An already connected and authorized ADB device.")
    parser.add_argument("--sdk", help="Android SDK directory.")
    parser.add_argument("--java-home", help="JDK directory.")
    parser.add_argument("--output", help="Local diagnostic report path (default: ignored build directory).")
    args = parser.parse_args()
    try:
        artifact, sdk, env = build(args)
        print(f"Probe built: {artifact}")
        if not args.build_only:
            probe(args, artifact, sdk, env)
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            print(error.stderr, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
