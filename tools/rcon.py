"""Loopback-only console for the isolated development server; never targets a live host."""
import socket
import struct
import sys

def command(text):
    with socket.create_connection(("127.0.0.1", 28902), timeout=10) as connection:
        def exchange(kind, payload):
            body = struct.pack("<ii", 1, kind) + payload.encode() + b"\x00\x00"
            connection.sendall(struct.pack("<i", len(body)) + body)
            def receive(count):
                result = b""
                while len(result) < count:
                    chunk = connection.recv(count - len(result))
                    if not chunk: raise ConnectionError("RCON closed")
                    result += chunk
                return result
            size = struct.unpack("<i", receive(4))[0]
            reply = receive(size)
            if struct.unpack("<i", reply[:4])[0] == -1: raise PermissionError("RCON authentication failed")
            return reply[8:-2].decode()
        exchange(3, "copper-local-development")
        return exchange(2, text)

if __name__ == "__main__":
    print(command(" ".join(sys.argv[1:])))
