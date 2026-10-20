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
