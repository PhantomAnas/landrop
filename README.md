# LanDrop

Fast, verified file transfer between a Windows PC and an Android phone over
your own Wi-Fi or LAN - no cloud, no cables, any file, any size. PC-to-PC and
phone-to-phone work the same way.

- **Android app** (Kotlin + Jetpack Compose): `android/` - release APK in
  this folder (`LanDrop-v*.apk`, side-load it).
- **PC app** (Python + PyQt6): `LANDROP.py` - one file. A ready-made
  Windows exe (`LanDrop.exe`, nothing to install) is attached to each
  [release](https://github.com/PhantomAnas/landrop/releases).

v0.3 changed the protocol: install v0.3 on both the PC and the phone.

## Features

- FIND lists every LanDrop on the network (broadcast on every adapter plus a
  scan of the local subnet, so it also works on hotspots), or type an
  address, or scan a QR code - the other device must **allow** the
  connection. Nothing connects by itself.
- Your own address is always on screen with a COPY button; QR codes close
  by themselves once connected. A phone can show its QR to another phone, and
  can link two PCs by scanning both their QR codes.
- Send any files (multi-select, drag & drop on the PC) after a confirmation;
  the receiver accepts or declines. Live progress, speed, time left,
  **pause, resume, stop** - from either side.
- **Parallel streams**: files travel as chunks over 3 connections at once
  (4 in FAST mode), each chunk **SHA-256 verified** on arrival; a damaged
  chunk is sent again, never silently kept. Runs of small files travel
  bundled in one request.
- **Resilient**: a dropped Wi-Fi link keeps the pairing, reconnects by
  itself and the transfer resumes exactly where it stopped. Only DISCONNECT
  ends a pairing.
- **Speed test** between the two devices (3 streams, nothing stored) so you
  can see what the network itself allows.
- Only the device you allowed can talk to you: every request carries a
  pairing token and must come from that device's address. Received files are
  written under the inbox folder only, with sanitised names.
- Received files land in a dedicated folder: `Download/LanDrop` on the phone,
  `Received/` next to the app on the PC (changeable); OPEN FOLDER is one tap
  away. History lists everything both ways: open, show in folder, delete
  files or remove entries, several at once.
- Windows Firewall helper: one click adds the rule for private and public
  networks (hotspots).
- Closing the app ends everything - no server or transfer keeps running in
  the background.
- Settings apply instantly: device name, transfer mode (Auto / Fast /
  Reliable), verification, keep-screen-on and Wi-Fi boost (phone), received
  folder (PC).

## Run

Phone: install the APK, open LanDrop.

PC (Python 3.10+ with PyQt6; `qrcode` for the QR dialog):

    pip install PyQt6 qrcode
    python LANDROP.py

Press FIX FIREWALL in Settings the first time (or allow Python through
Windows Firewall), and keep both devices on the same Wi-Fi / LAN.

## How fast is it?

The app itself moves 100-300 MB/s on a loopback test; over Wi-Fi the radio
is the limit. With both devices on a 2.4 GHz router every byte crosses the
air twice, so 1-3 MB/s is normal; connect the PC by cable to double it, use
5 GHz for several times more. SPEED TEST (shown when connected) tells you the
ceiling of your network; if transfers are close to it, nothing in the app is
holding them back.

## How it works

Both sides run the same tiny HTTP server (port 53555) and a UDP discovery
responder (port 53556). Pairing is a request the other side allows or denies;
allowing issues a random token that every later request must carry (it is kept
while a link is temporarily lost, forgotten on DISCONNECT).
A transfer is an *offer* (file list) the receiver accepts; the sender then
cuts the files into chunks (1-8 MB, by file size and mode), each with its own
SHA-256, and several workers deliver them over kept-alive connections in any
order. The receiver writes each chunk at its offset, verifies it and answers
with what it has, so pause, stop, resume, lost replies and lost links are all
trivial. Runs of small files travel bundled in one request.

Small incoming files are staged and published to the phone's Downloads in the
background so a folder full of tiny files does not crawl.

## Build the Windows exe

    pip install pyinstaller
    pyinstaller --onefile --noconsole --name LanDrop --icon=icon.ico ^
        --add-data "android/app/src/main/res/font/nunito.ttf;." LANDROP.py

(The exe keeps its settings, history and `Received` folder next to itself.)

## Build the Android app

    cd android
    ./gradlew assembleRelease      # needs Android SDK (compileSdk 37) + JDK 17

## License

MIT
