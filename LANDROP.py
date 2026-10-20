"""
LANDROP - PC side
=================

Sends files to, and receives files from, the LanDrop Android app over
the local network. Run it with:

    python LANDROP.py                # normal
    python LANDROP.py --port 53565   # another port (testing / two PCs)

* FIND DEVICES lists every LanDrop on the network; pick one and it asks
  that device, which must ALLOW the connection (and you get the same
  ALLOW / DENY when a device asks you). Typed address and QR work the same.
* SEND FILES (or drop files onto the window) asks once, then sends with
  progress, speed, pause and stop. The phone asks you before it sends.
* Everything received lands in the Received folder next to this file
  (changeable in Settings). History lists every file both ways.

Needs Python 3.10+ with PyQt6 (and `qrcode` for the QR code).
Windows Firewall must allow Python on private networks the first time.
"""

import hashlib
import http.client
import json
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from PyQt6.QtCore import QObject, QPoint, QRectF, QSize, Qt, QThread, QTimer, pyqtSignal
from PyQt6.QtGui import (QBrush, QColor, QFont, QFontDatabase, QImage, QLinearGradient,
                         QPainter, QPainterPath, QPen, QPixmap, QRadialGradient)
from PyQt6.QtWidgets import (QApplication, QDialog, QFileDialog, QFrame, QHBoxLayout,
                             QLabel, QLineEdit, QListWidget, QListWidgetItem, QMainWindow,
                             QPushButton, QScrollArea, QSizePolicy, QStackedWidget,
                             QVBoxLayout, QWidget)

# As a PyInstaller exe the script unpacks to a temp folder: settings, history and
# the Received folder must still live NEXT TO THE EXE, bundled data in _MEIPASS.
FROZEN = bool(getattr(sys, "frozen", False))
APP_DIR = Path(sys.executable).resolve().parent if FROZEN else Path(__file__).resolve().parent
RES_DIR = Path(getattr(sys, "_MEIPASS", str(APP_DIR)))
SETTINGS_FILE = APP_DIR / "landrop_settings.json"
HISTORY_FILE = APP_DIR / "landrop_history.json"
FONT_FILE = next((p for p in (
    RES_DIR / "nunito.ttf",
    APP_DIR / "nunito.ttf",
    APP_DIR / "android" / "app" / "src" / "main" / "res" / "font" / "nunito.ttf",
) if p.exists()), RES_DIR / "nunito.ttf")

APP_ID = "landrop"
VERSION = 1
APP_VERSION = "0.2"
DEFAULT_PORT = 53555
DISCOVERY_PORT = 53556
DISCOVER_CALL = b"LANDROP_DISCOVER"
DISCOVER_REPLY = "LANDROP_HERE"
CHUNK = 4 * 1024 * 1024
BUNDLE_FILE_MAX = 1024 * 1024        # files up to this size may travel bundled
BUNDLE_MAX = 4 * 1024 * 1024         # bytes of file data per bundle
BUNDLE_FILES_MAX = 400
SCHEME = "landrop://"
INSTANCE = uuid.uuid4().hex[:8]     # so this PC ignores its own discovery echo

PORT = DEFAULT_PORT
for i, a in enumerate(sys.argv):
    if a == "--port" and i + 1 < len(sys.argv):
        PORT = int(sys.argv[i + 1])


# ------------------------------------------------------------------ settings / history

def load_settings():
    data = {
        "device_name": socket.gethostname() or "PC",
        "peer": "",
        "received_folder": str(APP_DIR / "Received"),
        "transfer_mode": "auto",      # auto | fast (16 MB chunks) | reliable (1 MB chunks)
        "verify": True,               # SHA-256 check of every file on arrival
    }
    try:
        data.update(json.loads(SETTINGS_FILE.read_text(encoding="utf-8")))
    except Exception:
        pass
    return data


CFG = load_settings()


def save_settings():
    try:
        SETTINGS_FILE.write_text(json.dumps(CFG, indent=2), encoding="utf-8")
    except Exception as e:
        print(f"[settings] could not save: {e}")


def chunk_for(size):
    """Bytes per request for a file of `size` bytes, from the transfer mode."""
    mb = 1024 * 1024
    mode = CFG.get("transfer_mode", "auto")
    if mode == "fast":
        return 16 * mb
    if mode == "reliable":
        return 1 * mb
    if size >= 256 * mb:
        return 16 * mb
    if size >= 32 * mb:
        return 8 * mb
    if size >= 4 * mb:
        return 4 * mb
    return 1 * mb


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
        HISTORY.insert(0, {"direction": direction, "name": name, "size": size, "peer": peer,
                           "time": time.time(), "status": status, "path": str(path)})
        del HISTORY[500:]
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
    """One kept-alive HTTP connection to the peer for the chunk stream
    (no TCP handshake per chunk). Reconnects by itself after an error."""

    def __init__(self, address, timeout=120):
        self.host, port = address.rsplit(":", 1)
        self.port = int(port)
        self.timeout = timeout
        self.conn = None
        self.headers = dict(LINK.auth())

    def post(self, path, data):
        if self.conn is None:
            self.conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
            self.conn.connect()
            try:
                self.conn.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                self.conn.sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 1024 * 1024)
            except OSError:
                pass
        try:
            self.conn.request("POST", path, body=data,
                              headers={"Content-Type": "application/octet-stream",
                                       "Content-Length": str(len(data)), **self.headers})
            r = self.conn.getresponse()
            body = r.read()
            if r.getheader("Connection", "").lower() == "close":
                self.close()
            return json.loads(body.decode())
        except Exception:
            self.close()
            raise

    def close(self):
        try:
            if self.conn is not None:
                self.conn.close()
        except Exception:
            pass
        self.conn = None


def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


# ------------------------------------------------------------------ the link (connection)

class Link:
    """Pairing and the live link. Nothing connects without consent on BOTH ends:
    one side asks, the other sees ALLOW / DENY, and only then a pairing token is
    shared that every later request must carry."""
    OFF, REQUESTING, CONNECTED = "off", "requesting", "connected"

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

    def auth(self):
        return {TOKEN_HEADER: self.token}

    def start(self):
        threading.Thread(target=self._loop, daemon=True, name="link").start()

    def _loop(self):
        while True:
            try:
                if self.state == Link.CONNECTED:
                    self._heartbeat()
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
        if self.ping(self.peer_address):
            self.misses = 0
        else:
            self.misses += 1
            if self.misses >= 3:
                name = self.peer_name
                self._drop(f"Lost {name} - connect again when it is back")

    @staticmethod
    def ping(address):
        try:
            info = http_json(f"http://{address}/ping", timeout=2.5)
            return info if info.get("app") == APP_ID else None
        except Exception:
            return None

    @staticmethod
    def discover_all(timeout=2.0):
        """One broadcast; every LanDrop that answers in time (not ourselves): [(address, name)]."""
        found = {}
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            try:
                sock.ioctl(socket.SIO_UDP_CONNRESET, False)   # Windows UDP trap
            except (AttributeError, OSError):
                pass
            sock.settimeout(0.4)
            sock.sendto(DISCOVER_CALL, ("255.255.255.255", DISCOVERY_PORT))
            deadline = time.time() + timeout
            while time.time() < deadline:
                try:
                    data, addr = sock.recvfrom(512)
                except socket.timeout:
                    continue
                except OSError:
                    continue
                # "LANDROP_HERE <port> <instance> <name words...>"
                parts = data.decode(errors="replace").strip().split(" ", 3)
                if len(parts) >= 3 and parts[0] == DISCOVER_REPLY and parts[2] != INSTANCE:
                    address = f"{addr[0]}:{parts[1]}"
                    found[address] = (address, parts[3] if len(parts) > 3 and parts[3] else "Device")
            sock.close()
        except Exception as e:
            print(f"[link] discovery: {e}")
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
        if self.state == Link.CONNECTED:
            return "Disconnect first."
        if self.state == Link.REQUESTING:
            return "Already asking another device."
        self.cancel_outgoing = False
        self._set(Link.REQUESTING, message=f"Asking {a}...")
        try:
            info = self.ping(a)
            if not info:
                return self._fail(f"Nothing answers at {a}.\nIs LanDrop open on that device, on the same Wi-Fi?")
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
        """True only for the paired device: same IP as the peer AND the shared token."""
        return (self.state == Link.CONNECTED and bool(self.token) and token_header == self.token
                and self.peer_address.rpartition(":")[0] == ip)

    def _established(self, address, name, token):
        self.misses = 0
        self.token = token
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
        self.done = 0
        self.out_path = None
        self.finished = False
        self.digest = None      # running SHA-256 while sending / writing


class Batch:
    def __init__(self, bid, files, outgoing, peer):
        self.id, self.files, self.outgoing, self.peer = bid, files, outgoing, peer
        self.state = WAITING
        self.index = 0
        self.done_bytes = 0
        self.speed = 0
        self.note = ""
        self.paused_by = ""
        self.total = sum(max(f.size, 0) for f in files)
        self.last_activity = time.time()
        self._t = 0
        self._b = 0

    @property
    def finished(self):
        return self.state in (DONE, CANCELLED, FAILED)

    @property
    def fraction(self):
        return 0.0 if self.total <= 0 else min(1.0, self.done_bytes / self.total)

    @property
    def current(self):
        return self.files[min(self.index, len(self.files) - 1)]

    @property
    def eta(self):
        return None if self.speed <= 0 or self.total <= 0 else (self.total - self.done_bytes) / self.speed

    def tick_speed(self):
        now = time.time()
        self.last_activity = now
        if self._t == 0:
            self._t, self._b = now, self.done_bytes
            return
        if now - self._t >= 1:
            self.speed = (self.done_bytes - self._b) / (now - self._t)
            self._t, self._b = now, self.done_bytes


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


class Outgoing(QThread):
    """Sends one batch; pause / resume / cancel flip the batch state."""

    def __init__(self, paths):
        super().__init__()
        files = []
        for i, p in enumerate(paths):
            p = Path(p)
            try:
                size = p.stat().st_size
            except Exception:
                size = -1
            files.append(FileItem(f"f{i}_{uuid.uuid4().hex[:8]}", p.name, size, p))
        self.b = Batch(uuid.uuid4().hex[:12], files, True, LINK.peer_name)

    def run(self):
        b = self.b
        Transfers.current = b
        b.note = f"Waiting for {b.peer} to accept..."
        BUS.transfer_changed.emit()
        offer = {"id": b.id, "from": CFG["device_name"],
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
                return self.fail(f"{b.peer} declined", CANCELLED)
            if st == "unknown":
                return self.fail(f"{b.peer} forgot the transfer")
            if time.time() > deadline:
                return self.fail(f"No answer from {b.peer}")
            time.sleep(0.5)
        b.state = ACTIVE
        b.note = ""
        BUS.transfer_changed.emit()
        self.conn = PeerConn(LINK.peer_address)
        verify = bool(CFG.get("verify", True))

        i = 0
        while i < len(b.files):
            f = b.files[i]
            b.index = i
            # A run of small files goes as ONE request (a folder of icons would
            # otherwise cost a round trip each).
            if 0 <= f.size <= BUNDLE_FILE_MAX:
                j, total = i, 0
                while (j < len(b.files) and 0 <= b.files[j].size <= BUNDLE_FILE_MAX
                       and total + b.files[j].size <= BUNDLE_MAX and j - i < BUNDLE_FILES_MAX):
                    total += b.files[j].size
                    j += 1
                if j - i >= 2:
                    if not self.send_bundle(b.files[i:j], verify):
                        return
                    i = j
                    continue
            chunk = chunk_for(f.size)
            f.digest = hashlib.sha256() if verify else None
            try:
                fh = open(f.path, "rb")
            except Exception as e:
                return self.fail(f"Could not open {f.name}: {e}")
            with fh:
                offset = 0
                eof = False
                while not eof:
                    if not self.gate():
                        return
                    data = fh.read(chunk)
                    n = len(data)
                    eof = n < chunk
                    last = eof or (f.size >= 0 and offset + n >= f.size)
                    if last:
                        eof = True
                    if f.digest is not None:
                        f.digest.update(data)
                    sha = f.digest.hexdigest() if (last and f.digest is not None) else ""
                    sent = self.deliver(f, data, offset, last, sha)
                    if sent is None:
                        return
                    offset = sent
                    f.done = sent
                    b.done_bytes = sum(x.done for x in b.files[:i]) + sent
                    b.tick_speed()
                    BUS.transfer_changed.emit()
            f.finished = True
            history_add("sent", f.name, max(f.size, f.done), b.peer, "done", f.path)
            i += 1
        self.conn.close()
        b.state = DONE
        b.note = f"All {len(b.files)} file(s) delivered to {b.peer}"
        b.speed = 0
        BUS.transfer_changed.emit()

    def send_bundle(self, group, verify):
        """Sends `group` as one POST /bundle: u32 index length, JSON index [{fid,size,sha}],
        then the files back to back. False when the transfer ended."""
        b = self.b
        index, blobs = [], []
        for f in group:
            try:
                data = Path(f.path).read_bytes()
            except Exception as e:
                self.fail(f"Could not open {f.name}: {e}")
                return False
            index.append({"fid": f.fid, "size": len(data),
                          "sha": hashlib.sha256(data).hexdigest() if verify else ""})
            blobs.append(data)
        head = json.dumps(index).encode()
        body = len(head).to_bytes(4, "big") + head + b"".join(blobs)
        while True:
            if not self.gate():
                return False
            try:
                if self.conn.host + ":" + str(self.conn.port) != LINK.peer_address:
                    self.conn.close()
                    self.conn = PeerConn(LINK.peer_address)
                r = self.conn.post(f"/bundle?id={b.id}", body)
            except Exception as e:
                if b.state == ACTIVE:
                    b.state, b.paused_by, b.note = PAUSED, "link", "Connection lost - resumes when the link is back"
                    BUS.transfer_changed.emit()
                    print(f"[send] bundle failed, waiting: {e}")
                time.sleep(1)
                continue
            if r.get("ok"):
                break
            st = r.get("state")
            if st == "paused":
                if b.state == ACTIVE:
                    b.state, b.paused_by, b.note = PAUSED, "peer", f"Paused by {b.peer}"
                    BUS.transfer_changed.emit()
                continue
            if st in ("cancelled", "unknown"):
                b.state, b.note, b.speed = CANCELLED, f"{b.peer} stopped the transfer", 0
                BUS.transfer_changed.emit()
                return False
            self.fail("A file arrived damaged - try again" if r.get("error") == "checksum"
                      else f"{b.peer} refused a bundle: {r.get('error')}")
            return False
        for f in group:
            f.done = f.size
            f.finished = True
            history_add("sent", f.name, f.size, b.peer, "done", f.path)
        b.done_bytes = sum(x.done for x in b.files)
        b.index = b.files.index(group[-1])
        b.tick_speed()
        BUS.transfer_changed.emit()
        return True

    def gate(self):
        b = self.b
        polls = 0
        while True:
            if b.state == ACTIVE:
                return True
            if b.state != PAUSED:
                return False
            if b.paused_by == "link" and LINK.state == Link.CONNECTED:
                b.state = ACTIVE
                b.note = ""
                BUS.transfer_changed.emit()
                return True
            if b.paused_by == "peer":
                polls += 1
                if polls % 3 == 1:
                    try:
                        st = http_json(f"http://{LINK.peer_address}/status?id={b.id}", timeout=4, headers=LINK.auth()).get("state")
                    except Exception:
                        st = "paused"
                    if st == "active":
                        b.state = ACTIVE
                        b.note = ""
                        BUS.transfer_changed.emit()
                        return True
                    if st == "cancelled":
                        b.state = CANCELLED
                        b.note = f"{b.peer} stopped the transfer"
                        BUS.transfer_changed.emit()
                        return False
            time.sleep(0.3)

    def deliver(self, f, data, offset, last, sha=""):
        b = self.b
        while True:
            if not self.gate():
                return None
            path = f"/chunk?id={b.id}&fid={f.fid}&offset={offset}&last={1 if last else 0}" + (f"&sha={sha}" if sha else "")
            try:
                if self.conn.host + ":" + str(self.conn.port) != LINK.peer_address:
                    self.conn.close()
                    self.conn = PeerConn(LINK.peer_address)
                r = self.conn.post(path, data)
            except Exception as e:
                if b.state == ACTIVE:
                    b.state = PAUSED
                    b.paused_by = "link"
                    b.note = "Connection lost - resumes when the link is back"
                    BUS.transfer_changed.emit()
                    print(f"[send] chunk failed, waiting: {e}")
                time.sleep(1)
                continue
            if r.get("ok"):
                return int(r.get("received", offset + len(data)))
            st = r.get("state")
            if st == "paused":
                if b.state == ACTIVE:
                    b.state = PAUSED
                    b.paused_by = "peer"
                    b.note = f"Paused by {b.peer}"
                    BUS.transfer_changed.emit()
                continue
            if st in ("cancelled", "unknown"):
                b.state = CANCELLED
                b.note = f"{b.peer} stopped the transfer"
                b.speed = 0
                BUS.transfer_changed.emit()
                return None
            if r.get("error") == "checksum":
                self.fail(f"{f.name} arrived damaged - the checksum did not match. Try again.")
                return None
            if r.get("error") == "offset":
                have = int(r.get("received", -1))
                if have == offset + len(data):
                    return have
                self.fail(f"Out of sync with {b.peer} - start again")
                return None
            time.sleep(0.5)

    def fail(self, note, state=FAILED):
        self.b.state = state
        self.b.note = note
        self.b.speed = 0
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
    """The receiving side, driven by the HTTP server."""
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
        cls._accept(b)

    @classmethod
    def _accept(cls, b):
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
    def chunk(cls, bid, fid, offset, last, rfile, length, sha=""):
        with cls.lock:
            b = cls.batch

            def refuse(state):
                drain(rfile, length)
                return {"ok": False, "state": state}

            if b is None or b.id != bid:
                return refuse("unknown")
            if b.state == WAITING:
                return refuse("pending")
            if b.state == PAUSED:
                return refuse("paused")
            if b.state in (CANCELLED, FAILED):
                return refuse("cancelled")
            f = next((x for x in b.files if x.fid == fid), None)
            if f is None:
                return refuse("unknown")
            if f.finished or offset != f.done or length < 0 or (f.size >= 0 and offset + length > f.size):
                drain(rfile, length)
                return {"ok": False, "error": "offset", "received": f.done}
            try:
                if f.out_path is None:
                    f.out_path = unique_path(received_dir(), f.name + ".part")
                    f.out_path.touch()
                    f.digest = hashlib.sha256() if (sha or CFG.get("verify", True)) else None
                with open(f.out_path, "r+b") as out:
                    out.seek(offset)
                    left = length
                    while left > 0:
                        data = rfile.read(min(256 * 1024, left))
                        if not data:
                            raise OSError("the sender stopped early")
                        out.write(data)
                        if f.digest is not None:
                            f.digest.update(data)
                        left -= len(data)
            except Exception as e:
                print(f"[recv] could not save {f.name}: {e}")
                b.state = FAILED
                b.note = f"Could not save {f.name}: {e}"
                cls._discard(b)
                BUS.transfer_changed.emit()
                return {"ok": False, "state": "cancelled"}
            f.done += length
            b.done_bytes += length
            b.index = b.files.index(f)
            b.tick_speed()
            if last:
                mine = f.digest.hexdigest() if f.digest is not None else None
                if sha and mine is not None and sha.lower() != mine:
                    print(f"[recv] checksum mismatch on {f.name}")
                    try:
                        f.out_path.unlink()
                    except Exception:
                        pass
                    f.out_path = None
                    f.done = 0
                    b.state = FAILED
                    b.note = f"{f.name} arrived damaged (checksum mismatch) - ask for it again"
                    cls._discard(b)
                    BUS.transfer_changed.emit()
                    return {"ok": False, "error": "checksum"}
                final = unique_path(received_dir(), f.name)
                try:
                    f.out_path.rename(final)
                    f.out_path = final
                except Exception as e:
                    print(f"[recv] rename failed: {e}")
                f.finished = True
                history_add("received", f.name, f.done, b.peer, "done", f.out_path)
                print(f"[recv] saved {f.out_path.name} ({fmt_size(f.done)})")
                if all(x.finished for x in b.files):
                    b.state = DONE
                    b.note = f"Saved in {received_dir()}"
                    b.speed = 0
            BUS.transfer_changed.emit()
            return {"ok": True, "received": f.done}

    @classmethod
    def bundle(cls, bid, rfile, length):
        """Many small files in one request: u32 index length, JSON index, the bytes."""
        with cls.lock:
            b = cls.batch

            def refuse(state):
                drain(rfile, length)
                return {"ok": False, "state": state}

            if b is None or b.id != bid:
                return refuse("unknown")
            if b.state == WAITING:
                return refuse("pending")
            if b.state == PAUSED:
                return refuse("paused")
            if b.state in (CANCELLED, FAILED):
                return refuse("cancelled")
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
                f = next((x for x in b.files if x.fid == e.get("fid")), None)
                if f is None or f.finished or (f.size >= 0 and size != f.size):
                    drain(rfile, size)       # a resend we already have, or not ours
                    continue
                digest = hashlib.sha256() if (sha or CFG.get("verify", True)) else None
                part = unique_path(received_dir(), f.name + ".part")
                try:
                    with open(part, "wb") as out:
                        left = size
                        while left > 0:
                            data = rfile.read(min(256 * 1024, left))
                            if not data:
                                raise OSError("the sender stopped early")
                            out.write(data)
                            if digest is not None:
                                digest.update(data)
                            left -= len(data)
                except Exception as ex:
                    part.unlink(missing_ok=True)
                    b.state, b.note = FAILED, f"Could not save {f.name}: {ex}"
                    cls._discard(b)
                    BUS.transfer_changed.emit()
                    return {"ok": False, "state": "cancelled"}
                if sha and digest is not None and sha.lower() != digest.hexdigest():
                    part.unlink(missing_ok=True)
                    b.state, b.note = FAILED, f"{f.name} arrived damaged (checksum mismatch) - ask for it again"
                    cls._discard(b)
                    BUS.transfer_changed.emit()
                    return {"ok": False, "error": "checksum"}
                final = unique_path(received_dir(), f.name)
                part.rename(final)
                f.out_path = final
                f.done = size
                f.finished = True
                b.done_bytes += size
                b.index = b.files.index(f)
                history_add("received", f.name, size, b.peer, "done", final)
            b.tick_speed()
            if all(x.finished for x in b.files):
                b.state = DONE
                b.note = f"Saved in {received_dir()}"
                b.speed = 0
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
        for f in b.files:
            if not f.finished and f.out_path is not None:
                try:
                    f.out_path.unlink()
                except Exception:
                    pass
                f.out_path = None


def drain(rfile, length):
    left = length
    while left > 0:
        data = rfile.read(min(65536, left))
        if not data:
            break
        left -= len(data)


# ------------------------------------------------------------------ the server

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"      # keep-alive: one connection carries the whole chunk stream
    disable_nagle_algorithm = True     # headers + body go out at once, no delayed-ACK stall per reply

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
        return LINK.is_peer(self.client_address[0], self.headers.get(TOKEN_HEADER, ""))

    def _refuse(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 0:
            drain(self.rfile, n)
        print(f"[server] unauthorized {self.command} {self.path} from {self.client_address[0]}")
        self._send({"ok": False, "error": "unauthorized"})

    def do_GET(self):
        path, q = self._query()
        if path == "/ping":
            self._send({"app": APP_ID, "version": VERSION, "name": CFG["device_name"], "port": PORT})
        elif path == "/pair":
            self._send(LINK.on_pair_poll(q.get("id", "")))
        elif not self._authorized():
            self._refuse()
        elif path == "/offer":
            self._send({"state": Incoming.offer_state(q.get("id", ""))})
        elif path == "/status":
            self._send(Incoming.status(q.get("id", "")))
        else:
            self._send({"ok": False, "error": "unknown path"})

    def do_POST(self):
        path, q = self._query()
        ip = self.client_address[0]
        if path == "/pair" and q.get("cancel") == "1":
            self._json_body()
            LINK.on_pair_cancel(q.get("id", ""))
            self._send({"ok": True})
        elif path == "/pair":
            body = self._json_body()
            self._send(LINK.on_pair_request(ip, int(body.get("port", DEFAULT_PORT)),
                                            str(body.get("name") or "Device")[:40], str(body.get("id", ""))[:32]))
        elif not self._authorized():
            self._refuse()
        elif path == "/bye":
            self._json_body()
            LINK.on_bye()
            self._send({"ok": True})
        elif path == "/offer":
            self._send(Incoming.offer(self._json_body(), ip))
        elif path == "/chunk":
            n = int(self.headers.get("Content-Length") or 0)
            self._send(Incoming.chunk(q.get("id", ""), q.get("fid", ""), int(q.get("offset", -1)),
                                      q.get("last") == "1", self.rfile, n, q.get("sha", "")))
        elif path == "/bundle":
            n = int(self.headers.get("Content-Length") or 0)
            self._send(Incoming.bundle(q.get("id", ""), self.rfile, n))
        elif path == "/control":
            self._json_body()
            self._send(Incoming.control(q.get("id", ""), q.get("action", "")))
        else:
            self._send({"ok": False, "error": "unknown path"})


def start_server():
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler, bind_and_activate=False)
    server.daemon_threads = True
    server.allow_reuse_address = True
    try:
        server.socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024 * 1024)
    except OSError:
        pass
    server.server_bind()
    server.server_activate()
    threading.Thread(target=server.serve_forever, daemon=True, name="http").start()
    print(f"[server] listening on :{PORT}")

    def responder():
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                sock.ioctl(socket.SIO_UDP_CONNRESET, False)
            except (AttributeError, OSError):
                pass
            sock.bind(("", DISCOVERY_PORT))
            while True:
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


# ------------------------------------------------------------------ UI kit (glass)

BG_TOP, BG_MID, BG_BOT = QColor("#0B0E16"), QColor("#131A2B"), QColor("#0A0C12")
ACCENT = {"green": "#22C55E", "red": "#EF4444", "blue": "#5B8CFF", "cyan": "#22D3EE",
          "amber": "#F59E0B", "violet": "#A78BFA", "none": None}
FONT_FAMILY = "Segoe UI"


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
        # whisper of shadow
        sh = QPainterPath()
        sh.addRoundedRect(r.translated(0, 1.5), rad, rad)
        p.fillPath(sh, QColor(0, 0, 0, 22))
        # frosted fill
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


# ------------------------------------------------------------------ dialogs

class Popup(QDialog):
    """Dim everything, one centred glass card, two clear buttons."""

    def __init__(self, parent, heading, lines, ok_text, cancel_text, ok_accent="green", note=""):
        super().__init__(parent)
        self.setWindowFlags(Qt.WindowType.FramelessWindowHint | Qt.WindowType.Dialog)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setModal(True)
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

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        path = QPainterPath()
        path.addRoundedRect(r, 24, 24)
        p.fillPath(path, QColor(19, 26, 43, 246))
        p.end()


class ChooseDialog(QDialog):
    """Several LanDrops answered - pick the one to connect to."""

    def __init__(self, parent, found):
        super().__init__(parent)
        self.setWindowFlags(Qt.WindowType.FramelessWindowHint | Qt.WindowType.Dialog)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setModal(True)
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

    def pick(self, address):
        self.chosen = address
        self.accept()

    def paintEvent(self, e):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        r = QRectF(self.rect()).adjusted(0.5, 0.5, -0.5, -0.5)
        path = QPainterPath()
        path.addRoundedRect(r, 24, 24)
        p.fillPath(path, QColor(19, 26, 43, 246))
        p.end()


class QrDialog(QDialog):
    def __init__(self, parent, text):
        super().__init__(parent)
        self.setWindowTitle("Scan with the phone")
        self.setStyleSheet("background: #0B0E16;")
        lay = vbox(self, 24, 14)
        lay.addWidget(title("Scan this with the phone", 20))
        lay.addWidget(muted("LanDrop on the phone -> CONNECT -> SCAN THE QR CODE", 13))
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
        lay.addWidget(title(text.removeprefix(SCHEME), 18))
        lay.addWidget(muted("Or type that address into the phone.", 13))
        close = GlassButton("CLOSE", "none", 44)
        close.clicked.connect(self.accept)
        lay.addWidget(close)


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
        self.cancel_btn = GlassButton("CANCEL REQUEST", "red", 44)
        self.find_btn.clicked.connect(self.find_phone)
        self.qr_btn.clicked.connect(self.show_qr)
        self.disc_btn.clicked.connect(self.disconnect)
        self.cancel_btn.clicked.connect(LINK.cancel_request)
        self.addr = GlassField(f"device address, e.g. 192.168.1.30:{DEFAULT_PORT}")
        self.addr.setText(CFG.get("peer", ""))
        self.addr.returnPressed.connect(self.connect_typed)
        self.connect_btn = GlassButton("ASK TO CONNECT", "green", 44, 11)
        self.connect_btn.setFixedWidth(150)
        self.connect_btn.clicked.connect(self.connect_typed)
        self.row_auto = hbox()
        self.row_auto.addWidget(self.find_btn)
        self.row_auto.addWidget(self.qr_btn)
        self.row_manual = hbox()
        self.row_manual.addWidget(self.addr, 1)
        self.row_manual.addWidget(self.connect_btn)
        self.conn_widgets = QWidget()
        cw = vbox(self.conn_widgets, 0, 10)
        cw.addLayout(self.row_auto)
        cw.addLayout(self.row_manual)
        self.conn_widgets.setStyleSheet("background: transparent;")
        cl.addWidget(self.conn_widgets)
        cl.addWidget(self.disc_btn)
        cl.addWidget(self.cancel_btn)
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

        self.folder_line = faint("")
        self.folder_line.setAlignment(Qt.AlignmentFlag.AlignCenter)
        lay.addWidget(self.folder_line)
        lay.addStretch(1)

        self.refresh_link()
        self.refresh_transfer()
        self.stall_timer = QTimer(self)
        self.stall_timer.timeout.connect(self.refresh_transfer)
        self.stall_timer.start(1500)

    # ---- link ----
    def refresh_link(self):
        st = LINK.state
        connected = st == Link.CONNECTED
        requesting = st == Link.REQUESTING
        self.dot.set({"connected": ACCENT["green"], "requesting": ACCENT["amber"]}.get(st, "#8A93A6"),
                     pulsing=requesting)
        self.conn_card.set_accent("green" if connected else "amber" if requesting else "none")
        self.conn_title.setText("Connected" if connected else "Asking..." if requesting else "Not connected")
        self.conn_detail.setText(f"{LINK.peer_name}  ·  {LINK.peer_address}" if connected else LINK.message)
        self.conn_widgets.setVisible(not connected and not requesting)
        self.disc_btn.setVisible(connected)
        self.cancel_btn.setVisible(requesting)
        if connected or requesting:
            self.conn_status.setText("")
        if SERVER_ERROR and not self.conn_status.text():
            self.conn_status.setText(SERVER_ERROR)
        self.find_btn.setEnabled(not self._scanning)
        self.connect_btn.setEnabled(True)
        self.send_btn.setEnabled(connected and not Transfers.busy())
        self.send_hint.setText(
            "Connect to a phone or another PC first - then pick any files, any size." if not connected else
            "A transfer is running - wait for it to finish." if Transfers.busy() else
            "Pick any files, any size - or drop them anywhere on this window.")
        self.folder_line.setText(f"Received files go to  {received_dir()}")

    def find_phone(self):
        self._scanning = True
        self.conn_status.setText("Scanning the network...")
        self.find_btn.setEnabled(False)

        def go():
            found = LINK.discover_all(2.5)

            def done():
                self._scanning = False
                self.find_btn.setEnabled(True)
                if not found:
                    self.conn_status.setText(
                        "No device answered. Open LanDrop on the other device (same Wi-Fi) and check that "
                        "Windows Firewall allows Python on private networks.")
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
        QrDialog(self.win, f"{SCHEME}{local_ip()}:{PORT}").exec()

    def disconnect(self):
        LINK.disconnect()
        self.conn_status.setText("")

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
        paths = [p for p in paths if Path(p).is_file()]
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
        self.t_speed.setText(f"{fmt_size(int(b.speed))}/s   {fmt_eta(b.eta)}" if b.state == ACTIVE and b.speed > 0 else "")
        stalled = b.state == ACTIVE and not b.outgoing and time.time() - b.last_activity > 6
        self.t_note.setText(f"Waiting for {b.peer}..." if stalled else b.note)
        self.t_note.setVisible(bool(self.t_note.text()))
        self.pause_btn.setVisible(b.state in (ACTIVE, PAUSED))
        self.pause_btn.setText("RESUME" if b.state == PAUSED else "PAUSE")
        self.pause_btn.accent = ACCENT["green" if b.state == PAUSED else "amber"]
        self.pause_btn.update()
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


class HistoryPage(QWidget):
    def __init__(self, win):
        super().__init__()
        self.win = win
        lay = vbox(self, 0, 12)
        top = hbox()
        top.addWidget(title("History", 24), 1)
        clear = GlassButton("CLEAR", "red", 40, 12)
        clear.setFixedWidth(120)
        clear.clicked.connect(lambda: history_clear())
        top.addWidget(clear)
        lay.addLayout(top)
        self.empty = muted("Nothing yet. Files you send or receive will be listed here - double-click one to open it.")
        lay.addWidget(self.empty)
        self.list = QListWidget()
        self.list.setFont(font(13))
        self.list.setStyleSheet(
            "QListWidget { background: rgba(255,255,255,0.06); color: white; border: 1px solid rgba(255,255,255,0.2);"
            " border-radius: 18px; padding: 8px; outline: none; }"
            "QListWidget::item { padding: 10px 12px; border-radius: 12px; }"
            "QListWidget::item:selected { background: rgba(91,140,255,0.35); }"
            "QListWidget::item:hover { background: rgba(255,255,255,0.08); }")
        self.list.setWordWrap(True)
        self.list.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.list.itemDoubleClicked.connect(self.open_item)
        lay.addWidget(self.list, 1)
        lay.addWidget(faint("Double-click a file to open it; received files are in the Received folder."))
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
            it = QListWidgetItem(f"{h['name']}   ·   {fmt_size(h.get('size'))}   ·   {way} {h['peer']}   ·   {when}{status}")
            it.setData(Qt.ItemDataRole.UserRole, h.get("path", ""))
            self.list.addItem(it)

    def open_item(self, item):
        path = item.data(Qt.ItemDataRole.UserRole)
        if path and Path(path).exists():
            try:
                os.startfile(path)
            except Exception as e:
                print(f"[history] open: {e}")


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

        note = GlassCard()
        nl = vbox(note, 16, 4)
        nl.addWidget(title("Always with your consent", 15))
        nl.addWidget(muted("A device connects only after you ALLOW it, and every incoming transfer asks you first. "
                           "Nothing connects or receives by itself.", 12))
        lay.addWidget(note)
        lay.addWidget(self.choice_row("Transfer mode",
                                      "Auto picks the chunk size per file. Fast = big chunks for a solid network; "
                                      "Reliable = small chunks that retry cheaply on a flaky Wi-Fi.",
                                      "transfer_mode", [("auto", "AUTO"), ("fast", "FAST"), ("reliable", "RELIABLE")]))
        lay.addWidget(self.switch_row("Verify every file", "SHA-256 checksum on arrival - a damaged file is deleted and reported",
                                      "verify"))

        fcard = GlassCard()
        fl = vbox(fcard, 18, 6)
        fl.addWidget(muted("Received files folder"))
        self.folder = title(CFG["received_folder"], 15)
        fl.addWidget(self.folder)
        frow = hbox()
        open_btn = GlassButton("OPEN FOLDER", "blue", 42)
        change_btn = GlassButton("CHANGE...", "none", 42)
        open_btn.clicked.connect(lambda: os.startfile(str(received_dir())))
        change_btn.clicked.connect(self.change_folder)
        frow.addWidget(open_btn)
        frow.addWidget(change_btn)
        fl.addLayout(frow)
        lay.addWidget(fcard)

        lay.addWidget(faint(f"LanDrop v{APP_VERSION}  ·  this PC: {local_ip()}:{PORT}  ·  "
                            "If other devices cannot find this PC, allow Python through Windows Firewall (private networks)."))
        lay.addStretch(1)

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
        self.resize(760, 820)
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
            scroll.setStyleSheet("background: transparent;")
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


SERVER_ERROR = ""


def main():
    global SERVER_ERROR
    app = QApplication(sys.argv)
    load_font()
    app.setFont(font(13))
    try:
        start_server()
    except OSError as e:
        print(f"[server] cannot listen on {PORT}: {e}")
        SERVER_ERROR = (f"Port {PORT} is already in use on this PC (another LanDrop running?). "
                        "Close it and start LanDrop again - other devices cannot reach this one until then.")
    LINK.start()
    win = MainWindow()
    win.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
