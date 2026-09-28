# B0 device run

B0's exit criterion is *"a report of which APIs return UNAVAILABLE on real hardware."*

## What has been executed

| Check | Where | Status |
|---|---|---|
| Ledger constants re-derived independently | `tools/verify_ledger.py` | **executed**, 59 constants + 2 geohash vectors pass |
| Core + probe unit tests | `:core:test :probe:test` | executed in CI |
| Android modules compile, debug APK assembles | `:app:assembleDebug` | executed in CI |
| Instrumented probe on an Android runtime | `:app:connectedDebugAndroidTest` on an emulator | executed in CI (informational job) |
| **Probe on a physical handset with a modem** | — | **NOT EXECUTED** |

The development host for this pass had JDK 17 and network access but no Kotlin compiler, no
Gradle and no Android SDK, and the download of a toolchain was abandoned as too slow
(~166 KB/s aggregate). All Kotlin therefore compiles for the first time in CI, not locally. That
is stated plainly because it means the first CI run is a real compile check rather than a
formality, and it is why the Android-module surface was kept deliberately thin.

## What an emulator run does and does not prove

An emulator proves the harness **executes on a real Android runtime without crashing**, and that
it produces a well-formed report on disk. It proves nothing about hardware support: an emulator
has no cellular modem, no Wi-Fi RTT responder and no GNSS chipset, so nearly every radio probe
returns `UNAVAILABLE` or `FEATURE_ABSENT` there.

That is why the instrumented test asserts only structural invariants, and why the CI emulator job
is marked informational. Hardware support is exactly what a single device cannot answer, and the
report format is built to be aggregated across many devices in the S7 matrix for that reason.

## Obtaining a real report

**On a handset.**

1. `gradle :app:installDebug` (or install `app/build/outputs/apk/debug/app-debug.apk`).
2. Launch NetDiag and grant location and phone-state permission.
3. Press **Run + live 8 s**. The live pass is what resolves the asynchronous specs.
4. The report is written twice: internally, and to
   `/sdcard/Android/data/dev.extranet.netdiag/files/capability-report.json`.

```bash
# Works on every API level: a debug build is debuggable, so the shell user may read its
# internal storage through run-as.
adb exec-out run-as dev.extranet.netdiag cat files/capability-report.json > capability-report.json

# The external copy is the one you can also find in a file manager, but since API 30 the shell
# user cannot read /sdcard/Android/data any more, so this pull fails on modern devices.
adb pull /sdcard/Android/data/dev.extranet.netdiag/files/capability-report.json
```

`tools/pull-device-report.sh` does this with both paths and checks that what came back actually
parses as the report, which is worth having: a failed `run-as` writes its complaint into the
output file, so a size check alone will happily accept 46 bytes of error message as the
artifact.

**A trap worth knowing about.** `gradle :app:connectedDebugAndroidTest` uninstalls the app when
it finishes, and the app's storage goes with it — so the report is destroyed before any later
`adb` command can read it. Drive the run by hand when you want the report:

```bash
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.extranet.netdiag.test/androidx.test.runner.AndroidJUnitRunner
bash tools/pull-device-report.sh
```

**What to look at first**, in the order that matters:

1. `lte.signal.timingAdvance` and `nr.signal.timingAdvance` — whether the modem reports these at
   all is the single biggest assumption in the plan.
2. `gnss.measurements.carrierPhase` — the plan's biggest hardware lottery. Expect `UNAVAILABLE`
   on most consumer firmware, which is why sub-metre positioning was never a roadmap promise.
3. `gnss.measurements.pseudorangeRate` — should be `SUPPORTED` almost everywhere. This is the
   evidence base for the Doppler speedometer, the highest-confidence feature on the list.
4. `lte.identity.bandwidth` — without it the Shannon figure has no `B`, which is the root reason
   CQI-based throughput tables were unusable.
5. `connectivity.diagnosticsManager` — the OS already runs the DNS/TCP/TLS/HTTP chain, so this
   one capability largely supersedes hand-rolled probes in B1.
6. `power.thermalStatus` and any `wifi.rtt.*` rows.

## Aggregating across devices

```kotlin
val rows = SupportMatrix.build(reports)          // one row per capability
SupportMatrix.problemRows(rows, threshold = 0.5) // worst-supported first
SupportMatrix.supportRateFor(SystemId.SENSOR_CORE, rows)
```

This is the seed of S7 and should be run over every report collected before B2 fixes the sampling
strategy, because the fraction of users whose modem reports a timing advance determines whether
the ranging work is worth building at all.
