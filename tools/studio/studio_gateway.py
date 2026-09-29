#!/usr/bin/env python3
"""Speech-only gateway for a separately installed local VoiceStudio instance.

Standard library only. No models, inference, administration routes or microphone upload.
Plain HTTP is loopback-only; a private HTTPS reverse proxy can expose this limited service.
"""
from __future__ import annotations
import argparse
import hmac
import http.client
import http.server
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import secrets
import socket
import ssl
import threading
import time
from urllib.parse import urlsplit

MAX_AUDIO = 2_880_000

def validate_payload(raw: bytes) -> bytes:
    obj = json.loads(raw)
    allowed = {'input', 'model', 'voice', 'instructions', 'speed', 'response_format', 'stream_format'}
    if not isinstance(obj, dict) or set(obj) - allowed:
        raise ValueError('Unsupported speech fields')
    if not isinstance(obj.get('input'), str) or not 1 <= len(obj['input']) <= 600:
        raise ValueError('Invalid reply clause')
    for field, limit in [('model', 120), ('voice', 160)]:
        value = obj.get(field, '')
        if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_.:/-]{1,%d}' % limit, value):
            raise ValueError('Invalid engine or voice ID')
    if obj.get('response_format') != 'pcm' or obj.get('stream_format') != 'audio':
        raise ValueError('Only streamed PCM is allowed')
    speed = obj.get('speed', 1)
    if isinstance(speed, bool) or not isinstance(speed, (int, float)) or not math.isfinite(speed) or not .8 <= speed <= 1.2:
        raise ValueError('Invalid speed')
    if 'instructions' in obj and (not isinstance(obj['instructions'], str) or len(obj['instructions']) > 500):
        raise ValueError('Invalid style instructions')
    return json.dumps(obj, ensure_ascii=False, separators=(',', ':')).encode('utf-8')

def read_or_create_key(path: Path) -> str:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not path.exists():
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as f:
            f.write(secrets.token_urlsafe(32) + '\n')
    if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077:
        raise ValueError('Gateway key file must be a regular private file with mode 600')
    key = path.read_text().strip()
    if not 24 <= len(key) <= 512 or any(ord(c) < 33 or ord(c) > 126 for c in key):
        raise ValueError('Invalid gateway key')
    return key

def handler_type(key: str, upstream_port: int):
    capacity = threading.BoundedSemaphore(1)
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        server_version = 'RosalinaStudioGateway/1'
        def setup(self):
            super().setup(); self.connection.settimeout(8)
        def log_message(self, *_):
            pass  # Do not persist request text, headers, keys, addresses or response audio.
        def error(self, code: int):
            body = b'{"error":"Studio request unavailable"}'
            self.send_response(code); self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body))); self.send_header('Cache-Control', 'no-store')
            self.send_header('Connection', 'close'); self.end_headers(); self.close_connection = True
            try: self.wfile.write(body)
            except OSError: pass
        def permitted(self):
            expected = 'Bearer ' + key
            candidate = self.headers.get('Authorization', '')
            if not hmac.compare_digest(candidate.encode(), expected.encode()):
                self.error(401); return False
            return True
        def do_GET(self):
            if self.path != '/v1/audio/voices': self.error(404); return
            if self.permitted(): self.forward(None, False)
        def do_POST(self):
            if self.path != '/v1/audio/speech': self.error(404); return
            if not self.permitted(): return
            if self.headers.get('Transfer-Encoding') or self.headers.get_content_type() != 'application/json':
                self.error(400); return
            lengths = self.headers.get_all('Content-Length') or []
            try:
                if len(lengths) != 1: raise ValueError()
                n = int(lengths[0])
                if not 1 <= n <= 8192: raise ValueError()
                raw = self.rfile.read(n)
                if len(raw) != n: raise ValueError()
                body = validate_payload(raw)
            except (ValueError, OSError): self.error(400); return
            if not capacity.acquire(blocking=False): self.error(429); return
            try: self.forward(body, True)
            finally: capacity.release()
        def forward(self, body: bytes | None, speech: bool):
            con = http.client.HTTPConnection('127.0.0.1', upstream_port, timeout=5)
            headers = {'Accept': 'audio/pcm' if speech else 'application/json', 'Accept-Encoding': 'identity', 'Content-Type': 'application/json'}
            # Distinct backend credentials stay on the Mac; clients get only the gateway key.
            backend_key = os.environ.get('OMNIVOICE_API_KEY', '')
            backend_pin = os.environ.get('OMNIVOICE_SHARE_PIN', '')
            if backend_key: headers['Authorization'] = 'Bearer ' + backend_key
            if backend_pin: headers['X-OmniVoice-Pin'] = backend_pin
            sent_headers = False
            try:
                con.request('POST' if speech else 'GET', self.path, body, headers)
                response = con.getresponse()
                if response.status != 200:
                    self.error(response.status if response.status in (400, 401, 403, 429) else 502); return
                expected_type = 'audio/pcm' if speech else 'application/json'
                if response.getheader('Content-Type', '').split(';')[0].strip().lower() != expected_type:
                    self.error(502); return
                self.send_response(200); self.send_header('Content-Type', expected_type)
                self.send_header('Transfer-Encoding', 'chunked'); self.send_header('Cache-Control', 'no-store')
                self.send_header('Connection', 'close'); self.end_headers(); sent_headers = True; self.close_connection = True
                total = 0; start = time.monotonic()
                while True:
                    chunk = response.read1(4096)
                    if not chunk: break
                    total += len(chunk)
                    if total > (MAX_AUDIO if speech else 131072) or time.monotonic() - start > 90:
                        raise ValueError('Response limit exceeded')
                    self.wfile.write(f'{len(chunk):x}\r\n'.encode() + chunk + b'\r\n'); self.wfile.flush()
                if total == 0 or (speech and total % 2): raise ValueError('Truncated PCM')
                self.wfile.write(b'0\r\n\r\n'); self.wfile.flush()
            except (OSError, ValueError, http.client.HTTPException):
                if not sent_headers: self.error(502)
                # After speech started, close without a successful chunk terminator: do not fabricate completion.
                self.close_connection = True
            finally: con.close()
    return Handler

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=3902)
    parser.add_argument('--upstream-port', type=int, default=3900)
    parser.add_argument('--key-file', type=Path, default=Path.home()/'.config/rosalina-studio/gateway-key')
    parser.add_argument('--show-key', action='store_true', help='Print the private gateway key for deliberate phone pairing, then exit')
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 1 <= args.upstream_port <= 65535 or args.port == args.upstream_port:
        parser.error('Use distinct valid local ports')
    key = read_or_create_key(args.key_file)
    if args.show_key: print(key); return
    server = http.server.ThreadingHTTPServer(('127.0.0.1', args.port), handler_type(key, args.upstream_port))
    server.daemon_threads = True
    print(f'Rosalina speech-only gateway listening on loopback port {args.port}. Keep VoiceStudio open; pair through a private HTTPS endpoint. Ctrl+C stops the gateway.', flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()
if __name__ == '__main__': main()
