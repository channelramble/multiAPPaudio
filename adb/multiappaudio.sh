#!/bin/sh
# Multi-App Audio, ADB edition. Every command here is "set and forget": Android persists the
# result across reboots and app updates, so you only ever run each one once.
#
# Needs `adb` on your PATH and USB or wireless debugging enabled. Works on macOS/Linux, and on
# Windows through Git Bash or WSL. (All commands are also listed in the README if you'd rather
# type them yourself.)
set -eu

APP=io.github.channelramble.multiappaudio
LISTENER=$APP/$APP.MediaListener
FIX_FLAG=android.media.audio.audio_focus_desktop

usage() {
    cat <<'USAGE'
Usage: multiappaudio.sh <command> [args]

  enable [--reboot]        Multi-app audio on (applies after one reboot, then forever)
  disable [--reboot]       Multi-app audio off (applies after one reboot)
  passthrough PKG...       These apps never take audio focus: they don't pause others, aren't
                           paused by others, and Android Auto can't swap them out
  unpassthrough PKG...     Undo passthrough
  mute PKG...              Silence these apps completely
  unmute PKG...            Undo mute
  setup-app                One-time grants for the companion app (per-app volume, helpers)
  fix-flag on|off          Try Google's own fix for the focus-restore bug (reboot after;
                           newer builds may refuse it without root, which is harmless)
  status [PKG...]          Show the current state (and the modes of any PKGs given)

Example (YouTube keeps playing in Android Auto, alongside other apps):
  ./multiappaudio.sh enable --reboot
  ./multiappaudio.sh passthrough com.google.android.youtube
USAGE
}

die() { echo "error: $*" >&2; exit 1; }

need_device() {
    command -v adb >/dev/null 2>&1 || die "adb not found on PATH"
    [ "$(adb get-state 2>/dev/null || true)" = "device" ] ||
        die "no device (enable USB/wireless debugging and accept the prompt on the phone)"
}

check_pkg() {
    echo "$1" | grep -Eq '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$' || die "not a package name: $1"
}

maybe_reboot() {
    if [ "${1:-}" = "--reboot" ]; then
        echo "Rebooting the phone..."
        adb reboot
    else
        echo "Reboot the phone once to apply it (or re-run with --reboot)."
    fi
}

appop() { # op mode pkg...
    op=$1; mode=$2; shift 2
    [ $# -gt 0 ] || die "give at least one package name"
    for pkg in "$@"; do
        check_pkg "$pkg"
        adb shell cmd appops set "$pkg" "$op" "$mode"
        echo "$pkg: $op=$mode"
    done
}

[ $# -gt 0 ] || { usage; exit 1; }
cmd=$1; shift

case "$cmd" in
    enable)
        need_device
        adb shell settings put system multi_audio_focus_enabled 1
        echo "Multi-app audio enabled (stored in Settings.System; survives reboots)."
        maybe_reboot "${1:-}"
        ;;
    disable)
        need_device
        adb shell settings put system multi_audio_focus_enabled 0
        echo "Multi-app audio disabled."
        maybe_reboot "${1:-}"
        ;;
    passthrough)   need_device; appop TAKE_AUDIO_FOCUS ignore "$@" ;;
    unpassthrough) need_device; appop TAKE_AUDIO_FOCUS default "$@" ;;
    mute)          need_device; appop PLAY_AUDIO ignore "$@" ;;
    unmute)        need_device; appop PLAY_AUDIO default "$@" ;;
    setup-app)
        need_device
        adb shell pm list packages "$APP" | grep -q "package:$APP\$" ||
            die "install the Multi-App Audio app first"
        adb shell pm grant "$APP" android.permission.DUMP
        adb shell cmd notification allow_listener "$LISTENER"
        echo "Granted DUMP and notification access to $APP (both survive reboots)."
        ;;
    fix-flag)
        need_device
        case "${1:-}" in
            on)  adb shell device_config override media_audio "$FIX_FLAG" true ;;
            off) adb shell device_config clear_override media_audio "$FIX_FLAG" ;;
            *)   die "use: fix-flag on|off" ;;
        esac
        echo "If there was no error above, reboot once to apply it."
        ;;
    status)
        need_device
        echo "multi_audio_focus_enabled setting: $(adb shell settings get system multi_audio_focus_enabled | tr -d '\r')"
        adb shell dumpsys audio | grep -E "Multi Audio Focus enabled|External focus policy|No external focus policy" |
            sed 's/^[[:space:]]*/  /' || true
        for pkg in "$@"; do
            check_pkg "$pkg"
            echo "$pkg:"
            adb shell cmd appops get "$pkg" TAKE_AUDIO_FOCUS | sed 's/^/  /'
            adb shell cmd appops get "$pkg" PLAY_AUDIO | sed 's/^/  /'
        done
        ;;
    -h|--help|help) usage ;;
    *) usage; exit 1 ;;
esac
