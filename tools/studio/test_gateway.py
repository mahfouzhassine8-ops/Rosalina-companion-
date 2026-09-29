import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from studio_gateway import handler_type, read_or_create_key, validate_payload
from http.server import ThreadingHTTPServer

class GatewayTests(unittest.TestCase):
    def payload(self):return dict(input='A fixed reply.',model='tts-1',voice='default',speed=1,response_format='pcm',stream_format='audio')
    def test_reply_only_contract(self):self.assertEqual(self.payload(),json.loads(validate_payload(json.dumps(self.payload()).encode())))
    def test_microphone_and_history_rejected(self):
        for field in ('audio','messages','history','system_prompt','url'):
            with self.subTest(field=field),self.assertRaises(ValueError):validate_payload(json.dumps({**self.payload(),field:'not allowed'}).encode())
    def test_bounds(self):
        for change in ({'input':'a'*601},{'speed':float('nan')},{'speed':True},{'response_format':'wav'},{'instructions':'x'*501}):
            with self.assertRaises(ValueError):validate_payload(json.dumps({**self.payload(),**change}).encode())
    def test_private_key_persists_without_embedding(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'key';one=read_or_create_key(p);self.assertEqual(one,read_or_create_key(p));self.assertGreaterEqual(len(one),24);self.assertEqual(0,p.stat().st_mode&0o077)
    def test_insecure_key_file_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'key';read_or_create_key(p);p.chmod(0o644)
            with self.assertRaises(ValueError):read_or_create_key(p)
    def test_admin_paths_and_missing_auth_are_not_forwarded(self):
        server=ThreadingHTTPServer(('127.0.0.1',0),handler_type('testkey-1234567890123456789012345',9));thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            for path,code in [('/system/set-env',404),('/api/settings',404),('/v1/audio/transcriptions',404),('/v1/audio/voices',401)]:
                con=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=2);con.request('GET',path);res=con.getresponse();self.assertEqual(code,res.status);res.read();con.close()
        finally:server.shutdown();server.server_close();thread.join()
if __name__=='__main__':unittest.main()
