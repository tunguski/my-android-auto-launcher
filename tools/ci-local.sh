#!/bin/sh
# Runs the on-device tests the way CI runs them: one shard at a time, each on an
# emulator that has just been wiped and booted.
#
# Usage:
#   tools/ci-local.sh 1          one shard, once
#   tools/ci-local.sh all        all four shards, in order
#   tools/ci-local.sh 1 3        one shard, three times, wiped before each
#   tools/ci-local.sh all 3      the whole suite three times over
#
# Why this exists, rather than running gradlew against whatever emulator happens
# to be up. A test that read a stored setting straight after tapping the switch
# that writes it passed here for days and failed on every runner. Rerunning it
# here kept saying "flake" - because a rerun here is warm, and a runner is never
# warm. It failed on the first attempt on a freshly wiped device. A local green
# that does not boot cold is not evidence of anything, and two days were spent
# learning that twice.
#
# What is matched to CI: a wiped device per shard, the emulator options from the
# workflow, animations off the way the emulator action turns them off, and the
# shard division itself - which lives in tools/shard.sh, so this script and the
# workflow ask the same file the same question and cannot drift apart.
#
# What is not, and cannot be: the host. A runner has four shared cores and no
# GPU; this machine has more of both. Cold booting removes the difference that
# was actually hiding failures, not every difference there is.
set -u

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ]; then
    echo "ANDROID_HOME is not set" >&2
    exit 2
fi

AVD=${NAVI_AVD:-navi720}

# Windows keeps these under their .exe names; everywhere else they have none.
emulator=$ANDROID_HOME/emulator/emulator
[ -x "$emulator" ] || emulator=$ANDROID_HOME/emulator/emulator.exe
adb=$ANDROID_HOME/platform-tools/adb
[ -x "$adb" ] || adb=$ANDROID_HOME/platform-tools/adb.exe

if ! "$emulator" -list-avds | grep -qx "$AVD"; then
    echo "no such AVD: $AVD" >&2
    echo "Create one, or name an existing one with NAVI_AVD=<name>:" >&2
    echo "  sdkmanager 'system-images;android-35;google_apis;x86_64'" >&2
    echo "  avdmanager create avd -n $AVD -k 'system-images;android-35;google_apis;x86_64'" >&2
    "$emulator" -list-avds | sed 's/^/  have: /' >&2
    exit 2
fi

case ${1:-} in
    all) shards="0 1 2 3" ;;
    0 | 1 | 2 | 3) shards=$1 ;;
    *)
        echo "usage: $0 0|1|2|3|all [times]" >&2
        exit 2
        ;;
esac
times=${2:-1}

# The same options the workflow gives the emulator action, including the skin:
# 1280x720 landscape is the resolution of the head unit this is built for, and a
# test that measures anything against the display measures a different screen on
# a different one.
OPTIONS="-no-window -gpu swiftshader_indirect -noaudio -no-boot-anim
         -camera-back none -camera-front none -skin 1280x720"

boot() {
    "$adb" emu kill > /dev/null 2>&1 || true
    "$adb" kill-server > /dev/null 2>&1 || true
    # shellcheck disable=SC2086
    "$emulator" -avd "$AVD" -no-snapshot-load -wipe-data $OPTIONS > /dev/null 2>&1 &
    "$adb" wait-for-device
    waited=0
    while [ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        if [ "$waited" -gt 300 ]; then
            echo "the emulator did not finish booting in five minutes" >&2
            return 1
        fi
        sleep 5
        waited=$((waited + 5))
    done
    # What the emulator action does for every run it drives. Left on, the
    # launcher's own transitions outlast the waits that look for what they
    # reveal, which is a race this machine would have and a runner would not.
    "$adb" shell settings put global window_animation_scale 0 || true
    "$adb" shell settings put global transition_animation_scale 0 || true
    "$adb" shell settings put global animator_duration_scale 0 || true
}

failed=""
round=1
while [ "$round" -le "$times" ]; do
    for shard in $shards; do
        echo "──────── round $round/$times, shard $shard: booting $AVD wiped"
        if ! boot; then
            failed="$failed round$round/shard$shard(no boot)"
            continue
        fi
        echo "──────── round $round/$times, shard $shard: running"
        if sh tools/shard.sh "$shard"; then
            echo "──────── round $round/$times, shard $shard: PASSED"
        else
            echo "──────── round $round/$times, shard $shard: FAILED"
            failed="$failed round$round/shard$shard"
        fi
    done
    round=$((round + 1))
done

"$adb" emu kill > /dev/null 2>&1 || true

echo
if [ -z "$failed" ]; then
    echo "all green: shards [$shards] x $times, cold every time"
    exit 0
fi
# Named rather than counted. A shard that fails one round in three is the case
# this script was written to catch, and a summary that says "2 of 3 passed"
# invites exactly the shrug that hid the last one.
echo "FAILED:$failed"
exit 1
