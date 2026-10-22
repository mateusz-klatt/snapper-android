#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_SERIAL:?A disposable emulator serial is required}"
: "${SNAPPER_TEST_DEVICE_PIN:?The disposable emulator PIN is required}"
[[ "$ANDROID_SERIAL" == emulator-* && "$SNAPPER_TEST_DEVICE_PIN" =~ ^[0-9]{6}$ ]]

adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
pin_submissions=0
stable=0
for attempt in $(seq 1 30); do
    policy=$(adb shell dumpsys window policy | tr -d '\r')
    keyguard=$(printf '%s\n' "$policy" | awk '
        $1 == "KeyguardServiceDelegate" { active = 1; next }
        active { print }
        active && /^ *interactiveState=/ { exit }
    ')
    if grep -qx ' *showing=false' <<< "$keyguard" &&
        grep -qx ' *inputRestricted=false' <<< "$keyguard" &&
        grep -qx ' *screenState=SCREEN_STATE_ON' <<< "$keyguard" &&
        grep -qx ' *interactiveState=INTERACTIVE_STATE_AWAKE' <<< "$keyguard"; then
        stable=$((stable + 1))
        if ((stable == 3)); then break; fi
    else
        stable=0
        if ((pin_submissions < 3)) && grep -qx ' *showing=true' <<< "$keyguard"; then
            adb shell wm dismiss-keyguard
            sleep 1
            current_keyguard=$(adb shell dumpsys window policy | tr -d '\r' | awk '
                $1 == "KeyguardServiceDelegate" { active = 1; next }
                active { print }
                active && /^ *interactiveState=/ { exit }
            ')
            if grep -qx ' *showing=true' <<< "$current_keyguard"; then
                adb shell input text "$SNAPPER_TEST_DEVICE_PIN"
                adb shell input keyevent KEYCODE_ENTER
                pin_submissions=$((pin_submissions + 1))
            fi
        fi
    fi
    sleep 1
done
if ((stable < 3)); then
    printf 'Disposable emulator did not become awake and unlocked.\n' >&2
    printf '%s\n' "$keyguard" | grep -E '^ *(showing|inputRestricted|screenState|interactiveState)=' >&2
    exit 1
fi

home_component=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n 1)
[[ "$home_component" == */* ]]
home_package=${home_component%%/*}
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME > /dev/null
for attempt in $(seq 1 15); do
    resumed=$(adb shell dumpsys activity activities | grep -E 'topResumedActivity=|mResumedActivity:' || true)
    if grep -Fq "$home_package/" <<< "$resumed"; then
        printf 'Disposable emulator is awake, unlocked and has a resumed home activity.\n'
        exit 0
    fi
    sleep 1
done
printf 'Disposable emulator home activity did not resume.\n' >&2
exit 1
