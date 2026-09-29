#!/usr/bin/env python3
"""Synthetic-audio API contract fixture only; not VoiceStudio and not a voice-quality test."""
import argparse, http.server, json, math, ssl, struct, threading, time
p=argparse.ArgumentParser();p.add_argument('--cert',required=True);p.add_argument('--key',required=True);p.add_argument('--port',type=int,default=8443);args=p.parse_args()
class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*args): pass  # Never log headers, credentials, reply text or URLs.
    def auth(self):
        return self.headers.get('Authorization')=='Bearer test-studio-key' and self.headers.get('X-OmniVoice-Pin')=='123456'
    def do_GET(self):
        if self.path!='/v1/audio/voices' or not self.auth():self.send_error(401);return
        body=b'{"voices":[{"id":"default"}]}'
        self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def do_POST(self):
        if self.path!='/v1/audio/speech' or not self.auth():self.send_error(401);return
        n=int(self.headers.get('Content-Length','0'))
        if not 1<=n<=8192:self.send_error(400);return
        request=json.loads(self.rfile.read(n))
        expected={'input','model','voice','response_format','stream_format','speed','instructions'}
        if set(request)-expected or request.get('response_format')!='pcm' or request.get('stream_format')!='audio':self.send_error(400);return
        self.send_response(200);self.send_header('Content-Type','audio/pcm');self.send_header('Transfer-Encoding','chunked');self.send_header('Cache-Control','no-store');self.end_headers()
        tone=b''.join(struct.pack('<h',int(math.sin(i*2*math.pi*440/24000)*6000)) for i in range(24000))
        try:
            # Odd chunk sizes exercise network framing independently from PCM sample alignment.
            for start in range(0,len(tone),4001):
                chunk=tone[start:start+4001];self.wfile.write(f'{len(chunk):x}\r\n'.encode()+chunk+b'\r\n');self.wfile.flush();time.sleep(.04)
            self.wfile.write(b'0\r\n\r\n');self.wfile.flush()
        except (BrokenPipeError,ConnectionResetError):pass
server=http.server.ThreadingHTTPServer(('0.0.0.0',args.port),Handler)
ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);ctx.load_cert_chain(args.cert,args.key)
server.socket=ctx.wrap_socket(server.socket,server_side=True);server.serve_forever()
