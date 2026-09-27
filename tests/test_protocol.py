import hmac
import socket
import struct
import sys
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "linux"))
from tablet_link import Session, frame, read_exact


class ProtocolTest(unittest.TestCase):
    def test_authentication_accepts_matching_secret(self):
        server, client = socket.socketpair()
        secret = bytes(range(32))
        session = Session(server, "TEST", secret)
        result = []
        thread = threading.Thread(target=lambda: result.append(session.authenticate()))
        thread.start()
        greeting = read_exact(client, 37)
        self.assertEqual(greeting[:5], b"TLNK1")
        client.sendall(hmac.digest(secret, greeting[5:], "sha256"))
        self.assertEqual(read_exact(client, 1), b"\x01")
        thread.join(timeout=1)
        self.assertFalse(thread.is_alive())
        client.close()
        server.close()

    def test_authentication_rejects_wrong_secret(self):
        server, client = socket.socketpair()
        session = Session(server, "TEST", bytes(range(32)))
        errors = []
        def authenticate():
            try:
                session.authenticate()
            except PermissionError as error:
                errors.append(error)
        thread = threading.Thread(target=authenticate)
        thread.start()
        read_exact(client, 37)
        client.sendall(bytes(32))
        self.assertEqual(read_exact(client, 1), b"\x00")
        thread.join(timeout=1)
        self.assertEqual(len(errors), 1)
        client.close()
        server.close()

    def test_frame_uses_network_byte_order(self):
        server, client = socket.socketpair()
        frame(server, 5, b"abc", threading.Lock())
        self.assertEqual(read_exact(client, 8), b"\x05" + struct.pack("!I", 3) + b"abc")
        client.close()
        server.close()


if __name__ == "__main__":
    unittest.main()
