"""Stand-in for the TV's phone-connection server, with sample data, so the phone app can be
screenshotted in CI for the UI gallery. Serves app/src/main/assets/web (pass that folder).

It keeps changes in memory (notes, settings, mail accounts) like the TV would, serves a few nested
folders and two storages, and, with MOCK_LOG=<file>, appends every API request to that file as one
JSON line ({"method", "path", "query", "body"}) so tests can check what the page sent.
Test-only controls, never used by the gallery: POST /__mock/inbox {title, body, from} queues an
announcement for the phone, POST /__mock/crash {text} sets the "last crash", POST /__mock/field {field}
sets (or clears, with null) the TV text box the keyboard types into."""
import json, os, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

WEB = sys.argv[1]
LOG = os.environ.get('MOCK_LOG')
ROOT = '/storage/emulated/0'
USB = '/storage/1A2B-3C4D'


def entry(folder, name, is_dir=False, size=0, modified=1761700000000):
    return {'name': name, 'path': folder + '/' + name, 'dir': is_dir, 'size': size, 'modified': modified}


def folder(path, items):
    return [entry(path, n, d, s) for n, d, s in items]


TREE = {
    ROOT: folder(ROOT, [
        ('DCIM', True, 0), ('Movies', True, 0), ('Pictures', True, 0), ('Music', True, 0), ('Download', True, 0),
        ('Documents', True, 0), ('Projects', True, 0),
        ('Family vacation.mp4', False, 2_340_000_000), ('Beach sunset.jpg', False, 4_200_000),
        ('Road trip playlist.m4a', False, 9_800_000), ('Recipes.pdf', False, 1_200_000),
        ('Backup.zip', False, 640_000_000), ('Woodruff Family.apk', False, 22_000_000), ('notes.dat', False, 4_096),
    ]),
    ROOT + '/DCIM': folder(ROOT + '/DCIM', [('Camera', True, 0), ('Screenshots', True, 0)]),
    ROOT + '/DCIM/Camera': folder(ROOT + '/DCIM/Camera', [
        ('IMG_%04d.jpg' % (2100 + i), False, 2_400_000 + i * 131_000) for i in range(24)
    ] + [('VID_2026_0712.mp4', False, 412_000_000)]),
    ROOT + '/DCIM/Screenshots': [],
    ROOT + '/Movies': folder(ROOT + '/Movies', [
        ('Midnight Harbor (2024).mkv', False, 6_100_000_000), ('Paper Planes (2019).mp4', False, 2_900_000_000),
    ]),
    ROOT + '/Music': folder(ROOT + '/Music', [('Road trip', True, 0), ('Lullabies.m4a', False, 7_300_000)]),
    USB: folder(USB, [('Home movies', True, 0), ('Wedding.mov', False, 3_800_000_000), ('Taxes 2025.pdf', False, 880_000)]),
    USB + '/Home movies': folder(USB + '/Home movies', [('1998 Christmas.mp4', False, 1_100_000_000)]),
}
ROOTS = [{'name': 'Internal storage', 'path': ROOT}, {'name': 'USB drive', 'path': USB}]

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
    'notifyUrl': 'http://192.168.1.20:8080/api/notify?k=…&tvs=all',
    'shareUrl': 'http://192.168.1.20:8080/api/share?k=…',
    'google': {'clientId': '1234-example.apps.googleusercontent.com', 'hasSecret': True, 'connected': True,
               'redirectUri': 'https://blakew316.github.io/woodrufffiles/oauth.html'},
    'nest': {'project': '1a2b3c4d-5e6f-7a8b-9c0d', 'connected': True, 'doorbell': '', 'doorbellStatus': '',
             'person': True, 'motion': False},
    'tmdb': True,
    'weather': 'Austin, TX',
    'version': '2.0.128',
    'crash': '',
    'awayUrl': 'http://100.101.102.103:8080/?k=…',
    'mail': [{'id': 'm1', 'email': 'family@icloud.com', 'provider': 'iCloud Mail'}],
    'providers': [
        {'id': 'ICLOUD', 'label': 'iCloud Mail', 'help': 'Create an app-specific password at account.apple.com.', 'custom': False},
        {'id': 'GMAIL', 'label': 'Gmail', 'help': 'Turn on 2-Step Verification, then create an app password at myaccount.google.com.', 'custom': False},
        {'id': 'CUSTOM', 'label': 'Other (IMAP)', 'help': 'Use the IMAP server and password from your mail provider.', 'custom': True},
    ],
}
FIELD = {'field': {'id': 1, 'label': 'Search file names', 'secret': False, 'multiline': False, 'text': 'vacation'}}
INBOX = []
TVS = {'tvs': [
    {'id': 'this', 'name': 'Living Room', 'online': True, 'onScreen': True,
     'playing': {'title': 'Midnight Harbor', 'app': 'Woodruff Family', 'playing': True, 'position': 2710000, 'duration': 6840000}},
    {'id': 'b', 'name': 'Kids Room', 'online': True, 'onScreen': False,
     'playing': {'title': 'Backyard Science · S2 E4', 'app': 'YouTube', 'playing': True, 'position': 410000, 'duration': 1320000}},
    {'id': 'c', 'name': 'Bedroom', 'online': False, 'onScreen': False, 'playing': None},
]}
TYPES = {'.png': 'image/png', '.webmanifest': 'application/manifest+json'}


def log(method, url, body):
    if not LOG:
        return
    u = urlparse(url)
    try:
        data = json.loads(body) if body else None
    except ValueError:
        data = '<%d bytes>' % len(body)
    with open(LOG, 'a') as f:
        f.write(json.dumps({'t': time.time(), 'method': method, 'path': u.path, 'query': parse_qs(u.query), 'body': data}) + '\n')


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, body, mime='application/json', status=200, headers=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body)
        if isinstance(body, str):
            body = body.encode()
        self.send_response(status)
        self.send_header('Content-Type', mime)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def screen(self):
        # The TV tab shows the gallery's own TV Home screenshot as "what's on the TV".
        shot = os.path.join(os.environ.get('GALLERY_RAW', '../../build/gallery'), 'tv-home-dark.png')
        if os.path.exists(shot):
            return self.send(open(shot, 'rb').read(), 'image/png')
        return self.send({'message': 'Woodruff Family isn\'t on the TV screen right now.'}, status=409)

    def do_GET(self):
        u = urlparse(self.path)
        path, query = u.path, parse_qs(u.query)
        if path.startswith('/api/') and path not in ('/api/screen', '/api/tvs/screen', '/api/type', '/api/inbox', '/api/tvs'):
            log('GET', self.path, b'')
        if path == '/':
            # Read on every request, so an edited page shows up without restarting the server.
            page = open(os.path.join(WEB, 'index.html'), encoding='utf-8').read().replace('__TVX_KEY__', 'gallery')
            return self.send(page, 'text/html; charset=utf-8')
        ext = os.path.splitext(path)[1]
        if ext in TYPES and '/' not in path[1:]:
            return self.send(open(os.path.join(WEB, path[1:]), 'rb').read(), TYPES[ext])
        if path == '/api/screen':
            return self.screen()
        if path == '/api/tvs/screen':
            if query.get('id', [''])[0] == 'c':
                return self.send({'message': "Bedroom isn't showing Woodruff Family, or is off."}, status=409)
            return self.screen()
        if path == '/api/list':
            where = query.get('path', [ROOT])[0].rstrip('/') or ROOT
            if where not in TREE:
                return self.send('Can\'t read folder', 'text/plain', 500)
            parent = where.rsplit('/', 1)[0]
            in_root = any(where != r['path'] and where.startswith(r['path'] + '/') for r in ROOTS)
            return self.send({'path': where, 'parent': parent if in_root else None, 'files': TREE[where]})
        if path == '/api/download':
            name = query.get('path', ['file'])[0].rsplit('/', 1)[-1]
            return self.send(b'sample file', 'application/octet-stream', headers={'Content-Disposition': 'attachment; filename="%s"' % name})
        if path == '/api/inbox':
            messages = INBOX[:]
            del INBOX[:]
            return self.send({'messages': messages})
        api = {
            '/api/roots': ROOTS,
            '/api/type': FIELD,
            '/api/notes': {'notes': sorted(NOTES, key=lambda n: -n['updated'])},
            '/api/phones': {'phones': PHONES},
            '/api/settings': SETTINGS,
            '/api/tvs': TVS,
        }
        if path in api:
            return self.send(api[path])
        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        n = int(self.headers.get('content-length') or 0)
        raw = self.rfile.read(n)
        u = urlparse(self.path)
        path, query = u.path, parse_qs(u.query)
        if not path.startswith('/__mock/'):
            log('POST', self.path, raw if path != '/api/upload' else b'x' * len(raw))
        try:
            body = json.loads(raw) if raw and path != '/api/upload' else {}
        except ValueError:
            body = {}
        if path == '/__mock/inbox':
            INBOX.append({'title': body.get('title', 'Dinner\'s ready'), 'body': body.get('body', ''), 'from': body.get('from', "Mom's iPhone")})
            return self.send({'ok': True})
        if path == '/__mock/crash':
            SETTINGS['crash'] = body.get('text', '')
            return self.send({'ok': True})
        if path == '/__mock/field':
            FIELD['field'] = body.get('field')
            return self.send({'ok': True})
        if path == '/api/phone/hello':
            phone = {'id': body.get('id', 'p-blake'), 'name': body.get('name', "Blake's iPhone"),
                     'alerts': body.get('alerts', True), 'topic': 'woodruff-example'}
            return self.send({'phone': phone, 'phones': PHONES})
        if path == '/api/notes':
            note = next((x for x in NOTES if body.get('id') and x['id'] == body['id']), None)
            if note is None:
                note = {'id': 'n%d' % int(time.time() * 1000), 'pinned': False}
                NOTES.append(note)
            note.update({'title': body.get('title', ''), 'body': body.get('body', ''), 'updated': int(time.time() * 1000)})
            if 'pinned' in body:
                note['pinned'] = bool(body['pinned'])
            return self.send({'note': note})
        if path == '/api/notes/delete':
            NOTES[:] = [x for x in NOTES if x['id'] != body.get('id')]
            return self.send({'ok': True})
        if path == '/api/announce':
            return self.send({'ok': True, 'phones': len(body.get('phones') or []), 'tvs': 3 if body.get('tv', True) else 0})
        if path == '/api/settings/weather':
            SETTINGS['weather'] = body.get('place', '')
        elif path == '/api/settings/nest':
            for k in ('project', 'doorbell', 'person', 'motion'):
                if k in body:
                    SETTINGS['nest'][k] = body[k]
        elif path == '/api/settings/google':
            SETTINGS['google']['clientId'] = body.get('client_id', '')
        elif path == '/api/settings/tmdb':
            SETTINGS['tmdb'] = bool(body.get('key'))
        elif path == '/api/mail':
            label = next((p['label'] for p in SETTINGS['providers'] if p['id'] == body.get('provider')), 'Mail')
            SETTINGS['mail'].append({'id': 'm%d' % int(time.time() * 1000), 'email': body.get('email', ''), 'provider': label})
        elif path == '/api/mail/delete':
            SETTINGS['mail'] = [m for m in SETTINGS['mail'] if m['id'] != body.get('id')]
        elif path == '/api/crash/clear':
            SETTINGS['crash'] = ''
        if path.startswith('/api/settings') or path in ('/api/mail', '/api/mail/delete', '/api/crash/clear'):
            return self.send(SETTINGS)  # like the TV: settings changes answer with the full settings
        if path == '/api/upload':
            return self.send({'ok': True, 'name': query.get('name', [''])[0]})
        self.send({'ok': True})


ThreadingHTTPServer(('127.0.0.1', 8765), Handler).serve_forever()
