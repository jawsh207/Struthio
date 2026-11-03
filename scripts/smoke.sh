#!/usr/bin/env bash
# Installs the release APK, creates an account through the UI and walks every tab.
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

tap_text "Your name"
adb shell input text "Smoke"
sleep 1
adb shell input keyevent 111
sleep 1
tap_text "Create account"
sleep 14
adb exec-out screencap -p > smoke/02-home.png
tap_text "Global"
sleep 12
adb exec-out screencap -p > smoke/03-global.png
tap_text "Trending"
sleep 14
adb exec-out screencap -p > smoke/04-trending.png
tap_text "Messages"
sleep 4
adb exec-out screencap -p > smoke/05-messages.png
tap_text "Wallet"
sleep 3
adb exec-out screencap -p > smoke/06-wallet.png
tap_text "Notifications"
sleep 3
adb exec-out screencap -p > smoke/07-notifications.png
tap_text "Home"
sleep 2
tap_text "Settings"
sleep 3
adb exec-out screencap -p > smoke/08-settings.png
adb shell input keyevent 4
sleep 1
tap_text "Search notes, people, #tags"
sleep 3
adb exec-out screencap -p > smoke/09-search.png

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
python3 -m pip install --quiet pillow
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
print('SHEET-BEGIN')
for i in range(0,len(data),3000): print('SHEET:'+data[i:i+3000])
print('SHEET-END')
PY
grep -E "E AndroidRuntime|W System.err" smoke/logcat.txt | head -20
echo "Smoke test passed"
