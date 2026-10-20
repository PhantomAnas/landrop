# LanDrop - working notes (resume here)

Transfer files between a Windows PC and an Android phone (also PC<->PC
and phone<->phone) over the local network. Kotlin + Compose on Android,
PyQt6 on the PC, one symmetric HTTP + UDP protocol so any device can
connect to, send to or receive from any other.

- Android project: `android/` (package com.phantomanas.landrop)
- PC app: `LANDROP.py` (single file; `python LANDROP.py`, `--port N --data DIR --name X` for tests)
- Release APK: `LanDrop-v0.3.apk` (debug-signed, side-loadable)
- Windows exe: `dist-pc/LanDrop/LanDrop.exe` (not in git; attached to the GitHub release)
- Protocol v2: documented at the top of `android/.../Net.kt`; ports 53555 (HTTP) + 53556 (UDP discovery)
- Repo: https://github.com/PhantomAnas/landrop (public; source + README + release APK only)

## Status 2026-10-21 (session 3) - v0.3

Session 3 answered the user's first REAL-NETWORK test (see "User feedback"
below): speed, discovery, lost links, UI additions. Protocol bumped to v2 -
v0.3 on both sides is required (v0.2 cannot talk to it).

### What changed

- **Parallel verified chunks.** A transfer is cut into units - a chunk of a
  big file (offset + length + its own SHA-256) or a bundle of small files -
  that 3 workers (AUTO; 4 in FAST, 1 in RELIABLE) deliver over 3 kept-alive
  raw-socket connections in any order. The receiver writes each chunk at its
  offset and verifies it on its own, so a resend is harmless, a lost reply is
  answered by the receiver's bookkeeping (`dup:true`), and a damaged chunk is
  re-sent instead of failing the whole file. Chunk sizes: AUTO 1/2/4 MB by
  file size (max 8 MB in FAST) so one request never takes long on a slow link.
- **Live progress** on both ends (per 256 KB piece, not per chunk); the bytes
  counter is exact (bundle headers and refused/failed attempts are rolled back).
- **Lost link = kept pairing.** Link states: Off / Requesting / Connected /
  **Lost**. Losing the peer keeps the token and the peer address; the loop
  pings every 2 s and re-establishes with `POST /hello` (token-checked) - a
  running transfer waits ("Connection lost - resumes when the link is back")
  and continues by itself. Only DISCONNECT (`/bye`) forgets the pairing.
  Heartbeat: a transfer in progress counts as alive (no ping while traffic
  flows in the last 5 s); pings use a 4 s timeout, 3 misses -> Lost; after
  2 consecutive failed requests the sender forces a ping right away (a
  one-directional outage is caught too).
- **Discovery that works on real networks.** The UDP call now leaves EVERY
  adapter (socket bound per local IP, plain + directed broadcast, repeated
  3x) and, in parallel, the local /24 is swept with `/ping` (96 threads PC,
  64 phone, 0.8 s timeout) - finds devices on networks that filter
  broadcasts (hotspots). The phone holds a **MulticastLock** while the app
  is open (Samsung Wi-Fi chips drop broadcasts otherwise) -
  `CHANGE_WIFI_MULTICAST_STATE`.
- **Windows Firewall**: Settings shows whether an inbound rule named LanDrop
  exists for this exe/python and FIX FIREWALL adds one for ALL profiles
  (private + public, i.e. hotspots and new networks) via an elevated netsh.
- **Speed test** (connected): 3 streams x 4 s upload, then download, nothing
  stored (`/blast`). Shows the raw network ceiling next to transfer speed.
- **UI**: this device's address + COPY on the home/transfer screen (PC: also
  in the QR dialog); QR dialog / QR screen close by themselves once connected;
  OPEN FOLDER on the main screen; History with multi-select (PC: Ctrl/Shift,
  phone: long-press) and OPEN / SHOW IN FOLDER / DELETE FILES (with confirm) /
  REMOVE FROM LIST; styled thin scrollbars on the PC; opaque popup base on the
  phone (was blending with the background).
- **Phone <-> phone**: SHOW MY QR CODE screen (zxing encode) next to SCAN.
- **PC <-> PC via the phone**: LINK TWO PCs - scan PC 1's QR, then PC 2's;
  the phone POSTs `/introduce {address}` to PC 1 (unauthenticated, only
  accepted while PC 1 is not connected), which then asks PC 2 exactly as if
  the address had been typed there - PC 2 shows ALLOW / DENY as usual.
- **Clean exit.** PC: closing the window cancels the transfer, sends /bye,
  shuts the server and `os._exit`s after 1 s. Phone: BACK on the home screen
  with no transfer running finishes the activity -> `App.shutdown()` (cancel,
  /bye, close sockets, kill the process); with a transfer running BACK only
  backgrounds the app (notification keeps it alive); swiping it away from
  recents (`onTaskRemoved`) shuts everything down too.
- Fixed: `ForegroundServiceDidNotStartInTimeException` crash when a transfer
  finished within a second (service stopped before it started - now the
  service stops itself); history.json written atomically.

### VERIFIED (emulator via adb tunnels, two PC instances offscreen)

- PC<->PC loopback: pair/allow/deny; 200 MB and 1.5 GB hash-identical with
  pause/resume from both ends (bytes frozen while paused, counter exact);
  link outage 25 s mid-transfer -> Lost -> auto-reconnect -> file completes
  identical; STOP from the receiver leaves no .part; sender CANCEL while
  waiting closes the receiver's popup; speed test; disconnect from either end.
- PC<->phone (emulator): pair, PC->phone 200 MB (identical) and 303 files
  (300 x ~1.5 KB + 5 B + 0 B + 30 MB, all published), phone->PC 200 MB with
  pause from PC and from phone (identical), phone->PC 303 files (all 303
  identical), link outage (PC server killed + tunnel removed) -> phone Lost
  after 6 s -> reconnected 2 s after restore -> transfer completed identical;
  speed test from the phone; BACK ends the process and the PC shows
  "disconnected". Release APK (R8): pair by tapping ALLOW, 4-file receive by
  tapping ACCEPT (identical), clean exit, no crash.
- Size matrix PC->PC loopback (AUTO, wire time incl. ~0.8 s accept polling):
  1 B 1.0 s; 1000 x 1 KB 2.3 s; 200 x 100 KB 1.3 s (16 MB/s); 20 x 5 MB
  1.3 s (84 MB/s); 6 x 50 MB 1.8 s (179 MB/s); 60 mixed 1.5 s (138 MB/s);
  1.5 GB 5.0 s (320 MB/s) - all byte-identical. Emulator tunnel: PC->phone
  200 MB 26-34 MB/s, phone->PC ~17 MB/s (the tunnel's limit, 2x v0.2).
- Lint: 0 errors (12 style warnings, same as before). Exe starts, answers
  /ping, keeps data next to itself.

### NOT verifiable here - the user's phone + router must confirm

1. Real Wi-Fi speed. The app path is now 100-300 MB/s on loopback, so a slow
   transfer over Wi-Fi is the Wi-Fi. SPEED TEST shows the ceiling; expect
   2.4 GHz with BOTH devices on Wi-Fi to give ~1-3 MB/s (every byte crosses
   the air twice), PC on Ethernet roughly doubles it, 5 GHz is 5-10x.
2. FIND on the Galaxy A32 + Huawei router (multicast lock + per-adapter
   broadcast + sweep), hotspot mode (firewall "public" profile -> FIX FIREWALL).
3. Phone<->phone and the LINK TWO PCs flow with a real camera (code paths
   are the same as the verified QR scan; untestable with one emulator).
4. DELETE FILES on sent files on the phone depends on the picker's provider
   allowing deletes (received files always delete).

### Debug hooks (debug builds only, stripped from release)

`MainActivity.debugHook`: `am start -n com.phantomanas.landrop/.MainActivity
--es act accept|decline|allow|deny|pause|resume|stop|disconnect|speed|
connect:<addr>|page:<screen>` and `--es sendhist "name1|name2"` (sends files
the app RECEIVED earlier by name - adb-pushed files are not readable by the
app, see KNOWLEDGE.md). Quote the extra in the device shell (`'a|b'`) or `|`
becomes a pipe.

## User feedback that shaped session 3 (2026-10-20, first real test)

- ~1 MB/s both ways on a 2.4 GHz Huawei router, phone's own meter 0.5-1 MB/s,
  "expecting 5-10 MB/s" -> parallel streams, streaming bodies, speed test to
  tell app from network, size-matrix check of all cases.
- 40 MB file in FAST never completed: PC "waiting", phone "paused"/"connection
  lost", RESUME paused again -> root cause: the 2.5 s heartbeat ping timed out
  under upload load (3 misses in 6 s), the link was dropped, the token
  forgotten, so nothing could resume. Fixed by the Lost state + traffic-aware
  heartbeat + auto-reconnect (their words: "if happened it should auto connect
  mid transfer").
- "pc auto finder not working ... the pam app I made earlier auto found so
  well" (that app's phone found the PC; here the phone must RECEIVE a
  broadcast) -> multicast lock, per-adapter broadcast, subnet sweep, firewall
  fixer. Hotspot didn't work -> same causes (public profile).
- "copy button next to address", "auto close qr popup", "open selected folder
  on main screen", history "show file in folder" + "delete any/multi file",
  "scroll bar ui was very old style", "confirmation dialogue too transparent",
  "android to android QR", "pc to pc qr via phone", "ensure all processes get
  killed on close" -> all done, see above.

## Security notes (what protects the device)

- Nothing listens except the LanDrop HTTP server (LAN port 53555) and the UDP
  discovery responder (53556, replies name + port only).
- Unpaired devices can only: /ping (name, version, port, instance), /pair
  (which pops ALLOW / DENY, one at a time, expires in 60 s), poll their own
  request, and /introduce (makes THIS device ask another device to connect -
  the other device still shows ALLOW / DENY; only accepted while not connected).
- Paired device = the one the user ALLOWED: its IP + a 64-hex random token
  issued at pairing, required on every other request; kept while the link is
  Lost, forgotten on DISCONNECT / restart (tokens are never stored).
- Receiving always needs ACCEPT; files are written only under the inbox
  folder with a sanitised plain name (no path parts, no reserved Windows
  names), as .part / IS_PENDING until every chunk's SHA-256 matched, never
  executed.
- Bodies: JSON capped at 1 MB; chunk length must fit the announced file size;
  bundle index capped at 4 MB and must add up to Content-Length; /blast capped
  at 64 MB and only for the paired peer.
- Dependencies: PyQt6 + qrcode on the PC; AndroidX, CameraX, zxing on the
  phone. No analytics, no internet access.

## Windows exe

Built with PyInstaller 6.4 from the tracked `LanDrop.spec` at the repo root
(paths relative to SPECPATH, bundles nunito.ttf, unused Qt modules excluded,
~47 MB). `build-pc/LanDrop.spec` is the old absolute-path copy - ignore it.

    python -m PyInstaller --noconfirm --distpath dist-pc/LanDrop --workpath build-pc LanDrop.spec

Release assets per version: the APK, `LanDrop.exe` on its own, and
`LanDrop-PC-vX.Y.zip` (exe + README.txt).

Frozen: APP_DIR = folder of the exe (settings, history, Received). The
firewall fixer uses `sys.executable` (the exe itself, or python.exe when run
as a script). Attached to the GitHub release as LanDrop-PC-vX.Y.zip
(exe + README.txt).

## How to run / test

    cd android && source ./env.sh && ./gradlew installDebug      # phone build
    python LANDROP.py                                             # PC app

Emulator test topology (UDP broadcast does not cross the NAT, so pair by
address): `adb forward tcp:53555 tcp:53555` (PC -> phone) and
`adb reverse tcp:53585 tcp:53585` (phone -> PC when the PC runs with
`--port 53585`); the PC asks 127.0.0.1:53555, the phone allows (debug hook).
The offscreen harness lives in the session scratchpad: `pc_harness.py`
(imports LANDROP, commands appended to cmd<TAG>.txt: connect/accept/allow/
deny/send/pause/resume/stop/disconnect/grab/page/find/speed/server_down/
server_up/quit; state in status<TAG>.json; HARNESS_TAG=B HARNESS_PORT=53595
starts a second PC), `tlib.py` + `plib.py` helpers, suites `t_pc.py`,
`t_phone.py`, `t_loss.py`, `t_release.py`, `t_matrix.py`. Recreate from this
description if the scratchpad is gone. `server_down` must also close the
open connections, or the keep-alive streams finish the file regardless.

Windows: the first run needs the firewall rule (Settings -> FIX FIREWALL, or
allow Python/LanDrop on private AND public networks).

## Decisions

- Symmetric design (every device runs server + client) so PC<->PC and
  phone<->phone come for free; `Found` list + chooser when several answer.
- Per-chunk SHA-256 instead of per-file: order-independent (parallel streams),
  a damaged chunk is re-sent alone, and resume after a lost link needs no
  special protocol - workers simply retry their chunk until it is acknowledged.
- 3 streams by default: on Wi-Fi a single TCP stream stalls on every lost
  frame; several keep the air busy. Not more: each stream holds a chunk buffer
  in memory on the phone (4 x 8 MB in FAST).
- Lost-link pairing is kept on purpose (user's ask); DISCONNECT is the only
  thing that forgets a peer. The "no silent auto-connect" rule from session 2
  still holds for NEW devices.
- Received files: phone -> Download/LanDrop via MediaStore (pending flag until
  the checksum passes; one-request files staged in cache and published by 3
  threads); PC -> `Received/` next to the script, big files as .part until
  complete, bundled small files written straight to their final name.
- Git: source + README + the versioned release APK only (user's order); no
  settings/history json, no shots, no build outputs.
