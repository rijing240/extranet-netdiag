#!/usr/bin/env bash
#
# Retrieve a report from a device that is currently connected, with the app still installed.
#
# B0's capability report and B1's latency waterfall both come out this way; the report's file name
# is the second argument.
#
# Two things about this are not obvious and each cost a CI cycle:
#
#  1. The android-emulator-runner action executes its `script:` block line by line, each line in
#     its own shell. Variables do not survive from one line to the next and multi-line constructs
#     are torn apart. That is why this logic lives in a script instead of inline.
#  2. `gradle :app:connectedDebugAndroidTest` uninstalls the app when it finishes, which deletes
#     the app's storage and with it the report. So the test run has to be an explicit
#     install + `am instrument`, with this script run before anything uninstalls the app.
#
# The report is read out of internal storage through `run-as`, which works because a debug build
# is debuggable. `adb pull` of /sdcard/Android/data/<pkg>/files is only a fallback: the shell user
# has not been able to read that directory since API 30.
#
# Exits non-zero when the report could not be retrieved, because a probe run whose report cannot
# be recovered has not produced this batch's deliverable.
set -u

# The destination defaults to the report's own name, so two reports can be pulled in turn without
# the second overwriting the first.
PACKAGE="${1:-dev.extranet.netdiag}"
REPORT_NAME="${2:-capability-report.json}"
DESTINATION="${3:-$REPORT_NAME}"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not on PATH" >&2
  exit 1
fi

echo "--- device ---"
adb devices

echo "--- internal read (run-as $PACKAGE) ---"
adb exec-out run-as "$PACKAGE" cat "files/$REPORT_NAME" > "$DESTINATION" 2>/dev/null || true

# A failed run-as writes its complaint into the file rather than leaving it empty, so the check
# is on content and not only on size: error text is the one thing that must never be mistaken for
# the report.
looks_like_report() {
  [ -s "$1" ] && head -c 1 "$1" | grep -q '{'
}

if ! looks_like_report "$DESTINATION"; then
  echo "run-as did not give us the report; trying the external copy"
  adb pull "/sdcard/Android/data/$PACKAGE/files/$REPORT_NAME" "$DESTINATION" || true
fi

if ! looks_like_report "$DESTINATION"; then
  echo "FAILED: could not retrieve $REPORT_NAME from $PACKAGE" >&2
  echo "looked in: internal files/$REPORT_NAME (via run-as)" >&2
  echo "           /sdcard/Android/data/$PACKAGE/files/$REPORT_NAME (via adb pull)" >&2
  echo "if the app has already been uninstalled, the report went with it: pull before that" >&2
  echo "--- what we did get ---" >&2
  head -c 400 "$DESTINATION" >&2
  echo >&2
  exit 1
fi

echo "--- retrieved $DESTINATION ---"
wc -c "$DESTINATION"
python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print('parsed: schemaVersion', d.get('schemaVersion'), 'findings', len(d.get('findings', [])))" "$DESTINATION"
