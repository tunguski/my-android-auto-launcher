#!/bin/sh
# Runs one shard of the on-device tests, says what failed where it can be read,
# and keeps the device log whatever happens.
#
# Usage: tools/shard.sh 0 | 1 | 2 | 3
#        tools/shard.sh class=pl.matsuo.navi.HomeJourneyTest   (a filter, by hand)
#
# A file rather than a few lines inside the workflow, because the emulator action
# hands its "script" input to a shell as one wrapped string: a dollar sign in it
# can be expanded by the outer shell before the inner one ever sees it, so
# "exit $status" is liable to become "exit" with no argument - a failing shard
# reported as a green one, or the reverse. Nothing here reaches that outer shell:
# the workflow line is a path and a single digit. It is also checkable with
# "sh -n" and runnable by hand.
set -u

# ───────────────────────── which tests this shard runs ─────────────────────────
#
# Resolved here rather than in the workflow matrix, so that tools/ci-local.sh
# runs exactly what CI runs and the two cannot drift apart. Moving a class
# between shards is one edit in one file.
#
# Split by class, not with the runner's own numShards. That shards by method, by
# hash, so methods of different classes interleave in one process - and
# TripDbTest stops the recording service in its setup, because it drives the
# recorder's static fields directly. Interleaved, it pulls the service out from
# under the journey tests that depend on it. Measured: numShards=4 failed six
# tests that pass in one run.
#
# The last shard is the complement of the other three, written in terms of them,
# so a new test class lands in it automatically rather than silently never
# running - and so moving a class into shard 1 takes it out of shard 3 without a
# second edit.
SHARD_0="pl.matsuo.navi.HomeJourneyTest,pl.matsuo.navi.TripJourneyTest"
SHARD_1="pl.matsuo.navi.RecordingJourneyTest,pl.matsuo.navi.StartupJourneyTest"
SHARD_2="pl.matsuo.navi.BackupJourneyTest,pl.matsuo.navi.SettingsJourneyTest"

case ${1:-} in
    0) filter="class=$SHARD_0" ;;
    1) filter="class=$SHARD_1" ;;
    2) filter="class=$SHARD_2" ;;
    3) filter="notClass=$SHARD_0,$SHARD_1,$SHARD_2" ;;
    *=*) filter=$1 ;;
    *)
        echo "usage: $0 0|1|2|3   (or a runner filter such as class=...)" >&2
        exit 2
        ;;
esac

status=0
results=app/build/outputs/androidTest-results/connected

# ───────────────────────────────── finding adb ─────────────────────────────────
#
# It is on PATH on a runner, because setup-android puts platform-tools there. It
# is not necessarily on PATH anywhere else, and every call below used to carry
# "|| true", which turned that into silence: the dialog suppression never ran a
# single time on this machine, and the device log came out holding the words
# "adb: command not found" where a log should have been - so the first crash it
# was meant to explain could not be explained. A missing program is not a
# condition to tolerate here, because everything this script does after the
# tests needs it.
adb=$(command -v adb 2> /dev/null || true)
if [ -z "$adb" ]; then
    for candidate in \
        "${ANDROID_HOME:-}/platform-tools/adb" \
        "${ANDROID_HOME:-}/platform-tools/adb.exe" \
        "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
        "${ANDROID_SDK_ROOT:-}/platform-tools/adb.exe"; do
        if [ -x "$candidate" ]; then
            adb=$candidate
            break
        fi
    done
fi
if [ -z "$adb" ]; then
    echo "adb is not on PATH and not under ANDROID_HOME or ANDROID_SDK_ROOT." >&2
    echo "It is needed to suppress system dialogs and to keep the device log," >&2
    echo "and a shard that silently skips both is worse than one that stops." >&2
    exit 2
fi

# A crash or ANR dialog from any app on the device takes the focus and keeps it,
# and then every test in the shard reports only that the launcher never came up.
# The tests ask for this themselves before each one of them; it is asked here as
# well, because a dialog raised while the two APKs are being installed would be
# in front before the first test could ask.
"$adb" shell settings put global hide_error_dialogs 1 || true

# Cleared first, so that a shard which dies before writing any results cannot be
# summarised from the previous run's XML and called green.
rm -rf "$results"

./gradlew --no-daemon connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.$filter || status=$?

# ───────────────────────── say what failed, publicly ─────────────────────────
#
# The verdict is already decided above and nothing here may change it. What this
# report is for, and why the program that writes it is a file of its own rather
# than a string quoted inside this script, is written at the top of
# tools/junit-report.awk.
if [ -d "$results" ]; then
    find "$results" -name '*.xml' -print0 2> /dev/null \
        | xargs -0 -r awk -f tools/junit-report.awk -v mode=annotate || true

    # And the same thing as a table on the run page, for a human reading it
    # rather than an API. Written only when GitHub asked for one.
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
        {
            echo "### Shard ${1:-?}: \`$filter\`"
            echo
            if [ "$status" -eq 0 ]; then
                echo "All tests passed."
            else
                echo "| test | said |"
                echo "|---|---|"
                find "$results" -name '*.xml' -print0 2> /dev/null \
                    | xargs -0 -r awk -f tools/junit-report.awk -v mode=table || true
            fi
        } >> "$GITHUB_STEP_SUMMARY" || true
    fi
fi

# Kept whether the tests passed or not, and no failure here may change the
# verdict. Twice a shard has gone red with every test saying only that the
# launcher never appeared and that "android" was in front - which fits both a
# system dialog on top and an activity whose main thread never got to draw. The
# recording that tells those two apart used to die with the emulator.
# The tail, not the whole buffer: a device that has been up a while holds
# hundreds of thousands of lines, and what matters is the end. The crash buffer
# is kept on its own because it is the first thing worth reading and it is
# usually empty.
mkdir -p device-log
"$adb" logcat -d -v time -t 20000 > device-log/logcat.txt 2>&1 || true
"$adb" logcat -d -b crash -v time > device-log/crash.txt 2>&1 || true
"$adb" shell dumpsys window > device-log/window.txt 2>&1 || true
"$adb" shell dumpsys activity activities > device-log/activities.txt 2>&1 || true

exit $status
