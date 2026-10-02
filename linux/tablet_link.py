#!/usr/bin/env python3
"""Personal Tablet Link companion. Requires an existing Niri output."""
import argparse
import fcntl
import hashlib
import hmac
import json
import os
from pathlib import Path
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


class AudioDevices:
    def __init__(self):
        self.modules = []
        self.temp = None
        self.previous_sink = None
        self.previous_source = None

    def start(self):
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
    def __init__(self, sock, output, secret):
        self.sock = sock
        self.output = output
        self.secret = secret
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
        all_outputs = outputs()
        for _ in range(20):
            if self.output in all_outputs and all_outputs[self.output].get("logical"):
                break
            time.sleep(0.1)
            all_outputs = outputs()
        if self.output not in all_outputs:
            raise RuntimeError(f"Output {self.output} is not present in Niri")
        if len(all_outputs) < 2:
            raise RuntimeError("A second Niri output is required for the tablet")
        primary = next((v for k, v in all_outputs.items() if k != self.output), None)
        tablet = all_outputs[self.output]
        if not primary or not primary.get("logical") or not tablet.get("logical"):
            raise RuntimeError("Niri has not enabled both outputs")
        left = primary["logical"]["x"] - tablet["logical"]["width"]
        top = primary["logical"]["y"]
        command("niri", "msg", "output", self.output, "position", "set", str(left), str(top))
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
                self.pointer.scroll(-1 if ny > 0 else 1)
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

    def video_loop(self):
        while not self.stop_event.is_set():
            started = time.monotonic()
            capture = subprocess.run(("grim", "-o", self.output, "-s", "1", "-t", "ppm",
                                      "-c", "-"), capture_output=True)
            if capture.returncode or not capture.stdout:
                self.fail("Display capture stopped")
                return
            result = subprocess.run(("ffmpeg", "-hide_banner", "-loglevel", "error",
                                     "-f", "image2pipe", "-vcodec", "ppm", "-i", "pipe:0",
                                     "-frames:v", "1", "-q:v", "5", "-f", "image2pipe",
                                     "-vcodec", "mjpeg", "pipe:1"), input=capture.stdout,
                                    capture_output=True)
            if result.returncode or not result.stdout:
                self.fail("Display encoder stopped")
                return
            try:
                self.send(2, result.stdout)
            except OSError:
                self.stop_event.set()
                return
            self.stop_event.wait(max(0, 0.1 - (time.monotonic() - started)))

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
            self.send(1, json.dumps({"state": "connected", "width": self.geometry["width"],
                                     "height": self.geometry["height"]}).encode())
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
    args = parser.parse_args()
    for program in ("niri", "grim", "ffmpeg", "pactl", "tailscale"):
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
    with socket.socket() as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((ip, PORT))
        server.listen(1)
        print(f"Tablet Link listening on {ip}:{PORT} for output {args.output}", flush=True)
        while True:
            client, address = server.accept()
            print(f"Connection from {address[0]}", flush=True)
            Session(client, args.output, secret).run()


if __name__ == "__main__":
    main()
