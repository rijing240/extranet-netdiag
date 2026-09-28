# NetDiag — network-aware Android telemetry

A network-aware Android telemetry engine: it measures what the radio is actually doing, tells
the user whose fault a slow connection is, and — once there is enough data — predicts service
loss a few seconds before it happens.

This repository is at **B0: repo skeleton and capability probe**.

## What B0 delivers

1. The module boundaries for the seven systems (see below).
2. The **calculation ledger**: every physical constant and derived quantity the later batches
   depend on, encoded once as named Kotlin constants with unit tests.
3. The **capability probe**: a harness that asks the platform for every radio, cell identity,
   Wi-Fi RTT, GNSS and diagnostics API the plan depends on, and writes a machine-readable
   report of which ones are supported, which return `UNAVAILABLE`, on which API level, and what
   the device actually returned.

## System map

| ID | System | Module | Status |
|----|--------|--------|--------|
| S1 | Sensor Core — radio timeline, GNSS, context, sessions | `:android:sensor-core` | boundary + `SampleBudget` |
| S2 | Measurement Engine — probes, bufferbloat, capability detection | `:probe`, `:android:measurement` | capability probe complete |
| S3 | Inference — features, labelling, training, scoring | `:android:inference` | seam only (B8/B9) |
| S4 | Decision SDK — `NetworkConfidence`, offline tripwire | `:android:decision-sdk` | seam only (B10) |
| S5 | Collective — ingest, Capacity Atlas | `collective/` (not yet created) | not started (B6/B7) |
| S6 | Presentation — waterfall, forecast, relative index | `:app` | probe screen only (B11) |
| S7 | Capability Registry — per-model availability | `:probe` (`SupportMatrix`) | aggregation complete |
| X1 | Privacy / Consent | `:core` (`privacy`, `report`) | geohash + cell key hashing |
| X2 | Budget Guard | `:android:sensor-core` (`SampleBudget`) | complete for B0 |
| X3 | Test Harness | all modules + `tools/` | ledger verifier + device test |

`:core` and `:probe` are pure Kotlin/JVM with no Android dependency, which is what makes the
ledger, the classification rules and the report serialization testable on a machine with no
handset and no radio. `:android:measurement` supplies the only platform-facing implementation.

## The ledger is the point

`tools/verify_ledger.py` re-derives every constant from first principles in Python, reads the
literal (or the arithmetic expression) out of the Kotlin source, resolves identifier references
between declarations, and fails if the two disagree. This exists because a Kotlin unit test
cannot catch a number that is wrong in *both* the constant and the test — the same transcription
error made twice.

It has already paid for itself: it caught two incorrectly transcribed Shannon-Hartley anchors
(the −5 dB one was 9,600 bps off) before they could propagate into the capacity index.

```bash
python3 tools/verify_ledger.py     # 59 constants, 2 geohash vectors
```

## Building

There is no Gradle wrapper JAR checked in (it is a binary). Either generate one:

```bash
gradle wrapper        # then use ./gradlew
```

or invoke Gradle directly:

```bash
gradle :core:test :probe:test              # pure JVM kernel
gradle :android:sensor-core:testDebugUnitTest
gradle :app:assembleDebug                  # needs the Android SDK
```

CI provisions Gradle 8.11.1 itself, so no wrapper is required to get a green build.

## Running the capability probe

**On a handset.** Install the debug APK, launch NetDiag, and press *Run* (synchronous pass) or
*Run + live 8 s* (also listens for GNSS measurements, telephony callbacks and network
transitions). The report is written to the app's external files directory as
`capability-report.json` and can be shared from the screen.

**On an emulator.**

```bash
gradle :app:connectedDebugAndroidTest
adb pull /sdcard/Android/data/dev.extranet.netdiag/files/capability-report.json
```

## Privacy rule

Raw per-second detail never leaves the device. Uplinks carry ~25 kB session summaries keyed by a
salted, truncated hash of `geohash-7 | earfcn | pci | tac`, and a bucket is published only when
at least five independent observations back it. See `docs/architecture.md`.

## Documents

- `docs/architecture.md` — systems, layers, data flow, module boundaries.
- `docs/calculation-ledger.md` — every constant, its derivation, and the plan discrepancies.
- `docs/b0-device-run.md` — how to obtain the device report and what has and has not been run.
