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

PC: download `LanDrop.exe` from the latest
[release](https://github.com/PhantomAnas/landrop/releases) and run it, or run
from source (Python 3.10+):

    pip install -r requirements.txt
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

## Development setup

The repo holds only what is needed to build both apps; build outputs,
settings, history and received files are git-ignored.

    LANDROP.py         the whole PC app (UI, server, protocol)
    requirements.txt   PC dependencies
    LanDrop.spec       PyInstaller recipe for the Windows exe
    icon.ico           exe icon
    android/           the Android Studio / Gradle project (Kotlin + Compose)
      app/src/main/java/com/phantomanas/landrop/
        Net.kt         protocol (documented at the top), server, pairing, discovery
        Transfer.kt    sending / receiving engine, inbox, speed test
        Screens.kt     the screens;  Ui.kt  the glass widgets
    LanDrop-v*.apk     the current release APK

**PC app** - Python 3.10+ on Windows (it runs on macOS / Linux too; the
firewall helper is Windows-only):

    git clone https://github.com/PhantomAnas/landrop.git
    cd landrop
    pip install -r requirements.txt
    python LANDROP.py                       # normal
    python LANDROP.py --port 53565 --data testA --name PC-A   # a 2nd instance for PC<->PC tests

**Windows exe**:

    pip install pyinstaller
    pyinstaller --noconfirm --distpath dist-pc --workpath build-pc LanDrop.spec
    # -> dist-pc/LanDrop.exe  (keeps settings, history and Received/ next to itself)

**Android app** - JDK 17 and the Android SDK (compileSdk 37). Easiest: open
the `android/` folder in Android Studio and press Run. From a shell:

    cd android
    # tell Gradle where the SDK is: ANDROID_HOME, or a local.properties file with
    #   sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk
    ./gradlew installDebug          # debug build onto a connected phone / emulator
    ./gradlew assembleRelease       # -> app/build/outputs/apk/release/app-release.apk

`android/env.sh` / `env.ps1` are the maintainer's toolchain paths; adjust or
ignore them. Release builds are signed with the debug key (fine for
side-loading).

**Testing on an emulator**: UDP discovery does not cross the emulator's NAT,
so pair by address through adb tunnels: `adb forward tcp:53555 tcp:53555`
(PC asks `127.0.0.1:53555`) and `adb reverse tcp:53565 tcp:53565` (the phone
asks `127.0.0.1:53565` when the PC runs with `--port 53565`).

## Contributing

Issues and pull requests are welcome. Keep the protocol identical on both
sides: a change in `Net.kt` / `Transfer.kt` needs the same change in
`LANDROP.py` (and a bump of the protocol `VERSION` in both if old builds can no
longer talk to new ones). Please test a transfer in both directions before
opening a PR, and never commit build outputs, APKs other than the release one,
keys or personal files.

## License

MIT
