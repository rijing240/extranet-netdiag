#!/usr/bin/env bash
#
# Retrieve B0's capability report from a device that is currently connected.
#
# This exists as a script rather than as inline CI because the android-emulator-runner action
# executes its `script:` block line by line, each in its own shell. Variables do not survive from
# one line to the next and multi-line constructs are torn apart, which is not obvious from the
# action's documentation and cost a full CI cycle to find out.
#
# It must be run while the emulator is alive: the action stops the device when its step ends, so
# an `adb` invocation afterwards has nothing to talk to.
#
# The report is read from internal storage through `run-as`, which works because a debug build is
# debuggable. `adb pull` of /sdcard/Android/data/<pkg>/files cannot be used as the primary path:
# the shell user has not been able to read that directory since API 30. It is kept as a fallback
# for older images, where it does work.
#
# Exits non-zero when the report could not be retrieved, because a probe run whose report cannot
# be recovered has not produced this batch's deliverable.
set -u

PACKAGE="${1:-dev.extranet.netdiag}"
REPORT_NAME="${2:-capability-report.json}"
DESTINATION="${3:-capability-report.json}"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not on PATH" >&2
  exit 1
fi

echo "--- device ---"
adb devices

echo "--- internal read (run-as $PACKAGE) ---"
adb exec-out run-as "$PACKAGE" cat "files/$REPORT_NAME" > "$DESTINATION" 2>/dev/null || true

if [ ! -s "$DESTINATION" ]; then
  echo "run-as produced nothing; falling back to the external copy"
  adb pull "/sdcard/Android/data/$PACKAGE/files/$REPORT_NAME" "$DESTINATION" || true
fi

if [ ! -s "$DESTINATION" ]; then
  echo "FAILED: could not retrieve $REPORT_NAME from $PACKAGE" >&2
  echo "looked in: internal files/$REPORT_NAME (via run-as)" >&2
  echo "           /sdcard/Android/data/$PACKAGE/files/$REPORT_NAME (via adb pull)" >&2
  exit 1
fi

echo "--- retrieved $DESTINATION ---"
wc -c "$DESTINATION"
head -c 200 "$DESTINATION"
echo
