#!/usr/bin/env python3
"""Personal Tablet Link companion. Requires a spare Niri output for the tablet."""
import argparse
import fcntl
import hashlib
import hmac
import json
import os
from pathlib import Path
import select
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parent.parent
PORT = 39471
MAX_FRAME = 4 * 1024 * 1024
RATE = 48000
CHUNK = 1920  # 20 ms, mono 16-bit PCM
VIDEO_HELLO = b"TLV1"
VIDEO_RESEND = b"TLN1"
VIDEO_HISTORY = 90  # pictures kept for resending, about three seconds
VIDEO_PAYLOAD = 1150  # keeps datagrams inside Tailscale's 1280 byte MTU


def command(*args, check=True):
    return subprocess.run(args, check=check, capture_output=True, text=True).stdout.strip()


def read_exact(sock, count):
    data = bytearray()
    while len(data) < count:
        part = sock.recv(count - len(data))
        if not part:
            raise ConnectionError("Tablet disconnected")
        data.extend(part)
    return bytes(data)


def frame(sock, kind, payload, lock):
    if len(payload) > MAX_FRAME:
        raise ValueError("Frame too large")
    with lock:
        sock.sendall(bytes((kind,)) + struct.pack("!I", len(payload)) + payload)


def outputs():
    return json.loads(command("niri", "msg", "-j", "outputs"))


class AccessUnits:
    """Splits an Annex B H.264 byte stream into access units (one per picture)."""
    def __init__(self):
        self.data = bytearray()
        self.scan = 0
        self.picture = False
        self.key = False

    def feed(self, chunk):
        """Returns the (unit, is_keyframe) pairs completed by this chunk."""
        self.data += chunk
        units = []
        while True:
            index = self.data.find(b"\x00\x00\x01", self.scan)
            if index < 0:
                self.scan = max(self.scan, len(self.data) - 2)
                break
            if index + 4 >= len(self.data):
                self.scan = index
                break
            kind = self.data[index + 3] & 0x1f
            slice_start = kind in (1, 5) and self.data[index + 4] & 0x80
            # A picture ends at the next non-slice unit or the next first slice.
            if self.picture and (kind not in (1, 5) or slice_start):
                start = index - 1 if index and self.data[index - 1] == 0 else index
                units.append((bytes(self.data[:start]), self.key))
                del self.data[:start]
                index -= start
                self.picture = self.key = False
            if kind in (1, 5):
                self.picture = True
                self.key = self.key or kind == 5
            self.scan = index + 3
        return units

    def flush(self):
        """Returns the buffered picture; used when the encoder goes quiet after writing one."""
        unit = (bytes(self.data), self.key)
        self.__init__()
        return unit


def datagrams(number, unit, key):
    count = -(-len(unit) // VIDEO_PAYLOAD)
    for index in range(count):
        yield (struct.pack("!IHHB", number, index, count, key) +
               unit[index * VIDEO_PAYLOAD:(index + 1) * VIDEO_PAYLOAD])


class AudioDevices:
    def __init__(self):
        self.modules = []
        self.temp = None
        self.previous_sink = None
        self.previous_source = None

    def start(self):
        for line in command("pactl", "list", "modules", "short").splitlines():
            if "tablet_link_" in line:  # left behind by a killed companion
                subprocess.run(("pactl", "unload-module", line.split("\t")[0]), capture_output=True)
        self.previous_sink = command("pactl", "get-default-sink")
        self.previous_source = command("pactl", "get-default-source")
        self.temp = tempfile.TemporaryDirectory(prefix="tablet-link-")
        self.speaker = Path(self.temp.name) / "speaker.pcm"
        self.microphone = Path(self.temp.name) / "microphone.pcm"
        os.mkfifo(self.speaker, 0o600)
        os.mkfifo(self.microphone, 0o600)
        try:
            self.modules.append(command(
                "pactl", "load-module", "module-pipe-sink",
                f"file={self.speaker}", "sink_name=tablet_link_speaker",
                "sink_properties=device.description=Tablet_Link_Speaker",
                "format=s16le", f"rate={RATE}", "channels=1"))
            self.modules.append(command(
                "pactl", "load-module", "module-pipe-source",
                f"file={self.microphone}", "source_name=tablet_link_microphone",
                "source_properties=device.description=Tablet_Link_Microphone",
                "format=s16le", f"rate={RATE}", "channels=1"))
            command("pactl", "set-default-sink", "tablet_link_speaker")
            command("pactl", "set-default-source", "tablet_link_microphone")
        except Exception:
            self.stop()
            raise

    def stop(self):
        if self.previous_sink:
            subprocess.run(("pactl", "set-default-sink", self.previous_sink), capture_output=True)
            self.previous_sink = None
        if self.previous_source:
            subprocess.run(("pactl", "set-default-source", self.previous_source), capture_output=True)
            self.previous_source = None
        for module in reversed(self.modules):
            subprocess.run(("pactl", "unload-module", module), capture_output=True)
        self.modules.clear()
        if self.temp:
            self.temp.cleanup()
            self.temp = None


class Pointer:
    """uinput absolute pointer. Niri maps its range onto the bounding box of all outputs."""
    def __init__(self, width, height):
        self.fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        try:
            for kind in (1, 2, 3):  # UI_SET_EVBIT: EV_KEY, EV_REL, EV_ABS
                fcntl.ioctl(self.fd, 0x40045564, kind)
            fcntl.ioctl(self.fd, 0x40045565, 272)  # UI_SET_KEYBIT, BTN_LEFT
            fcntl.ioctl(self.fd, 0x40045566, 8)  # UI_SET_RELBIT, REL_WHEEL
            for code in (0, 1):  # UI_SET_ABSBIT, ABS_X and ABS_Y
                fcntl.ioctl(self.fd, 0x40045567, code)
            absmax = [0] * 64
            absmax[0], absmax[1] = width - 1, height - 1
            identity = struct.pack("<80sHHHHI256i", b"Tablet Link Pointer", 0x03, 0x1234,
                                   0x3947, 1, 0, *absmax, *([0] * 192))
            os.write(self.fd, identity)
            fcntl.ioctl(self.fd, 0x5501)  # UI_DEV_CREATE
            time.sleep(0.3)
        except Exception:
            os.close(self.fd)
            raise

    def emit(self, *events):
        for kind, code, value in events + ((0, 0, 0),):
            os.write(self.fd, struct.pack("@llHHi", 0, 0, kind, code, value))

    def move(self, x, y):
        self.emit((3, 0, x), (3, 1, y))

    def button(self, down):
        self.emit((1, 272, int(down)))

    def scroll(self, steps):
        self.emit((2, 8, steps))

    def close(self):
        fcntl.ioctl(self.fd, 0x5502)  # UI_DEV_DESTROY
        os.close(self.fd)


class Session:
    def __init__(self, sock, output, secret, udp=None, mode="2560x1600@60", scale=2.0,
                 side="left", fps=30, bitrate=3000):
        self.sock = sock
        self.output = output
        self.secret = secret
        self.udp = udp
        self.mode = mode
        self.scale = scale
        self.side = side
        self.fps = fps
        self.bitrate = bitrate
        self.token = os.urandom(16)
        self.send_lock = threading.Lock()
        self.stop_event = threading.Event()
        self.audio = AudioDevices()
        self.mic_fd = None
        self.mouse_down = False
        self.pointer = None
        self.threads = []

    def send(self, kind, payload):
        frame(self.sock, kind, payload, self.send_lock)

    def authenticate(self):
        challenge = os.urandom(32)
        self.sock.sendall(b"TLNK1" + challenge)
        response = read_exact(self.sock, 32)
        if not hmac.compare_digest(response, hmac.digest(self.secret, challenge, "sha256")):
            self.sock.sendall(b"\x00")
            raise PermissionError("Shared secret did not match")
        self.sock.sendall(b"\x01")

    def prepare_output(self):
        command("niri", "msg", "output", self.output, "on")
        command("niri", "msg", "output", self.output, "custom-mode", self.mode)
        command("niri", "msg", "output", self.output, "scale", str(self.scale))
        width, height = (int(value) for value in self.mode.split("@")[0].split("x"))
        for _ in range(30):
            all_outputs = outputs()
            tablet = all_outputs.get(self.output, {})
            current = tablet.get("current_mode")
            if tablet.get("logical") and current is not None and \
                    (tablet["modes"][current]["width"], tablet["modes"][current]["height"]) == (width, height):
                break
            time.sleep(0.1)
        else:
            raise RuntimeError(f"Niri did not enable {self.output} at {self.mode}")
        self.pixels = (width, height)
        others = [v["logical"] for k, v in all_outputs.items() if k != self.output and v.get("logical")]
        if not others:
            raise RuntimeError("A second Niri output is required for the tablet")
        # Sit beside the largest other output, so the layout does not depend on output order.
        primary = max(others, key=lambda g: g["width"] * g["height"])
        left = primary["x"] + primary["width"] if self.side == "right" else \
            primary["x"] - tablet["logical"]["width"]
        # "--" keeps a negative coordinate from being read as an option.
        command("niri", "msg", "output", self.output, "position", "set", "--", str(left), str(primary["y"]))
        logical = [v["logical"] for v in outputs().values() if v.get("logical")]
        self.geometry = outputs()[self.output]["logical"]
        self.origin = (min(g["x"] for g in logical), min(g["y"] for g in logical))
        self.bounds = (max(g["x"] + g["width"] for g in logical) - self.origin[0],
                       max(g["y"] + g["height"] for g in logical) - self.origin[1])

    def start_input(self):
        try:
            self.pointer = Pointer(*self.bounds)
        except OSError as error:
            raise RuntimeError("Could not create the pointer; check /dev/uinput access") from error

    def input(self, payload):
        if len(payload) != 9:
            raise ValueError("Invalid touch packet")
        action, nx, ny = struct.unpack("!Bff", payload)
        if action == 3:
            if -1 <= ny <= 1 and ny != 0:
                self.pointer.scroll(1 if ny > 0 else -1)  # content follows the fingers
            return
        if not (0 <= nx <= 1 and 0 <= ny <= 1):
            return
        # Pointer coordinates are compositor-wide logical coordinates from the bounding box origin.
        geometry = self.geometry
        x = geometry["x"] - self.origin[0] + int(nx * max(geometry["width"] - 1, 1))
        y = geometry["y"] - self.origin[1] + int(ny * max(geometry["height"] - 1, 1))
        self.pointer.move(x, y)
        if action == 0 and not self.mouse_down:
            self.pointer.button(True)
            self.mouse_down = True
        elif action == 1 and self.mouse_down:
            self.pointer.button(False)
            self.mouse_down = False

    def start_recorder(self):
        # Hardware H.264 with no B-frames and a keyframe every second, so a lost
        # datagram costs at most a one second freeze.
        recorder = subprocess.Popen(
            ("wf-recorder", "-o", self.output, "-y", "-c", "h264_vaapi", "-d", "/dev/dri/renderD128",
             "-D", "-r", str(self.fps), "-b", "0", "-p", f"g={self.fps}",
             "-p", "rc_mode=VBR", "-p", f"b={self.bitrate * 1000}",
             "-p", f"maxrate={self.bitrate * 1250}", "-p", f"bufsize={self.bitrate * 500}",
             "-m", "h264", "-f", "pipe:1"),
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
        try:
            fcntl.fcntl(recorder.stdout, 1031, 1 << 20)  # F_SETPIPE_SZ, whole pictures per read
        except OSError:
            pass
        return recorder

    def video_loop(self):
        peer = self.sock.getpeername()[0]
        hello = VIDEO_HELLO + self.token
        resend = VIDEO_RESEND + self.token
        target = None
        recorder = None
        units = AccessUnits()
        number = 0
        history = {}
        pace = self.bitrate * 1000 / 8 * 2  # bytes per second on the wire
        due = 0
        skipping = False

        def send(datagram):
            # Spread datagrams out; a burst overflows the queues of a relayed path.
            nonlocal due
            now = time.monotonic()
            due = max(due, now - 0.004)
            if due > now:
                time.sleep(due - now)
            due += len(datagram) / pace
            try:
                self.udp.sendto(datagram, target)
            except OSError:
                pass  # video is lossy; the tablet asks again or waits for a keyframe

        try:
            while not self.stop_event.is_set():
                sources = [self.udp] + ([recorder.stdout] if recorder else [])
                ready = select.select(sources, [], [], 0.003 if units.picture else 0.5)[0]
                if self.udp in ready:
                    try:
                        data, address = self.udp.recvfrom(2048)
                    except OSError:
                        data, address = b"", (None, 0)
                    if address[0] != peer:
                        pass
                    elif hmac.compare_digest(data, hello):
                        # The tablet announces its video port, and repeats it to keep the path open.
                        target = address
                        if recorder is None:
                            recorder = self.start_recorder()
                    elif len(data) >= 24 and hmac.compare_digest(data[:20], resend) and target:
                        # The tablet lists the parts of a picture that never arrived.
                        wanted = history.get(struct.unpack("!I", data[20:24])[0], ())
                        indexes = struct.unpack(f"!{(len(data) - 24) // 2}H", data[24:24 + (len(data) - 24) // 2 * 2])
                        for index in indexes or range(len(wanted)):
                            # Resends only use spare capacity; adding to a backlog makes losses worse.
                            if index < len(wanted) and due - time.monotonic() < 0.03:
                                send(wanted[index])
                done = []
                if recorder and recorder.stdout in ready:
                    data = os.read(recorder.stdout.fileno(), 1 << 20)
                    if not data:
                        self.fail("Display capture stopped")
                        return
                    done = units.feed(data)
                elif not ready and units.picture:
                    done = [units.flush()]
                for unit, key in done:
                    # When the path cannot keep up, drop pictures up to a keyframe instead of
                    # queueing them; the display stays current rather than falling behind.
                    backlog = due - time.monotonic()
                    if skipping or backlog > 0.15:
                        skipping = not (key and backlog < 0.05)
                    if not skipping:
                        history[number] = list(datagrams(number, unit, key))
                        history.pop(number - VIDEO_HISTORY, None)
                        for datagram in history[number]:
                            send(datagram)
                    number += 1
        finally:
            if recorder:
                recorder.send_signal(signal.SIGINT)
                try:
                    recorder.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    recorder.kill()

    def speaker_loop(self):
        fd = os.open(self.audio.speaker, os.O_RDONLY | os.O_NONBLOCK)
        try:
            while not self.stop_event.is_set():
                try:
                    data = os.read(fd, CHUNK)
                    if data:
                        self.send(3, data)
                    else:
                        self.stop_event.wait(0.02)
                except BlockingIOError:
                    self.stop_event.wait(0.01)
        except OSError:
            self.stop_event.set()
        finally:
            os.close(fd)

    def fail(self, message):
        if not self.stop_event.is_set():
            try:
                self.send(1, json.dumps({"state": "error", "message": message}).encode())
            except OSError:
                pass
        self.stop_event.set()
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    def run(self):
        try:
            self.sock.settimeout(15)
            self.authenticate()
            self.prepare_output()
            self.start_input()
            self.audio.start()
            self.send(1, json.dumps({"state": "connected", "width": self.pixels[0],
                                     "height": self.pixels[1], "token": self.token.hex()}).encode())
            for target in (self.video_loop, self.speaker_loop):
                thread = threading.Thread(target=target, daemon=True)
                thread.start()
                self.threads.append(thread)
            while not self.stop_event.is_set():
                kind = read_exact(self.sock, 1)[0]
                length = struct.unpack("!I", read_exact(self.sock, 4))[0]
                if length > MAX_FRAME:
                    raise ValueError("Frame too large")
                payload = read_exact(self.sock, length)
                if kind == 4:
                    if self.mic_fd is None:
                        try:
                            self.mic_fd = os.open(self.audio.microphone, os.O_WRONLY | os.O_NONBLOCK)
                        except OSError:
                            continue
                    try:
                        os.write(self.mic_fd, payload)
                    except (BlockingIOError, BrokenPipeError):
                        pass
                elif kind == 5:
                    self.input(payload)
                elif kind == 6:
                    modules = command("pactl", "list", "modules", "short")
                    if any(not any(line.startswith(module + "\t") for line in modules.splitlines())
                           for module in self.audio.modules):
                        raise RuntimeError("Tablet audio device stopped")
                    if self.output not in outputs() or not outputs()[self.output].get("logical"):
                        raise RuntimeError("Tablet display stopped")
                else:
                    raise ValueError("Unknown frame type")
        except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
            print(f"Session ended: {error}", file=sys.stderr)
            self.fail(str(error))
        finally:
            self.stop_event.set()
            if self.mouse_down:
                self.pointer.button(False)
            if self.mic_fd is not None:
                os.close(self.mic_fd)
            self.audio.stop()
            if self.pointer:
                self.pointer.close()
            subprocess.run(("niri", "msg", "output", self.output, "off"), capture_output=True)
            self.sock.close()


def main():
    parser = argparse.ArgumentParser(description="Tablet Link Linux companion")
    parser.add_argument("--output", required=True, help="Niri output reserved for the tablet")
    parser.add_argument("--mode", default="2560x1600@60", help="tablet output mode (default: %(default)s)")
    parser.add_argument("--scale", type=float, default=2.0, help="tablet output scale (default: %(default)s)")
    parser.add_argument("--side", choices=("left", "right"), default="left",
                        help="side of the main monitor the tablet sits on (default: %(default)s)")
    parser.add_argument("--fps", type=int, default=30, help="video frame rate (default: %(default)s)")
    parser.add_argument("--bitrate", type=int, default=3000, help="video kbit/s (default: %(default)s)")
    args = parser.parse_args()
    if not args.mode.replace("x", "").replace("@", "").replace(".", "").isdigit() or \
            args.mode.count("x") != 1 or args.mode.count("@") != 1:
        parser.error("--mode must look like 2560x1600@60")
    for program in ("niri", "wf-recorder", "pactl", "tailscale"):
        if not shutil.which(program):
            parser.error(f"Missing program: {program}")
    secret_file = ROOT / ".local-secret"
    if not secret_file.exists():
        parser.error("Run ./scripts/make-secret.sh first")
    secret = bytes.fromhex(secret_file.read_text().strip())
    if len(secret) != 32:
        parser.error(".local-secret must contain 32 random bytes as hex")
    available = outputs()
    if args.output not in available:
        parser.error(f"{args.output} is not in niri msg outputs; a virtual or dummy output is required")
    if len(available) < 2 or args.output == json.loads(command("niri", "msg", "-j", "focused-output"))["name"]:
        parser.error("Refusing to turn off the only or currently focused desktop output")
    command("niri", "msg", "output", args.output, "off")
    # A service stop must unwind the session so audio devices and defaults are restored.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    ip = command("tailscale", "ip", "-4").splitlines()[0]
    with socket.socket() as server, socket.socket(type=socket.SOCK_DGRAM) as udp:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((ip, PORT))
        server.listen(1)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 1 << 20)
        udp.bind((ip, PORT))
        udp.setblocking(False)
        print(f"Tablet Link listening on {ip}:{PORT} for output {args.output}", flush=True)
        while True:
            client, address = server.accept()
            print(f"Connection from {address[0]}", flush=True)
            Session(client, args.output, secret, udp, args.mode, args.scale, args.side,
                    args.fps, args.bitrate).run()


if __name__ == "__main__":
    main()
