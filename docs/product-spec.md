# Product spec

What the app is meant to be, condensed from the two longer drafts into the version that gets
built. It fixes three things: what the app asks, what it is allowed to say, and how the screens
fit together. `architecture.md` keeps the batch plan, the module boundaries and the data flows;
this file is the reference for what a screen may show.

## What the earlier drafts got wrong, and what changed

- **Navigation conflicted.** One draft proposed five experiences (Find, Speed, Check, Hotspot,
  History); another proposed three tabs. Three tabs stayed: **Checkup, Speed, Signal**. Hotspot
  and History became contextual or secondary screens, reached from a result or from Settings.
- **The compass overpromised.** Radio readings have no direction; only a change in them as the
  user moves says anything. It ships as a warmer / colder trend first, and an arrow appears only
  when the motion sensors and the sample density support one.
- **"Network Health 72%" was undefined.** Every result shows one of the seven diagnosis states
  instead. A percentage can return only with a written formula and weights.
- **Too much was specified at once.** Ask NetDiag, Compare Two Phones and the collective layer
  are roadmap, not spec. They are in Later.

## The principle

One question, one measurement, one answer, one action.

## The three tabs

| Tab | The one question | The measurement |
|---|---|---|
| Checkup | Is my connection working? | Reachability and the path to the internet |
| Speed | How fast is it, really? | Throughput, with latency beside it |
| Signal | Where is the signal better? | Live radio readings, a scan, and the best spot found |

Hotspot diagnosis and History are not tabs. They start from a result or from Settings.

## Every result has the same shape

What happened → Where is the problem → What to do.

Details unfold in three layers: **Answer → Why → Evidence**. The answer is a diagnosis state and
one sentence, "why" is the cause, "evidence" is the numbers that produced it.

## Diagnosis states

Good, Fair, Slow, Weak, Offline, Checking, Unknown.

Below the confidence floor the app says **"Couldn't determine the cause"** rather than guessing.
Unknown is a real outcome, not an error: it is what the app shows when the evidence does not
support a diagnosis.

## One reusable diagram

The Path Diagram: Phone → Network/Hotspot/Router → Internet, with one state per hop. It powers
Checkup, Hotspot and Wi-Fi diagnosis, so those screens cannot disagree about where the problem
is. The model says which hop failed, not which box sits there.

## The MVP loop

Checkup → Speed → Signal (live, scan, best spot) → Hotspot diagnosis → Settings. About eleven
screens including their failure states.

## The five layers

Each layer talks only to the one below it. The UI never sees raw radio data, only a verdict.

```
UI (Checkup · Speed · Signal)
        ↑ Verdict
DECISION     rules → state + plain-language copy
        ↑ Findings
INFERENCE    classify cause + confidence
        ↑ Measurements
MEASUREMENT  radio · probes · Wi-Fi/hotspot hops · speed
        ↑
ANDROID      TelephonyManager, ConnectivityManager, sockets
```

1. **Measurement.** Collectors run independently: radio (RSRP, RSSNR, technology), gateway ping,
   DNS/TCP/TLS/first-byte, throughput, and Wi-Fi/hotspot link info. Each emits timestamped
   samples with a status (ok, failed, unavailable). Missing data is explicit, never zero. A
   foreground service runs during tests and scans; nothing runs in the background by default.
   The one request the app makes that nobody asked for is the withdrawal switch, read once at
   launch so a retired build can stop working; it sends nothing but its own address and it fails
   open, so it cannot affect a phone that is offline. See `docs/release-and-updates.md`.
2. **Inference.** Turns samples into findings like `signal: weak`, `hop2: slow`,
   `cause: upstream`, each with a confidence. The path model gives one state per hop. The signal
   trend uses a smoothed moving window to say warmer / colder; it never claims a direction.
3. **Decision.** A pure rules function: findings → Verdict { state, what, where, action,
   evidence }. States as above. Below the confidence floor it returns Unknown. Copy lives here,
   so wording is testable and the UI stays dumb.
4. **Presentation.** ViewModels observe a verdict and render the three disclosure levels. Each
   screen is a state machine: idle → running → result / error / permission-needed.
5. **Storage.** Room holds speed results, scan results and saved spots. Only derived results and
   coarse information, with a clear delete option. History and patterns come later as queries
   over the same tables.

The collective layer sits beside the Decision layer and changes none of the core: an upload queue
with a privacy filter, no raw coordinates and no cell IDs.

The vocabulary is implemented in
`core/src/main/kotlin/dev/extranet/netdiag/core/verdict/`: `Sample`, `Finding`, `Verdict`,
`DiagnosisState`. A sample carries no number unless it happened; a finding cannot be a test in
progress and must cite evidence; a verdict cannot name a cause while it is Unknown, and
`Verdict.withheldBelow(floor, retryAction)` is the confidence rule itself.

## Rules that keep it sane

- Measurement and inference are testable without a phone: recorded sample files replay through
  the same code.
- Every verdict carries confidence and evidence, so Unknown is a first-class outcome.
- Permission and failure states are part of the machine, not afterthoughts: no network,
  permission denied, test interrupted.
- A confidence threshold gates every diagnosis and every direction. Below it the answer is
  Unknown.
- Anything stored carries a privacy line where it is stored: saved spots, history.

## Later (roadmap, not spec)

History and patterns, multi-SIM comparison, connection timeline, notifications, Ask NetDiag,
personal maps, the collective data layer.

## Open decisions

- The value of the confidence floor. The code takes it as a parameter; no number has been agreed.
  0.5 is a proposed starting point, not a decision.
- The exact screen inventory behind "about eleven".
- The formula and weights, if a percentage figure ever returns.
