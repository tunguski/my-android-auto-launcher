#!/bin/sh
# Runs one shard of the on-device tests and keeps the device log whatever happens.
#
# A file rather than a few lines inside the workflow, because the emulator action
# hands its "script" input to a shell as one wrapped string: a dollar sign in it
# can be expanded by the outer shell before the inner one ever sees it, so
# "exit $status" is liable to become "exit" with no argument - a failing shard
# reported as a green one, or the reverse. Nothing here reaches that outer shell:
# the workflow line is a path and a filter, and every variable is read from this
# file. It is also checkable with "sh -n" and runnable by hand.
#
# Usage: tools/shard.sh class=pl.matsuo.navi.HomeJourneyTest,...
#
# The one argument is a runner filter and never contains a space.
set -u

filter=$1
status=0

./gradlew --no-daemon connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.$filter || status=$?

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
adb logcat -d -v time -t 20000 > device-log/logcat.txt 2>&1 || true
adb logcat -d -b crash -v time > device-log/crash.txt 2>&1 || true
adb shell dumpsys window > device-log/window.txt 2>&1 || true
adb shell dumpsys activity activities > device-log/activities.txt 2>&1 || true

exit $status
