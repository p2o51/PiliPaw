#!/bin/zsh
# Mini bar transition check on the Pixel Tablet (dedicated adb server :5038).
# Usage: tablet.sh install | start | log | shot NAME | tap X Y | swipe X1 Y1 X2 Y2 MS | back | reset
A=(env ANDROID_ADB_SERVER_PORT=5038 $HOME/Library/Android/sdk/platform-tools/adb -s adb-3408105H803AEE-Deuouc._adb-tls-connect._tcp)
OUT=${TMPDIR:-/tmp}/miniplayer-shots
mkdir -p $OUT
case $1 in
  install) $A install -r ~/Developer/pilipala-merge/app/build/outputs/apk/debug/app-debug.apk ;;
  start) $A shell logcat -c; $A shell am start -n com.gpo.pilipala/.MainActivity ;;
  log) $A shell logcat -d -s PiliMini:D ;;
  shot) $A exec-out screencap -p > $OUT/$2.png && sips -Z 900 $OUT/$2.png --out $OUT/$2-s.png >/dev/null && echo $OUT/$2-s.png ;;
  tap) $A shell input tap $2 $3 ;;
  swipe) $A shell input swipe $2 $3 $4 $5 ${6:-300} ;;
  back) $A shell input keyevent KEYCODE_BACK ;;
  reset) $A shell wm size reset; $A shell wm density reset ;;
esac
