# LanDrop

Fast, verified file transfer between a Windows PC and an Android phone over
your own Wi-Fi - no cloud, no cables, any file, any size. PC-to-PC and
phone-to-phone work the same way.

- **Android app** (Kotlin + Jetpack Compose): `android/` - release APK in
  this folder (`LanDrop-v*.apk`, side-load it).
- **PC app** (Python + PyQt6): `LANDROP.py` - one file.

## Features

- Finds the other device by itself (UDP discovery), or type its address, or
  scan the QR code the PC shows. Several devices found -> pick one.
- Clear connection status, connect / disconnect on either side, auto-reconnect.
- Send any files (multi-select, drag & drop on the PC) after a confirmation;
  the receiver accepts or declines. Progress, speed, time left, **pause,
  resume, stop** - from either side.
- Every file is **SHA-256 verified** on arrival; a damaged file is deleted and
  reported instead of silently kept.
- Resilient: a dropped Wi-Fi link pauses the transfer and it resumes where it
  stopped once the devices see each other again.
- Received files land in a dedicated folder: `Download/LanDrop` on the phone,
  `Received/` next to the script on the PC (changeable).
- History of everything sent and received, tap to open.
- Settings apply instantly: device name, auto-connect, ask-before-receive,
  transfer mode (Auto / Fast / Reliable), verification, keep-screen-on and
  Wi-Fi boost (phone), received folder (PC).

## Run

Phone: install the APK, open LanDrop.

PC (Python 3.10+ with PyQt6; `qrcode` for the QR dialog):

    pip install PyQt6 qrcode
    python LANDROP.py

Allow Python through Windows Firewall on private networks the first time, and
keep both devices on the same Wi-Fi.

## How it works

Both sides run the same tiny HTTP server (port 53555) and a UDP discovery
responder (port 53556). A transfer is an *offer* (file list) the receiver
accepts, then the sender streams each file in chunks (1-16 MB, chosen by file
size) over a kept-alive connection; the receiver appends at the given offset
and answers with how many bytes it has, which makes pause, stop, resume and
lost replies trivial. The last chunk carries the file's SHA-256.

Small incoming files are staged and published to the phone's Downloads in the
background so a folder full of tiny files does not crawl.

## Build the Android app

    cd android
    ./gradlew assembleRelease      # needs Android SDK (compileSdk 37) + JDK 17

## License

MIT
