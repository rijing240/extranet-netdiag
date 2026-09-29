# Calculation ledger

Every physical constant and derived quantity the project depends on, with its derivation. The
ledger lives in `core/src/main/kotlin/dev/extranet/netdiag/core/ledger/` as named Kotlin
constants, is pinned by unit tests in `core/src/test/`, and is independently re-derived by
`tools/verify_ledger.py`.

**No later batch may re-derive any of these.** Add a constant here instead.

```bash
python3 tools/verify_ledger.py    # 59 constants + 2 geohash vectors, exit 0 when they agree
```

## Timing advance

Derived in `TimingAdvance.kt`.

| Quantity | Derivation | Value |
|---|---|---|
| Basic time unit Ts | `1 / (15000 × 2048)` | 32.552083 333 ns |
| One command step | `16 × Ts` | 520.8333 ns (round trip) |
| **Metres per command step** | `16Ts × c / 2`, `c = 299792458` | **78.0709526 m** |
| Same, rounded c | `16Ts × 3×10⁸ / 2` | 78.125 m |
| Metres per reported basic unit | `78.0709526 / 16` | 4.8794345 m |
| Half-step quantisation | `78.0709526 / 2` | 39.0355 m |
| **GSM step** | `(48/13 µs) × c / 2` | **553.463 m** |

Android's `CellSignalStrengthLte.getTimingAdvance()` reports **basic time units**, not command
steps — one command step is 16 basic units. This is why `distanceMetresFromBasicUnits` and
`distanceMetresFromCommandSteps` are separate functions, and why `rangeFromBasicUnits` returns a
`RangeBand` rather than a `Double`: the plan's decision is that timing advance ships as a range
band and never as a map pin.

`conservativeRangeFromBasicUnits` is deliberately **asymmetric**: non-line-of-sight propagation
can only lengthen the path, so the band extends `[−50 m, +150 m]` around the estimate.

## Shannon–Hartley

`C = B × log₂(1 + 10^(SINR/10))`, derived in `ShannonHartley.kt`.

| SINR | 20 MHz capacity |
|---|---|
| −5 dB | **7,928,183.2 bps** (7.93 Mbps) |
| 0 dB | **20,000,000 bps** (20.00 Mbps, exact) |
| 10 dB | **69,188,632.4 bps** (69.19 Mbps) |
| 20 dB | **133,164,229.7 bps** (133.16 Mbps) |

**Usage restriction.** This is the capacity of an *entire isolated channel*. A handset gets a
scheduler grant on a sector shared with every other active user, so an "efficiency" dial showing
"you are using 4% of the available pipe" reads roughly 4% on a perfectly healthy network. The
ledger therefore exposes `relativeCapacityUsage` and `docs/architecture.md` forbids rendering it
as an absolute gauge. The metric is only meaningful against the same cell's own baseline history
— which is what the Capacity Atlas (B7) exists to supply.

## Doppler velocity

`f_d = (v · u) / λ`, derived in `Doppler.kt`.

| Quantity | Value |
|---|---|
| GPS L1 carrier | 1,575,420,000 Hz |
| **L1 wavelength λ** | **0.19029419 m** |
| **σ_v at 1 Hz Doppler noise** | **0.19029 m/s** |
| σ_v at 2 Hz (worst case) | 0.38059 m/s |
| NR n78 wavelength (3.5 GHz) | 0.085655 m |

Android already exposes the converted quantity as
`GnssMeasurement.getPseudorangeRateMetersPerSecond()`, so the ledger keeps the wavelength only as
the uncertainty budget. Unlike coordinate differencing, this error does **not** grow with the
sampling interval — that is why Doppler speed reacts faster and more smoothly than GPS speed.
Solving for the velocity vector needs 4 unknowns (3 velocity components + receiver clock drift)
and therefore ≥ 4 satellites.

## Wi-Fi RTT

`d = c × RTT / 2`, derived in `WifiRtt.kt`.

| Round trip | Distance |
|---|---|
| 20 ns | **2.99792458 m** (3.00 m with rounded c) |
| 40 ns | 5.99584916 m |

Documented individual-measurement accuracy is 1–2 m, so `WifiRtt.rangeMetres` returns a band of
`TYPICAL_ACCURACY_METRES`. Availability, not arithmetic, is the constraint: this needs an AP
implementing the FTM responder side, so every call site is gated on
`WifiRtt.REQUIRED_FEATURE` (`android.hardware.wifi.rtt`).

## Bufferbloat

`Δ = loaded_p50 − idle_p50`, derived in `Bufferbloat.kt`. This is the replacement for the retired
Little's Law. Grades: A < 30 ms, B < 60 ms, C < 150 ms, D < 400 ms, F otherwise.

Stability needs ~30 samples per state, so a real test runs 60–75 s and saturates the link. The
payload ceiling is `MAX_TEST_BYTES` = 10 MB, and the capped variant saturates 5 Mbps for 15 s =
9,375,000 bytes. An uncapped 20 Mbps test would exhaust the ceiling in 4 seconds — which is why
the test is user-initiated only.

## The model gate

`n = z² p (1−p) / e²`, derived in `ModelGate.kt`.

At 95% confidence, worst-case p = 0.5, ±5 percentage points: **385 positive events**.

| Scenario | Time to 385 positives |
|---|---|
| One handset at a 2% loss base rate | 19,250 observed minutes = **320.8 hours** |
| 200 users × 60 min/day | **1.60 days** |

This asymmetry is the reason the data-collecting consumer app (B1–B7) must ship publicly long
before the paid prediction SDK (B8–B10). Without the fleet there is no dataset.

## Storage and bandwidth budgets

Derived in `ByteBudget.kt`.

| Quantity | Value |
|---|---|
| Radio sample | 26 bytes; 1 Hz → **93,600 B/hour** |
| GNSS burst (30 s cap) | 8 sats × 18 B × 10 Hz × 30 s = **43,200 B** |
| Session payload | 150,000 B raw → **25,000 B** uploaded |
| Fleet upload | 10k DAU × 25 kB × 30 d = **7.5 GB/month** |
| Rows/month, one row per sample | **1,080,000,000** |
| Rows/month, summarised per session | **300,000** |

The last two rows are 3,600× apart and are the reason on-device aggregation is mandatory rather
than an optimisation.

## Atlas bucketing

Derived in `Atlas.kt`.

| Parameter | Value |
|---|---|
| Geohash precision | 7 (~153 m × 153 m) |
| Minimum bucket size | 5 independent observations |
| Hour buckets | 24 |
| Cell key length | 16 hex characters (64 bits) |

Cell keys are `SHA-256(salt ‖ geohash ‖ earfcn ‖ pci ‖ tac)` truncated to 16 hex characters,
with a `0x1F` field separator so `("a","bc")` and `("ab","c")` cannot collide. Verified against
an independently computed vector in `PrivacyTest`.

## Probe engine budget (B1, `MeasurementBudget`)

| Parameter | Value | Why this number |
|---|---|---|
| Probe sets per run | 100 | B1's exit criterion |
| Failure-rate ceiling | 0.05, exclusive | allows sporadic packet loss, not a stage that is broken on every set |
| Typical set | 1,000 ms | a healthy four-stage set is ~150 ms; this is the budgeting figure, not a measurement |
| Wall-clock cap | 200,000 ms | twice the typical budget for a full run, so a merely slow network still finishes |
| DNS / TCP / TLS / TTFB budgets | 2,000 / 3,000 / 3,000 / 5,000 ms | per-stage, so one dead stage cannot consume the run |
| Platform report wait | 3,000 ms | how long the OS gets to deliver its connectivity report |
| DNS / HTTPS ports | 53 / 443 | the ports the probe actually opens |
| Percentiles reported | p50, p95 | nearest rank, never interpolated |

The cap and the criterion are in tension, and the arithmetic is pinned so nobody has to
rediscover it: a set's worst case is 13,000 ms, so a run in which every stage of every set times
out can only finish **15** of the 100 sets before the cap stops it. That is deliberate — the
report then says it was truncated, and a truncated run is not allowed to claim the criterion.

## Discrepancies found in the approved plan

Recorded rather than silently absorbed. None of them changes a decision; two of them changed a
number.

1. **Per-sample row count.** The plan states 1.08 × 10¹⁰ rows/month. The derivation is
   `10,000 × 3,600 × 30` = **1.08 × 10⁹** — a factor-of-ten slip. The conclusion is unchanged:
   a billion rows per month is still fatal for a free-tier database.
2. **Required positive samples.** The plan quotes 384. `ceil(1.96² × 0.25 / 0.05²)` is **385**.
   The ledger uses the ceiling, because a shortfall is the failure mode that matters.
3. **Timing-advance quantisation.** The plan says "±80 m quantization". Half a command step is
   **±39.04 m**; one full step is 78.07 m. The plan's figure is the conservative reading (one
   whole step treated as the error), and `rangeFromBasicUnits` uses the tight bound while
   `conservativeRangeFromBasicUnits` additionally applies the NLOS bias.
4. **Shannon anchors.** The plan's rounded "7.9 Mbps" at −5 dB is 28 kbps away from the true
   7.93 Mbps. The ledger carries full precision so the value can serve as a regression baseline.

## Retired calculations

Recorded in `RetiredCalculations.kt`, asserted present by `RetiredCalculationsTest`. These are
kept as reviewable data rather than deleted, because each is plausible enough that a future
contributor would otherwise reinvent it.

| id | Formula | Why it cannot be implemented | Replacement |
|---|---|---|---|
| `rsrp-multilateration` | `min Σ(‖p−pᵢ‖ − dᵢ)²` | Android exposes no tower coordinates to third-party apps (and MLS was sunset in 2024); TA is serving-cell only, so three independent ranges cannot be obtained; sector antennas make the constraint an arc, not a circle | Do not position. `FusedLocationProvider` already does it better, for free |
| `path-loss-exponent` | `RSSI = Ptx − 10n·log₁₀d − C` | Two unknowns (Ptx, d), one observation; and RSRP is reference-signal power, not narrowband RSSI | GNSS C/N0 collapse, barometric delta, low-band shift, RAT downgrade, TA trend |
| `littles-law` | `L = λW` | An identity, not a predictor; λ (packet arrival rate) is unobservable — `NetworkStatsManager` returns polled byte counters | Loaded-minus-idle RTT delta (`Bufferbloat.deltaMilliseconds`) |
