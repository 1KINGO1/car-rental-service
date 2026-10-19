"""Local laboratory notification receiver. Stores no credentials; logs genuine AM payloads."""
import json
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock

events = []
lock = Lock()


class Receiver(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/health", "/events"):
            self.send_error(404)
            return
        with lock:
            body = json.dumps(events if self.path == "/events" else {"status": "UP"}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path not in ("/alerts", "/critical"):
            self.send_error(404)
            return
        size = int(self.headers.get("Content-Length", 0))
        if size <= 0 or size > 1024 * 1024:
            self.send_error(413)
            return
        try:
            payload = json.loads(self.rfile.read(size))
        except (ValueError, UnicodeDecodeError):
            self.send_error(400)
            return
        event = {"receivedAt": datetime.now(timezone.utc).isoformat(), "path": self.path, "payload": payload}
        with lock:
            events.append(event)
            del events[:-200]
        print(json.dumps(event, ensure_ascii=False), flush=True)
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()


ThreadingHTTPServer(("0.0.0.0", 8080), Receiver).serve_forever()
