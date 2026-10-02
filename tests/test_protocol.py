import hmac
import socket
import struct
import sys
import threading
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "linux"))
from tablet_link import AccessUnits, Session, datagrams, frame, read_exact


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

    def test_access_units_split_pictures_across_arbitrary_chunks(self):
        start = b"\x00\x00\x00\x01"
        key = start + b"\x67sps" + start + b"\x68pps" + start + b"\x06sei" + start + b"\x65\x88idr"
        second = start + b"\x06sei" + start + b"\x41\x9aone"
        third = start + b"\x41\x9atwo"
        stream = key + second + third
        for size in (1, 3, 7, len(stream)):
            units = AccessUnits()
            found = []
            for offset in range(0, len(stream), size):
                found += units.feed(stream[offset:offset + size])
            self.assertEqual(found, [(key, True), (second, False)])
            self.assertTrue(units.picture)
            self.assertEqual(units.flush(), (third, False))
            self.assertFalse(units.picture)

    def test_access_units_keep_slices_of_one_picture_together(self):
        start = b"\x00\x00\x01"
        picture = start + b"\x41\x9afirst" + start + b"\x41\x1asecond"
        units = AccessUnits()
        self.assertEqual(units.feed(picture + start + b"\x41\x9anext"), [(picture, False)])

    def test_datagrams_carry_picture_number_and_position(self):
        unit = bytes(range(256)) * 10
        packets = list(datagrams(7, unit, True))
        self.assertEqual(len(packets), 3)
        self.assertEqual([struct.unpack("!IHHB", packet[:9]) for packet in packets],
                         [(7, 0, 3, 1), (7, 1, 3, 1), (7, 2, 3, 1)])
        self.assertEqual(b"".join(packet[9:] for packet in packets), unit)


if __name__ == "__main__":
    unittest.main()
