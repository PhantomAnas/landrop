# LanDrop - working notes (resume here)

Transfer files between a Windows PC and an Android phone (also PC<->PC
and phone<->phone) over the local network. Kotlin + Compose on Android,
PyQt6 on the PC, one symmetric HTTP + UDP protocol so any device can
connect to, send to or receive from any other.

- Android project: `android/` (package com.phantomanas.landrop)
- PC app: `LANDROP.py` (single file; `python LANDROP.py`, `--port N` for tests)
- Release APK: `LanDrop-v0.1.apk` (debug-signed, side-loadable)
- Protocol: documented at the top of `android/.../Net.kt`; ports 53555 (HTTP) + 53556 (UDP discovery)

## Status 2026-10-20 (session 1, ended on the user's clock)

DONE and verified on the emulator (PC app driven offscreen by a harness):
- Connection: manual address, auto-connect to last peer, heartbeat (3 misses = lost),
  DISCONNECT / CONNECT on both sides, status dot/pill, multi-device discovery
  with a chooser (code written; see untested list).
- PC -> phone transfer: 3 files (60 MB + 37 KB + empty) byte-exact; 640 MB file
  SHA-256 identical on both ends with a PAUSE from the phone and RESUME from the PC
  in the middle; STOP from the phone deletes the partial file; 200 small files in
  ~15 s sender-side, all published to Download/LanDrop, hashes match.
- Integrity: SHA-256 per file computed while streaming; receiver deletes + reports
  a mismatch ("checksum" error) -> sender shows Failed.
- Optimisations: adaptive chunk size (Auto: 1/4/8/16 MB by file size; Fast = 16 MB;
  Reliable = 1 MB), HTTP keep-alive on both servers + reused connection on the PC
  sender, TCP_NODELAY + single-write replies (this took per-request cost from
  56 ms to 2 ms), 1 MB socket buffers, Wi-Fi performance lock on the phone during
  transfers, small incoming files staged to cache and published to MediaStore by
  3 background threads (MediaStore costs ~150 ms/file on the emulator), history
  saves debounced on both sides, PC UI refresh coalesced with timers.
- Settings (instant-apply, both sides): device name, auto-connect, ask before
  receiving, transfer mode, verify files; phone also: keep screen on, Wi-Fi boost;
  PC also: received folder (OPEN / CHANGE).
- History on both sides (tap/double-click opens the file), CLEAR.
- Screens shot: home (idle/connected), connect, offer popup, progress, paused,
  stopped, done, PC window idle/sending (android/shots/, scratch pc_*.png).

## SESSION 2 PLAN - user feedback from a first real try (2026-10-20 night)

Do these in order; take the time to test each one properly, the user wants
a professional app, not speed. Verify on the emulator AND on the real phone
(Galaxy A32) where it matters.

1. **No auto-connect. Explicit pairing with consent on BOTH ends.**
   - Remove auto-connect entirely (setting, the Link loop's `autoConnect`,
     the saved-peer reconnect). Keep the heartbeat for an established link.
   - "FIND DEVICES" scans and LISTS everything that answers (name + address),
     the user picks one. Replace `discover()`/single-result flows with the
     list everywhere (PC `find_phone`, phone ConnectScreen) - keep the scan
     running for ~3 s and allow RESCAN.
   - Choosing a device sends a CONNECT REQUEST; the other device shows
     "<name> wants to connect - ALLOW / DENY". Only after ALLOW are both
     sides Connected. Protocol: `/hello` becomes a request carrying a random
     pairing token; the callee answers pending; the caller polls `/hello?id=`
     (same pattern as `/offer`). A denied or ignored (60 s) request shows a
     clear message on the caller.
   - Typed address and QR do the same request flow (consent still needed).
   - What the user saw: the PC "connected to some Android by itself" - that
     was the emulator through `adb forward tcp:53555` + the saved peer
     `127.0.0.1:53555` in `landrop_settings.json`. Delete that key from the
     settings file and remove the forward after testing; the no-auto-connect
     redesign removes the behaviour for good.

2. **Security review (the app must not be a door into the device).**
   - Only the paired peer may do anything: every request except /ping and
     the pairing request must come from the connected peer's IP (check
     `remoteIp`), otherwise reply 403 and ignore. Today /offer, /chunk,
     /control are accepted from anyone on the LAN.
   - Pairing token: the token from the ALLOWed pairing must accompany every
     later request (header `X-LanDrop-Token`); reject without it. Rotate on
     each new pairing. This stops a spoofed IP on open Wi-Fi.
   - Receiving ALWAYS needs the user's ACCEPT (keep "ask before receiving"
     ON and consider removing the option to turn it off; the user asked for
     a permission prompt on every send session). Offer popup must show who
     is sending, how many files, total size, and the folder.
   - File names: `safeName` strips path separators already - also reject
     names that are only dots, reserved Windows names (CON, NUL, ...), and
     cap length; never follow the sender's path. Verify on both sides.
   - Limits: refuse offers with > N files / absurd sizes? (no - any size is
     a feature) but do cap JSON bodies (1 MB, done) and reject chunks whose
     Content-Length does not match the offer's remaining size.
   - No listening on public interfaces beyond need: bind 0.0.0.0 is fine on
     a home LAN; document that it is LAN-only, no internet exposure, no
     cleartext secrets (there are none). Android: `usesCleartextTraffic`
     stays (LAN HTTP) but add a network-security-config limiting cleartext
     to private ranges if feasible.
   - Discovery replies leak only name + port; fine. Rate-limit /hello
     requests (one pending at a time) so nobody can spam popups.
   - Received files are written with their own names only, never executed;
     PC: never `os.startfile` automatically, only on the user's double-click.
   - Run a quick scan of the Python app's dependencies (PyQt6, qrcode only)
     and the APK (no third-party SDKs besides AndroidX/CameraX/zxing).

3. **UI bugs the user hit.**
   - PC: buttons stay DISABLED after a click (FIND DEVICES / CONNECT). Cause:
     `QTimer.singleShot(0, ...)` is called from a worker thread in
     `find_phone` / `connect_typed`; a timer created off the GUI thread never
     fires, so the re-enable lambda never runs. Fix: a `pyqtSignal` on BUS
     (`ui_call = pyqtSignal(object)`) that runs callables on the GUI thread,
     or `QMetaObject.invokeMethod` - and audit every QTimer/`setText` call
     made from threads (`Link._set` emits a signal - OK).
   - DISCONNECT felt weird on both sides: define it precisely - DISCONNECT
     tells the peer `/bye`, both show "Disconnected", nothing reconnects by
     itself (no auto-connect any more), and the connect card returns to the
     FIND / QR / typed-address state immediately. Test disconnect from each
     side while idle and while a transfer runs (transfer must pause with a
     clear note, not fail silently).
   - Re-check every button's enabled/disabled logic after each action on
     both apps; nothing should stay greyed without a visible reason.

4. **Per-session receive permission (user request, verify it is airtight).**
   Every offer (one file or many) must pop ACCEPT/DECLINE on the receiver,
   PC and phone, including when the PC window is minimised (raise it / tray
   notification). Confirmed in code for both, but test on the real phone and
   on the PC with the window in the background.

5. Then the rest of the test matrix below, then the optimisation follow-ups.

NOT YET TESTED (do these first next session):
1. Phone -> PC sending (SAF picker on the emulator: tap SEND FILES, pick from
   Downloads; the picker screenshot is android/shots/picker.png). Code path is
   `Outgoing` in Transfer.kt; the PC receiver `Incoming.chunk` in LANDROP.py.
2. PC -> PC: run a second instance `python LANDROP.py --port 53585` (the harness
   supports HARNESS_TAG=B HARNESS_PORT=53585 with its own settings/received dir).
3. Multi-device chooser UI (needs two devices answering discovery; UDP cannot
   cross the emulator NAT - real phone + PC, or two PC instances on one machine).
4. QR scan on the phone (camera on the emulator shows a test pattern - needs the
   real phone; the PC's QR dialog renders `landrop://ip:port`).
5. The RELEASE apk on the emulator (R8!) - only the debug build was exercised.
   Lint + assembleRelease were run at the end of session 1 (see git log / APK).
6. Real phone (Galaxy A32): UDP discovery, Wi-Fi lock, speed over real Wi-Fi,
   notification + foreground service while switching apps, MediaStore on API 33.
7. Last PC UI edits (coalesced refresh timers, debounced history save, Nagle fix,
   choice rows in Settings) were only compile-checked + probe_send-smoke-tested,
   not driven end to end.

OPTIMISATION FOLLOW-UPS (user wants "as fast as safely possible", 2026-10-21+):
- Many tiny files still cost one round trip each (~70 ms/file on the emulator
  path; the request itself is 2 ms, the rest is per-file bookkeeping on the PC
  sender + Compose updates on the phone). Next step: a `/bundle` request that
  packs many small files (say up to 4 MB total) into ONE body with a small
  index header, verified per file; receiver unpacks into the staging path.
- Benchmark on real Wi-Fi (phone + PC) for each transfer mode and for 100 MB /
  1 GB / 500 x 10 KB; set Auto thresholds from those numbers, not emulator ones.
- Measure receiver-side disk write vs network on the phone (MediaStore fd write
  for big files) - if disk-bound, raise the copy buffer from 256 KB to 1 MB.
- Consider resume-after-restart (persist batch state + partial file URIs).
- Consider allowing a queue of batches (today a second offer is refused "busy").

Known limitations / ideas:
- A transfer does not survive an app restart on either side (no persisted resume).
- One transfer at a time per device (a second offer gets "busy").
- Pre-Android-10 phones write with the File API into Download/LanDrop (untested).
- Timing log line `saved X create=..ms write=..ms finish=..ms` stays in logcat.

## How to run / test

    cd android && source ./env.sh && ./gradlew installDebug      # phone build
    python LANDROP.py                                             # PC app

Emulator test topology (UDP broadcast does not cross the NAT, so pair by address):
`adb forward tcp:53555 tcp:53555` (PC -> phone) and `adb reverse tcp:53565 tcp:53565`
(phone -> PC when the PC runs with `--port 53565`); the phone then types
`127.0.0.1:53565` (or 10.0.2.2:53565). The offscreen harness lives in the session
scratchpad (`pc_harness.py`: commands appended to cmd.txt, state in status.json,
grabs as pc_*.png) - recreate it from this description if the scratchpad is gone.

Windows: the first run needs Python allowed through the firewall (private networks).

## Decisions

- Symmetric design (every device runs server + client) so PC<->PC and
  phone<->phone come for free; `Found` list + chooser when several answer.
- Files go in chunks with an offset handshake (`{"error":"offset","received":n}`)
  so pause/stop/resume and "reply lost" are all cheap; no parallel streams - one
  TCP stream fills Wi-Fi and parallelism would break ordered resume + hashing.
- Received files: phone -> Download/LanDrop via MediaStore (pending flag until the
  checksum passes); PC -> `Received/` next to the script, `.part` until complete.
- Git: source + README + the versioned release APK only (user's order); no
  settings/history json, no shots, no build outputs.
