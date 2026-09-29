#!/usr/bin/env python3
"""Independently re-derive every constant in the calculation ledger and check the Kotlin
source actually says what the plan claims.

Why this exists: the ledger is the one place where a wrong number silently propagates into
every later batch. The Kotlin unit tests pin the values inside the Kotlin build, but they
cannot catch a number that is wrong in *both* the constant and the test, because the same
transcription error is made twice. This script recomputes each quantity from first principles
in a different language, reads the literal (or the arithmetic expression) out of the `.kt`
file, resolves identifiers between declarations, and asserts the two agree.

Run:  python tools/verify_ledger.py
Exit: 0 when every constant matches, 1 otherwise.
"""

from __future__ import annotations

import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LEDGER = ROOT / "core" / "src" / "main" / "kotlin" / "dev" / "extranet" / "netdiag" / "core"

# ---------------------------------------------------------------------------------------
# first-principles values
# ---------------------------------------------------------------------------------------

C_EXACT = 299_792_458.0          # m/s, SI definition
C_ROUNDED = 300_000_000.0        # m/s, historical basis of the 78.125 m TA step

SUBCARRIER_HZ = 15_000.0
FFT_SIZE = 2_048
TS_DENOM = SUBCARRIER_HZ * FFT_SIZE                      # 30_720_000
TS = 1.0 / TS_DENOM                                       # basic time unit, seconds
TA_STEP_UNITS = 16
TA_STEP_S = TS * TA_STEP_UNITS                            # 520.8333 ns round trip
TA_STEP_M = TA_STEP_S * C_EXACT / 2.0                     # 78.070952... m
TA_STEP_M_APPROX = TA_STEP_S * C_ROUNDED / 2.0            # 78.125 m
METRES_PER_BASIC_UNIT = TA_STEP_M / TA_STEP_UNITS         # 4.879434... m
QUANT_HALF_STEP_M = TA_STEP_M / 2.0                       # 39.0354... m

GSM_BIT_S = 48.0 / 13.0 * 1.0e-6
GSM_STEP_M = GSM_BIT_S * C_EXACT / 2.0                    # 553.46 m

L1_HZ = 1_575_420_000.0
L1_WAVELENGTH_M = C_EXACT / L1_HZ                         # 0.190294... m
N78_HZ = 3_500_000_000.0
N78_WAVELENGTH_M = C_EXACT / N78_HZ                       # 0.085655 m

REF_BW_HZ = 20_000_000.0


def shannon(bandwidth_hz: float, sinr_db: float) -> float:
    """C = B * log2(1 + 10^(SINR/10))."""
    return bandwidth_hz * math.log2(1.0 + 10.0 ** (sinr_db / 10.0))


Z_95 = 1.96
Z_90 = 1.6448536269514722


def required_positive_samples(z: float = Z_95, margin: float = 0.05, p: float = 0.5) -> int:
    """n = z^2 * p * (1-p) / e^2, rounded up."""
    return math.ceil(z * z * p * (1.0 - p) / (margin * margin))


POSITIVES = required_positive_samples()
BASE_RATE = 0.02
OBSERVED_MINUTES = POSITIVES / BASE_RATE
SINGLE_DEVICE_HOURS = OBSERVED_MINUTES / 60.0
FLEET_USERS = 200
MINUTES_PER_USER_PER_DAY = 60.0
FLEET_DAYS = OBSERVED_MINUTES / (FLEET_USERS * MINUTES_PER_USER_PER_DAY)

RADIO_SAMPLE_BYTES = 26
GNSS_BYTES_PER_EPOCH = 144
GNSS_EPOCHS_PER_SECOND = 10
GNSS_BURST_SECONDS = 30
SESSION_COMPRESSED_BYTES = 25_000
DAU = 10_000

# B1's probe budget. The gate and the cap are the pair worth re-deriving: the criterion asks for
# 100 sets, and the cap has to be large enough that a merely slow network still finishes them
# while a dead one is cut off in minutes rather than hours.
PROBE_SETS_PER_RUN = 100
PROBE_FAILURE_CEILING = 0.05
PROBE_TYPICAL_SET_MS = 1_000
PROBE_WALL_CLOCK_CAP_MS = PROBE_SETS_PER_RUN * PROBE_TYPICAL_SET_MS * 2
DNS_TIMEOUT_MS = 2_000
TCP_TIMEOUT_MS = 3_000
TLS_TIMEOUT_MS = 3_000
TTFB_TIMEOUT_MS = 5_000
OS_DIAGNOSTICS_WAIT_MS = 3_000
DNS_PORT = 53
HTTPS_PORT = 443
PERCENTILE_P50 = 0.50
PERCENTILE_P95 = 0.95
PROBE_WORST_CASE_SET_MS = DNS_TIMEOUT_MS + TCP_TIMEOUT_MS + TLS_TIMEOUT_MS + TTFB_TIMEOUT_MS

# ---------------------------------------------------------------------------------------
# Kotlin source extraction
# ---------------------------------------------------------------------------------------

_DECL = re.compile(
    r"^\s*(?:public\s+)?(?:const\s+)?val\s+(?P<name>[A-Z][A-Z0-9_]*)"
    r"\s*:\s*(?P<type>Double|Int|Long|Float)\s*=\s*(?P<rhs>[^\n]+?)\s*$",
    re.MULTILINE,
)

# An identifier token, but not the exponent of a float literal such as `1.0e-6`.
_IDENT = re.compile(r"(?<![0-9.])([A-Za-z_][A-Za-z0-9_.]*)")

# Only these characters may appear in a numeric initialiser once identifiers are substituted.
_SAFE = re.compile(r"^[0-9_.eE+\-*/() ]+$")


def eval_numeric(expr: str) -> float:
    """Evaluate a Kotlin numeric initialiser containing only arithmetic on numeric literals."""
    cleaned = expr.replace("_", "").strip()
    if cleaned.endswith(("L", "f", "F")):
        cleaned = cleaned[:-1]
    if not _SAFE.match(cleaned):
        raise ValueError(f"refusing to evaluate non-numeric initialiser: {expr!r}")
    return float(eval(cleaned, {"__builtins__": {}}, {}))  # noqa: S307 - whitelisted charset


def load_constants() -> dict[str, float]:
    """Extract every numeric ledger constant, resolving references between declarations."""
    files = sorted(LEDGER.rglob("*.kt"))
    if not files:
        raise SystemExit(f"no Kotlin sources found under {LEDGER}")

    raw: dict[str, str] = {}
    for path in files:
        for match in _DECL.finditer(path.read_text(encoding="utf-8")):
            rhs = match.group("rhs").strip()
            if rhs.startswith("//"):
                continue
            raw.setdefault(match.group("name"), rhs)

    resolved: dict[str, float] = {}
    for _ in range(len(raw) + 2):
        progressed = False
        for name, rhs in raw.items():
            if name in resolved:
                continue
            substituted = _IDENT.sub(
                lambda mo: (
                    repr(resolved[mo.group(1).split(".")[-1]])
                    if mo.group(1).split(".")[-1] in resolved
                    else mo.group(0)
                ),
                rhs,
            )
            if _IDENT.search(substituted):
                continue
            try:
                resolved[name] = eval_numeric(substituted)
                progressed = True
            except ValueError:
                continue
        if not progressed:
            break
    return resolved


# ---------------------------------------------------------------------------------------
# reporting
# ---------------------------------------------------------------------------------------

class Report:
    def __init__(self, constants: dict[str, float]) -> None:
        self.constants = constants
        self.rows: list[tuple[bool, str, str, str]] = []

    def check(self, label: str, expected: float, kotlin_name: str, tol: float) -> None:
        actual = self.constants.get(kotlin_name)
        if actual is None:
            self.rows.append((False, label, f"{expected:.6g}", "MISSING"))
            return
        ok = abs(expected - actual) <= tol
        self.rows.append((ok, label, f"{expected:.6g}", f"{actual:.6g}"))

    def emit(self) -> int:
        width = max(len(r[1]) for r in self.rows) + 2
        failures = sum(1 for r in self.rows if not r[0])
        print(f"{'':4} {'quantity'.ljust(width)} {'derived'.rjust(16)} {'in kotlin':>16}")
        print("-" * (width + 42))
        for ok, label, exp, act in self.rows:
            print(f"{'ok' if ok else 'FAIL':>4} {label.ljust(width)} {exp.rjust(16)} {act:>16}")
        print("-" * (width + 42))
        print(f"{len(self.rows) - failures}/{len(self.rows)} constants match")
        return 1 if failures else 0


def geohash_encode(latitude: float, longitude: float, precision: int) -> str:
    """Independent geohash implementation, used to cross-check published test vectors."""
    alphabet = "0123456789bcdefghjkmnpqrstuvwxyz"
    lat_min, lat_max = -90.0, 90.0
    lon_min, lon_max = -180.0, 180.0
    out: list[str] = []
    ch = bits = 0
    longitude_next = True
    while len(out) < precision:
        if longitude_next:
            mid = (lon_min + lon_max) / 2.0
            if longitude >= mid:
                ch = (ch << 1) | 1
                lon_min = mid
            else:
                ch <<= 1
                lon_max = mid
        else:
            mid = (lat_min + lat_max) / 2.0
            if latitude >= mid:
                ch = (ch << 1) | 1
                lat_min = mid
            else:
                ch <<= 1
                lat_max = mid
        longitude_next = not longitude_next
        bits += 1
        if bits == 5:
            out.append(alphabet[ch])
            bits = ch = 0
    return "".join(out)


def main() -> int:
    constants = load_constants()
    r = Report(constants)

    r.check("SPEED_OF_LIGHT_MPS", C_EXACT, "SPEED_OF_LIGHT_MPS", 1e-9)
    r.check("ROUNDED_SPEED_OF_LIGHT_MPS", C_ROUNDED, "ROUNDED_SPEED_OF_LIGHT_MPS", 1e-9)
    r.check("METRES_PER_KILOMETRE", 1_000.0, "METRES_PER_KILOMETRE", 0)

    r.check("SUBCARRIER_SPACING_HZ", SUBCARRIER_HZ, "SUBCARRIER_SPACING_HZ", 1e-9)
    r.check("FFT_SIZE", FFT_SIZE, "FFT_SIZE", 0)
    r.check("BASIC_TIME_UNIT_DENOMINATOR", TS_DENOM, "BASIC_TIME_UNIT_DENOMINATOR", 1e-6)
    r.check("BASIC_TIME_UNIT_SECONDS", TS, "BASIC_TIME_UNIT_SECONDS", 1e-20)
    r.check("COMMAND_STEP_SECONDS", TA_STEP_S, "COMMAND_STEP_SECONDS", 1e-20)
    r.check("COMMAND_STEP_METRES", TA_STEP_M, "COMMAND_STEP_METRES", 1e-9)
    r.check("COMMAND_STEP_METRES_APPROX", TA_STEP_M_APPROX, "COMMAND_STEP_METRES_APPROX", 1e-9)
    r.check("COMMAND_STEP_BASIC_UNITS", TA_STEP_UNITS, "COMMAND_STEP_BASIC_UNITS", 0)
    r.check("METRES_PER_BASIC_UNIT", METRES_PER_BASIC_UNIT, "METRES_PER_BASIC_UNIT", 1e-12)
    r.check("QUANTISATION_HALF_STEP_METRES", QUANT_HALF_STEP_M, "QUANTISATION_HALF_STEP_METRES", 1e-12)
    r.check("GSM_BIT_SECONDS", GSM_BIT_S, "GSM_BIT_SECONDS", 1e-20)
    r.check("GSM_COMMAND_STEP_METRES", GSM_STEP_M, "GSM_COMMAND_STEP_METRES", 1e-9)
    r.check("NLOS_BIAS_MIN_METRES", 50.0, "NLOS_BIAS_MIN_METRES", 0)
    r.check("NLOS_BIAS_MAX_METRES", 150.0, "NLOS_BIAS_MAX_METRES", 0)

    r.check("REFERENCE_BANDWIDTH_HZ", REF_BW_HZ, "REFERENCE_BANDWIDTH_HZ", 1e-9)
    r.check("ANCHOR_SINR_10_DB_BPS", shannon(REF_BW_HZ, 10.0), "ANCHOR_SINR_10_DB_BPS", 1.0)
    r.check("ANCHOR_SINR_0_DB_BPS", shannon(REF_BW_HZ, 0.0), "ANCHOR_SINR_0_DB_BPS", 1e-6)
    r.check("ANCHOR_SINR_20_DB_BPS", shannon(REF_BW_HZ, 20.0), "ANCHOR_SINR_20_DB_BPS", 1.0)
    r.check("ANCHOR_SINR_MINUS_5_DB_BPS", shannon(REF_BW_HZ, -5.0), "ANCHOR_SINR_MINUS_5_DB_BPS", 1.0)

    r.check("L1_CARRIER_HZ", L1_HZ, "L1_CARRIER_HZ", 1e-3)
    r.check("L1_WAVELENGTH_METRES", L1_WAVELENGTH_M, "L1_WAVELENGTH_METRES", 1e-12)
    r.check("NR_N78_CARRIER_HZ", N78_HZ, "NR_N78_CARRIER_HZ", 1e-3)
    r.check("NR_N78_WAVELENGTH_METRES", N78_WAVELENGTH_M, "NR_N78_WAVELENGTH_METRES", 1e-12)
    r.check("L1_VELOCITY_SIGMA_MPS", L1_WAVELENGTH_M, "L1_VELOCITY_SIGMA_MPS", 1e-12)
    r.check("DOPPLER_SIGMA_HZ_MIN", 0.5, "DOPPLER_SIGMA_HZ_MIN", 0)
    r.check("DOPPLER_SIGMA_HZ_MAX", 2.0, "DOPPLER_SIGMA_HZ_MAX", 0)
    r.check("VELOCITY_SOLVE_UNKNOWNS", 4, "VELOCITY_SOLVE_UNKNOWNS", 0)
    r.check("VELOCITY_SOLVE_MIN_SATELLITES", 4, "VELOCITY_SOLVE_MIN_SATELLITES", 0)

    r.check("TYPICAL_ACCURACY_METRES", 2.0, "TYPICAL_ACCURACY_METRES", 0)
    r.check("MIN_PLAUSIBLE_ROUND_TRIP_NANOS", 1, "MIN_PLAUSIBLE_ROUND_TRIP_NANOS", 0)

    r.check("Z_95", Z_95, "Z_95", 1e-12)
    r.check("Z_90", Z_90, "Z_90", 1e-12)
    r.check("DEFAULT_MARGIN", 0.05, "DEFAULT_MARGIN", 1e-12)
    r.check("WORST_CASE_PROPORTION", 0.5, "WORST_CASE_PROPORTION", 1e-12)
    r.check("DEFAULT_LOSS_BASE_RATE", BASE_RATE, "DEFAULT_LOSS_BASE_RATE", 1e-12)
    r.check("DEFAULT_MINUTES_PER_USER_PER_DAY", MINUTES_PER_USER_PER_DAY,
            "DEFAULT_MINUTES_PER_USER_PER_DAY", 1e-12)
    r.check("PLANNED_FLEET_USERS", FLEET_USERS, "PLANNED_FLEET_USERS", 0)

    r.check("SAMPLES_PER_STATE", 30, "SAMPLES_PER_STATE", 0)
    r.check("NOMINAL_TEST_SECONDS", 75.0, "NOMINAL_TEST_SECONDS", 0)
    r.check("MAX_TEST_BYTES", 10_000_000, "MAX_TEST_BYTES", 0)
    r.check("CAPPED_SATURATION_BPS", 5_000_000, "CAPPED_SATURATION_BPS", 0)
    r.check("SEVERE_DELTA_MS", 400.0, "SEVERE_DELTA_MS", 0)

    r.check("RADIO_SAMPLE_BYTES", RADIO_SAMPLE_BYTES, "RADIO_SAMPLE_BYTES", 0)
    r.check("RADIO_SAMPLES_PER_SECOND", 1, "RADIO_SAMPLES_PER_SECOND", 0)
    r.check("GNSS_BYTES_PER_EPOCH", GNSS_BYTES_PER_EPOCH, "GNSS_BYTES_PER_EPOCH", 0)
    r.check("GNSS_EPOCHS_PER_SECOND", GNSS_EPOCHS_PER_SECOND, "GNSS_EPOCHS_PER_SECOND", 0)
    r.check("GNSS_BURST_SECONDS", GNSS_BURST_SECONDS, "GNSS_BURST_SECONDS", 0)
    r.check("SESSION_RAW_BYTES", 150_000, "SESSION_RAW_BYTES", 0)
    r.check("SESSION_COMPRESSED_BYTES", SESSION_COMPRESSED_BYTES, "SESSION_COMPRESSED_BYTES", 0)
    r.check("PLANNED_DAILY_ACTIVE_USERS", DAU, "PLANNED_DAILY_ACTIVE_USERS", 0)
    r.check("BACKGROUND_SAMPLE_INTERVAL_SECONDS", 30.0, "BACKGROUND_SAMPLE_INTERVAL_SECONDS", 0)

    r.check("GEOHASH_PRECISION", 7, "GEOHASH_PRECISION", 0)
    r.check("GEOHASH_CELL_METRES", 153.0, "GEOHASH_CELL_METRES", 0)
    r.check("MIN_BUCKET_SIZE", 5, "MIN_BUCKET_SIZE", 0)
    r.check("HOUR_BUCKETS", 24, "HOUR_BUCKETS", 0)
    r.check("CELL_KEY_HEX_LENGTH", 16, "CELL_KEY_HEX_LENGTH", 0)

    r.check("SETS_PER_RUN", PROBE_SETS_PER_RUN, "SETS_PER_RUN", 0)
    r.check("FAILURE_RATE_CEILING", PROBE_FAILURE_CEILING, "FAILURE_RATE_CEILING", 1e-12)
    r.check("TYPICAL_SET_MILLIS", PROBE_TYPICAL_SET_MS, "TYPICAL_SET_MILLIS", 0)
    r.check("WALL_CLOCK_CAP_MILLIS", PROBE_WALL_CLOCK_CAP_MS, "WALL_CLOCK_CAP_MILLIS", 0)
    r.check("DNS_TIMEOUT_MILLIS", DNS_TIMEOUT_MS, "DNS_TIMEOUT_MILLIS", 0)
    r.check("TCP_TIMEOUT_MILLIS", TCP_TIMEOUT_MS, "TCP_TIMEOUT_MILLIS", 0)
    r.check("TLS_TIMEOUT_MILLIS", TLS_TIMEOUT_MS, "TLS_TIMEOUT_MILLIS", 0)
    r.check("TTFB_TIMEOUT_MILLIS", TTFB_TIMEOUT_MS, "TTFB_TIMEOUT_MILLIS", 0)
    r.check("OS_DIAGNOSTICS_WAIT_MILLIS", OS_DIAGNOSTICS_WAIT_MS, "OS_DIAGNOSTICS_WAIT_MILLIS", 0)
    r.check("DNS_PORT", DNS_PORT, "DNS_PORT", 0)
    r.check("HTTPS_PORT", HTTPS_PORT, "HTTPS_PORT", 0)
    r.check("PERCENTILE_P50", PERCENTILE_P50, "PERCENTILE_P50", 1e-12)
    r.check("PERCENTILE_P95", PERCENTILE_P95, "PERCENTILE_P95", 1e-12)

    rc = r.emit()

    # String constants cannot be numerically cross-checked, so assert them verbatim.
    wifi_src = (LEDGER / "ledger" / "WifiRtt.kt").read_text(encoding="utf-8")
    expected_feature = 'REQUIRED_FEATURE: String = "android.hardware.wifi.rtt"'
    if expected_feature not in wifi_src:
        print(f"FAIL  WifiRtt.REQUIRED_FEATURE is not the documented feature flag")
        rc = 1
    else:
        print("ok    WifiRtt.REQUIRED_FEATURE matches the documented flag")

    print()
    print("derived quantities quoted in the approved plan:")
    print(f"  TA step, metres                       {TA_STEP_M:>18.6f}  (plan: 78.071)")
    print(f"  GSM TA step, metres                   {GSM_STEP_M:>18.6f}  (plan: 553.5)")
    print(f"  Shannon 20 MHz @ 10 dB, Mbps          {shannon(REF_BW_HZ, 10.0) / 1e6:>18.3f}  (plan: 69.2)")
    print(f"  Shannon 20 MHz @  0 dB, Mbps          {shannon(REF_BW_HZ, 0.0) / 1e6:>18.3f}  (plan: 20.0)")
    print(f"  Shannon 20 MHz @ 20 dB, Mbps          {shannon(REF_BW_HZ, 20.0) / 1e6:>18.3f}  (plan: 133.2)")
    print(f"  Shannon 20 MHz @ -5 dB, Mbps          {shannon(REF_BW_HZ, -5.0) / 1e6:>18.3f}  (plan: 7.9)")
    print(f"  Doppler sigma_v on L1 at 1 Hz, m/s    {L1_WAVELENGTH_M:>18.6f}  (plan: 0.19)")

    radio_bytes_per_hour = RADIO_SAMPLE_BYTES * 3_600
    gnss_burst = GNSS_BYTES_PER_EPOCH * GNSS_EPOCHS_PER_SECOND * GNSS_BURST_SECONDS
    fleet_bytes_month = SESSION_COMPRESSED_BYTES * DAU * 30
    rows_per_sample = DAU * 3_600 * 30
    rows_summary = DAU * 30
    payload_5mbps_15s = 5_000_000 / 8 * 15

    print()
    print("storage and bandwidth budgets:")
    print(f"  radio bytes/hour                      {radio_bytes_per_hour:>18,}  (plan: 93,600)")
    print(f"  gnss burst bytes (30 s)               {gnss_burst:>18,}  (plan: 43,200)")
    print(f"  fleet upload/month, bytes             {fleet_bytes_month:>18,}  (plan: 7.5e9)")
    print(f"  rows/month, one row per sample        {rows_per_sample:>18,}  (plan says 1.08e10)")
    print(f"  rows/month, summarised per session    {rows_summary:>18,}  (plan: 300,000)")
    print(f"  positives for +/-5% at 95%            {POSITIVES:>18,}  (plan: 384, truncated)")
    print(f"  observed minutes needed               {OBSERVED_MINUTES:>18,.0f}")
    print(f"  single-device hours                   {SINGLE_DEVICE_HOURS:>18,.1f}  (plan: 320 h)")
    print(f"  fleet days, 200 users x 60 min/day    {FLEET_DAYS:>18.2f}  (plan: ~2 days)")
    print(f"  bufferbloat payload, 5 Mbps x 15 s    {payload_5mbps_15s:>18,.0f}  (plan: <=10 MB)")

    sets_in_cap = PROBE_WALL_CLOCK_CAP_MS // PROBE_WORST_CASE_SET_MS
    print()
    print("probe engine budget (B1):")
    print(f"  probe sets per run                    {PROBE_SETS_PER_RUN:>18,}  (exit criterion)")
    print(f"  failure rate ceiling                  {PROBE_FAILURE_CEILING:>18}  (5%, exclusive)")
    print(f"  worst case for one set, ms            {PROBE_WORST_CASE_SET_MS:>18,}  (four stage budgets)")
    print(f"  wall clock cap, ms                    {PROBE_WALL_CLOCK_CAP_MS:>18,}  (2 x sets x typical)")
    print(f"  sets finishable if every stage times out {sets_in_cap:>14,}  (then the run is truncated)")

    print()
    print("plan discrepancies found (documented in docs/calculation-ledger.md):")
    if abs(rows_per_sample - 1.08e10) > 1:
        print(f"  * per-sample rows: plan says 1.08e10, derivation gives {rows_per_sample:.3g}")
        print("    a factor-of-ten slip; the conclusion is unchanged (1.08e9 rows/month is still fatal)")
    if POSITIVES != 384:
        print(f"  * required positives: plan says 384 (truncated), the ceiling is {POSITIVES}")
    print(f"  * TA quantisation: plan says +/-80 m; half a command step is +/-{QUANT_HALF_STEP_M:.3f} m")
    print("    (the plan's figure is one full step, i.e. a conservative bound)")

    print()
    print("geohash cross-check against published test vectors:")
    vectors = [((42.6, -5.6), 5, "ezs42"), ((57.64911, 10.40744), 11, "u4pruydqqvj")]
    gh_rc = 0
    for (lat, lon), precision, expected in vectors:
        actual = geohash_encode(lat, lon, precision)
        ok = actual == expected
        gh_rc |= 0 if ok else 1
        print(f"  {'ok' if ok else 'FAIL'}  ({lat}, {lon}) p{precision} -> {actual} (expected {expected})")
    print(f"  reference value for the unit test: (42.6, -5.6) atlas p7 -> "
          f"{geohash_encode(42.6, -5.6, 7)}")

    return rc | gh_rc


if __name__ == "__main__":
    sys.exit(main())
