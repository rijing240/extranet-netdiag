# Project history

A compacted record of what this project has built, what was caught along the way, and where the
tree stands. The living specifications are in `architecture.md`, `calculation-ledger.md`,
`probe-engine.md` and `b0-device-run.md`; this document records the work itself.

State as of 30 September 2026. Repository `rijing240/extranet-netdiag`, branch
`b3-native-chrome`.

## Where it started

The baseline idea was a list of calculations a phone can genuinely do, fed to the platform's own
APIs:

1. Timing advance as distance to the serving tower (78.07 m per command step, 553.5 m for GSM).
2. Quality metrics rather than strength alone — RSRQ, SINR, and what they separate (weak coverage
   versus interference versus load).
3. Neighbour cells plus band, PCI and EARFCN.
4. Wi-Fi RTT ranging (`d = c · RTT / 2`, 1–2 m accuracy, needs an FTM-responding AP).
5. Raw GNSS measurements — pseudorange, carrier phase, Doppler.
6. Active network probes that split one latency into DNS, TCP, TLS and time-to-first-byte.

That list became a 13-batch plan (B0 through B13) over a layered architecture: pure JVM kernel,
Android adapters, app, and a collective layer. B0, B1 and B2 are done and merged. Everything else
is still planned, with two deliberate empty seams in the tree.

## Milestones

| Batch | What it delivered | Merged as | State |
|---|---|---|---|
| B0 | Skeleton, calculation ledger, capability probe, report format, S7 support matrix | PR #1, `476093e` | Merged |
| B1 | Probe engine: DNS/TCP/TLS/TTFB waterfall, nearest-rank statistics, computed exit criterion, OS diagnostics alongside ours | PR #2, `494eeec` | Merged |
| Editorial UI | The app was restyled in the editorial design language | PR #3, `ef556ab` | Merged |
| Pinned key | One debug keystore in the repo, wired into AGP's built-in debug signing config, so CI builds install over an existing app instead of failing | PR #4, `349db40` | Merged |
| B2 | Radio timeline sampler, signal compass, two-hop blame, mini-throughput check, CSV export | PR #5, `2b8528c` | Merged |
| — | Live B2 timeline screenshot from the A03 session | `f7a0e45` | On `main` |
| B3 | Native chrome: `Scaffold` edge to edge, `TopAppBar`, bottom `NavigationBar`, one `LazyColumn` of cards per screen | `0f75f49` | On `b3-native-chrome`, one commit ahead of `main` |

## What each batch contains

### B0 — skeleton, ledger, capability probe

- `settings.gradle.kts` fixed the module boundaries so later batches have somewhere to land:
  `:core`, `:probe`, `:measure` are pure Kotlin/JVM; `:android:sensor-core`,
  `:android:measurement`, `:android:inference`, `:android:decision-sdk` are Android modules;
  `:app` is the application. The collective layer is a Cloudflare Worker with its own toolchain
  and no Gradle module, and does not exist yet.
- The ledger in `core/.../ledger/` holds every physical constant and derived quantity as named
  Kotlin constants — timing advance, Shannon–Hartley, Doppler, Wi-Fi RTT, bufferbloat, the model
  gate, storage budgets, Atlas bucketing. No later batch re-derives any of them.
- `RetiredCalculations.kt` records plausible but unbuildable ideas (RSRP multilateration,
  path-loss inversion, Little's Law) with the reason each fails, so a future contributor does not
  reinvent them.
- The capability probe asks ~70 platform APIs what they actually return and writes a report to
  disk. Two rules keep the output honest: a finding's `detail` prefers the catalog's explanation
  over generic text, and the S7 support rate divides by probed devices, not reporting ones.
- `CapabilityProbeRunner` sits behind `PlatformReportSource`, a four-member seam, so the whole
  classification table is testable on a machine with no radio.

### B1 — the probe engine

- One probe set is one pass through four stages against one target: DNS on the wire
  (`DatagramSocket`, the resolver the active network handed us), TCP handshake to the resolved
  address, TLS with SNI and hostname verification, then an HTTP/1.1 GET timed to its first byte.
- The stages are deliberately not an HTTP client: a client folds all four durations into one,
  which is the number the waterfall exists to decompose.
- A failed stage marks later stages **skipped**, not failed — "slow handshake" and "no connection
  to handshake over" are different facts about the network.
- Percentiles are nearest-rank, never interpolated; with fewer than twenty sets, p95 is simply
  the slowest set, which the docs say plainly.
- The exit criterion is computed: 100 sets attempted, under 5% failed, and not truncated by the
  wall-clock cap. A truncated run cannot pass, however clean it looks.

### B2 — radio timeline, compass, blame, throughput

- `RadioTimelineSampler` (in `:android:sensor-core`) samples the radio once a second: RSRP, RSRQ,
  RSSNR, level, timing advance, technology. A `TimelineBudget` ring holds 7,200 samples — two
  hours — at 26 bytes each.
- `SignalCompass` does not locate a tower; Android exposes no tower coordinates. It aggregates
  sampled RSRP by the user's heading into 16 sectors and names one only when its median beats the
  overall median by at least 2 dB, which is above the ~1 dB multipath wiggle on a handset.
- `TwoHopProbe` checks the gateway and the internet separately, so a first hop that filters probes
  reads as "unusual, first hop may filter probes" rather than as an offline network.
- `MiniThroughputProbe` moves a bounded payload and grades the result POOR/MARGINAL/GOOD against
  the 0.5/10 Mbps bands.
- `TimelineCsv` exports the session with a fixed 11-column order so exports never drift between
  sessions.

### Editorial UI and the pinned debug key

- The app was styled as an editorial layout — palette, type, hairlines, a stitch motif — and the
  three screens (probe, waterfall, timeline) were rendered in it. B3 is converting that language
  from a document layout to an app shell: top bar, bottom tabs, cards on bone, keeping the
  palette and type.
- The debug key is pinned in `keystore/debug.keystore` and wired into AGP's built-in `debug`
  signing config, so a CI-produced APK can be installed over an existing install instead of
  failing on a signature mismatch. Verified with `install -r` on two physical phones.

## Mistakes caught, and what caught them

Recorded because each one is a failure mode the tests or the ledger now cover.

| Mistake | Caught by | Fix |
|---|---|---|
| Compass improvement sign inverted (`overall − sector` points at the worst sector, because RSRP is negative dBm) | Needle test | `sector − overall` (`d7c8f61`); `improvementDb` became a constructor property so `copy()` can set it (`8cd8e30`) |
| `withTimeoutOrNull` (suspend) called from a plain function in the sampler | Compile/CI | The heading read became a synchronous bounded latch (`2122a53`) |
| Missing `import RadioTimeline` in the timeline screen | CI | Added (`c16015f`) |
| Two-hop call site and two loose ends | CI | `ff9851f` |
| Reversed assertions in the instrumented timeline test — JUnit takes `(message, condition)`, `kotlin.test` takes `(actual, message)` | CI, four separate runs | Four commits, ending with every assertion message-first (`42e035f`, `a974019`, `616dbb8`, `a42f9e2`) |
| Probe stage durations measured before the work ran | CI | Moved after the work (`dfec808`) |
| A skipped stage expected a zero duration | CI | Expect `null` (`5b338d0`) |
| `kotlin.test` argument order in a source test | CI | `1c64efc` |
| Five platform API mistakes and three permission declarations | Real-device first run | `961b31c`; timing advance recorded as LTE-only in the public SDK (`5a9324f`) |
| Catalog tests silently shrinking the deliverable | Review | `95053e8` |
| B0 report destroyed by `connectedDebugAndroidTest` uninstalling the app | Device workflow | Install and instrument by hand; one command per line (`3054a99`, `f6cc38f`) |
| Font files not valid Android resource names; `ColorScheme` typed as the factory function | CI | `d493a39`, `4ef23a8`, `4732d0a` |
| Emulator job ran out of disk | CI | A disk-free step before the emulator (`7d9d92c`) |

The ledger verifier also re-derived B2's timeline constants and found the discrepancies already
recorded in `calculation-ledger.md`: the plan's per-sample row count is a factor of ten out
(1.08 × 10⁹, not 10¹⁰), the required positive sample count is 385, not 384, and a timing-advance
sentinel (`Int.MAX_VALUE`) appears live whenever the radio is idle and must be stored as absent,
never as a distance.

## Evidence from real hardware

- Both test phones run the debug build installed over the previous one, driven by hand with
  `adb install -r -t` and `am instrument` so the report on disk survives the test run.
- The B2 session on an A03 (LTE, SIM) recorded 60 samples at 1 Hz, CSV 61 lines. RSRP −105 dBm,
  RSRQ −12, RSSNR +1, level 3, with a drop from −99 to −105 mid-session.
- The two-hop probe found the gateway silent and the internet answering in 150 ms, and said so
  instead of misdiagnosing an offline network.
- The mini-throughput check moved 1.6 KB in 935 ms against a 160 ms ping: 0.00 Mbps, "data barely
  moves" — read at the time as a congested network. B3 found the probe itself at fault on two
  counts: the rate was computed with the wrong unit (bytes × 1000 / nanoseconds, six orders of
  magnitude low), and the payload path `/__down?bytes=1000000` was asked of the connectivity
  endpoint, which answers it with a 204/404 rather than a megabyte. So the sentence "data barely
  moves" was describing an error page. Both are fixed, and pinned by unit tests that need no
  network: `MiniThroughputProbe.rate` for the unit, `countBodyBytes` for the payload count.
- The compass declined with "not enough samples with a heading yet" because the phone lay flat on
  a table. Declining was correct; a direction would have been invented.
- The B3 build on the same A03 answered from the UI. Checkup returned **Fair**: the first hop
  (10.206.136.54, the DNS server the network advertises) did not answer port 80 in three rounds
  while `connectivitycheck.gstatic.com` answered in 165 ms, so the answer named the local link as
  filtering the probe rather than failing, and the path drew phone Fair → network Offline →
  internet Good. The 1 MB transfer in that same run moved 1,000,000 B in 1907 ms (4.19 Mbps).
- Speed on the same phone after the probe fix: 1,000,000 B in 2255 ms, 3.55 Mbps at a 203 ms ping -
  **Fair**, inside the ledger's 0.5 Mbps to 10 Mbps band, instead of the "data barely moves" the
  broken probe claimed.
- Signal ran a full 60 s live session: 60 samples, RSRP in 60 of 60, timing advance in 1 of 60,
  heading in 0 of 60. The last reading was -104 dBm RSRP, -1 dB RSSNR and no timing advance, drawn
  as "-" rather than as a distance, and the compass declined with "not enough samples with a
  heading yet" because the phone lay still on a table.
- Screenshots of the running app are in `device/`: `b3-checkup-idle.png`, `b3-checkup-answer.png`,
  `b3-checkup-evidence.png`, `b3-checkup-path.png`, `b3-speed-fixed.png`, `b3-signal-session.png`.

## CI and verification

Four jobs in `.github/workflows/ci.yml`:

1. Calculation ledger (independent re-derivation) — `tools/verify_ledger.py`, currently
   **86/86 constants match**, exit 0. It re-derives every constant from first principles in
   Python and compares against the Kotlin source, because a Kotlin test cannot catch a number
   that is wrong in both the constant and the test.
2. Unit tests (pure JVM kernel).
3. Android build and JVM unit tests — assembles the debug APK and compiles the instrumented
   tests.
4. Capability probe on device (informational) — an emulator run, marked informational because an
   emulator has no modem, no Wi-Fi RTT responder and no GNSS.

The development host has no Kotlin compiler and no Android SDK, so CI is the first place Kotlin
compiles. The workaround that stuck is to read whole files before pushing, audit files against
their own imports, and fix a class of errors in one pass rather than one error per seven-minute
CI cycle.

## Current state

- `main` is at `f7a0e45`, with PRs #1–#5 merged.
- `b3-native-chrome` is one commit ahead of `main` (`0f75f49`, the app frame). The working tree
  has an uncommitted change in [MainActivity.kt](../app/src/main/kotlin/dev/extranet/netdiag/app/MainActivity.kt)
  that renames the three tabs to Checkup, Speed and Signal and swaps the Signal icon — the first
  step toward the condensed product spec below.
- `:android:inference` and `:android:decision-sdk` are seams with no implementation, by design.
- The collective layer does not exist yet.

## Product direction: condensed spec

A tighter spec was agreed after B2, to replace the earlier five-experience document. The full
version is [product-spec.md](product-spec.md), and its vocabulary — Sample, Finding, Verdict and
DiagnosisState — is implemented in `core/.../verdict/`. The short form:

- **Principle:** one question, one measurement, one answer, one action.
- **Tabs:** Checkup, Speed, Signal. Hotspot and History become contextual or secondary screens.
- **Result shape:** What happened → Where is the problem → What to do, with three disclosure
  levels: Answer → Why → Evidence.
- **Diagnosis states:** Good, Fair, Slow, Weak, Offline, Checking, Unknown. When confidence is
  low, say "couldn't determine the cause" rather than guessing.
- **One reusable component:** the Path Diagram — Phone → Network/Hotspot/Router → Internet, one
  state per hop — powering Checkup, Hotspot and Wi-Fi diagnosis.
- **MVP loop:** Checkup → Speed → Signal (live, scan, best spot) → Hotspot diagnosis → Settings,
  about 11 screens.
- **Five layers:** Presentation (verdicts only, never raw radio data) ← Decision (pure rules to
  state plus plain-language copy) ← Inference (cause and confidence, path model, smoothed
  warmer/colder trend — never a direction claim) ← Measurement (collectors emitting timestamped
  samples with status ok/failed/unavailable, where missing is explicit and never zero) ← Android
  APIs.
- **Rules:** a confidence threshold below which no diagnosis or direction is shown; failure
  states per screen (no network, permission denied, test interrupted); a privacy line on anything
  stored; foreground service only during tests and scans; measurement and inference testable
  without a phone by replaying recorded sample files; Room for derived results and saved spots.
- **Later:** history and patterns, multi-SIM comparison, connection timeline, notifications, Ask
  NetDiag, personal maps, collective upload queue with a privacy filter.

## Documents

| File | Contents |
|---|---|
| `README.md` | What the project is, how to build it, how to run each harness |
| `architecture.md` | Layers, module boundaries, data flow, the privacy rule, deliberate omissions |
| `product-spec.md` | The three tabs, the result shape, the diagnosis states, the five layers, the rules |
| `calculation-ledger.md` | Every constant with its derivation, plus the discrepancies found in the plan |
| `probe-engine.md` | The four stages, the statistics, the exit criterion, how to run it |
| `b0-device-run.md` | How to obtain the device report and what has and has not been executed |
| `project-history.md` | This file — the work itself |
