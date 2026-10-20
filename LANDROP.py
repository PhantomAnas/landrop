"""
LANDROP - PC side
=================

Sends files to, and receives files from, the LanDrop Android app (or another
PC running this script) over the local network. Run it with:

    python LANDROP.py                 # normal
    python LANDROP.py --port 53565    # another port (testing / two PCs)
    python LANDROP.py --data DIR      # settings, history and inbox in DIR

* FIND DEVICES lists every LanDrop on the network; pick one and it asks
  that device, which must ALLOW the connection (and you get the same
  ALLOW / DENY when a device asks you). Typed address and QR work the same.
* SEND FILES (or drop files onto the window) asks once, then sends with
  progress, speed, pause and stop. The other side asks you before it sends.
* Files travel over several parallel connections in verified chunks; a lost
  Wi-Fi link pauses the transfer and it resumes by itself when the link is back.
* Everything received lands in the Received folder next to this file
  (changeable in Settings). History lists every file both ways.

Needs Python 3.10+ with PyQt6 (and `qrcode` for the QR code).
Windows Firewall must allow the app - Settings has a FIX FIREWALL button.
"""

import ctypes
import hashlib
import json
import os
import select
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from PyQt6.QtCore import QObject, QRectF, Qt, QTimer, pyqtSignal
from PyQt6.QtGui import (QBrush, QColor, QFont, QFontDatabase, QImage, QLinearGradient,
                         QPainter, QPainterPath, QPen, QPixmap, QRadialGradient)
from PyQt6.QtWidgets import (QAbstractItemView, QApplication, QDialog, QFileDialog, QFrame,
                             QHBoxLayout, QLabel, QLineEdit, QListWidget, QListWidgetItem,
                             QMainWindow, QPushButton, QScrollArea, QSizePolicy, QStackedWidget,
                             QVBoxLayout, QWidget)

# As a PyInstaller exe the script unpacks to a temp folder: settings, history and
# the Received folder must still live NEXT TO THE EXE, bundled data in _MEIPASS.
FROZEN = bool(getattr(sys, "frozen", False))
APP_DIR = Path(sys.executable).resolve().parent if FROZEN else Path(__file__).resolve().parent
RES_DIR = Path(getattr(sys, "_MEIPASS", str(APP_DIR)))

PORT = 53555
DATA_DIR = APP_DIR
NAME_OVERRIDE = ""
for _i, _a in enumerate(sys.argv):
    if _a == "--port" and _i + 1 < len(sys.argv):
        PORT = int(sys.argv[_i + 1])
    if _a == "--data" and _i + 1 < len(sys.argv):
        DATA_DIR = Path(sys.argv[_i + 1]).resolve()
    if _a == "--name" and _i + 1 < len(sys.argv):
        NAME_OVERRIDE = sys.argv[_i + 1]
DATA_DIR.mkdir(parents=True, exist_ok=True)

SETTINGS_FILE = DATA_DIR / "landrop_settings.json"
HISTORY_FILE = DATA_DIR / "landrop_history.json"
FONT_FILE = next((p for p in (
    RES_DIR / "nunito.ttf",
    APP_DIR / "nunito.ttf",
    APP_DIR / "android" / "app" / "src" / "main" / "res" / "font" / "nunito.ttf",
) if p.exists()), RES_DIR / "nunito.ttf")

APP_ID = "landrop"
VERSION = 2                          # protocol version (parallel verified chunks, reconnect)
APP_VERSION = "0.3"
DEFAULT_PORT = 53555
DISCOVERY_PORT = 53556
DISCOVER_CALL = b"LANDROP_DISCOVER"
DISCOVER_REPLY = "LANDROP_HERE"
BUNDLE_FILE_MAX = 1024 * 1024        # files up to this size may travel bundled
BUNDLE_MAX = 4 * 1024 * 1024         # bytes of file data per bundle
BUNDLE_FILES_MAX = 400
PIECE = 256 * 1024                   # socket write / read granularity (progress updates)
SCHEME = "landrop://"
INSTANCE = uuid.uuid4().hex[:8]      # so this PC ignores its own discovery echo
STOPPING = False
CREATE_NO_WINDOW = 0x08000000 if os.name == "nt" else 0


# ------------------------------------------------------------------ settings / history

def load_settings():
    data = {
        "device_name": socket.gethostname() or "PC",
        "peer": "",
        "received_folder": str(DATA_DIR / "Received"),
        "transfer_mode": "auto",      # auto | fast | reliable  (streams + chunk size)
        "verify": True,               # SHA-256 check of every chunk on arrival
    }
    try:
        data.update(json.loads(SETTINGS_FILE.read_text(encoding="utf-8")))
    except Exception:
        pass
    if NAME_OVERRIDE:
        data["device_name"] = NAME_OVERRIDE
    return data


CFG = load_settings()


def save_settings():
    try:
        SETTINGS_FILE.write_text(json.dumps(CFG, indent=2), encoding="utf-8")
    except Exception as e:
        print(f"[settings] could not save: {e}")


def transfer_plan(size):
    """(streams, chunk bytes) for a file of `size` bytes, from the transfer mode.
    Several streams keep a Wi-Fi link full even when single connections stall;
    chunks stay small enough that one request never takes long on a slow link."""
    mb = 1024 * 1024
    mode = CFG.get("transfer_mode", "auto")
    if mode == "fast":
        return 4, 8 * mb
    if mode == "reliable":
        return 1, 1 * mb
    if size >= 64 * mb:
        return 3, 4 * mb
    if size >= 4 * mb:
        return 3, 2 * mb
    return 3, 1 * mb


def received_dir() -> Path:
    p = Path(CFG["received_folder"])
    p.mkdir(parents=True, exist_ok=True)
    return p


def load_history():
    try:
        return json.loads(HISTORY_FILE.read_text(encoding="utf-8"))
    except Exception:
        return []


HISTORY = load_history()
HISTORY_LOCK = threading.Lock()
_history_dirty = threading.Event()


def _history_saver():
    """Writes the history file at most every 0.7 s, off the transfer path."""
    while True:
        _history_dirty.wait()
        time.sleep(0.7)
        _history_dirty.clear()
        with HISTORY_LOCK:
            snapshot = list(HISTORY)
        try:
            HISTORY_FILE.write_text(json.dumps(snapshot, indent=1), encoding="utf-8")
        except Exception as e:
            print(f"[history] could not save: {e}")


threading.Thread(target=_history_saver, daemon=True, name="history").start()


def history_add(direction, name, size, peer, status, path):
    with HISTORY_LOCK:
        HISTORY.insert(0, {"id": uuid.uuid4().hex[:10], "direction": direction, "name": name, "size": size,
                           "peer": peer, "time": time.time(), "status": status, "path": str(path)})
        del HISTORY[500:]
    _history_dirty.set()
    BUS.history_changed.emit()


def history_remove(ids):
    with HISTORY_LOCK:
        HISTORY[:] = [h for h in HISTORY if h.get("id") not in ids]
    _history_dirty.set()
    BUS.history_changed.emit()


def history_clear():
    with HISTORY_LOCK:
        HISTORY.clear()
    _history_dirty.set()
    BUS.history_changed.emit()


def fmt_size(n):
    if n is None or n < 0:
        return "?"
    if n < 1024:
        return f"{n} B"
    if n < 1024 ** 2:
        return f"{n / 1024:.0f} KB"
    if n < 1024 ** 3:
        return f"{n / 1024 ** 2:.1f} MB"
    return f"{n / 1024 ** 3:.2f} GB"


def fmt_eta(seconds):
    if seconds is None or seconds < 0:
        return ""
    s = int(seconds)
    if s < 60:
        return f"{s}s left"
    if s < 3600:
        return f"{s // 60}m {s % 60}s left"
    return f"{s // 3600}h {(s % 3600) // 60}m left"


RESERVED_NAMES = {"CON", "PRN", "AUX", "NUL"} | {f"COM{i}" for i in range(1, 10)} | {f"LPT{i}" for i in range(1, 10)}


def safe_name(name):
    """A name the sender chose is only ever a plain file name inside our folder."""
    name = name.replace("\\", "/").rsplit("/", 1)[-1]
    bad = '\\/:*?"<>|'
    n = "".join("_" if c in bad or ord(c) < 32 else c for c in name).strip().rstrip(". ")
    if not n or set(n) == {"."}:
        n = "file"
    if n.split(".")[0].upper() in RESERVED_NAMES:
        n = "_" + n
    return n[:180]


def unique_path(folder: Path, name: str) -> Path:
    p = folder / name
    if not p.exists():
        return p
    stem, ext = os.path.splitext(name)
    i = 1
    while True:
        p = folder / f"{stem} ({i}){ext}"
        if not p.exists():
            return p
        i += 1


# ------------------------------------------------------------------ signals bus

class Bus(QObject):
    """Everything that happens on worker / server threads reaches the UI here."""
    link_changed = pyqtSignal()
    transfer_changed = pyqtSignal()
    offer_arrived = pyqtSignal()
    pair_request = pyqtSignal()
    history_changed = pyqtSignal()
    ui_call = pyqtSignal(object)        # run a callable on the GUI thread (never QTimer from a thread)


def on_ui(fn):
    """Schedule `fn()` on the GUI thread from any thread."""
    BUS.ui_call.emit(fn)


BUS = Bus()
BUS.ui_call.connect(lambda fn: fn())


# ------------------------------------------------------------------ http helpers

TOKEN_HEADER = "X-LanDrop-Token"


def http_json(url, data=None, timeout=6, content_type="application/octet-stream", headers=None):
    req = urllib.request.Request(url, data=data, method="POST" if data is not None else "GET")
    if data is not None:
        req.add_header("Content-Type", content_type)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=timeout) as reply:
        return json.loads(reply.read().decode())


def post_json(url, obj, timeout=6, headers=None):
    return http_json(url, json.dumps(obj).encode(), timeout, "application/json", headers)


class PeerConn:
    """One kept-alive raw HTTP/1.1 connection to the peer for the chunk stream:
    no connection per chunk, big send buffer, bodies streamed piece by piece so
    progress is live. Reconnects by itself after an error."""

    def __init__(self, address, timeout=120):
        self.host, port = address.rsplit(":", 1)
        self.port = int(port)
        self.timeout = timeout
        self.sock = None

    def _connect(self):
        s = socket.create_connection((self.host, self.port), timeout=10)
        try:
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 2 * 1024 * 1024)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024 * 1024)
        except OSError:
            pass
        s.settimeout(self.timeout)
        self.sock = s

    def post(self, path, pieces, length, on_progress=None):
        """POST `length` bytes given as an iterable of byte pieces; returns the JSON reply."""
        if self.sock is None:
            self._connect()
        try:
            head = (f"POST {path} HTTP/1.1\r\nHost: {self.host}\r\nContent-Type: application/octet-stream\r\n"
                    f"Content-Length: {length}\r\n{TOKEN_HEADER}: {LINK.token}\r\nConnection: keep-alive\r\n\r\n")
            self.sock.sendall(head.encode())
            for piece in pieces:
                self.sock.sendall(piece)
                if on_progress:
                    on_progress(len(piece))
            return self._reply()
        except Exception:
            self.close()
            raise

    def get_stream(self, path):
        """GET; returns (content length, a reader(n) for the body)."""
        if self.sock is None:
            self._connect()
        try:
            head = (f"GET {path} HTTP/1.1\r\nHost: {self.host}\r\n{TOKEN_HEADER}: {LINK.token}\r\n"
                    f"Connection: keep-alive\r\n\r\n")
            self.sock.sendall(head.encode())
            length, close, rest = self._head()
            buf = [rest]

            def reader(n):
                if buf[0]:
                    out, buf[0] = buf[0][:n], buf[0][n:]
                    return out
                return self.sock.recv(n)
            return length, reader, close
        except Exception:
            self.close()
            raise

    def _head(self):
        data = b""
        while b"\r\n\r\n" not in data:
            more = self.sock.recv(65536)
            if not more:
                raise OSError("connection closed")
            data += more
            if len(data) > 65536:
                raise OSError("bad reply")
        head, rest = data.split(b"\r\n\r\n", 1)
        lines = head.decode(errors="replace").split("\r\n")
        length, close = 0, False
        for ln in lines[1:]:
            k, _, v = ln.partition(":")
            if k.strip().lower() == "content-length":
                length = int(v.strip() or 0)
            if k.strip().lower() == "connection" and v.strip().lower() == "close":
                close = True
        return length, close, rest

    def _reply(self):
        length, close, body = self._head()
        while len(body) < length:
            more = self.sock.recv(min(65536, length - len(body)))
            if not more:
                raise OSError("connection closed")
            body += more
        if close:
            self.close()
        LINK.touch()
        return json.loads(body[:length].decode())

    def close(self):
        try:
            if self.sock is not None:
                self.sock.close()
        except Exception:
            pass
        self.sock = None


def local_ip():
    """The address other devices should use: the adapter that carries the default
    route (the real Wi-Fi / Ethernet), not a virtual adapter (WSL, VirtualBox, VPN)."""
    for probe in ("8.8.8.8", "10.255.255.255"):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect((probe, 1))
            ip = s.getsockname()[0]
            s.close()
            if not ip.startswith("127."):
                return ip
        except Exception:
            pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip.startswith("192.168.") or ip.startswith("10."):
                return ip
    except Exception:
        pass
    return "127.0.0.1"


def local_ips():
    """Every IPv4 of this PC (Wi-Fi, Ethernet, hotspot, virtual adapters), main one first."""
    ips = [local_ip()]
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    return [ip for ip in ips if not ip.startswith("127.")] or ["127.0.0.1"]


def is_private(ip):
    a, b = (int(x) for x in ip.split(".")[:2])
    return a == 10 or a == 192 and b == 168 or a == 172 and 16 <= b <= 31


# ------------------------------------------------------------------ the link (connection)

class Link:
    """Pairing and the live link. Nothing connects without consent on BOTH ends:
    one side asks, the other sees ALLOW / DENY, and only then a pairing token is
    shared that every later request must carry. A link that drops (Wi-Fi blip)
    keeps its token and comes back by itself when the peer answers again -
    only DISCONNECT (either side) forgets the pairing."""
    OFF, REQUESTING, CONNECTED, LOST = "off", "requesting", "connected", "lost"

    class Request:
        def __init__(self, rid, ip, port, name, token):
            self.id, self.ip, self.port, self.name, self.token = rid, ip, port, name, token
            self.at = time.time()
            self.answer = "pending"      # pending | allowed | denied

    def __init__(self):
        self.state = Link.OFF
        self.peer_address = ""
        self.peer_name = ""
        self.message = "Not connected"
        self.token = ""
        self.incoming: "Link.Request | None" = None     # a device asking us
        self.last_request: "Link.Request | None" = None
        self.cancel_outgoing = False
        self.misses = 0
        self.last_traffic = 0.0
        self.lost_at = 0.0
        self.troubled = 0

    def auth(self):
        return {TOKEN_HEADER: self.token}

    def touch(self):
        """Any successful exchange with the peer counts as a heartbeat."""
        self.last_traffic = time.time()
        self.misses = 0
        self.troubled = 0

    def note_failure(self):
        """A request to the peer failed: after a couple of those the heartbeat checks right away."""
        self.troubled += 1

    def start(self):
        threading.Thread(target=self._loop, daemon=True, name="link").start()

    def _loop(self):
        while not STOPPING:
            try:
                if self.state == Link.CONNECTED:
                    self._heartbeat()
                elif self.state == Link.LOST:
                    self._try_reconnect()
                r = self.incoming
                if r is not None and time.time() - r.at > 60:
                    r.answer = "denied"
                    self.incoming = None
                    self._set(self.state, message=f"The request from {r.name} expired")
            except Exception as e:
                print(f"[link] loop: {e}")
            time.sleep(2)

    def _set(self, state, address=None, name=None, message=None):
        self.state = state
        if address is not None:
            self.peer_address = address
        if name is not None:
            self.peer_name = name
        if message is not None:
            self.message = message
        BUS.link_changed.emit()

    def _heartbeat(self):
        # A transfer in progress proves the link; only ping when it is quiet (or when our own requests keep failing).
        if self.troubled < 2 and time.time() - self.last_traffic < 5:
            return
        if self.ping(self.peer_address, timeout=4):
            self.touch()
        else:
            self.misses += 1
            if self.misses >= 3:
                self.lost_at = time.time()
                self._set(Link.LOST, message=f"Lost {self.peer_name} - reconnecting when it is back...")
                print(f"[link] lost {self.peer_name}")

    def _try_reconnect(self):
        if not self.ping(self.peer_address, timeout=3):
            return
        try:
            r = post_json(f"http://{self.peer_address}/hello", {"name": CFG["device_name"]}, timeout=4, headers=self.auth())
        except Exception:
            return
        if r.get("ok"):
            self.touch()
            self._set(Link.CONNECTED, message=f"Connected to {self.peer_name}")
            print(f"[link] reconnected to {self.peer_name}")
        else:
            self._drop(f"{self.peer_name} disconnected")

    @staticmethod
    def ping(address, timeout=2.5):
        try:
            info = http_json(f"http://{address}/ping", timeout=timeout)
            return info if info.get("app") == APP_ID else None
        except Exception:
            return None

    @staticmethod
    def discover_all(timeout=3.0):
        """Every LanDrop on the network (not ourselves): [(address, name)].
        A UDP call goes out of EVERY network adapter (plain and directed broadcast,
        repeated), and in parallel the local subnet is swept with /ping so devices
        on networks that filter broadcasts (hotspots, some routers) are found too."""
        found = {}
        mine = {f"{ip}:{PORT}" for ip in local_ips()}
        socks = []
        targets = []
        for ip in [""] + local_ips():
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                try:
                    s.ioctl(socket.SIO_UDP_CONNRESET, False)   # Windows UDP trap
                except (AttributeError, OSError):
                    pass
                s.bind((ip, 0))
                s.setblocking(False)
                dests = [("255.255.255.255", DISCOVERY_PORT)]
                if ip:
                    dests.append((ip.rsplit(".", 1)[0] + ".255", DISCOVERY_PORT))
                socks.append(s)
                targets.append((s, dests))
            except Exception as e:
                print(f"[link] discovery socket {ip or 'any'}: {e}")

        def call():
            for s, dests in targets:
                for d in dests:
                    try:
                        s.sendto(DISCOVER_CALL, d)
                    except Exception:
                        pass

        def sweep():
            nets = []
            for ip in local_ips():
                if is_private(ip):
                    net = ip.rsplit(".", 1)[0]
                    if net not in nets:
                        nets.append(net)
            addresses = [f"{net}.{i}:{DEFAULT_PORT}" for net in nets[:2] for i in range(1, 255)]
            with ThreadPoolExecutor(max_workers=96) as pool:
                for address, info in zip(addresses, pool.map(lambda a: Link.ping(a, timeout=0.8), addresses)):
                    if info and info.get("instance") != INSTANCE and address not in mine:
                        found.setdefault(address, (address, info.get("name") or "Device"))
        sweeper = threading.Thread(target=sweep, daemon=True)
        sweeper.start()
        deadline = time.time() + timeout
        next_call = 0
        calls = 0
        while time.time() < deadline:
            if calls < 3 and time.time() >= next_call:
                call()
                calls += 1
                next_call = time.time() + 0.5
            readable, _, _ = select.select(socks, [], [], 0.1) if socks else ([], [], [])
            for s in readable:
                try:
                    data, addr = s.recvfrom(512)
                except OSError:
                    continue
                # "LANDROP_HERE <port> <instance> <name words...>"
                parts = data.decode(errors="replace").strip().split(" ", 3)
                if len(parts) >= 3 and parts[0] == DISCOVER_REPLY and parts[2] != INSTANCE:
                    address = f"{addr[0]}:{parts[1]}"
                    if address not in mine:
                        found[address] = (address, parts[3] if len(parts) > 3 and parts[3] else "Device")
        for s in socks:
            s.close()
        sweeper.join(timeout=max(0.0, deadline + 1.5 - time.time()))
        return list(found.values())

    @staticmethod
    def normalize(address):
        a = address.strip().removeprefix(SCHEME).rstrip("/")
        if ":" not in a:
            a = f"{a}:{DEFAULT_PORT}"
        host, _, port = a.rpartition(":")
        if not host or not port.isdigit() or any(c in host for c in " /\\"):
            return None
        return a

    def request_pair(self, address):
        """Ask the device at `address` to connect (blocking, up to 90 s while it decides).
        Returns None on success, otherwise the message to show."""
        a = self.normalize(address)
        if not a:
            return "That does not look like an address."
        if self.state in (Link.CONNECTED, Link.LOST):
            return "Disconnect first."
        if self.state == Link.REQUESTING:
            return "Already asking another device."
        self.cancel_outgoing = False
        self._set(Link.REQUESTING, message=f"Asking {a}...")
        try:
            info = self.ping(a)
            if not info:
                return self._fail(f"Nothing answers at {a}.\nIs LanDrop open on that device, on the same network?")
            name = info.get("name") or "Device"
            rid = uuid.uuid4().hex[:12]
            try:
                r = post_json(f"http://{a}/pair", {"id": rid, "name": CFG["device_name"], "port": PORT})
            except Exception:
                return self._fail(f"{name} did not answer the request.")
            if not r.get("ok"):
                return self._fail(f"{name} is busy - connected to another device or already deciding on a request."
                                  if r.get("error") == "busy" else f"{name} refused the request.")
            self._set(self.state, message=f"Waiting for {name} to allow...")
            deadline = time.time() + 90
            while time.time() < deadline:
                if self.cancel_outgoing:
                    self._withdraw(a, rid)
                    return self._fail("Request cancelled")
                time.sleep(0.7)
                try:
                    st = http_json(f"http://{a}/pair?id={rid}", timeout=4)
                except Exception:
                    continue
                state = st.get("state")
                if state == "allowed":
                    self._established(a, st.get("name") or name, st.get("token", ""))
                    return None
                if state == "denied":
                    return self._fail(f"{name} declined.")
                if state == "unknown":
                    return self._fail(f"{name} forgot the request - try again.")
            self._withdraw(a, rid)
            return self._fail(f"{name} did not answer in time.")
        finally:
            if self.state == Link.REQUESTING:
                self._set(Link.OFF)

    @staticmethod
    def _withdraw(address, rid):
        try:
            post_json(f"http://{address}/pair?cancel=1&id={rid}", {}, timeout=3)
        except Exception:
            pass

    def _fail(self, text):
        self._set(Link.OFF, message=text)
        print(f"[link] pair failed: {text}")
        return text

    def cancel_request(self):
        self.cancel_outgoing = True

    # ---- called by the server
    def on_pair_request(self, ip, port, name, rid):
        if self.state != Link.OFF or self.incoming is not None or not rid:
            return {"ok": False, "error": "busy"}
        req = Link.Request(rid, ip, port, name, uuid.uuid4().hex + uuid.uuid4().hex)
        self.last_request = req
        self.incoming = req
        self._set(self.state, message=f"{name} wants to connect")
        print(f"[link] pair request from {name} at {ip}:{port}")
        BUS.pair_request.emit()
        return {"ok": True, "state": "pending"}

    def on_pair_poll(self, rid):
        r = self.last_request
        if r is None or r.id != rid:
            return {"state": "unknown"}
        if r.answer == "allowed":
            return {"state": "allowed", "token": r.token, "name": CFG["device_name"]}
        return {"state": r.answer}

    def on_pair_cancel(self, rid):
        r = self.incoming
        if r is not None and r.id == rid:
            r.answer = "denied"
            self.incoming = None
            self._set(self.state, message=f"{r.name} withdrew the request")

    def on_introduce(self, address):
        """A phone scanned this PC's QR and another device's: start asking that
        device, exactly as if the address had been typed here. The other device
        still shows ALLOW / DENY, so nothing connects without consent."""
        if self.state != Link.OFF:
            return {"ok": False, "error": "busy"}
        a = self.normalize(address or "")
        if not a:
            return {"ok": False, "error": "address"}
        if a in {f"{ip}:{PORT}" for ip in local_ips()}:
            return {"ok": False, "error": "self"}

        def go():
            msg = self.request_pair(a)
            if msg:
                self._set(self.state, message=msg)
        threading.Thread(target=go, daemon=True).start()
        return {"ok": True}

    def on_hello(self, name):
        """The peer came back after a dropped link (its token was checked)."""
        if self.state == Link.LOST:
            self.touch()
            self._set(Link.CONNECTED, name=name or self.peer_name, message=f"Connected to {name or self.peer_name}")
            print(f"[link] {self.peer_name} is back")
        else:
            self.touch()
        return {"ok": True, "name": CFG["device_name"]}

    def allow(self):
        r = self.incoming
        if r is None:
            return
        self.incoming = None
        r.answer = "allowed"
        self._established(f"{r.ip}:{r.port}", r.name, r.token)

    def deny(self):
        r = self.incoming
        if r is None:
            return
        self.incoming = None
        r.answer = "denied"
        self._set(self.state, message=f"You declined {r.name}")

    def is_peer(self, ip, token_header):
        """True only for the paired device: same IP as the peer AND the shared token
        (a dropped link keeps the pairing, so its requests still count)."""
        return (self.state in (Link.CONNECTED, Link.LOST) and bool(self.token) and token_header == self.token
                and self.peer_address.rpartition(":")[0] == ip)

    def _established(self, address, name, token):
        self.token = token
        self.touch()
        self._set(Link.CONNECTED, address, name, f"Connected to {name}")
        if CFG.get("peer") != address:
            CFG["peer"] = address
            save_settings()
        print(f"[link] connected to {name} at {address}")

    def _drop(self, text):
        self.token = ""
        self._set(Link.OFF, "", "", text)

    def disconnect(self):
        """DISCONNECT: tell the peer, forget the token. Nothing reconnects by itself."""
        a, headers = self.peer_address, self.auth()
        if a:
            threading.Thread(target=lambda: self._bye(a, headers), daemon=True).start()
        self._drop("Disconnected")

    @staticmethod
    def _bye(a, headers):
        try:
            post_json(f"http://{a}/bye", {}, timeout=3, headers=headers)
        except Exception:
            pass

    def on_bye(self):
        """The peer said /bye (the server checked the token first)."""
        name = self.peer_name
        self._drop(f"{name} disconnected")


LINK = Link()


# ------------------------------------------------------------------ transfers

WAITING, ACTIVE, PAUSED, DONE, CANCELLED, FAILED = "waiting", "active", "paused", "done", "cancelled", "failed"


class FileItem:
    def __init__(self, fid, name, size, path=None):
        self.fid, self.name, self.size, self.path = fid, name, size, path
        self.done = 0                   # bytes acknowledged (sender) / written and verified (receiver)
        self.out_path = None
        self.finished = False
        self.chunks_done = set()        # receiver: offsets of chunks that are in the file
        self.digest = None              # receiver, unknown-size stream path only


class Batch:
    def __init__(self, bid, files, outgoing, peer):
        self.id, self.files, self.outgoing, self.peer = bid, files, outgoing, peer
        self.state = WAITING
        self.index = 0
        self.live = 0                   # bytes of the chunks in flight right now
        self.speed = 0
        self.note = ""
        self.paused_by = ""             # me | peer
        self.streams = 1
        self.total = sum(max(f.size, 0) for f in files)
        self.last_activity = time.time()
        self.lock = threading.Lock()
        self.by_fid = {f.fid: f for f in files}          # O(1) lookups: a thousand files must not cost a million compares
        self.pos = {f.fid: i for i, f in enumerate(files)}
        self.done_sum = 0
        self._t = 0
        self._b = 0

    @property
    def finished(self):
        return self.state in (DONE, CANCELLED, FAILED)

    @property
    def done_bytes(self):
        return self.done_sum + self.live

    @property
    def fraction(self):
        return 0.0 if self.total <= 0 else max(0.0, min(1.0, self.done_bytes / self.total))

    @property
    def current(self):
        return self.files[min(self.index, len(self.files) - 1)]

    @property
    def eta(self):
        return None if self.speed <= 0 or self.total <= 0 else max(0, self.total - self.done_bytes) / self.speed

    def progress(self, n):
        """`n` more bytes are on the wire (negative: a failed attempt is rolled back)."""
        with self.lock:
            self.live = max(0, self.live + n)
            now = time.time()
            self.last_activity = now
            if self._t == 0:
                self._t, self._b = now, self.done_bytes
            elif now - self._t >= 1:
                self.speed = (self.done_bytes - self._b) / (now - self._t)
                self._t, self._b = now, self.done_bytes
                BUS.transfer_changed.emit()

    def credit(self, f, n):
        """`n` bytes of `f` are safely across: move them from live to done."""
        with self.lock:
            self.live = max(0, self.live - n)
            self.done_sum += n
            f.done += n


class Transfers:
    current: "Batch | None" = None
    offer: "Batch | None" = None

    @classmethod
    def busy(cls):
        return cls.current is not None and not cls.current.finished

    @classmethod
    def dismiss(cls):
        if cls.current is not None and cls.current.finished:
            cls.current = None
            BUS.transfer_changed.emit()


def file_pieces(fh, offset, length, digest=None):
    """Yields `length` bytes of `fh` from `offset` in PIECE-sized reads (hashing on the way)."""
    fh.seek(offset)
    left = length
    while left > 0:
        data = fh.read(min(PIECE, left))
        if not data:
            raise OSError("the file shrank while sending")
        if digest is not None:
            digest.update(data)
        left -= len(data)
        yield data


class Outgoing:
    """Sends one batch over several parallel connections. The work is cut into
    units - a chunk of a big file (offset + length + its own SHA-256) or a bundle
    of small files - that any worker can deliver in any order, so streams stay
    busy, a lost reply just means a harmless resend, and a dropped link resumes
    exactly where it stopped once the peer is back."""

    def __init__(self, paths):
        files = []
        for i, p in enumerate(paths):
            p = Path(p)
            try:
                size = p.stat().st_size
            except Exception:
                size = -1
            files.append(FileItem(f"f{i}_{uuid.uuid4().hex[:8]}", p.name, size, p))
        self.b = Batch(uuid.uuid4().hex[:12], files, True, LINK.peer_name)
        self.units = None
        self.unit_lock = threading.Lock()
        self.verify = bool(CFG.get("verify", True))
        self.workers = []
        self.failed = False

    def start(self):
        threading.Thread(target=self.run, daemon=True, name="send").start()

    def run(self):
        b = self.b
        Transfers.current = b
        b.note = f"Waiting for {b.peer} to accept..."
        BUS.transfer_changed.emit()
        offer = {"id": b.id, "from": CFG["device_name"], "v": VERSION,
                 "files": [{"fid": f.fid, "name": f.name, "size": f.size} for f in b.files]}
        try:
            r = post_json(f"http://{LINK.peer_address}/offer", offer, headers=LINK.auth())
        except Exception:
            return self.fail(f"{b.peer} is not answering")
        if not r.get("ok"):
            return self.fail(f"{b.peer} is busy with another transfer" if r.get("error") == "busy"
                             else f"{b.peer} refused")
        deadline = time.time() + 180
        while True:
            if b.state == CANCELLED:
                self.control("cancel")
                return
            try:
                st = http_json(f"http://{LINK.peer_address}/offer?id={b.id}", timeout=4, headers=LINK.auth()).get("state")
            except Exception:
                st = "pending"
            if st == "accepted":
                break
            if st == "declined":
                return self.fail(f"{b.peer} declined", CANCELLED, tell_peer=False)
            if st == "unknown":
                return self.fail(f"{b.peer} forgot the transfer", tell_peer=False)
            if time.time() > deadline:
                return self.fail(f"No answer from {b.peer}")
            time.sleep(0.5)
        b.state = ACTIVE
        b.note = ""
        streams = max(transfer_plan(f.size)[0] for f in b.files)
        b.streams = streams
        BUS.transfer_changed.emit()
        self.units = self._units()
        self.workers = [threading.Thread(target=self.worker, daemon=True, name=f"send{i}") for i in range(streams)]
        for w in self.workers:
            w.start()
        for w in self.workers:
            w.join()
        if b.state in (ACTIVE, PAUSED) and not self.failed:
            if all(f.finished for f in b.files):
                b.state = DONE
                b.note = f"All {len(b.files)} file(s) delivered to {b.peer}"
                b.speed = 0
            else:
                self.fail("Not every file made it - try again")
        BUS.transfer_changed.emit()

    def _units(self):
        """("bundle", [files]) | ("chunk", f, offset, length) | ("stream", f) - lazily."""
        files = self.b.files
        i = 0
        while i < len(files):
            f = files[i]
            if 0 <= f.size <= BUNDLE_FILE_MAX:
                j, total = i, 0
                while (j < len(files) and 0 <= files[j].size <= BUNDLE_FILE_MAX
                       and total + files[j].size <= BUNDLE_MAX and j - i < BUNDLE_FILES_MAX):
                    total += files[j].size
                    j += 1
                if j - i >= 2:
                    yield ("bundle", files[i:j])
                    i = j
                    continue
            if f.size < 0:
                yield ("stream", f)
            elif f.size == 0:
                yield ("chunk", f, 0, 0)
            else:
                chunk = transfer_plan(f.size)[1]
                for off in range(0, f.size, chunk):
                    yield ("chunk", f, off, min(chunk, f.size - off))
            i += 1

    def next_unit(self):
        with self.unit_lock:
            try:
                return next(self.units)
            except StopIteration:
                return None

    def worker(self):
        b = self.b
        conn = PeerConn(LINK.peer_address)
        handles = {}
        try:
            while True:
                if not self.gate(conn):
                    return
                unit = self.next_unit()
                if unit is None:
                    return
                b.index = max(b.index, b.pos[(unit[1][-1] if unit[0] == "bundle" else unit[1]).fid])
                ok = {"bundle": self.send_bundle, "chunk": self.send_chunk, "stream": self.send_stream}[unit[0]](conn, handles, unit)
                if not ok:
                    return
        finally:
            conn.close()
            for fh in handles.values():
                try:
                    fh.close()
                except Exception:
                    pass

    def handle(self, handles, f):
        fh = handles.get(f.fid)
        if fh is None:
            fh = handles[f.fid] = open(f.path, "rb")
        return fh

    def post(self, conn, path, pieces, length):
        """One request with retries: a lost link waits for the reconnect, a lost
        reply is answered by the receiver's own bookkeeping (duplicates are fine).
        Returns the reply, or None when the transfer is over."""
        b = self.b
        attempt = 0
        while True:
            if not self.gate(conn):
                return None
            credited = [0]

            def progress(n):
                credited[0] += n
                b.progress(n)
            try:
                r = conn.post(path, pieces(), length, progress)
                if not r.get("ok"):
                    b.progress(-credited[0])     # refused: those bytes do not count
                return r
            except Exception as e:
                b.progress(-credited[0])
                LINK.note_failure()
                attempt += 1
                if b.state == ACTIVE:
                    b.note = ("Connection lost - resumes when the link is back" if LINK.state != Link.CONNECTED
                              else "Retrying...")
                    BUS.transfer_changed.emit()
                print(f"[send] request failed ({attempt}): {e}")
                time.sleep(min(1.0 + attempt * 0.5, 4.0))

    def send_chunk(self, conn, handles, unit):
        _, f, offset, length = unit
        b = self.b
        try:
            fh = self.handle(handles, f)
            sha = ""
            if self.verify:
                d = hashlib.sha256()
                for _ in file_pieces(fh, offset, length, d):
                    pass
                sha = d.hexdigest()
        except Exception as e:
            self.fail(f"Could not read {f.name}: {e}")
            return False
        path = f"/chunk?id={b.id}&fid={f.fid}&offset={offset}&len={length}" + (f"&sha={sha}" if sha else "")
        tries = 0
        while True:
            r = self.post(conn, path, lambda: file_pieces(fh, offset, length), length)
            if r is None:
                return False
            if r.get("ok"):
                b.credit(f, length)
                b.note = ""
                if r.get("complete") or f.done >= f.size:
                    self.file_done(f)
                return True
            if not self.on_refusal(r, f):
                return False
            if r.get("error") == "checksum":
                tries += 1
                if tries >= 3:
                    self.fail(f"{f.name} keeps arriving damaged - try again later")
                    return False
                continue
            if r.get("error") in ("range", "unknown"):
                self.fail(f"Out of sync with {b.peer} - start again")
                return False

    def send_stream(self, conn, handles, unit):
        """A file whose size is unknown: sequential chunks, the last one flagged."""
        _, f = unit
        b = self.b
        try:
            fh = self.handle(handles, f)
        except Exception as e:
            self.fail(f"Could not read {f.name}: {e}")
            return False
        chunk = transfer_plan(0)[1]
        offset = 0
        while True:
            fh.seek(offset)
            data = fh.read(chunk)
            last = len(data) < chunk
            sha = hashlib.sha256(data).hexdigest() if self.verify else ""
            path = (f"/chunk?id={b.id}&fid={f.fid}&offset={offset}&len={len(data)}&last={1 if last else 0}"
                    + (f"&sha={sha}" if sha else ""))
            r = self.post(conn, path, lambda: iter([data] if data else []), len(data))
            if r is None:
                return False
            if r.get("ok"):
                b.credit(f, len(data))
                offset += len(data)
                if last:
                    self.file_done(f)
                    return True
                continue
            if not self.on_refusal(r, f):
                return False
            if r.get("error") == "offset":
                offset = int(r.get("received", offset))
                continue
            self.fail(f"Out of sync with {b.peer} - start again")
            return False

    def send_bundle(self, conn, handles, unit):
        """Sends a group of small files as one POST /bundle: u32 index length, JSON
        index [{fid,size,sha}], then the files back to back."""
        _, group = unit
        b = self.b
        index, blobs = [], []
        for f in group:
            try:
                data = Path(f.path).read_bytes()
            except Exception as e:
                self.fail(f"Could not open {f.name}: {e}")
                return False
            index.append({"fid": f.fid, "size": len(data),
                          "sha": hashlib.sha256(data).hexdigest() if self.verify else ""})
            blobs.append(data)
        head = json.dumps(index).encode()
        body = len(head).to_bytes(4, "big") + head + b"".join(blobs)
        pieces = [body[i:i + PIECE] for i in range(0, len(body), PIECE)]
        while True:
            t0 = time.time()
            r = self.post(conn, f"/bundle?id={b.id}", lambda: iter(pieces), len(body))
            if r is None:
                return False
            if r.get("ok"):
                b.progress(-(len(body) - sum(f.size for f in group)))     # the index header is not file data
                for f in group:
                    b.credit(f, f.size)
                    self.file_done(f)
                b.note = ""
                print(f"[send] bundle: {len(group)} files, {fmt_size(len(body))} in {time.time() - t0:.2f}s")
                return True
            if not self.on_refusal(r, group[0]):
                return False
            self.fail("A file arrived damaged - try again" if r.get("error") == "checksum"
                      else f"{b.peer} refused a bundle: {r.get('error')}")
            return False

    def on_refusal(self, r, f):
        """Handles the receiver's non-ok states; True when the caller may retry."""
        b = self.b
        st = r.get("state")
        if st == "paused":
            if b.state == ACTIVE:
                b.state, b.paused_by, b.note, b.speed = PAUSED, "peer", f"Paused by {b.peer}", 0
                BUS.transfer_changed.emit()
            return True
        if st == "pending":
            time.sleep(0.5)
            return True
        if st in ("cancelled", "unknown"):
            b.state, b.note, b.speed = CANCELLED, f"{b.peer} stopped the transfer", 0
            BUS.transfer_changed.emit()
            return False
        if r.get("error") == "unauthorized":
            if LINK.state == Link.CONNECTED:
                self.fail(f"{b.peer} no longer knows this connection - connect again", tell_peer=False)
                return False
            time.sleep(1)
            return True
        return True

    def file_done(self, f):
        if f.finished:
            return
        f.finished = True
        history_add("sent", f.name, max(f.size, f.done), self.b.peer, "done", f.path)
        BUS.transfer_changed.emit()

    def gate(self, conn):
        """Waits while paused or while the link is down; False when the transfer is over."""
        b = self.b
        polls = 0
        waited_link = False
        while True:
            if STOPPING or b.finished or self.failed:
                return False
            if b.state == ACTIVE:
                if LINK.state == Link.CONNECTED:
                    if waited_link:
                        b.note = ""
                        BUS.transfer_changed.emit()
                    return True
                if LINK.state == Link.OFF:
                    self.fail("Disconnected - the transfer was stopped", CANCELLED, tell_peer=False)
                    return False
                # Link lost: hold the chunk, it resumes when the peer is back.
                if not waited_link:
                    waited_link = True
                    conn.close()
                    b.note = "Connection lost - resumes when the link is back"
                    b.speed = 0
                    BUS.transfer_changed.emit()
                time.sleep(0.5)
                continue
            if b.state != PAUSED:
                return False
            if b.paused_by == "peer":
                polls += 1
                if polls % 3 == 1:
                    try:
                        st = http_json(f"http://{LINK.peer_address}/status?id={b.id}", timeout=4, headers=LINK.auth()).get("state")
                    except Exception:
                        st = "paused"
                    if st == "active":
                        b.state, b.paused_by, b.note = ACTIVE, "", ""
                        BUS.transfer_changed.emit()
                        continue
                    if st == "cancelled":
                        b.state, b.note = CANCELLED, f"{b.peer} stopped the transfer"
                        BUS.transfer_changed.emit()
                        return False
            time.sleep(0.3)

    def fail(self, note, state=FAILED, tell_peer=True):
        was_running = self.b.state in (ACTIVE, PAUSED)
        self.failed = True
        self.b.state = state
        self.b.note = note
        self.b.speed = 0
        if tell_peer and was_running and LINK.state == Link.CONNECTED:
            self.control("cancel")       # the receiver must not sit waiting for more
        BUS.transfer_changed.emit()
        print(f"[send] {note}")

    def control(self, action):
        bid = self.b.id

        def go():
            try:
                post_json(f"http://{LINK.peer_address}/control?id={bid}&action={action}", {}, timeout=4, headers=LINK.auth())
            except Exception as e:
                print(f"[send] control {action}: {e}")
        threading.Thread(target=go, daemon=True).start()

    def pause(self):
        if self.b.state == ACTIVE:
            self.b.state, self.b.paused_by, self.b.note, self.b.speed = PAUSED, "me", "Paused", 0
            self.control("pause")
            BUS.transfer_changed.emit()

    def resume(self):
        if self.b.state == PAUSED:
            self.b.state, self.b.paused_by, self.b.note = ACTIVE, "", ""
            self.control("resume")
            BUS.transfer_changed.emit()

    def cancel(self):
        if self.b.finished:
            return
        self.b.state, self.b.note, self.b.speed = CANCELLED, "Stopped", 0
        self.control("cancel")       # also while still waiting: the receiver's popup must go
        BUS.transfer_changed.emit()


class Incoming:
    """The receiving side, driven by the HTTP server. Chunks of one file may
    arrive on several connections at once; each is written at its offset and
    verified on its own, so order does not matter and a resend is harmless."""
    lock = threading.Lock()
    batch: "Batch | None" = None

    @classmethod
    def offer(cls, obj, from_ip):
        with cls.lock:
            if Transfers.busy() or Transfers.offer is not None:
                return {"ok": False, "error": "busy"}
            files = [FileItem(f.get("fid", f"f{i}"), safe_name(f.get("name", "file")), int(f.get("size", -1)))
                     for i, f in enumerate(obj.get("files", []))]
            if not files:
                return {"ok": False, "error": "empty"}
            b = Batch(obj.get("id") or uuid.uuid4().hex, files, False,
                      obj.get("from") or LINK.peer_name or from_ip)
            cls.batch = b
            # The receiver ALWAYS decides: every send session pops ACCEPT / DECLINE.
            b.note = "Waiting for your answer"
            Transfers.offer = b
            BUS.offer_arrived.emit()

            def timeout():
                time.sleep(180)
                if Transfers.offer is b:
                    cls.decline()
            threading.Thread(target=timeout, daemon=True).start()
            print(f"[recv] offer from {b.peer}: {len(files)} file(s), {fmt_size(b.total)}")
            return {"ok": True}

    @classmethod
    def accept(cls):
        b = Transfers.offer
        if b is None:
            return
        Transfers.offer = None
        b.state = ACTIVE
        b.note = ""
        b.last_activity = time.time()
        Transfers.current = b
        BUS.transfer_changed.emit()

    @classmethod
    def decline(cls):
        b = Transfers.offer
        if b is None:
            return
        Transfers.offer = None
        b.state = CANCELLED
        b.note = "Declined"
        BUS.transfer_changed.emit()

    @classmethod
    def offer_state(cls, bid):
        b = cls.batch
        if b is None or b.id != bid:
            return "unknown"
        return {WAITING: "pending", ACTIVE: "accepted", PAUSED: "accepted", DONE: "accepted"}.get(b.state, "declined")

    @classmethod
    def _check(cls, bid, rfile, length):
        """The batch this request belongs to, or a refusal dict (body drained)."""
        b = cls.batch
        if b is None or b.id != bid:
            drain(rfile, length)
            return None, {"ok": False, "state": "unknown"}
        if b.state == WAITING:
            drain(rfile, length)
            return None, {"ok": False, "state": "pending"}
        if b.state == PAUSED:
            drain(rfile, length)
            return None, {"ok": False, "state": "paused"}
        if b.state in (CANCELLED, FAILED, DONE):
            drain(rfile, length)
            return None, {"ok": False, "state": "cancelled"}
        return b, None

    @classmethod
    def chunk(cls, bid, fid, offset, length, last, rfile, sha=""):
        with cls.lock:
            b, refusal = cls._check(bid, rfile, length)
            if refusal:
                return refusal
            f = b.by_fid.get(fid)
            if f is None or offset < 0 or length < 0:
                drain(rfile, length)
                return {"ok": False, "error": "unknown"}
            if f.finished or offset in f.chunks_done:
                drain(rfile, length)
                return {"ok": True, "dup": True, "done": f.done, "complete": f.finished}
            if f.size >= 0 and offset + length > f.size:
                drain(rfile, length)
                return {"ok": False, "error": "range", "received": f.done}
            if f.size < 0 and offset != f.done:        # unknown size: strictly sequential
                drain(rfile, length)
                return {"ok": False, "error": "offset", "received": f.done}
            try:
                if f.out_path is None:
                    f.out_path = unique_path(received_dir(), f.name + ".part")
                    f.out_path.touch()
                    if f.size < 0:
                        f.digest = hashlib.sha256() if (sha or CFG.get("verify", True)) else None
            except Exception as e:
                return cls._broken(b, f, rfile, length, e)
            out_path = f.out_path
            b.index = max(b.index, b.pos[f.fid])
        # The bytes come in OUTSIDE the lock so other connections keep flowing.
        digest = hashlib.sha256() if sha else None
        got = 0
        try:
            with open(out_path, "r+b") as out:
                out.seek(offset)
                left = length
                while left > 0:
                    if b.state in (CANCELLED, FAILED):
                        raise OSError("stopped")
                    data = rfile.read(min(PIECE, left))
                    if not data:
                        raise OSError("the sender stopped early")
                    out.write(data)
                    if digest is not None:
                        digest.update(data)
                    left -= len(data)
                    got += len(data)
                    b.progress(len(data))
        except Exception as e:
            b.progress(-got)
            if b.state in (CANCELLED, FAILED):
                drain(rfile, length - got)
                cls._discard(b)              # the handle is closed now: the partial can go
                return {"ok": False, "state": "cancelled"}
            if b.state == ACTIVE:
                print(f"[recv] chunk of {f.name} at {offset} broke off: {e}")
            return {"ok": False, "error": "incomplete"}
        if digest is not None and sha.lower() != digest.hexdigest():
            b.progress(-got)
            print(f"[recv] checksum mismatch on {f.name} at {offset} - asking again")
            return {"ok": False, "error": "checksum"}
        with cls.lock:
            if b.state in (CANCELLED, FAILED):
                b.progress(-got)
                cls._discard(b)
                return {"ok": False, "state": "cancelled"}
            if f.finished or offset in f.chunks_done:
                b.progress(-got)
                return {"ok": True, "dup": True, "done": f.done, "complete": f.finished}
            f.chunks_done.add(offset)
            b.credit(f, length)
            complete = (f.size >= 0 and f.done >= f.size) or (f.size < 0 and last)
            if complete:
                cls._finish(b, f)
            return {"ok": True, "done": f.done, "complete": complete}

    @classmethod
    def _broken(cls, b, f, rfile, length, e):
        drain(rfile, length)
        print(f"[recv] could not save {f.name}: {e}")
        b.state = FAILED
        b.note = f"Could not save {f.name}: {e}"
        cls._discard(b)
        BUS.transfer_changed.emit()
        return {"ok": False, "state": "cancelled"}

    @classmethod
    def _finish(cls, b, f, rename=True):
        """Called under the lock once every byte of `f` is in: name it, log it."""
        if rename:
            final = unique_path(received_dir(), f.name)
            try:
                f.out_path.rename(final)
                f.out_path = final
            except Exception as e:
                print(f"[recv] rename failed: {e}")
        f.finished = True
        b.finished_count = getattr(b, "finished_count", 0) + 1
        history_add("received", f.name, f.done, b.peer, "done", f.out_path)
        if f.size > BUNDLE_FILE_MAX or len(b.files) <= 20:
            print(f"[recv] saved {f.out_path.name} ({fmt_size(f.done)})")
        if b.finished_count >= len(b.files) and all(x.finished for x in b.files):
            b.state = DONE
            b.note = f"Saved in {received_dir()}"
            b.speed = 0
        BUS.transfer_changed.emit()

    @classmethod
    def bundle(cls, bid, rfile, length):
        """Many small files in one request: u32 index length, JSON index, the bytes."""
        with cls.lock:
            b, refusal = cls._check(bid, rfile, length)
            if refusal:
                return refusal
        raw = rfile.read(4)
        if len(raw) < 4:
            return {"ok": False, "error": "bad index"}
        head_len = int.from_bytes(raw, "big")
        if head_len <= 0 or head_len > 4 * 1024 * 1024:
            drain(rfile, max(0, length - 4))
            return {"ok": False, "error": "bad index"}
        try:
            index = json.loads(rfile.read(head_len).decode())
        except Exception:
            drain(rfile, max(0, length - 4 - head_len))
            return {"ok": False, "error": "bad index"}
        expected = 4 + head_len + sum(int(e.get("size", 0)) for e in index)
        if expected != length:
            drain(rfile, max(0, length - 4 - head_len))
            return {"ok": False, "error": "size mismatch"}
        for e in index:
            size = int(e.get("size", 0))
            sha = e.get("sha", "")
            f = b.by_fid.get(e.get("fid"))
            if f is None or f.finished or (f.size >= 0 and size != f.size):
                drain(rfile, size)       # a resend we already have, or not ours
                continue
            digest = hashlib.sha256() if sha else None
            # Small files go straight to their final name (verified before anyone sees them
            # complete; a failure deletes the file) - the .part + rename pair would double the disk work.
            part = unique_path(received_dir(), f.name)
            got = 0
            try:
                with open(part, "wb") as out:
                    left = size
                    while left > 0:
                        data = rfile.read(min(PIECE, left))
                        if not data:
                            raise OSError("the sender stopped early")
                        out.write(data)
                        if digest is not None:
                            digest.update(data)
                        left -= len(data)
                        got += len(data)
                        b.progress(len(data))
            except Exception as ex:
                part.unlink(missing_ok=True)
                b.progress(-got)
                return {"ok": False, "error": "incomplete"}
            if digest is not None and sha.lower() != digest.hexdigest():
                part.unlink(missing_ok=True)
                b.progress(-got)
                return {"ok": False, "error": "checksum"}
            with cls.lock:
                if f.finished:
                    part.unlink(missing_ok=True)
                    b.progress(-got)
                    continue
                f.out_path = part
                b.credit(f, size)
                cls._finish(b, f, rename=False)
        BUS.transfer_changed.emit()
        return {"ok": True}

    @classmethod
    def control(cls, bid, action):
        with cls.lock:
            b = cls.batch
            if b is None or b.id != bid:
                return {"ok": False, "state": "unknown"}
            if action == "pause" and b.state == ACTIVE:
                b.state, b.paused_by, b.note, b.speed = PAUSED, "peer", f"Paused by {b.peer}", 0
            elif action == "resume" and b.state == PAUSED:
                b.state, b.paused_by, b.note = ACTIVE, "", ""
            elif action == "cancel":
                if Transfers.offer is b:
                    Transfers.offer = None
                    b.state, b.note = CANCELLED, f"{b.peer} withdrew the offer"
                else:
                    cls._stop(b, f"{b.peer} stopped the transfer")
            BUS.transfer_changed.emit()
            return {"ok": True}

    @classmethod
    def status(cls, bid):
        b = cls.batch
        if b is None or b.id != bid:
            return {"state": "cancelled"}
        state = {ACTIVE: "active", PAUSED: "paused", WAITING: "pending", DONE: "done"}.get(b.state, "cancelled")
        return {"state": state, "received": b.current.done}

    @classmethod
    def pause(cls):
        with cls.lock:
            b = cls.batch
            if b and b.state == ACTIVE:
                b.state, b.paused_by, b.note, b.speed = PAUSED, "me", "Paused", 0
                BUS.transfer_changed.emit()

    @classmethod
    def resume(cls):
        with cls.lock:
            b = cls.batch
            if b and b.state == PAUSED:
                b.state, b.paused_by, b.note = ACTIVE, "", ""
                BUS.transfer_changed.emit()

    @classmethod
    def cancel(cls):
        with cls.lock:
            b = cls.batch
            if b:
                cls._stop(b, "Stopped")
                BUS.transfer_changed.emit()

    @classmethod
    def _stop(cls, b, note):
        if b.finished:
            return
        b.state, b.note, b.speed = CANCELLED, note, 0
        cls._discard(b)

    @staticmethod
    def _discard(b):
        """Deletes the partial files of a stopped batch. A chunk still being written
        keeps its file open (Windows refuses the delete) - that writer calls this
        again once it has closed the handle, so the path is kept until it is gone."""
        for f in b.files:
            if not f.finished and f.out_path is not None:
                try:
                    f.out_path.unlink()
                    f.out_path = None
                except FileNotFoundError:
                    f.out_path = None
                except Exception:
                    pass


def drain(rfile, length):
    left = length
    while left > 0:
        data = rfile.read(min(65536, left))
        if not data:
            break
        left -= len(data)


class SpeedTest:
    """Raw network speed to the peer, no disk, no hashing - tells whether a slow
    transfer is the app or the Wi-Fi. A few seconds each way, several streams."""
    running = False
    result = ""

    @classmethod
    def start(cls, done):
        if cls.running or LINK.state != Link.CONNECTED:
            return
        cls.running = True
        cls.result = "Measuring upload..."
        threading.Thread(target=cls._run, args=(done,), daemon=True).start()

    @classmethod
    def _run(cls, done):
        try:
            up = cls._upload(3)
            cls.result = f"Upload {fmt_size(int(up))}/s - measuring download..."
            on_ui(done)
            down = cls._download(3)
            cls.result = f"Speed test: upload {fmt_size(int(up))}/s  ·  download {fmt_size(int(down))}/s  ·  3 streams"
        except Exception as e:
            cls.result = f"Speed test failed: {e}"
        cls.running = False
        on_ui(done)

    @staticmethod
    def _upload(streams):
        block = bytes(PIECE)
        total = [0]
        lock = threading.Lock()
        stop = time.time() + 4

        def one():
            conn = PeerConn(LINK.peer_address)
            try:
                while time.time() < stop and LINK.state == Link.CONNECTED:
                    n = 4 * 1024 * 1024
                    r = conn.post(f"/blast?n={n}", iter([block] * (n // PIECE)), n)
                    if not r.get("ok"):
                        break
                    with lock:
                        total[0] += n
            finally:
                conn.close()
        t0 = time.time()
        ts = [threading.Thread(target=one, daemon=True) for _ in range(streams)]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        return total[0] / max(0.001, time.time() - t0)

    @staticmethod
    def _download(streams):
        total = [0]
        lock = threading.Lock()
        stop = time.time() + 4

        def one():
            conn = PeerConn(LINK.peer_address)
            try:
                while time.time() < stop and LINK.state == Link.CONNECTED:
                    n = 4 * 1024 * 1024
                    length, reader, close = conn.get_stream(f"/blast?n={n}")
                    left = length
                    while left > 0:
                        data = reader(min(PIECE, left))
                        if not data:
                            raise OSError("closed")
                        left -= len(data)
                        with lock:
                            total[0] += len(data)
                    if close:
                        conn.close()
            finally:
                conn.close()
        t0 = time.time()
        ts = [threading.Thread(target=one, daemon=True) for _ in range(streams)]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        return total[0] / max(0.001, time.time() - t0)


# ------------------------------------------------------------------ the server

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"      # keep-alive: one connection carries a whole chunk stream
    disable_nagle_algorithm = True     # headers + body go out at once, no delayed-ACK stall per reply
    timeout = 90                       # an idle or half-dead connection is dropped after this
    rbufsize = 256 * 1024

    def log_message(self, *args):
        pass

    def _send(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _query(self):
        u = urllib.parse.urlparse(self.path)
        return u.path, {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}

    def _json_body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        if n > 1024 * 1024:
            drain(self.rfile, n)
            return {}
        try:
            return json.loads(self.rfile.read(n).decode())
        except Exception:
            return {}

    def _authorized(self):
        ok = LINK.is_peer(self.client_address[0], self.headers.get(TOKEN_HEADER, ""))
        if ok:
            LINK.touch()
        return ok

    def _refuse(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 0:
            drain(self.rfile, n)
        print(f"[server] unauthorized {self.command} {self.path} from {self.client_address[0]}")
        self._send({"ok": False, "error": "unauthorized"})

    def do_GET(self):
        path, q = self._query()
        if path == "/ping":
            self._send({"app": APP_ID, "version": VERSION, "name": CFG["device_name"], "port": PORT, "instance": INSTANCE})
        elif path == "/pair":
            self._send(LINK.on_pair_poll(q.get("id", "")))
        elif not self._authorized():
            self._refuse()
        elif path == "/offer":
            self._send({"state": Incoming.offer_state(q.get("id", ""))})
        elif path == "/status":
            self._send(Incoming.status(q.get("id", "")))
        elif path == "/blast":
            n = max(0, min(int(q.get("n", 0) or 0), 64 * 1024 * 1024))
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(n))
            self.end_headers()
            block = bytes(PIECE)
            left = n
            while left > 0:
                k = min(PIECE, left)
                self.wfile.write(block if k == PIECE else block[:k])
                left -= k
        else:
            self._send({"ok": False, "error": "unknown path"})

    def do_POST(self):
        path, q = self._query()
        ip = self.client_address[0]
        n = int(self.headers.get("Content-Length") or 0)
        if path == "/pair" and q.get("cancel") == "1":
            self._json_body()
            LINK.on_pair_cancel(q.get("id", ""))
            self._send({"ok": True})
        elif path == "/pair":
            body = self._json_body()
            self._send(LINK.on_pair_request(ip, int(body.get("port", DEFAULT_PORT)),
                                            str(body.get("name") or "Device")[:40], str(body.get("id", ""))[:32]))
        elif path == "/introduce":
            body = self._json_body()
            self._send(LINK.on_introduce(str(body.get("address", ""))[:64]))
        elif not self._authorized():
            self._refuse()
        elif path == "/hello":
            body = self._json_body()
            self._send(LINK.on_hello(str(body.get("name") or "")[:40]))
        elif path == "/bye":
            self._json_body()
            LINK.on_bye()
            self._send({"ok": True})
        elif path == "/offer":
            self._send(Incoming.offer(self._json_body(), ip))
        elif path == "/chunk":
            self._send(Incoming.chunk(q.get("id", ""), q.get("fid", ""), int(q.get("offset", -1)),
                                      int(q.get("len", n)), q.get("last") == "1", self.rfile, q.get("sha", "")))
        elif path == "/bundle":
            self._send(Incoming.bundle(q.get("id", ""), self.rfile, n))
        elif path == "/control":
            self._json_body()
            self._send(Incoming.control(q.get("id", ""), q.get("action", "")))
        elif path == "/blast":
            drain(self.rfile, n)
            self._send({"ok": True, "n": n})
        else:
            drain(self.rfile, n)
            self._send({"ok": False, "error": "unknown path"})


SERVER = None


def start_server():
    global SERVER
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler, bind_and_activate=False)
    server.daemon_threads = True
    server.allow_reuse_address = True
    try:
        server.socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 2 * 1024 * 1024)
    except OSError:
        pass
    server.server_bind()
    server.server_activate()
    threading.Thread(target=server.serve_forever, daemon=True, name="http").start()
    print(f"[server] listening on :{PORT}")
    SERVER = server

    def responder():
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                sock.ioctl(socket.SIO_UDP_CONNRESET, False)
            except (AttributeError, OSError):
                pass
            sock.bind(("", DISCOVERY_PORT))
            while not STOPPING:
                try:
                    data, addr = sock.recvfrom(256)
                    if data.strip() == DISCOVER_CALL:
                        sock.sendto(f"{DISCOVER_REPLY} {PORT} {INSTANCE} {CFG['device_name']}".encode(), addr)
                except Exception as e:
                    print(f"[discovery] hiccup: {e}")
                    time.sleep(0.2)
        except Exception as e:
            print(f"[discovery] stopped: {e}")
    threading.Thread(target=responder, daemon=True, name="udp").start()
    return server


# ------------------------------------------------------------------ Windows Firewall

def firewall_rule_present():
    """True when an inbound rule named LanDrop exists for this program (any profile)."""
    if os.name != "nt":
        return True
    try:
        out = subprocess.run(["netsh", "advfirewall", "firewall", "show", "rule", "name=LanDrop", "verbose"],
                             capture_output=True, text=True, timeout=8, creationflags=CREATE_NO_WINDOW).stdout
        return sys.executable.lower() in out.lower()
    except Exception:
        return False


def firewall_fix():
    """Adds the inbound rule (asks for admin once). Covers private AND public networks,
    so hotspots and new Wi-Fi networks (which Windows calls 'public') work too."""
    exe = sys.executable
    args = (f'/c netsh advfirewall firewall delete rule name="LanDrop" & '
            f'netsh advfirewall firewall add rule name="LanDrop" dir=in action=allow program="{exe}" enable=yes profile=any')
    try:
        ctypes.windll.shell32.ShellExecuteW(None, "runas", "cmd.exe", args, None, 0)
        return True
    except Exception as e:
        print(f"[firewall] {e}")
        return False


# ------------------------------------------------------------------ UI kit (glass)

BG_TOP, BG_MID, BG_BOT = QColor("#0B0E16"), QColor("#131A2B"), QColor("#0A0C12")
ACCENT = {"green": "#22C55E", "red": "#EF4444", "blue": "#5B8CFF", "cyan": "#22D3EE",
          "amber": "#F59E0B", "violet": "#A78BFA", "none": None}
FONT_FAMILY = "Segoe UI"
APP_STYLE = """
QScrollBar:vertical { background: transparent; width: 10px; margin: 4px 2px 4px 2px; }
QScrollBar::handle:vertical { background: rgba(255,255,255,0.26); border-radius: 5px; min-height: 36px; }
QScrollBar::handle:vertical:hover { background: rgba(255,255,255,0.42); }
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0px; }
QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical { background: transparent; }
QScrollBar:horizontal { background: transparent; height: 10px; margin: 2px 4px 2px 4px; }
QScrollBar::handle:horizontal { background: rgba(255,255,255,0.26); border-radius: 5px; min-width: 36px; }
QScrollBar::add-line:horizontal, QScrollBar::sub-line:horizontal { width: 0px; }
QScrollBar::add-page:horizontal, QScrollBar::sub-page:horizontal { background: transparent; }
QToolTip { background: #131A2B; color: white; border: 1px solid rgba(255,255,255,0.3); padding: 4px; }
"""


def load_font():
    global FONT_FAMILY
    if FONT_FILE.exists():
        fid = QFontDatabase.addApplicationFont(str(FONT_FILE))
        fams = QFontDatabase.applicationFontFamilies(fid) if fid >= 0 else []
        if fams:
            FONT_FAMILY = fams[0]


def font(size=14, weight=QFont.Weight.Bold):
    f = QFont(FONT_FAMILY, size)
    f.setWeight(weight)
    return f


class GlassButton(QPushButton):
    """The luminous pill: frosted gradient, light rim, accent wash, press-scale feel."""

    def __init__(self, text, accent="none", height=44, size=13, parent=None):
        super().__init__(text, parent)
        self.accent = ACCENT.get(accent)
        self.setMinimumHeight(height)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setFont(font(size, QFont.Weight.ExtraBold))
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        self.setStyleSheet("background: transparent; border: none; color: white;")
        self._hover = False

    def set_accent(self, accent):
        self.accent = ACCENT.get(accent)
        self.update()

    def enterEvent(self, e):
        self._hover = True
        self.update()

    def leaveEvent(self, e):
        self._hover = False
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        r = QRectF(self.rect()).adjusted(1.5, 1.5, -1.5, -1.5)
        rad = r.height() / 2
        if self.isDown():
            r = r.adjusted(2, 1, -2, -1)
            rad = r.height() / 2
        path = QPainterPath()
        path.addRoundedRect(r, rad, rad)
        sh = QPainterPath()
        sh.addRoundedRect(r.translated(0, 1.5), rad, rad)
        p.fillPath(sh, QColor(0, 0, 0, 22))
        g = QLinearGradient(r.topLeft(), r.bottomLeft())
        g.setColorAt(0, QColor(255, 255, 255, 70))
        g.setColorAt(0.5, QColor(255, 255, 255, 28))
        g.setColorAt(1, QColor(0, 0, 0, 33))
        p.fillPath(path, QBrush(g))
        if self.accent:
            c = QColor(self.accent)
            g2 = QLinearGradient(r.topLeft(), r.bottomLeft())
            c1, c2 = QColor(c), QColor(c)
            c1.setAlpha(76)
            c2.setAlpha(122)
            g2.setColorAt(0, c1)
            g2.setColorAt(1, c2)
            p.fillPath(path, QBrush(g2))
        if self._hover and self.isEnabled():
            p.fillPath(path, QColor(255, 255, 255, 18))
        rim = QLinearGradient(r.topLeft(), r.bottomLeft())
        a = 255 if self.hasFocus() else 153
        rim.setColorAt(0, QColor(255, 255, 255, a))
        rim.setColorAt(1, QColor(255, 255, 255, int(a * 0.45)))
        p.setPen(QPen(QBrush(rim), 1.6))
        p.drawPath(path)
        p.setPen(QColor(255, 255, 255, 255 if self.isEnabled() else 110))
        p.setFont(self.font())
        p.drawText(r, Qt.AlignmentFlag.AlignCenter, self.text())
        p.end()


class GlassCard(QFrame):
    def __init__(self, accent="none", radius=22, parent=None):
        super().__init__(parent)
        self.accent = ACCENT.get(accent)
        self.radius = radius
        self.setAttribute(Qt.WidgetAttribute.WA_StyledBackground, False)

    def set_accent(self, accent):
        self.accent = ACCENT.get(accent)
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        path = QPainterPath()
        path.addRoundedRect(r, self.radius, self.radius)
        g = QLinearGradient(r.topLeft(), r.bottomLeft())
        g.setColorAt(0, QColor(255, 255, 255, 46))
        g.setColorAt(1, QColor(255, 255, 255, 18))
        p.fillPath(path, QBrush(g))
        if self.accent:
            c = QColor(self.accent)
            g2 = QLinearGradient(r.topLeft(), r.bottomLeft())
            c1, c2 = QColor(c), QColor(c)
            c1.setAlpha(40)
            c2.setAlpha(14)
            g2.setColorAt(0, c1)
            g2.setColorAt(1, c2)
            p.fillPath(path, QBrush(g2))
        rim = QLinearGradient(r.topLeft(), r.bottomLeft())
        rim.setColorAt(0, QColor(255, 255, 255, 128))
        rim.setColorAt(1, QColor(255, 255, 255, 38))
        p.setPen(QPen(QBrush(rim), 1))
        p.drawPath(path)
        p.end()


class GlassProgress(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.fraction = 0.0
        self.accent = QColor(ACCENT["blue"])
        self.indeterminate = False
        self._sweep = 0.0
        self.setFixedHeight(16)
        self.timer = QTimer(self)
        self.timer.timeout.connect(self._tick)
        self.timer.start(30)

    def _tick(self):
        self._sweep = (self._sweep + 0.02) % 1.6
        if self.indeterminate or self.fraction > 0:
            self.update()

    def set(self, fraction, accent, indeterminate=False):
        self.fraction = max(0.0, min(1.0, fraction))
        self.accent = QColor(ACCENT.get(accent) or ACCENT["blue"])
        self.indeterminate = indeterminate
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        rad = r.height() / 2
        track = QPainterPath()
        track.addRoundedRect(r, rad, rad)
        p.fillPath(track, QColor(255, 255, 255, 36))
        p.setPen(QPen(QColor(255, 255, 255, 50), 1))
        p.drawPath(track)
        p.setClipPath(track)
        w = float(r.width())
        transparent = QColor(0, 0, 0, 0)
        if self.indeterminate:
            x = r.left() + w * (self._sweep - 0.3)
            g = QLinearGradient(x - w * 0.25, 0.0, x + w * 0.25, 0.0)
            c = QColor(self.accent)
            c.setAlpha(230)
            g.setColorAt(0.0, transparent)
            g.setColorAt(0.5, c)
            g.setColorAt(1.0, transparent)
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(QBrush(g))
            p.drawRect(r)
        elif self.fraction > 0:
            fw = max(w * self.fraction, r.height())
            fr = QRectF(r.left(), r.top(), fw, r.height())
            fill = QPainterPath()
            fill.addRoundedRect(fr, rad, rad)
            g = QLinearGradient(fr.left(), 0.0, fr.right(), 0.0)
            c1 = QColor(self.accent)
            c1.setAlpha(190)
            g.setColorAt(0.0, c1)
            g.setColorAt(1.0, QColor(self.accent))
            p.fillPath(fill, QBrush(g))
            x = fr.left() + fw * (self._sweep - 0.3)
            g2 = QLinearGradient(x - fw * 0.2, 0.0, x + fw * 0.2, 0.0)
            g2.setColorAt(0.0, transparent)
            g2.setColorAt(0.5, QColor(255, 255, 255, 90))
            g2.setColorAt(1.0, transparent)
            p.fillPath(fill, QBrush(g2))
        p.end()


class StatusDot(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.color = QColor("#8A93A6")
        self.pulsing = False
        self._a = 1.0
        self._dir = -1
        self.setFixedSize(26, 26)
        self.timer = QTimer(self)
        self.timer.timeout.connect(self._tick)
        self.timer.start(40)

    def _tick(self):
        if not self.pulsing:
            if self._a != 1.0:
                self._a = 1.0
                self.update()
            return
        self._a += 0.04 * self._dir
        if self._a <= 0.5 or self._a >= 1.0:
            self._dir *= -1
            self._a = max(0.5, min(1.0, self._a))
        self.update()

    def set(self, color, pulsing=False):
        self.color = QColor(color)
        self.pulsing = pulsing
        self.update()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        c = QColor(self.color)
        cx, cy = self.width() / 2, self.height() / 2
        glow = QRadialGradient(cx, cy, 13)
        g1 = QColor(c)
        g1.setAlpha(int(140 * self._a))
        glow.setColorAt(0, g1)
        glow.setColorAt(1, QColor(0, 0, 0, 0))
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QBrush(glow))
        p.drawEllipse(QRectF(0, 0, 26, 26))
        c.setAlpha(int(255 * self._a))
        p.setBrush(c)
        p.setPen(QPen(QColor(255, 255, 255, 90), 1.5))
        p.drawEllipse(QRectF(cx - 6, cy - 6, 12, 12))
        p.end()


class Toggle(QPushButton):
    def __init__(self, checked=False, parent=None):
        super().__init__(parent)
        self.setCheckable(True)
        self.setChecked(checked)
        self.setFixedSize(58, 32)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setStyleSheet("background: transparent; border: none;")

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        path = QPainterPath()
        path.addRoundedRect(r, 16, 16)
        on = self.isChecked()
        c = QColor(ACCENT["green"])
        c.setAlpha(190)
        p.fillPath(path, c if on else QColor(255, 255, 255, 50))
        p.setPen(QPen(QColor(255, 255, 255, 100), 1))
        p.drawPath(path)
        x = r.right() - 29 if on else r.left() + 3
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QColor("white"))
        p.drawEllipse(QRectF(x, r.top() + 3, 26, 26))
        p.end()


def label(text="", size=14, color="#FFFFFF", weight=QFont.Weight.Bold, wrap=True):
    lb = QLabel(text)
    lb.setFont(font(size, weight))
    lb.setStyleSheet(f"color: {color}; background: transparent;")
    lb.setWordWrap(wrap)
    return lb


def title(text="", size=22):
    return label(text, size, "#FFFFFF", QFont.Weight.ExtraBold)


def muted(text="", size=13):
    return label(text, size, "rgba(255,255,255,0.72)")


def faint(text="", size=12):
    return label(text, size, "rgba(255,255,255,0.45)")


class GlassField(QLineEdit):
    def __init__(self, placeholder="", parent=None):
        super().__init__(parent)
        self.setPlaceholderText(placeholder)
        self.setFont(font(15))
        self.setMinimumHeight(44)
        self.setStyleSheet(
            "QLineEdit { background: rgba(0,0,0,0.32); color: white; border: 1px solid rgba(255,255,255,0.35);"
            " border-radius: 14px; padding: 6px 14px; selection-background-color: #5B8CFF; }"
            "QLineEdit:focus { border: 1px solid rgba(255,255,255,0.8); }")


class Background(QWidget):
    """Dark navy gradient + soft radial accent glow (the house look)."""

    def paintEvent(self, e):
        p = QPainter(self)
        g = QLinearGradient(0, 0, 0, self.height())
        g.setColorAt(0, BG_TOP)
        g.setColorAt(0.55, BG_MID)
        g.setColorAt(1, BG_BOT)
        p.fillRect(self.rect(), QBrush(g))
        glow = QRadialGradient(self.width() * 0.5, self.height() * 0.1, self.width() * 0.8)
        c = QColor(ACCENT["blue"])
        c.setAlpha(56)
        glow.setColorAt(0, c)
        glow.setColorAt(1, QColor(0, 0, 0, 0))
        p.fillRect(self.rect(), QBrush(glow))
        p.end()


def vbox(parent=None, margin=0, spacing=12):
    lay = QVBoxLayout(parent) if parent else QVBoxLayout()
    lay.setContentsMargins(margin, margin, margin, margin)
    lay.setSpacing(spacing)
    return lay


def hbox(margin=0, spacing=12):
    lay = QHBoxLayout()
    lay.setContentsMargins(margin, margin, margin, margin)
    lay.setSpacing(spacing)
    return lay


def copy_text(text):
    QApplication.clipboard().setText(text)


def my_address():
    return f"{local_ip()}:{PORT}"


# ------------------------------------------------------------------ dialogs

class DarkDialog(QDialog):
    """Frameless, dim-safe dialog base with a solid dark rounded base."""

    def __init__(self, parent):
        super().__init__(parent)
        self.setWindowFlags(Qt.WindowType.FramelessWindowHint | Qt.WindowType.Dialog)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setModal(True)

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        path = QPainterPath()
        path.addRoundedRect(r, 24, 24)
        p.fillPath(path, QColor(19, 26, 43, 246))
        p.end()


class Popup(DarkDialog):
    """One centred glass card, two clear buttons."""

    def __init__(self, parent, heading, lines, ok_text, cancel_text, ok_accent="green", note=""):
        super().__init__(parent)
        root = vbox(self, 0, 0)
        card = GlassCard("blue", 24)
        card.setMinimumWidth(460)
        lay = vbox(card, 24, 8)
        lay.addWidget(title(heading, 20))
        for ln in lines:
            lay.addWidget(muted(ln, 13))
        if note:
            lay.addWidget(faint(note))
        lay.addSpacing(10)
        row = hbox()
        self.cancel_btn = GlassButton(cancel_text, "red", 46)
        self.ok_btn = GlassButton(ok_text, ok_accent, 46)
        self.cancel_btn.clicked.connect(self.reject)
        self.ok_btn.clicked.connect(self.accept)
        row.addWidget(self.cancel_btn)
        row.addWidget(self.ok_btn)
        lay.addLayout(row)
        root.addWidget(card)


class ChooseDialog(DarkDialog):
    """Several LanDrops answered - pick the one to connect to."""

    def __init__(self, parent, found):
        super().__init__(parent)
        self.chosen = None
        root = vbox(self, 0, 0)
        card = GlassCard("blue", 24)
        card.setMinimumWidth(460)
        lay = vbox(card, 24, 10)
        lay.addWidget(title("Which device?", 20))
        for address, name in found:
            b = GlassButton(f"{name}   ·   {address}", "green", 46, 13)
            b.clicked.connect(lambda _, a=address: self.pick(a))
            lay.addWidget(b)
        cancel = GlassButton("CANCEL", "red", 44)
        cancel.clicked.connect(self.reject)
        lay.addWidget(cancel)
        root.addWidget(card)
        BUS.link_changed.connect(self._link)

    def _link(self):
        if LINK.state in (Link.CONNECTED, Link.REQUESTING):
            self.reject()

    def pick(self, address):
        self.chosen = address
        self.accept()


class QrDialog(QDialog):
    """Shows this PC's address as a QR code; closes by itself once a device connects."""

    def __init__(self, parent, text):
        super().__init__(parent)
        self.setWindowTitle("Scan with the phone")
        self.setStyleSheet("background: #0B0E16;")
        lay = vbox(self, 24, 14)
        lay.addWidget(title("Scan this with the phone", 20))
        lay.addWidget(muted("LanDrop on the phone -> CONNECT -> SCAN A QR CODE", 13))
        view = QLabel()
        view.setAlignment(Qt.AlignmentFlag.AlignCenter)
        try:
            import qrcode
            img = qrcode.make(text).convert("RGB")
            img = img.resize((360, 360))
            data = img.tobytes("raw", "RGB")
            qimg = QImage(data, img.width, img.height, 3 * img.width, QImage.Format.Format_RGB888)
            view.setPixmap(QPixmap.fromImage(qimg))
        except Exception as e:
            view.setText(f"The QR library is missing ({e}).\nType the address instead.")
            view.setStyleSheet("color: white;")
        lay.addWidget(view)
        address = text.removeprefix(SCHEME)
        row = hbox()
        row.addWidget(title(address, 18), 1)
        copy = GlassButton("COPY", "blue", 38, 11)
        copy.setFixedWidth(90)
        copy.clicked.connect(lambda: (copy_text(address), copy.setText("COPIED")))
        row.addWidget(copy)
        lay.addLayout(row)
        lay.addWidget(muted("Or type that address into the other device. This closes by itself once connected.", 13))
        close = GlassButton("CLOSE", "none", 44)
        close.clicked.connect(self.accept)
        lay.addWidget(close)
        BUS.link_changed.connect(self._link)

    def _link(self):
        if LINK.state == Link.CONNECTED:
            self.accept()


# ------------------------------------------------------------------ pages

class TransferPage(QWidget):
    def __init__(self, win):
        super().__init__()
        self.win = win
        self.worker: Outgoing | None = None
        self._scanning = False
        lay = vbox(self, 0, 14)

        # connection card
        self.conn_card = GlassCard()
        cl = vbox(self.conn_card, 20, 6)
        top = hbox()
        self.dot = StatusDot()
        self.conn_title = title("Not connected", 20)
        self.conn_detail = muted("", 13)
        tcol = vbox(None, 0, 2)
        tcol.addWidget(self.conn_title)
        tcol.addWidget(self.conn_detail)
        top.addWidget(self.dot)
        top.addLayout(tcol, 1)
        cl.addLayout(top)
        cl.addSpacing(6)
        self.find_btn = GlassButton("FIND DEVICES", "blue", 44)
        self.qr_btn = GlassButton("SHOW QR CODE", "cyan", 44)
        self.disc_btn = GlassButton("DISCONNECT", "red", 44)
        self.speed_btn = GlassButton("SPEED TEST", "violet", 44)
        self.cancel_btn = GlassButton("CANCEL REQUEST", "red", 44)
        self.find_btn.clicked.connect(self.find_phone)
        self.qr_btn.clicked.connect(self.show_qr)
        self.disc_btn.clicked.connect(self.disconnect)
        self.speed_btn.clicked.connect(self.speed_test)
        self.cancel_btn.clicked.connect(LINK.cancel_request)
        self.addr = GlassField(f"device address, e.g. 192.168.1.30:{DEFAULT_PORT}")
        self.addr.setText(CFG.get("peer", ""))
        self.addr.returnPressed.connect(self.connect_typed)
        self.connect_btn = GlassButton("ASK TO CONNECT", "green", 44, 11)
        self.connect_btn.setFixedWidth(150)
        self.connect_btn.clicked.connect(self.connect_typed)
        row_auto = hbox()
        row_auto.addWidget(self.find_btn)
        row_auto.addWidget(self.qr_btn)
        row_manual = hbox()
        row_manual.addWidget(self.addr, 1)
        row_manual.addWidget(self.connect_btn)
        self.conn_widgets = QWidget()
        cw = vbox(self.conn_widgets, 0, 10)
        cw.addLayout(row_auto)
        cw.addLayout(row_manual)
        self.conn_widgets.setStyleSheet("background: transparent;")
        cl.addWidget(self.conn_widgets)
        row_live = hbox()
        row_live.addWidget(self.speed_btn)
        row_live.addWidget(self.disc_btn)
        self.live_widgets = QWidget()
        lw = vbox(self.live_widgets, 0, 0)
        lw.addLayout(row_live)
        self.live_widgets.setStyleSheet("background: transparent;")
        cl.addWidget(self.live_widgets)
        cl.addWidget(self.cancel_btn)
        # this PC's address, always visible, one click to copy
        me = hbox()
        self.me_label = muted("", 13)
        me.addWidget(self.me_label, 1)
        self.copy_btn = GlassButton("COPY ADDRESS", "none", 34, 10)
        self.copy_btn.setFixedWidth(130)
        self.copy_btn.clicked.connect(self.copy_address)
        me.addWidget(self.copy_btn)
        cl.addLayout(me)
        self.conn_status = muted("", 13)
        cl.addWidget(self.conn_status)
        lay.addWidget(self.conn_card)

        # send card
        send_card = GlassCard("cyan")
        sl = vbox(send_card, 20, 8)
        self.send_btn = GlassButton("SEND FILES", "cyan", 58, 17)
        self.send_btn.clicked.connect(self.pick_files)
        sl.addWidget(self.send_btn)
        self.send_hint = muted("Pick any files, any size - or drop them anywhere on this window.", 13)
        self.send_hint.setAlignment(Qt.AlignmentFlag.AlignCenter)
        sl.addWidget(self.send_hint)
        lay.addWidget(send_card)

        # transfer card
        self.tcard = GlassCard("blue")
        tl = vbox(self.tcard, 20, 8)
        trow = hbox()
        self.t_title = title("", 19)
        self.t_file = muted("", 13)
        tcol2 = vbox(None, 0, 2)
        tcol2.addWidget(self.t_title)
        tcol2.addWidget(self.t_file)
        trow.addLayout(tcol2, 1)
        tl.addLayout(trow)
        self.bar = GlassProgress()
        tl.addWidget(self.bar)
        nums = hbox()
        self.t_pct = title("0%", 17)
        self.t_bytes = muted("", 13)
        self.t_speed = faint("", 12)
        nums.addWidget(self.t_pct)
        nums.addStretch(1)
        nums.addWidget(self.t_speed)
        nums.addSpacing(16)
        nums.addWidget(self.t_bytes)
        tl.addLayout(nums)
        self.t_note = muted("", 13)
        tl.addWidget(self.t_note)
        brow = hbox()
        self.pause_btn = GlassButton("PAUSE", "amber", 44)
        self.stop_btn = GlassButton("STOP", "red", 44)
        self.ok_btn = GlassButton("OK", "green", 44)
        self.pause_btn.clicked.connect(self.pause_resume)
        self.stop_btn.clicked.connect(self.stop)
        self.ok_btn.clicked.connect(Transfers.dismiss)
        brow.addWidget(self.pause_btn)
        brow.addWidget(self.stop_btn)
        brow.addWidget(self.ok_btn)
        tl.addLayout(brow)
        lay.addWidget(self.tcard)
        self.tcard.hide()

        frow = hbox()
        self.folder_line = faint("")
        frow.addWidget(self.folder_line, 1)
        open_btn = GlassButton("OPEN FOLDER", "none", 34, 10)
        open_btn.setFixedWidth(120)
        open_btn.clicked.connect(lambda: open_path(received_dir()))
        frow.addWidget(open_btn)
        lay.addLayout(frow)
        lay.addStretch(1)

        self.refresh_link()
        self.refresh_transfer()
        self.stall_timer = QTimer(self)
        self.stall_timer.timeout.connect(self.refresh_transfer)
        self.stall_timer.start(1000)

    # ---- link ----
    def refresh_link(self):
        st = LINK.state
        connected = st == Link.CONNECTED
        requesting = st == Link.REQUESTING
        lost = st == Link.LOST
        self.dot.set({"connected": ACCENT["green"], "requesting": ACCENT["amber"], "lost": ACCENT["amber"]}.get(st, "#8A93A6"),
                     pulsing=requesting or lost)
        self.conn_card.set_accent("green" if connected else "amber" if (requesting or lost) else "none")
        self.conn_title.setText("Connected" if connected else "Asking..." if requesting else
                                "Reconnecting..." if lost else "Not connected")
        self.conn_detail.setText(f"{LINK.peer_name}  ·  {LINK.peer_address}" if connected else LINK.message)
        self.conn_widgets.setVisible(st == Link.OFF)
        self.live_widgets.setVisible(connected or lost)
        self.speed_btn.setVisible(connected)
        self.speed_btn.setEnabled(not Transfers.busy() and not SpeedTest.running)
        self.cancel_btn.setVisible(requesting)
        if connected or requesting:
            self.conn_status.setText(SpeedTest.result if (connected and SpeedTest.result) else "")
        if SERVER_ERROR and not self.conn_status.text():
            self.conn_status.setText(SERVER_ERROR)
        self.me_label.setText(f"This PC:  {my_address()}")
        self.find_btn.setEnabled(not self._scanning)
        self.find_btn.setText("SCANNING..." if self._scanning else "FIND DEVICES")
        self.connect_btn.setEnabled(True)
        self.send_btn.setEnabled(connected and not Transfers.busy())
        self.send_hint.setText(
            "Connect to a phone or another PC first - then pick any files, any size." if not connected else
            "A transfer is running - wait for it to finish." if Transfers.busy() else
            "Pick any files, any size - or drop them anywhere on this window.")
        self.folder_line.setText(f"Received files go to  {received_dir()}")

    def copy_address(self):
        copy_text(my_address())
        self.copy_btn.setText("COPIED")
        QTimer.singleShot(1500, lambda: self.copy_btn.setText("COPY ADDRESS"))

    def find_phone(self):
        self._scanning = True
        self.conn_status.setText("Scanning the network (broadcast + every address on this network)...")
        self.refresh_link()

        def go():
            found = LINK.discover_all(3.0)

            def done():
                self._scanning = False
                self.refresh_link()
                if not found:
                    self.conn_status.setText(
                        "No device answered. Open LanDrop on the other device (same Wi-Fi or LAN). If it still "
                        "cannot see this PC, press FIX FIREWALL in Settings - or type its address above.")
                else:
                    self.conn_status.setText(f"{len(found)} device{'s' if len(found) > 1 else ''} found")
                    self.choose_device(found)
            on_ui(done)
        threading.Thread(target=go, daemon=True).start()

    def choose_device(self, found):
        dlg = ChooseDialog(self.win, found)
        if dlg.exec() and dlg.chosen:
            self.addr.setText(dlg.chosen)
            self.ask(dlg.chosen)

    def connect_typed(self):
        a = self.addr.text().strip()
        if a:
            self.ask(a)

    def ask(self, address):
        """Send a pairing request; the other device must ALLOW it."""
        self.conn_status.setText("")

        def go():
            msg = LINK.request_pair(address) or ""
            on_ui(lambda: self.conn_status.setText(msg))
        threading.Thread(target=go, daemon=True).start()

    def show_qr(self):
        QrDialog(self.win, f"{SCHEME}{my_address()}").exec()

    def disconnect(self):
        LINK.disconnect()
        self.conn_status.setText("")

    def speed_test(self):
        SpeedTest.start(self.refresh_link)
        self.refresh_link()

    # ---- sending ----
    def pick_files(self):
        paths, _ = QFileDialog.getOpenFileNames(self.win, "Files to send", str(Path.home()))
        if paths:
            self.offer_to_send(paths)

    def offer_to_send(self, paths):
        if LINK.state != Link.CONNECTED:
            self.conn_status.setText("Connect to a device first.")
            return
        if Transfers.busy():
            return
        expanded = []
        for p in paths:
            p = Path(p)
            if p.is_dir():
                expanded.extend(x for x in sorted(p.rglob("*")) if x.is_file())
            elif p.is_file():
                expanded.append(p)
        paths = [str(p) for p in expanded]
        if not paths:
            return
        sizes = [Path(p).stat().st_size for p in paths]
        lines = [f"•  {Path(p).name}  ({fmt_size(s)})" for p, s in list(zip(paths, sizes))[:6]]
        if len(paths) > 6:
            lines.append(f"…and {len(paths) - 6} more")
        lines.append("")
        lines.append(f"{len(paths)} file{'s' if len(paths) > 1 else ''}  ·  {fmt_size(sum(sizes))}")
        if Popup(self.win, f"Send to {LINK.peer_name}?", lines, "SEND", "CANCEL").exec():
            self.worker = Outgoing(paths)
            self.worker.start()

    # ---- the running transfer ----
    def refresh_transfer(self):
        b = Transfers.current
        self.send_btn.setEnabled(LINK.state == Link.CONNECTED and not Transfers.busy())
        self.speed_btn.setEnabled(not Transfers.busy() and not SpeedTest.running)
        if b is None:
            self.tcard.hide()
            return
        self.tcard.show()
        accent = {DONE: "green", CANCELLED: "red", FAILED: "red", PAUSED: "amber"}.get(b.state, "blue")
        self.tcard.set_accent(accent)
        heading = {WAITING: f"Waiting for {b.peer}" if b.outgoing else "Incoming",
                   ACTIVE: f"Sending to {b.peer}" if b.outgoing else f"Receiving from {b.peer}",
                   PAUSED: "Paused", DONE: "Done", CANCELLED: "Stopped", FAILED: "Failed"}[b.state]
        self.t_title.setText(heading)
        prefix = f"File {b.index + 1} of {len(b.files)}  ·  " if len(b.files) > 1 else ""
        self.t_file.setText(prefix + b.current.name)
        self.bar.set(b.fraction, accent, indeterminate=b.state == WAITING)
        self.t_pct.setText(f"{int(b.fraction * 100)}%")
        self.t_bytes.setText(f"{fmt_size(b.done_bytes)} / {fmt_size(b.total)}")
        streams = f"   ·   {b.streams} streams" if b.outgoing and b.streams > 1 else ""
        self.t_speed.setText(f"{fmt_size(int(b.speed))}/s   {fmt_eta(b.eta)}{streams}" if b.state == ACTIVE and b.speed > 0 else "")
        stalled = b.state == ACTIVE and time.time() - b.last_activity > 6 and LINK.state == Link.CONNECTED
        self.t_note.setText((f"Waiting for {b.peer}..." if stalled and not b.note else b.note))
        self.t_note.setVisible(bool(self.t_note.text()))
        self.pause_btn.setVisible(b.state in (ACTIVE, PAUSED))
        self.pause_btn.setText("RESUME" if b.state == PAUSED else "PAUSE")
        self.pause_btn.set_accent("green" if b.state == PAUSED else "amber")
        self.stop_btn.setVisible(b.state in (WAITING, ACTIVE, PAUSED))
        self.stop_btn.setText("CANCEL" if b.state == WAITING else "STOP")
        self.ok_btn.setVisible(b.finished)

    def pause_resume(self):
        b = Transfers.current
        if b is None:
            return
        if b.outgoing and self.worker:
            (self.worker.resume if b.state == PAUSED else self.worker.pause)()
        elif not b.outgoing:
            (Incoming.resume if b.state == PAUSED else Incoming.pause)()

    def stop(self):
        b = Transfers.current
        if b is None:
            return
        if b.outgoing and self.worker:
            self.worker.cancel()
        elif not b.outgoing:
            Incoming.cancel()


def open_path(path):
    try:
        os.startfile(str(path))
    except Exception as e:
        print(f"[open] {e}")


def show_in_folder(path):
    p = Path(path)
    try:
        if p.exists():
            subprocess.Popen(["explorer", "/select,", str(p)])
        else:
            os.startfile(str(p.parent))
    except Exception as e:
        print(f"[open] {e}")


class HistoryPage(QWidget):
    def __init__(self, win):
        super().__init__()
        self.win = win
        lay = vbox(self, 0, 12)
        top = hbox()
        top.addWidget(title("History", 24), 1)
        clear = GlassButton("CLEAR LIST", "red", 40, 12)
        clear.setFixedWidth(130)
        clear.clicked.connect(self.clear_all)
        top.addWidget(clear)
        lay.addLayout(top)
        self.empty = muted("Nothing yet. Files you send or receive will be listed here.")
        lay.addWidget(self.empty)
        self.list = QListWidget()
        self.list.setFont(font(13))
        self.list.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        self.list.setStyleSheet(
            "QListWidget { background: rgba(255,255,255,0.06); color: white; border: 1px solid rgba(255,255,255,0.2);"
            " border-radius: 18px; padding: 8px; outline: none; }"
            "QListWidget::item { padding: 10px 12px; border-radius: 12px; }"
            "QListWidget::item:selected { background: rgba(91,140,255,0.35); }"
            "QListWidget::item:hover { background: rgba(255,255,255,0.08); }")
        self.list.setWordWrap(True)
        self.list.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.list.itemDoubleClicked.connect(lambda it: open_path(it.data(Qt.ItemDataRole.UserRole)["path"]))
        self.list.itemSelectionChanged.connect(self.refresh_buttons)
        lay.addWidget(self.list, 1)
        row = hbox()
        self.open_btn = GlassButton("OPEN", "blue", 40, 11)
        self.folder_btn = GlassButton("SHOW IN FOLDER", "none", 40, 11)
        self.delete_btn = GlassButton("DELETE FILES", "red", 40, 11)
        self.remove_btn = GlassButton("REMOVE FROM LIST", "none", 40, 11)
        self.open_btn.clicked.connect(self.open_selected)
        self.folder_btn.clicked.connect(self.show_selected)
        self.delete_btn.clicked.connect(self.delete_selected)
        self.remove_btn.clicked.connect(self.remove_selected)
        for b in (self.open_btn, self.folder_btn, self.delete_btn, self.remove_btn):
            row.addWidget(b)
        lay.addLayout(row)
        lay.addWidget(faint("Click to select (Ctrl / Shift for several). Double-click opens a file. "
                            "DELETE FILES removes the selected files from this PC."))
        self.refresh()

    def refresh(self):
        self.list.clear()
        with HISTORY_LOCK:
            items = list(HISTORY)
        self.empty.setVisible(not items)
        self.list.setVisible(bool(items))
        for h in items:
            way = "SENT to" if h["direction"] == "sent" else "RECEIVED from"
            when = time.strftime("%d %b, %H:%M", time.localtime(h.get("time", 0)))
            status = "" if h.get("status") == "done" else f"   [{h.get('status')}]"
            gone = "" if Path(h.get("path", "")).exists() else "   [file gone]"
            it = QListWidgetItem(f"{h['name']}   ·   {fmt_size(h.get('size'))}   ·   {way} {h['peer']}   ·   {when}{status}{gone}")
            it.setData(Qt.ItemDataRole.UserRole, h)
            self.list.addItem(it)
        self.refresh_buttons()

    def selected(self):
        return [it.data(Qt.ItemDataRole.UserRole) for it in self.list.selectedItems()]

    def refresh_buttons(self):
        n = len(self.selected())
        self.open_btn.setEnabled(n >= 1)
        self.folder_btn.setEnabled(n >= 1)
        self.delete_btn.setEnabled(n >= 1)
        self.remove_btn.setEnabled(n >= 1)
        self.delete_btn.setText(f"DELETE {n} FILES" if n > 1 else "DELETE FILE" if n == 1 else "DELETE FILES")

    def open_selected(self):
        for h in self.selected()[:10]:
            if Path(h.get("path", "")).exists():
                open_path(h["path"])

    def show_selected(self):
        seen = set()
        for h in self.selected()[:5]:
            if h.get("path") and h["path"] not in seen:
                seen.add(h["path"])
                show_in_folder(h["path"])

    def delete_selected(self):
        sel = [h for h in self.selected() if Path(h.get("path", "")).exists()]
        if not sel:
            history_remove({h.get("id") for h in self.selected()})
            return
        lines = [f"•  {h['name']}" for h in sel[:6]]
        if len(sel) > 6:
            lines.append(f"…and {len(sel) - 6} more")
        lines.append("")
        lines.append(f"{len(sel)} file{'s' if len(sel) > 1 else ''} will be deleted from this PC. This cannot be undone.")
        if any(h["direction"] == "sent" for h in sel):
            lines.append("Sent files are your originals - they will be deleted too.")
        if Popup(self.win, "Delete these files?", lines, "DELETE", "KEEP", ok_accent="red").exec():
            failed = 0
            for h in sel:
                try:
                    Path(h["path"]).unlink()
                except Exception as e:
                    failed += 1
                    print(f"[history] delete {h['path']}: {e}")
            history_remove({h.get("id") for h in self.selected()})
            if failed:
                self.empty.setText(f"{failed} file(s) could not be deleted (in use?)")

    def remove_selected(self):
        history_remove({h.get("id") for h in self.selected()})

    def clear_all(self):
        with HISTORY_LOCK:
            n = len(HISTORY)
        if n and Popup(self.win, "Clear the history?", ["Only the list is cleared - no file is deleted."],
                       "CLEAR", "KEEP", ok_accent="red").exec():
            history_clear()


class SettingsPage(QWidget):
    def __init__(self, win):
        super().__init__()
        self.win = win
        lay = vbox(self, 0, 12)
        lay.addWidget(title("Settings", 24))

        card = GlassCard()
        cl = vbox(card, 18, 6)
        cl.addWidget(muted("This PC's name (what other devices see)"))
        self.name = GlassField("PC")
        self.name.setText(CFG["device_name"])
        self.name.textChanged.connect(self.name_changed)
        cl.addWidget(self.name)
        lay.addWidget(card)

        fw = GlassCard()
        fl = vbox(fw, 16, 6)
        fl.addWidget(title("Windows Firewall", 15))
        self.fw_status = muted("Checking...", 12)
        fl.addWidget(self.fw_status)
        frow = hbox()
        self.fw_btn = GlassButton("FIX FIREWALL (asks for admin once)", "blue", 42, 12)
        self.fw_btn.clicked.connect(self.fix_firewall)
        frow.addWidget(self.fw_btn)
        fl.addLayout(frow)
        lay.addWidget(fw)
        QTimer.singleShot(300, self.check_firewall)

        lay.addWidget(self.choice_row("Transfer mode",
                                      "Auto: 3 parallel streams, chunk size by file size. Fast: 4 streams, 8 MB chunks for a strong "
                                      "network. Reliable: 1 stream, 1 MB chunks for a flaky Wi-Fi. Every chunk is verified.",
                                      "transfer_mode", [("auto", "AUTO"), ("fast", "FAST"), ("reliable", "RELIABLE")]))
        lay.addWidget(self.switch_row("Verify every chunk", "SHA-256 checksum on arrival - a damaged chunk is sent again",
                                      "verify"))

        fcard = GlassCard()
        fl2 = vbox(fcard, 18, 6)
        fl2.addWidget(muted("Received files folder"))
        self.folder = title(CFG["received_folder"], 15)
        fl2.addWidget(self.folder)
        frow2 = hbox()
        open_btn = GlassButton("OPEN FOLDER", "blue", 42)
        change_btn = GlassButton("CHANGE...", "none", 42)
        open_btn.clicked.connect(lambda: open_path(received_dir()))
        change_btn.clicked.connect(self.change_folder)
        frow2.addWidget(open_btn)
        frow2.addWidget(change_btn)
        fl2.addLayout(frow2)
        lay.addWidget(fcard)

        note = GlassCard()
        nl = vbox(note, 16, 4)
        nl.addWidget(title("Always with your consent", 15))
        nl.addWidget(muted("A device connects only after you ALLOW it, and every incoming transfer asks you first. "
                           "A connection that drops (Wi-Fi blip) comes back by itself; DISCONNECT ends it for good.", 12))
        lay.addWidget(note)

        ips = ", ".join(f"{ip}:{PORT}" for ip in local_ips())
        lay.addWidget(faint(f"LanDrop v{APP_VERSION}  ·  this PC: {ips}"))
        lay.addStretch(1)

    def check_firewall(self):
        def go():
            ok = firewall_rule_present()
            on_ui(lambda: self.fw_status.setText(
                "A rule for LanDrop exists - other devices can reach this PC on any network." if ok else
                "No rule for LanDrop yet. If phones cannot find or reach this PC (especially on a hotspot or a new "
                "Wi-Fi, which Windows treats as 'public'), press FIX FIREWALL."))
            on_ui(lambda: self.fw_btn.setText("FIREWALL RULE AGAIN (asks for admin)" if ok else "FIX FIREWALL (asks for admin once)"))
        threading.Thread(target=go, daemon=True).start()

    def fix_firewall(self):
        firewall_fix()
        self.fw_status.setText("Accept the Windows prompt... checking again in a moment.")
        QTimer.singleShot(4000, self.check_firewall)

    def switch_row(self, heading, detail, key, after=None):
        card = GlassCard()
        row = hbox(16, 12)
        card.setLayout(row)
        col = vbox(None, 0, 2)
        col.addWidget(title(heading, 15))
        col.addWidget(muted(detail, 12))
        row.addLayout(col, 1)
        tg = Toggle(bool(CFG.get(key)))

        def flip(on):
            CFG[key] = bool(on)
            save_settings()
            if after:
                after(on)
        tg.toggled.connect(flip)
        row.addWidget(tg)
        return card

    def choice_row(self, heading, detail, key, options):
        card = GlassCard()
        col = vbox(card, 16, 6)
        col.addWidget(title(heading, 15))
        col.addWidget(muted(detail, 12))
        row = hbox(0, 8)
        buttons = []

        def paint():
            for k, b in buttons:
                b.accent = ACCENT["green"] if CFG.get(key) == k else None
                b.update()

        for k, text in options:
            b = GlassButton(text, "none", 36, 11)

            def pick(_, k=k):
                CFG[key] = k
                save_settings()
                paint()
            b.clicked.connect(pick)
            buttons.append((k, b))
            row.addWidget(b)
        paint()
        col.addLayout(row)
        return card

    def name_changed(self, text):
        CFG["device_name"] = text.strip() or (socket.gethostname() or "PC")
        save_settings()

    def change_folder(self):
        folder = QFileDialog.getExistingDirectory(self.win, "Where received files go", CFG["received_folder"])
        if folder:
            CFG["received_folder"] = folder
            save_settings()
            self.folder.setText(folder)


# ------------------------------------------------------------------ window

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("LanDrop")
        self.resize(760, 860)
        self.setMinimumSize(560, 640)
        self.setAcceptDrops(True)
        self.pair_popup = None
        self.offer_popup = None

        bg = Background()
        root = vbox(bg, 26, 16)
        head = hbox()
        head.addWidget(title("LanDrop", 32))
        head.addStretch(1)
        self.tabs = []
        for i, name in enumerate(("TRANSFER", "HISTORY", "SETTINGS")):
            b = GlassButton(name, "none", 38, 11)
            b.setFixedWidth(118)
            b.clicked.connect(lambda _, i=i: self.show_page(i))
            head.addWidget(b)
            self.tabs.append(b)
        root.addLayout(head)

        self.pages = QStackedWidget()
        self.pages.setStyleSheet("background: transparent;")
        self.transfer = TransferPage(self)
        self.history = HistoryPage(self)
        self.settings = SettingsPage(self)
        for page in (self.transfer, self.history, self.settings):
            scroll = QScrollArea()
            scroll.setWidget(page)
            scroll.setWidgetResizable(True)
            scroll.setFrameShape(QFrame.Shape.NoFrame)
            scroll.setStyleSheet("QScrollArea { background: transparent; }")
            scroll.viewport().setStyleSheet("background: transparent;")
            page.setStyleSheet("background: transparent;")
            self.pages.addWidget(scroll)
        root.addWidget(self.pages, 1)
        self.setCentralWidget(bg)
        self.show_page(0)

        # UI refreshes are coalesced: a batch of 1000 small files fires thousands of
        # signals, and rebuilding the history list for each one would stall the sender.
        self.ui_timer = QTimer(self)
        self.ui_timer.setSingleShot(True)
        self.ui_timer.setInterval(80)
        self.ui_timer.timeout.connect(self._refresh_ui)
        self.hist_timer = QTimer(self)
        self.hist_timer.setSingleShot(True)
        self.hist_timer.setInterval(400)
        self.hist_timer.timeout.connect(self.history.refresh)
        BUS.link_changed.connect(self._schedule_ui)
        BUS.transfer_changed.connect(self._schedule_ui)
        BUS.offer_arrived.connect(self.on_offer)
        BUS.pair_request.connect(self.on_pair_request)
        BUS.history_changed.connect(lambda: self.hist_timer.start())

    def _schedule_ui(self):
        if not self.ui_timer.isActive():
            self.ui_timer.start()

    def _refresh_ui(self):
        self.transfer.refresh_link()
        self.transfer.refresh_transfer()
        # The other side withdrew its request / offer: take the popup away too.
        if self.pair_popup is not None and LINK.incoming is None:
            self.pair_popup.reject()
        if self.offer_popup is not None and Transfers.offer is None:
            self.offer_popup.reject()

    def show_page(self, i):
        self.pages.setCurrentIndex(i)
        for j, b in enumerate(self.tabs):
            b.accent = ACCENT["blue"] if i == j else None
            b.update()

    def on_offer(self):
        b = Transfers.offer
        if b is None:
            return
        lines = [f"•  {f.name}  ({fmt_size(f.size)})" for f in b.files[:6]]
        if len(b.files) > 6:
            lines.append(f"…and {len(b.files) - 6} more")
        lines.append("")
        lines.append(f"{len(b.files)} file{'s' if len(b.files) > 1 else ''}  ·  {fmt_size(b.total)}")
        popup = Popup(self, f"{b.peer} wants to send you", lines, "ACCEPT", "DECLINE",
                      note=f"Saved in {received_dir()}")
        self.offer_popup = popup
        self.activateWindow()
        ok = popup.exec()
        self.offer_popup = None
        if ok and Transfers.offer is b:
            Incoming.accept()
        else:
            Incoming.decline()

    def on_pair_request(self):
        r = LINK.incoming
        if r is None:
            return
        popup = Popup(self, f"{r.name} wants to connect",
                      [f"From {r.ip}:{r.port}.", "",
                       "Allow only a device you recognise. Once connected it can offer you files -",
                       "you still accept or decline every transfer."],
                      "ALLOW", "DENY")
        self.pair_popup = popup
        self.activateWindow()
        ok = popup.exec()
        self.pair_popup = None
        if ok and LINK.incoming is r:
            LINK.allow()
        else:
            LINK.deny()

    # drag & drop anywhere
    def dragEnterEvent(self, e):
        if e.mimeData().hasUrls():
            e.acceptProposedAction()

    def dropEvent(self, e):
        paths = [u.toLocalFile() for u in e.mimeData().urls() if u.isLocalFile()]
        if paths:
            self.show_page(0)
            self.transfer.offer_to_send(paths)

    def closeEvent(self, e):
        """Closing the window ends everything: the transfer, the link, the server -
        nothing keeps running in the background."""
        shutdown()
        e.accept()


def shutdown():
    global STOPPING
    if STOPPING:
        return
    STOPPING = True
    try:
        b = Transfers.current
        if b is not None and not b.finished:
            b.state = CANCELLED
            if not b.outgoing:
                Incoming._discard(b)
        if LINK.state in (Link.CONNECTED, Link.LOST) and LINK.peer_address:
            try:
                post_json(f"http://{LINK.peer_address}/bye", {}, timeout=1.5, headers=LINK.auth())
            except Exception:
                pass
        if SERVER is not None:
            threading.Thread(target=SERVER.shutdown, daemon=True).start()
    except Exception as ex:
        print(f"[exit] {ex}")
    # Whatever a worker thread is still doing, the process ends now.
    threading.Thread(target=lambda: (time.sleep(1.0), os._exit(0)), daemon=True).start()


SERVER_ERROR = ""


def main():
    global SERVER_ERROR
    app = QApplication(sys.argv)
    load_font()
    app.setFont(font(13))
    app.setStyleSheet(APP_STYLE)
    try:
        start_server()
    except OSError as e:
        print(f"[server] cannot listen on {PORT}: {e}")
        SERVER_ERROR = (f"Port {PORT} is already in use on this PC (another LanDrop running?). "
                        "Close it and start LanDrop again - other devices cannot reach this one until then.")
    LINK.start()
    win = MainWindow()
    win.show()
    code = app.exec()
    shutdown()
    sys.exit(code)


if __name__ == "__main__":
    main()
