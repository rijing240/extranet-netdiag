# Architecture

## Layering

The blueprint describes five processing layers plus a parallel collective layer. B0 only builds
the bottom of that stack, but the module boundaries are fixed now so later batches have
somewhere to land.

```
L0 Sensors            :android:sensor-core      radio timeline, GNSS, context, sessions  (B2/B3/B5)
L1 Normalization      :probe, :core             per-device calibration, ranges, budgets  (B0 done)
L2 Feature/State      :android:inference        coverage / interference / load triple     (B8)
L3 Classification     :android:inference        deadzone vs DNS vs core outage            (B8/B9)
L4 Policy / Decision  :android:decision-sdk     NetworkConfidence, offline tripwire       (B10)
L5 Presentation       :app                      waterfall, forecast, relative index       (B11)

L-C Collective        collective/ (Worker+TS)   ingest, Capacity Atlas, baselines         (B6/B7)
```

## Module boundaries

```
:core                       pure Kotlin/JVM, zero Android dependency
  ledger/                   the calculation ledger — the single source of truth
  privacy/                  geohash encoder, pseudonymous cell key hasher
  json/                     dependency-free JSON emitter
  report/                   SystemId, SupportStatus, CapabilityFinding, CapabilityReport

:probe                      pure Kotlin/JVM, depends on :core
  ProbeSpec / ProbeKind     what to ask, and how it has to be asked
  ProbeCatalog              every platform capability the project depends on
  CapabilityProbeRunner     classification rules, behind PlatformReportSource
  SupportMatrix             S7 aggregation across device reports

:android:sensor-core        S1 + X2
  SampleBudget              sampling policy: 1 Hz cap, duty cycling, thermal governor

:android:measurement        S2 + S7 platform adapters
  AndroidPlatformProbe      PlatformReportSource implemented against real APIs
  AsyncCapabilitySession    live-session probe for GNSS, telephony, network transitions

:android:inference          S3 seam — DropRiskScorer, deliberately unimplemented until B9
:android:decision-sdk       S4 seam — NetworkConfidence, deliberately unimplemented until B10
:app                        S6 — capability probe screen + ProbeHarness
```

### Why the engine is split from the platform

`CapabilityProbeRunner` never touches Android. It runs against `PlatformReportSource`, a
four-member seam. That is what makes the classification table — below-API-level, permission,
feature, not-probed, then the platform's own answer, in that order — testable on any machine,
and it means the Android module contains almost no logic that could be wrong: it renders values
and translates exceptions.

## Data flow: capability probe (B0)

```
ProbeCatalog.ALL ──► CapabilityProbeRunner ──► CapabilityReport ──► JSON on disk
                            │                        │
              PlatformReportSource              SupportMatrix.build()   (S7, across devices)
                            │
        AndroidPlatformProbe │ AsyncCapabilitySession
        (synchronous reads)  │ (GNSS measurements, telephony callbacks,
                             │  GNSS status, network transitions)
```

## Data flow: telemetry (target, B6 onward)

```
S1 sensors ─┐
S2 probes  ─┼─► on-device ring buffer ─► features ─► model ─► policy ─► action
            │                                  │
            └────── S6 dashboard ◄─────────────┘
                               │
                  session summary (~25 kB, hashed cell key)
                               ▼
              S5 Worker ─► Postgres ─► Atlas aggregation ─► baselines back to device
```

## The privacy rule, and why it is also an architecture constraint

**Raw per-second detail never leaves the phone.** Only ~25 kB session summaries go up, keyed by
salted hashed cell keys.

This is three decisions wearing one hat:

1. **Privacy.** No raw coordinate or cell identity leaves the device, so there is nothing to
   subpoena or leak. `CellKeyHasher` is pseudonymisation, not anonymisation: the salt is a
   server-side secret and is never shipped in the APK, which makes it a revocation tool.
2. **Bandwidth.** At 10,000 daily actives, session summaries are 7.5 GB per month. Shipping the
   raw timeline instead would be tens of gigabytes and would need a paid tier immediately.
3. **Database survival.** One row per second per device is **1.08 × 10⁹ rows per month**
   (`ByteBudget.fleetRowsIfPerSamplePerMonth`). Aggregating on the device brings the same fleet
   to 300,000 rows per month. No free-tier Postgres accepts the first number, and no amount of
   server-side rolling up fixes it, because the rows have to be written before they can be
   aggregated.

Aggregation therefore happens *on the device*, and the Atlas publishes a bucket only when
`Atlas.MIN_BUCKET_SIZE` independent observations back it.

## Deliberate omissions

- **No positioning.** RSRP multilateration is recorded as retired in
  `RetiredCalculations.RSRP_MULTILATERATION`: Android exposes no tower coordinates, and timing
  advance is available for the serving cell only, so three independent ranges cannot be
  obtained. `FusedLocationProvider` already solves this better and for free.
- **No path-loss inversion.** Two unknowns, one observation. See
  `RetiredCalculations.PATH_LOSS_EXPONENT`.
- **No Little's Law.** An identity, not a predictor, and its arrival-rate term is unobservable.
  Replaced by the loaded-minus-idle RTT delta in `Bufferbloat`.
- **No radar map on the dashboard.** Superseded by the waterfall and the forecast timeline,
  which are the two components no free OS API already provides.
