# The probe engine (S2, batch B1)

A latency number on its own cannot tell you whose fault a slow connection is. Good radio signal
plus a slow name lookup is a resolver problem. A fast lookup plus a slow handshake is the path.
A healthy connection plus a late first byte is the server. B1 exists to split one latency into
four, and to say which of them is carrying the time.

## The four stages

One **probe set** is one pass through the waterfall against one target:

| Stage | What is measured | How |
|---|---|---|
| `dns` | one UDP query to the resolver the active network handed us, timed to the reply | `DatagramSocket`, connected to the resolver so a stray datagram cannot be timed as our answer |
| `tcp` | the TCP handshake with the address the lookup returned | `Socket.connect`, to the A record we resolved rather than re-resolving by name |
| `tls` | the TLS handshake over that connection, with SNI and hostname verification | layered `SSLSocket` |
| `ttfb` | one HTTP/1.1 GET, timed to the first response byte | written on the TLS socket, first byte read |

The stages are deliberately not an HTTP client. A client would fold all four into one duration,
which is the number this batch exists to decompose.

When a stage fails, the stages after it are recorded as **skipped**, not failed. "The handshake
was slow" and "there was no connection to handshake over" are different facts, and only the first
is information about the network.

The DNS stage is done on the wire rather than through the platform's resolver API because that
API reports a completion, not a round trip — and because its callback signature changed shape in
API 30. `DnsWire` builds and parses the packets, which also means the codec is tested on a JVM
with no network at all.

## The statistics

Percentiles are **nearest rank**: `ceil(fraction × n)`, no interpolation. Every number in the
report is a number some probe actually observed. Interpolating would invent a millisecond, and
the first time that mattered would be in an argument with an operator about whose network is
slow.

That has a consequence worth knowing when reading a small run: with fewer than twenty sets,
`ceil(0.95n)` is `n`, so p95 *is* the slowest set rather than a tail statistic. A five-set smoke
test's p95 says almost nothing.

## The verdict

`ProbeRun.meetsExitCriterion` is B1's exit criterion, computed rather than eyeballed:

- 100 sets attempted, and
- fewer than 5% of them failed, and
- the run was **not** truncated by the wall-clock cap.

The third condition is the one worth arguing for. A run that hit the cap has not measured what
it set out to measure, so a low failure rate among the sets it managed is not evidence about the
other 85. A short run therefore cannot pass, however clean it looks.

## Running it

**In the app.** Launch NetDiag, switch to *Waterfall (B1)*, press *Run 100 sets*. The screen shows
the per-stage p50/p95, the failure modes grouped with counts, the verdict, and the platform's own
connectivity verdict next to ours. The report is written to the app's internal and external files
directories as `probe-waterfall.json`.

**On the command line.**

```bash
gradle :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.extranet.netdiag.test/androidx.test.runner.AndroidJUnitRunner
bash tools/pull-device-report.sh dev.extranet.netdiag probe-waterfall.json
```

Pull the report **before** anything uninstalls the app: `gradle connectedDebugAndroidTest`
uninstalls it when it finishes, and the app's storage — with the report in it — goes with it.

## What the platform adds

`AndroidOsDiagnostics` supplies the two things a JVM cannot answer for itself:

1. **The resolver** of the active network, read from `LinkProperties`. Without it the DNS stage is
   reported skipped rather than silently folded into the TCP row.
2. **The OS's own connectivity report**, from `ConnectivityDiagnosticsManager` (API 30+). The
   system already probes DNS, HTTP and HTTPS to decide whether a network is usable, so its verdict
   is independent evidence: if the platform says the network is validated and our sets are failing,
   the fault is ours or the target's, and the report puts both facts in one place.

The platform's raw counts are quoted, not decoded. The constants that would name the probe bits
are deprecated in the platform itself, and a confidently wrong decode is worse than a number
faithfully copied with its source named.
