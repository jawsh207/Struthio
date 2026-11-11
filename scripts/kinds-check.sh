#!/usr/bin/env bash
# Read-only: shows the remote signer sheet, then opens one public event of each new kind and takes screenshots. Publishes nothing.
set -u
mkdir -p smoke
APK=app/build/outputs/apk/release/app-x86_64-release.apk
PKG=app.struthio.nostr
adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${SIZE%x*}; H=${SIZE#*x}
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 8

find_tap() {
  for i in $(seq 0 "${2:-0}"); do
    adb exec-out uiautomator dump /dev/tty > smoke/ui.xml 2>/dev/null
    if python3 - "$1" <<'PY'
import re,subprocess,sys
xml=open('smoke/ui.xml',errors='ignore').read()
for node in re.findall(r'<node [^>]*>',xml):
    if re.search(r'(text|content-desc)="[^"]*'+re.escape(sys.argv[1]), node):
        a,b,c,d=map(int,re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',node).groups())
        subprocess.run(['adb','shell','input','tap',str((a+c)//2),str((b+d)//2)]); print('tapped',sys.argv[1]); sys.exit(0)
sys.exit(1)
PY
    then sleep 2; return 0; fi
    adb shell input swipe $((W/2)) $((H*75/100)) $((W/2)) $((H*35/100)) 600; sleep 2
  done
  echo "not found: $1"; return 1
}

find_tap "Use a remote signer" 3
sleep 3
adb exec-out screencap -p > smoke/k0-bunker.png
adb shell input keyevent 4; sleep 2

find_tap "nsec, ncryptsec, or npub to browse read-only" 3
adb shell input text npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6
adb shell input keyevent 111; sleep 1
find_tap "Sign in"; sleep 15

n=1
for kind in 20 21 22 30023 10011; do
  ref=$(cat smoke/kind-$kind.txt 2>/dev/null)
  [ -z "$ref" ] && { echo "no event for kind $kind"; continue; }
  adb shell am start -a android.intent.action.VIEW -d "nostr:$ref" >/dev/null
  sleep 14
  adb exec-out screencap -p > smoke/k$n-kind$kind.png
  if [ "$kind" = 30023 ]; then
    adb shell input swipe $((W/2)) $((H*75/100)) $((W/2)) $((H*25/100)) 600; sleep 2
    adb exec-out screencap -p > smoke/k$n-kind$kind-more.png
  fi
  adb shell input keyevent 4; sleep 2
  n=$((n+1))
done
PID=$(adb shell pidof $PKG)
adb logcat -d --pid=$PID | grep -E "AndroidRuntime|FATAL" | head -20
python3 - <<'PY'
import base64,glob,io
from PIL import Image
files=sorted(glob.glob('smoke/k*.png'))
w,h=300,660
sheet=Image.new('RGB',(w*len(files),h),(128,128,128))
for i,f in enumerate(files): sheet.paste(Image.open(f).convert('RGB').resize((w,h)),(i*w,0))
out=io.BytesIO(); sheet.save(out,'JPEG',quality=60)
data=base64.b64encode(out.getvalue()).decode()
for i in range(0,len(data),3000): print('SHEET:'+data[i:i+3000])
PY
