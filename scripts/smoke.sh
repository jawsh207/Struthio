#!/usr/bin/env bash
# Installs the release APK, signs in read-only with a public npub and walks the app. Publishes nothing.
set -u
mkdir -p smoke
APK=app/build/outputs/apk/release/app-x86_64-release.apk
PKG=app.struthio.nostr

adb install -r "$APK"
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 8
adb exec-out screencap -p > smoke/01-onboarding.png

tap_text() {
  adb exec-out uiautomator dump /dev/tty > smoke/ui.xml 2>/dev/null
  python3 - "$1" <<'PY'
import re,subprocess,sys
xml=open('smoke/ui.xml',errors='ignore').read()
want=sys.argv[1]
for node in re.findall(r'<node [^>]*>',xml):
    if f'text="{want}"' in node or f'content-desc="{want}"' in node:
        m=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',node)
        a,b,c,d=map(int,m.groups())
        subprocess.run(['adb','shell','input','tap',str((a+c)//2),str((b+d)//2)])
        print('tapped',want); sys.exit(0)
print('not found:',want)
PY
  sleep 2
}

long_press() {
  adb exec-out uiautomator dump /dev/tty > smoke/ui.xml 2>/dev/null
  python3 - "$1" <<'PY'
import re,subprocess,sys
xml=open('smoke/ui.xml',errors='ignore').read()
for node in re.findall(r'<node [^>]*>',xml):
    if f'content-desc="{sys.argv[1]}"' in node:
        a,b,c,d=map(int,re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',node).groups())
        x,y=(a+c)//2,(b+d)//2
        subprocess.run(['adb','shell','input','swipe',str(x),str(y),str(x),str(y),'900']); print('held',sys.argv[1]); sys.exit(0)
print('not found:',sys.argv[1])
PY
  sleep 2
}

NPUB=npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6
adb shell pm grant "$PKG" android.permission.CAMERA
tap_text "nsec, or npub to browse read-only"
adb shell input text "$NPUB"
sleep 1
adb shell input keyevent 111
sleep 1
tap_text "Sign in"
sleep 26
adb exec-out screencap -p > smoke/02-following.png
long_press "Reactions"
sleep 1
adb exec-out screencap -p > smoke/03-emoji.png
adb shell input keyevent 4
sleep 1
adb shell am start -a android.intent.action.VIEW -d "nostr:$NPUB" >/dev/null
sleep 12
adb exec-out screencap -p > smoke/04-profile.png
adb shell input keyevent 4
sleep 2
tap_text "Wallet"
sleep 3
tap_text "Scan QR code"
sleep 5
adb exec-out screencap -p > smoke/05-scanner.png
adb shell input keyevent 4
sleep 2
tap_text "Messages"
sleep 3
adb exec-out screencap -p > smoke/06-messages.png
tap_text "Notifications"
sleep 3
adb exec-out screencap -p > smoke/07-notifications.png
tap_text "Home"
sleep 2
tap_text "Global"
sleep 8
adb exec-out screencap -p > smoke/08-global.png
tap_text "Settings"
sleep 3
tap_text "Account check"
sleep 12
adb exec-out screencap -p > smoke/09-check.png
adb shell input keyevent 4
sleep 1
adb shell input keyevent 4
sleep 1
for server in https://blossom.primal.net https://blossom.band; do
  curl -s -o /dev/null -m 10 -w "$server %{http_code}\n" "$server/" || echo "$server unreachable"
done

adb logcat -d > smoke/logcat.txt
if grep -E "FATAL EXCEPTION|ANR in $PKG" smoke/logcat.txt; then
  grep -A25 "FATAL EXCEPTION" smoke/logcat.txt | head -60
  echo "App crashed"
  exit 1
fi
adb shell pidof "$PKG" || { echo "App is not running"; exit 1; }
adb shell dumpsys meminfo "$PKG" | grep -E "TOTAL PSS|TOTAL RSS" > smoke/memory.txt
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" | tee smoke/startup.txt
cat smoke/memory.txt
python3 -m pip install --quiet --break-system-packages pillow || python3 -m pip install --quiet pillow
python3 - <<'PY'
import base64,glob,io
from PIL import Image
files=sorted(glob.glob('smoke/0*.png'))
w,h=300,660
sheet=Image.new('RGB',(w*5,h*2),(128,128,128))
for i,f in enumerate(files[:10]):
    sheet.paste(Image.open(f).convert('RGB').resize((w,h)),((i%5)*w,(i//5)*h))
out=io.BytesIO(); sheet.save(out,'JPEG',quality=62)
data=base64.b64encode(out.getvalue()).decode()
for i in range(0,len(data),3000): print('SHEET:'+data[i:i+3000])
PY
grep -E "E AndroidRuntime|W System.err" smoke/logcat.txt | head -20
echo "Smoke test passed"
