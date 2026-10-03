# NetDiag — network-aware Android telemetry

A network-aware Android telemetry engine: it measures what the radio is actually doing, tells
the user whose fault a slow connection is, and — once there is enough data — predicts service
loss a few seconds before it happens.

The shipped app is built from the `b3-native-chrome` branch. Releases, and the only place to
download the APK, are on the [releases page](#install).

## Install

NetDiag is distributed as a signed APK on GitHub Releases rather than through the Play Store:

<https://github.com/rijing240/extranet-netdiag/releases>

Download `extranet-<version>.apk`, open it, and let your browser or file manager install it. It
needs Android 8 (API 26) or newer, and asks for location and phone state because the measurements
are about which tower and which link the handset is on.

To update, install the newer APK over the older one. Android accepts that because every release
carries the same signing key and a higher version code. NetDiag never checks by itself: open the
app, tap the ⓘ in the top bar, press **Check for updates**, and it reports the newest published
build and offers to open its download page. Nothing is requested from GitHub until you tap that
button, and an ordinary commit never tells anyone to update. See
[`docs/release-and-updates.md`](docs/release-and-updates.md).

One exception to "installs over the old one": a debug build you installed yourself (from
`gradle :app:assembleDebug`) was signed with a different key, so uninstall that first.

## What B0 delivers

1. The module boundaries for the seven systems (see below).
2. The **calculation ledger**: every physical constant and derived quantity the later batches
   depend on, encoded once as named Kotlin constants with unit tests.
3. The **capability probe**: a harness that asks the platform for every radio, cell identity,
   Wi-Fi RTT, GNSS and diagnostics API the plan depends on, and writes a machine-readable
   report of which ones are supported, which return `UNAVAILABLE`, on which API level, and what
   the device actually returned.

## What B1 delivers

1. The **probe engine** in `:measure`: one probe set splits a request into DNS, TCP, TLS and
   time-to-first-byte, using JDK sockets rather than an HTTP client so the four numbers mean what
   they say.
2. The **latency waterfall**: nearest-rank p50/p95 per stage over a run of probe sets, plus
   grouped failure modes, so a fault can be attributed to a layer instead of to "the network".
3. B1's **exit criterion, computed rather than eyeballed**: 100 sets attempted, under a 5%
   failure rate, and not cut short by the wall-clock cap. A truncated run cannot claim it.
4. The **platform's own verdict** alongside ours, from `ConnectivityDiagnosticsManager`, including
   a suspected data stall if the OS saw one.

## System map

| ID | System | Module | Status |
|----|--------|--------|--------|
| S1 | Sensor Core — radio timeline, GNSS, context, sessions | `:android:sensor-core` | boundary + `SampleBudget` |
| S2 | Measurement Engine — probes, bufferbloat, capability detection | `:probe`, `:measure`, `:android:measurement` | capability probe + probe engine complete |
| S3 | Inference — features, labelling, training, scoring | `:android:inference` | seam only (B8/B9) |
| S4 | Decision SDK — `NetworkConfidence`, offline tripwire | `:android:decision-sdk` | seam only (B10) |
| S5 | Collective — ingest, Capacity Atlas | `collective/` (not yet created) | not started (B6/B7) |
| S6 | Presentation — waterfall, forecast, relative index | `:app` | probe and waterfall screens (B11 for the graph) |
| S7 | Capability Registry — per-model availability | `:probe` (`SupportMatrix`) | aggregation complete |
| X1 | Privacy / Consent | `:core` (`privacy`, `report`) | geohash + cell key hashing |
| X2 | Budget Guard | `:android:sensor-core` (`SampleBudget`) | complete for B0 |
| X3 | Test Harness | all modules + `tools/` | ledger verifier + device tests |

`:core`, `:probe` and `:measure` are pure Kotlin/JVM with no Android dependency, which is what
makes the ledger, the classification rules, the waterfall and its statistics testable on a
machine with no handset and no radio. `:android:measurement` supplies the only platform-facing
implementations: the capability probe, the live session, and the active network's resolver.

## The ledger is the point

`tools/verify_ledger.py` re-derives every constant from first principles in Python, reads the
literal (or the arithmetic expression) out of the Kotlin source, resolves identifier references
between declarations, and fails if the two disagree. This exists because a Kotlin unit test
cannot catch a number that is wrong in *both* the constant and the test — the same transcription
error made twice.

It has already paid for itself: it caught two incorrectly transcribed Shannon-Hartley anchors
(the −5 dB one was 9,600 bps off) before they could propagate into the capacity index.

```bash
python3 tools/verify_ledger.py     # 86 constants, 2 geohash vectors
```

## Building

There is no Gradle wrapper JAR checked in (it is a binary). Either generate one:

```bash
gradle wrapper        # then use ./gradlew
```

or invoke Gradle directly:

```bash
gradle :core:test :probe:test :measure:test   # pure JVM kernel
gradle :android:sensor-core:testDebugUnitTest
gradle :app:assembleDebug                     # needs the Android SDK
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

Pull before anything uninstalls the app: `connectedDebugAndroidTest` uninstalls it when it
finishes, and the report goes with it. `tools/pull-device-report.sh` reads both reports out of
internal storage, which is the only path that works from API 30 onwards.

## Running the probe engine

Launch the app and switch to *Waterfall (B1)*, then press *Run 100 sets*. It writes
`probe-waterfall.json` next to the capability report: per-stage p50/p95, the failure modes, the
verdict, and the platform's own connectivity report. See `docs/probe-engine.md`.

## Privacy rule

Raw per-second detail never leaves the device. Uplinks carry ~25 kB session summaries keyed by a
salted, truncated hash of `geohash-7 | earfcn | pci | tac`, and a bucket is published only when
at least five independent observations back it. See `docs/architecture.md`.

## Documents

- `docs/architecture.md` — systems, layers, data flow, module boundaries.
- `docs/product-spec.md` — the three tabs, the result shape, the diagnosis states and the rules a screen follows.
- `docs/calculation-ledger.md` — every constant, its derivation, and the plan discrepancies.
- `docs/probe-engine.md` — the four stages, the statistics, the exit criterion, and how to run it.
- `docs/b0-device-run.md` — how to obtain the device report and what has and has not been run.
- `docs/project-history.md` — what each batch delivered, the mistakes caught, the device evidence, and the current state.
