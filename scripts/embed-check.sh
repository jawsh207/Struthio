#!/usr/bin/env bash
# Opens a public note with a YouTube link read-only, taps play and records what the player shows. Publishes nothing.
set -u
mkdir -p smoke
APK=app/build/outputs/apk/release/app-x86_64-release.apk
PKG=app.struthio.nostr
NOTE=$(cat smoke/note.txt)
adb install -r "$APK"
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 8

find_tap() { # text or description, optional number of scrolls to look through
  for i in $(seq 0 "${2:-0}"); do
    adb exec-out uiautomator dump /dev/tty > smoke/ui.xml 2>/dev/null
    if python3 - "$1" <<'PY'
import re,subprocess,sys
xml=open('smoke/ui.xml',errors='ignore').read()
for node in re.findall(r'<node [^>]*>',xml):
    if f'text="{sys.argv[1]}"' in node or f'content-desc="{sys.argv[1]}"' in node:
        a,b,c,d=map(int,re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',node).groups())
        subprocess.run(['adb','shell','input','tap',str((a+c)//2),str((b+d)//2)]); print('tapped',sys.argv[1]); sys.exit(0)
sys.exit(1)
PY
    then sleep 2; return 0; fi
    adb shell input swipe 500 1500 500 800 400; sleep 1
  done
  echo "not found: $1"; return 1
}

find_tap "nsec, ncryptsec, or npub to browse read-only"
adb shell input text npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6
adb shell input keyevent 111; sleep 1
find_tap "Sign in"; sleep 15
find_tap "Settings"
find_tap "Embedded videos" 8
adb exec-out screencap -p > smoke/e1-setting.png
adb shell input keyevent 4; sleep 2
adb shell am start -a android.intent.action.VIEW -d "nostr:$NOTE" >/dev/null
sleep 12
adb exec-out screencap -p > smoke/e2-note.png
find_tap "Play on YouTube" 3
sleep 6
adb exec-out screencap -p > smoke/e3-play.png
sleep 12
adb exec-out screencap -p > smoke/e4-later.png
# Tap the middle of the player, as a person would if it shows its own play button.
adb exec-out uiautomator dump /dev/tty > smoke/ui.xml 2>/dev/null
adb shell input tap 540 800; sleep 10
adb exec-out screencap -p > smoke/e5-tapped.png
adb logcat -d > smoke/logcat.txt
grep -iE "chromium|console|youtube|webview|cr_" smoke/logcat.txt | grep -v "^--" | tail -60
python3 - <<'PY'
import base64,glob,io
from PIL import Image
files=sorted(glob.glob('smoke/e*.png'))
w,h=360,800
sheet=Image.new('RGB',(w*len(files),h),(128,128,128))
for i,f in enumerate(files): sheet.paste(Image.open(f).convert('RGB').resize((w,h)),(i*w,0))
out=io.BytesIO(); sheet.save(out,'JPEG',quality=65)
data=base64.b64encode(out.getvalue()).decode()
for i in range(0,len(data),3000): print('SHEET:'+data[i:i+3000])
PY
