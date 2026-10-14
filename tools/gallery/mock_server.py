"""Stand-in for the TV's phone-connection server, with sample data, so the phone app can be
screenshotted in CI for the UI gallery. Serves app/src/main/assets/web (pass that folder)."""
import json, os, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

WEB = sys.argv[1]
PAGE = open(os.path.join(WEB, 'index.html'), encoding='utf-8').read().replace('__TVX_KEY__', 'gallery')
ROOT = '/storage/emulated/0'
FILES = [
    {'name': n, 'path': ROOT + '/' + n, 'dir': d, 'size': s} for n, d, s in [
        ('DCIM', True, 0), ('Movies', True, 0), ('Pictures', True, 0), ('Music', True, 0), ('Download', True, 0),
        ('Documents', True, 0), ('Projects', True, 0),
        ('Family vacation.mp4', False, 2_340_000_000), ('Beach sunset.jpg', False, 4_200_000),
        ('Road trip playlist.m4a', False, 9_800_000), ('Recipes.pdf', False, 1_200_000),
        ('Backup.zip', False, 640_000_000), ('Woodruff Family.apk', False, 22_000_000), ('notes.dat', False, 4_096),
    ]
]
NOTES = [
    {'id': 'a', 'title': 'Groceries', 'body': 'Milk\nEggs\nCoffee beans', 'pinned': True, 'updated': 1},
    {'id': 'b', 'title': 'Wi-Fi for guests', 'body': 'Network: Woodruff-Guest', 'pinned': False, 'updated': 1},
]
PHONES = [
    {'id': 'p-blake', 'name': "Blake's iPhone", 'alerts': True},
    {'id': 'p-mom', 'name': "Mom's iPhone", 'alerts': True},
    {'id': 'p-kids', 'name': 'Kids iPad', 'alerts': False},
]
SETTINGS = {
    'notifyUrl': 'http://192.168.1.20:8080/api/notify?k=…',
    'google': {'clientId': '1234-example.apps.googleusercontent.com', 'hasSecret': True, 'connected': True,
               'redirectUri': 'https://blakew316.github.io/woodrufffiles/oauth.html'},
    'nest': {'project': '1a2b3c4d-5e6f-7a8b-9c0d', 'connected': True},
    'mail': [{'id': 'm1', 'email': 'family@icloud.com', 'provider': 'iCloud Mail'}],
    'providers': [{'id': 'ICLOUD', 'label': 'iCloud Mail', 'help': 'Create an app-specific password at account.apple.com.', 'custom': False}],
}
TYPES = {'.png': 'image/png', '.webmanifest': 'application/manifest+json'}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, body, mime='application/json'):
        if isinstance(body, (dict, list)):
            body = json.dumps(body)
        if isinstance(body, str):
            body = body.encode()
        self.send_response(200)
        self.send_header('Content-Type', mime)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == '/':
            return self.send(PAGE, 'text/html; charset=utf-8')
        ext = os.path.splitext(path)[1]
        if ext in TYPES and '/' not in path[1:]:
            return self.send(open(os.path.join(WEB, path[1:]), 'rb').read(), TYPES[ext])
        api = {
            '/api/roots': [{'name': 'Internal storage', 'path': ROOT}],
            '/api/list': {'path': ROOT, 'parent': None, 'files': FILES},
            '/api/type': {'field': {'id': 1, 'label': 'Search file names', 'secret': False, 'multiline': False, 'text': 'vacation'}},
            '/api/notes': {'notes': NOTES},
            '/api/phones': {'phones': PHONES},
            '/api/inbox': {'messages': []},
            '/api/settings': SETTINGS,
        }
        if path in api:
            return self.send(api[path])
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        n = int(self.headers.get('content-length') or 0)
        self.rfile.read(n)
        if urlparse(self.path).path == '/api/phone/hello':
            return self.send({'phone': {'id': 'p-blake', 'name': "Blake's iPhone", 'alerts': True, 'topic': 'woodruff-example'}, 'phones': PHONES})
        self.send({'ok': True})


ThreadingHTTPServer(('127.0.0.1', 8765), Handler).serve_forever()
