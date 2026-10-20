# LanDrop - working notes (resume here)

Transfer files between a Windows PC and an Android phone (also PC<->PC
and phone<->phone) over the local network. Kotlin + Compose on Android,
PyQt6 on the PC, one symmetric HTTP + UDP protocol so any device can
connect to, send to or receive from any other.

- Android project: `android/` (package com.phantomanas.landrop)
- PC app: `LANDROP.py` (single file; `python LANDROP.py`, `--port N` for tests)
- Release APK: `LanDrop-v0.2.apk` (debug-signed, side-loadable)
- Protocol: documented at the top of `android/.../Net.kt`; ports 53555 (HTTP) + 53556 (UDP discovery)
- Repo: https://github.com/PhantomAnas/landrop (public; source + README + release APK only)

## Status 2026-10-20 (session 2) - v0.2

Session 2 implemented the user's feedback from a first real try (see
"User feedback" below) and finished the test matrix on the emulator.

VERIFIED on the emulator (PC app driven offscreen by a harness, two PC
instances for PC<->PC):
- Pairing with consent: FIND lists devices; pick one -> the other side shows
  "<name> wants to connect ALLOW / DENY"; typed address and QR go through the
  same request. Tested PC->PC (ask, allow, deny, cancel request, disconnect
  from either side) and phone->PC (ask from the phone, allow on the PC).
- Security: every request after pairing needs the peer's IP AND the pairing
  token (header X-LanDrop-Token) - an unauthenticated /offer is refused;
  receiver always confirms each send session; sender cancel withdraws the
  offer and closes the popup; file names hardened; chunk length bound to
  the offer; one pending pairing request at a time, expires after 60 s.
- Transfers both directions: PC->phone 60 MB + 640 MB (hash-identical, pause
  from phone / resume from PC), STOP deletes partials; phone->PC 2 files via
  the system picker and 640 MB (hash-identical, pause from PC / resume from
  phone); PC->PC file + 200 small files.
- Bundling: runs of small files (<= 1 MB each, <= 4 MB / 400 files per
  request) travel in ONE request. 200 x 500 B files: PC->phone 3.8 s on the
  wire (was 55 s on day 1), PC->PC 1.9 s, phone->PC bundle path OK.
  MediaStore publishing on the phone still takes ~25 s in the background on
  the EMULATOR (3 threads) - shown as "Saving into Download/LanDrop...".
- UI: PC buttons re-enable (GUI-thread marshalling via BUS.ui_call), stale
  status text cleared, popups have a dark base and close when withdrawn,
  history rows readable; phone screens re-shot (home, connect, asking,
  offer, progress, paused-by-peer, done, settings, history).
- Lint clean; release APK v0.2 built (R8) and smoke-tested on the emulator.

STILL TO VERIFY / DO NEXT:
1. (done) Release APK v0.2 smoke-tested on the emulator: pair + 3-file batch
   (bundle + chunked paths), no crash.
2. REAL PHONE (Galaxy A32): UDP discovery (FIND must list the PC and the PC
   must list the phone), QR scan with the real camera, speed over real Wi-Fi
   (emulator path is ~25 MB/s PC->phone, ~8 MB/s phone->PC - the tunnel, not
   the app), Wi-Fi lock, notification + transfer while switching apps,
   MediaStore publish speed on real hardware.
3. Multi-device chooser with 2+ real devices answering discovery (the code
   path is exercised PC->PC only through typed addresses; broadcast loopback
   on one Windows machine is unreliable, so FIND between two local instances
   was not provable).
4. Phone<->phone: untestable here (one emulator); the code is symmetric.
5. Optional: resume after app restart; a queue of batches (second offer
   while busy is refused with "busy").

## User feedback that shaped session 2 (2026-10-20 night)

- "don't add auto connect, just auto finder that lists anything it finds
  and I choose, and the other device gets a request to connect" -> done.
- "ensure cyber security check so no unwanted app could peek in" -> token +
  IP check on every request, consent on both ends, hardened names; see
  Security notes below.
- "when I send files it should ask permission on the other device before
  receiving" -> always, no setting to turn it off.
- "many UI buttons become disabled once clicked, and disconnect weird" ->
  root cause was QTimer.singleShot from worker threads (never fires); fixed
  with a GUI-thread signal; disconnect now has one definition (see below).

## Security notes (what protects the device)

- Nothing listens except the LanDrop HTTP server (LAN port 53555) and the UDP
  discovery responder (53556, replies name + port only).
- Unpaired devices can only: /ping (name, version, port), /pair (which pops
  ALLOW / DENY, one at a time, expires in 60 s) and poll their own request.
- Paired device = the one the user ALLOWED: its IP + a 64-hex random token
  issued at pairing, required on every other request; forgotten on
  disconnect / lost link / restart (tokens are never stored).
- Receiving always needs ACCEPT; files are written only under the inbox
  folder with a sanitised plain name (no path parts, no reserved Windows
  names), as .part / IS_PENDING until the SHA-256 matches, never executed.
- Bodies: JSON capped at 1 MB; chunk length must fit the announced file size;
  bundle index capped at 4 MB and must add up to Content-Length.
- Dependencies: PyQt6 + qrcode on the PC; AndroidX, CameraX, zxing on the
  phone. No analytics, no internet access.

## Disconnect, defined

DISCONNECT on either side sends /bye (token-checked) -> both show
"Disconnected" / "<name> disconnected", the token is forgotten, nothing
reconnects by itself, the connect card returns to FIND / QR / address. A
lost link (3 missed pings, 6 s) shows "Lost <name> - connect again when it
is back". A running transfer pauses with "Connection lost - resumes when
the link is back" and continues after a NEW pairing (the batch is kept).

## How to run / test

    cd android && source ./env.sh && ./gradlew installDebug      # phone build
    python LANDROP.py                                             # PC app

Emulator test topology (UDP broadcast does not cross the NAT, so pair by
address): `adb forward tcp:53555 tcp:53555` (PC -> phone) and
`adb reverse tcp:53565 tcp:53565` (phone -> PC when the PC runs with
`--port 53565`); the phone types `127.0.0.1:53565` and asks; the PC allows.
The offscreen harness lives in the session scratchpad (`pc_harness.py`:
commands appended to cmd<TAG>.txt - connect/accept/decline/send/pause/
resume/stop/cancel/disconnect/grab/page/qr/find -, state in status<TAG>.json;
HARNESS_TAG=B HARNESS_PORT=53585 starts a second PC). Recreate from this
description if the scratchpad is gone.

Windows: the first run needs Python allowed through the firewall (private
networks).

## Decisions

- Symmetric design (every device runs server + client) so PC<->PC and
  phone<->phone come for free; `Found` list + chooser when several answer.
- Files go in chunks with an offset handshake (`{"error":"offset","received":n}`)
  so pause/stop/resume and "reply lost" are all cheap; small files are bundled;
  no parallel streams - one TCP stream fills Wi-Fi and parallelism would break
  ordered resume + hashing.
- Received files: phone -> Download/LanDrop via MediaStore (pending flag until
  the checksum passes; small files staged in cache and published by 3 threads);
  PC -> `Received/` next to the script, `.part` until complete.
- Git: source + README + the versioned release APK only (user's order); no
  settings/history json, no shots, no build outputs.
- Timing log line `saved X create=..ms write=..ms finish=..ms` stays in logcat.
