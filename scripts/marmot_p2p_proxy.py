#!/usr/bin/env python3
"""Hostile loopback middle layer for the direct (P2P) Olm channel tests.

One proxy sits in front of each participant's share server; the others only
know the proxy URL (as if found by NSD). Per exchange request it may drop the
request, drop the response after the server processed it, deliver the request
twice, delay it, corrupt one byte, or replay an older captured request to the
server out of band. It can also refuse every connection ("offline"), and the
participant behind it may be killed and restarted on a new port.

Frames inside a section are MAC-protected, so an attacker can drop, repeat
or reorder whole containers but cannot drop or reorder single frames; the
frame-level cases (reversed, missing, duplicated frames) are covered by the
native tests. Loopback only; no payload is ever printed.
"""
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import random
import threading
import time
import urllib.error
import urllib.request

FAULTS = ("drop_request", "drop_response", "duplicate", "delay", "corrupt", "replay_stale")


class HostileProxy:
    def __init__(self, seed, fault_rate=0.4):
        self.target = None
        self.offline = False
        self.fault_rate = fault_rate
        self.rng = random.Random(seed)
        self.lock = threading.Lock()
        self.stash = []
        self.wire = []
        self.stats = Counter()
        proxy = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                pass

            def refuse(self):
                self.close_connection = True
                try:
                    self.connection.shutdown(2)
                except OSError:
                    pass

            def reply(self, status, body, content_type):
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(body)
                self.close_connection = True

            def do_GET(self):
                if proxy.offline or proxy.target is None:
                    proxy.count("refused")
                    return self.refuse()
                status, body, kind = proxy.forward("GET", self.path, None)
                if status is None:
                    return self.refuse()
                proxy.capture(body)
                self.reply(status, body, kind)

            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                body = self.rfile.read(length) if 0 < length <= 400_000 else b""
                if proxy.offline or proxy.target is None:
                    proxy.count("refused")
                    return self.refuse()
                fault = proxy.choose()
                proxy.count(fault)
                if fault == "drop_request":
                    return self.refuse()
                if fault == "delay":
                    time.sleep(proxy.uniform(0.2, 1.0))
                if fault == "corrupt":
                    index = proxy.randrange(len(body)) if body else 0
                    body = body[:index] + bytes([body[index] ^ 0x5A]) + body[index + 1:] if body else body
                status, response, kind = proxy.forward("POST", self.path, body)
                if fault == "duplicate":
                    status, response, kind = proxy.forward("POST", self.path, body)
                if fault == "replay_stale":
                    stale = proxy.old_request()
                    if stale is not None:
                        proxy.forward("POST", self.path, stale)
                proxy.remember(body)
                proxy.capture(body, response)
                if status is None or fault == "drop_response":
                    return self.refuse()
                self.reply(status, response, kind)

        class Server(ThreadingHTTPServer):
            def handle_error(self, request, client_address):
                pass  # a client that gave up mid-reply is one of the tested faults

        self.server = Server(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def count(self, key):
        with self.lock:
            self.stats[key] += 1

    def choose(self):
        with self.lock:
            if self.rng.random() >= self.fault_rate:
                return "pass"
            return self.rng.choice(FAULTS)

    def uniform(self, a, b):
        with self.lock:
            return self.rng.uniform(a, b)

    def randrange(self, n):
        with self.lock:
            return self.rng.randrange(n)

    def remember(self, body):
        with self.lock:
            self.stash.append(body)
            del self.stash[:-24]

    def capture(self, *bodies):
        with self.lock:
            self.wire.extend(b for b in bodies if b)

    def reveals(self, markers):
        """Markers (bytes) that appear in anything that crossed this proxy."""
        with self.lock:
            return sorted({m.hex() for m in markers for body in self.wire if m in body})

    def old_request(self):
        with self.lock:
            return self.rng.choice(self.stash[:-1]) if len(self.stash) > 1 else None

    def set_target(self, port):
        self.target = f"http://127.0.0.1:{port}"

    def forward(self, method, path, body):
        request = urllib.request.Request(self.target + path, data=body, method=method)
        if body is not None:
            request.add_header("Content-Type", "application/octet-stream")
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return response.status, response.read(), response.headers.get("Content-Type", "")
        except urllib.error.HTTPError as error:
            return error.code, error.read(), "text/plain"
        except OSError:
            return None, b"", ""

    def close(self):
        self.server.shutdown()
        self.server.server_close()
