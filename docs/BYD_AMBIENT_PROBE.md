# Read-only BYD ambient-light probe

This diagnostic prepares a compatibility check for upstream [PR #345](https://github.com/shihabal3amri/DiPlay/pull/345). The probe itself does not integrate or enable music lighting. The original contribution was tested on a Tang; the Sealion 06 read/write observations below verify only the tested lamp operations. Music-following integration is documented separately in [BYD_AMBIENT_LIGHTING.md](BYD_AMBIENT_LIGHTING.md).

The helper runs once under the already authorized ADB shell identity. It reads only nine allowlisted interior-lamp values from the PR's setting device (1023): equipment presence, raw color/brightness/area capabilities, current front/rear colors and brightness, and current area. It reports matching lamp constants and the presence of the PR's setter signatures, without invoking setters. Unknown IDs are reported rather than guessed. A conflicting `BYDAUTO_DEVICE_SETTING` constant prevents live reads.

No APK install, package rename, rooting, new ADB authorization, lamp writes or persistent service is required. The runner pushes a temporary DEX into its own uniquely named `/data/local/tmp` directory and removes that directory afterward. A lost ADB connection may prevent that cleanup; the exact temporary path is then reported. The helper has a 20-second process deadline.

The report contains lamp diagnostics and Android API level, not VINs, serial numbers, credentials, phone information, media metadata or unrelated vehicle fields. Local build output and the default report are under the ignored `build/` directory.

## Build in advance

Requires Python 3, a JDK supporting `javac --release 8`, Android SDK platform/build-tools and ADB platform-tools. Use the project's configured JDK/SDK. This builds only; no device connection is needed:

```sh
python3 scripts/probe_byd_ambient.py --build-only
```

If necessary, pass `--sdk /path/to/android/sdk --java-home /path/to/jdk`, or set `ANDROID_HOME` and `JAVA_HOME`.

## Run on the head unit

Connect the car using its current Wi-Fi address and existing ADB authorization first. With exactly one authorized device connected:

```sh
python3 scripts/probe_byd_ambient.py
```

With multiple devices, use `--serial` with the exact entry from `adb devices`. The runner does not choose an arbitrary device or initiate a network connection. The complete report prints to the terminal and is saved to `build/probes/byd-ambient/report.txt`; `--output /path/to/report.txt` overrides that destination.

## Interpret the result

- `probe.complete=true` means the diagnostic finished. Check individual error lines even when it completed.
- `pr345.snapshot_shape_matches=true` means the current values fit the PR's read gate: lamp-present equals 1, color numbers 1–31, raw brightness 1–6 and area 1–3. It does not establish the supported palette or physical behavior.
- `pr345.write_api_present=true` means the exact setter signatures and IDs exist. Write authorization and physical effects remain untested.
- Capability values are reported as signed decimal and hexadecimal. Their encoding is left uninterpreted; they may be bitmasks or enums rather than maxima.
- Missing fields, unreadable values, a different setting device or values outside the PR's assumed ranges need investigation before porting its vehicle-control code.

Even a matching report cannot prove beat synchronization, front/rear zone behavior or reliable restoration. Those need a separate controlled write test after reviewing the read-only findings.

## Local verification

The desktop regression harness uses a fake BYD manager whose setters throw if called. It checks the getter allowlist, unequal zone state, raw capability bits, out-of-range values, access denial, missing fields and missing setter signatures. A child-process test confirms that the helper publishes its report and exits even when framework threads remain alive:

```sh
mkdir -p build/probes/byd-ambient/tests
javac --release 8 -d build/probes/byd-ambient/tests \
  scripts/probes/BydAmbientLightProbe.java scripts/probes/BydAmbientLightProbeTest.java
java -cp build/probes/byd-ambient/tests com.shilapi.xcertplay.tools.BydAmbientLightProbeTest
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/probes -p 'test_*.py'
```

These checks establish local probe behavior; they do not validate lighting writes.

## Read-only vehicle result: 2026-10-08

The probe completed on the user's Sealion 06 DiLink 5 head unit, Android API 32. It invoked no lighting setters and its temporary device directory was removed. Initial readings completed but the process stayed alive after SDK initialization; an explicit exit and a regression test fixed that shutdown issue. The corrected runner completed with exit code 0.

| Reading | Value |
| --- | --- |
| Setting device | 1023, matching the PR |
| Lamp present | 1 |
| Front / rear color | 28 / 28 |
| Front / rear raw brightness | 6 / 6 |
| Current area | 3 |
| Raw color / brightness / area capability fields | 4 / 1 / 1 |
| Expected getters, setters and feature IDs | Present |
| PR snapshot shape check | Passed |

These read-only observations establish readable state and the expected API layout on this firmware. The capability encodings remain uninterpreted. They do not by themselves prove a 31-color palette, write authorization, zone semantics, beat synchronization or restoration.

The complete local report is saved to `build/probes/byd-ambient/report.txt` and remains ignored by Git.

## Controlled brightness/color test: 2026-10-08

After explicit user authorization, a separate one-shot helper checked Park, saved the original five-field state, and tested these changes through the PR's exact `setIntArray` signature and feature IDs:

1. Keep color 28 and area 3; lower both brightness values from 6 to 3; restore the original state.
2. Keep brightness 6 and area 3; change both colors from 28 to 1; restore the original state.

The first test held each phase for approximately 12 seconds. The user requested a repeat, which held each phase for approximately 20 seconds. Every write returned 0, and readback confirmed both test states and restoration. During the repeat, the user confirmed that both dimming and color changes were visible. A fresh read-only probe afterward confirmed the original `28,28,6,6,3` state again.

The write helper was separate from the read-only probe and is retained locally at `/tmp/diplay-ambient-write-20261008`. Its fake-client checks passed for normal dim/color/restore, a partial write followed by failure, leaving Park, starting outside Park and an unsupported snapshot. Its temporary device helper and state backup were removed after confirmed restoration. The report is saved locally to the ignored `build/probes/byd-ambient/write-test-20261008.txt`.

This verifies shell-authorized physical control for the tested colors, brightness levels and all-zone batch, plus restoration under normal test conditions. It does not validate the full palette, independently addressed zones, continuous music synchronization or recovery from a blocked service/power loss. Music-following integration remains separate work.
