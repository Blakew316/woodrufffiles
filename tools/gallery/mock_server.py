"""Stand-in for the TV's phone-connection server, with sample data, so the phone app can be
screenshotted in CI for the UI gallery. Serves app/src/main/assets/web (pass that folder).

It keeps changes in memory (settings, mail accounts, Family Memories) like the TV would, serves a few nested
folders and two storages, and, with MOCK_LOG=<file>, appends every API request to that file as one
JSON line ({"method", "path", "query", "body"}) so tests can check what the page sent.
Family Memories: sample photos and videos, each picture drawn here (a landscape, in SVG); what the phone adds is
kept and served back as it was sent, and GET /__mock/memories lists what was added (name, taken, bytes and, for a
JPEG, its size) so tests can check what the page sent.
Test-only controls, never used by the gallery: POST /__mock/inbox {title, body, from} queues an
announcement for the phone, POST /__mock/crash {text} sets the "last crash", POST /__mock/field {field}
sets (or clears, with null) the TV text box the keyboard types into, and POST /__mock/set {settings, tvs, phones}
changes the sample settings (merged), the linked TVs or the phones (replaced); POST /__mock/reset puts the TVs back.
Remote keys are checked like the TV does; the Den TV has no Netflix, so a test can see that error.
TV health: four TVs, as in the TV's own health gallery: Living Room (this TV, all good), Den (needs attention: its
Drive sign-in ended and the last update didn't install), Kitchen (off for two hours) and Bedroom (reset: it turns down
its link). /api/tvs/health answers each like a TV would (409 with the last report for Kitchen and Bedroom);
/api/tvs/relink takes the code 123456 (then Bedroom is linked again and all good).
GET /api/diagnostics answers with a sample Smoothness Report (and X-Recording-Left, like the TV); POST
/api/diagnostics/record starts a 3-minute "recording" here, so the phone page's countdown can be seen.
Watch: 64 movies and 4 shows with the TV gallery's sample names, Continue watching, every
picture drawn here in SVG and served from /api/poster (so the gallery never needs the network), and every Watch route
(home with an ETag, the grids with sort, filters, search and paging, a title's details, a season's episodes, search,
starting something on a TV or on every TV, the controls, Mark as watched and Remove from Continue watching).
POST /__mock/set {library: "ready" | "offline" | "unreachable" | "expired" | "expiredEmpty" | "notSignedIn" | "notSetUp" |
"empty" | "loading" | "keysRejected" | "down"} picks the library's state ("down": the TV doesn't answer), and
{watch: {"started": false}} makes starts wait on the TV (the "Ready on…" alert); /__mock/reset puts them back.
Sign In from the phone: settings.google.state and settings.nest.state ("signedIn", "expired", "keysRejected",
"notSignedIn", "notSetUp") give Connections their words; /__mock/set merges settings one level deep, so
{settings: {google: {state: "expired"}}} is enough. POST /api/signin answers with a sample attempt whose authUrl is
GET /__mock/google (a plain page); GET /api/signin/status answers "pending" until /__mock/set {signin: …} says
"done" (then signed in, and Connections say so), "weekly" (signed in, and Google ends it every week), "finishing",
"failed", "cancelled", "replaced" or "expired"; {signin: "keys" | "project" | "down"} makes POST /api/signin answer
that the keys or the cameras project come first, or not at all. {thisSignIns: {drive: "expired"}} gives Living Room's
own report those sign-in states, and {signinTv: "…"} the TV's name the sheet shows. Inbox messages keep their id and link."""
import json, math, os, sys, time
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

PHOTOS = ROOT + '/Pictures/Google Photos'
VIDEOS = ROOT + '/Movies/Google Photos'
DAY = 86_400_000


def _memories():
    """30 photos and 4 videos, a few days apart, newest first (as the TV lists them)."""
    newest = int(time.mktime((2026, 10, 26, 16, 42, 0, 0, 0, -1)) * 1000)
    out = []
    for i in range(34):
        video = i % 9 == 4
        name = ('VID_%04d.mov' if video else 'IMG_%04d.jpg') % (4120 - i)
        out.append({'path': (VIDEOS if video else PHOTOS) + '/' + name, 'name': name, 'video': video,
                    'taken': newest - i * 3 * DAY - (i % 5) * 3_600_000, 'size': 840_000 + i * 17_000, 'v': 1761500000000 + i})
    return out


MEMORIES = _memories()
ADDED = {}      # path -> (bytes, content type): what the phone sent, served back as it was
MEM_LOG = []    # what the phone added, for tests
PALETTES = [('#ffb36b', '#ff6f61', '#2f4858', '#4f6d7a'), ('#9be7ff', '#4ea8de', '#2d6a4f', '#52b788'),
            ('#ffd6a5', '#f08080', '#6d597a', '#b56576'), ('#caf0f8', '#90e0ef', '#0077b6', '#023e8a'),
            ('#fde68a', '#f59e0b', '#7c2d12', '#b45309'), ('#e0c3fc', '#8ec5fc', '#3a506b', '#5bc0be')]


def landscape(i, w, h):
    """A sample photo: sky, sun and two hills, in one of a few palettes; portrait for every third one."""
    a, b, c, d = PALETTES[i % len(PALETTES)]
    sx, sy, r = w * (0.25 + (i * 37 % 50) / 100), h * 0.32, min(w, h) * 0.09
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">'
            '<defs><linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient></defs>'
            '<rect width="%d" height="%d" fill="url(#s)"/><circle cx="%.0f" cy="%.0f" r="%.0f" fill="#fff8e7" opacity=".9"/>'
            '<path d="M0 %.0f Q %.0f %.0f %.0f %.0f T %d %.0f V %d H 0 Z" fill="%s"/>'
            '<path d="M0 %.0f Q %.0f %.0f %.0f %.0f T %d %.0f V %d H 0 Z" fill="%s"/></svg>') % (
        w, h, w, h, a, b, w, h, sx, sy, r,
        h * 0.62, w * 0.25, h * 0.5, w * 0.5, h * 0.64, w, h * 0.6, h, d,
        h * 0.78, w * 0.3, h * 0.66, w * 0.6, h * 0.8, w, h * 0.74, h, c)


def jpeg_size(b):
    """A JPEG's width and height (from its frame header); None for anything else."""
    if b[:2] != b'\xff\xd8':
        return None
    i = 2
    while i + 9 < len(b) and b[i] == 0xFF:
        if b[i + 1] in (0xC0, 0xC1, 0xC2):
            return [int.from_bytes(b[i + 7:i + 9], 'big'), int.from_bytes(b[i + 5:i + 7], 'big')]
        i += 2 + int.from_bytes(b[i + 2:i + 4], 'big')
    return None


def memories_json():
    return {'items': MEMORIES, 'folder': PHOTOS, 'fit': [1920, 1080], 'slideshow': True}
PHONES = [
    {'id': 'p-blake', 'name': "Blake's iPhone", 'alerts': True},
    {'id': 'p-mom', 'name': "Mom's iPhone", 'alerts': True},
    {'id': 'p-kids', 'name': 'Kids iPad', 'alerts': False},
]
SETTINGS = {
    'notifyUrl': 'http://192.168.1.20:8080/api/notify?k=…&tvs=all',
    'shareUrl': 'http://192.168.1.20:8080/api/share?k=…',
    'google': {'clientId': '1234-example.apps.googleusercontent.com', 'hasSecret': True, 'connected': True, 'state': 'signedIn',
               'redirectUri': 'https://blakew316.github.io/woodrufffiles/oauth.html'},
    'nest': {'project': '1a2b3c4d-5e6f-7a8b-9c0d', 'connected': True, 'state': 'signedIn', 'doorbell': '', 'doorbellStatus': '',
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
SETTINGS_START = json.loads(json.dumps(SETTINGS))
ACCOUNT_WORDS = {'signedIn': ('Signed in', 'Connected'), 'expired': ('Sign-in ended', 'Sign-in ended'),
                 'keysRejected': ('Keys not accepted', 'Keys not accepted'), 'notSignedIn': ('Not signed in', 'Not connected'),
                 'notSetUp': ('Not set up', 'Not connected')}


def settings_json():
    """The settings as the TV answers them: each sign-in's words and "connected" follow its state, as on the TV."""
    out = json.loads(json.dumps(SETTINGS))
    for key, i in (('google', 0), ('nest', 1)):
        st = out[key].get('state', 'signedIn')
        out[key]['state'] = st
        out[key]['text'] = ACCOUNT_WORDS[st][i]
        out[key]['connected'] = st in ('signedIn', 'keysRejected')
    out['google']['hasSecret'] = out['google']['state'] != 'notSetUp'
    return out


SIGNIN = {'mode': None, 'n': 0, 'grant': 'drive', 'tv': 'Living Room'}


def signin_status(grant):
    """/api/signin/status, as the mock state says: a sign-in that's done shows in Settings and the reports too."""
    mode = SIGNIN['mode']
    if mode in ('done', 'weekly'):
        if grant == 'drive':
            SETTINGS['google']['state'] = 'signedIn'
        elif grant == 'cameras':
            SETTINGS['nest']['state'] = 'signedIn'
        THIS_SIGNINS.pop(grant, None)
        return {'status': 'signedIn', 'message': None, 'weekly': mode == 'weekly'}
    if mode == 'failed':
        return {'status': 'failed', 'message': 'Living Room couldn’t reach Google.', 'weekly': False}
    if mode in ('finishing', 'cancelled', 'replaced', 'expired'):
        return {'status': mode, 'message': None, 'weekly': False}
    return {'status': 'pending', 'message': None, 'weekly': False}
THIS_SIGNINS = {}   # /__mock/set {thisSignIns}: Living Room's own sign-in states
FIELD = {'field': {'id': 1, 'label': 'Search file names', 'secret': False, 'multiline': False, 'text': 'vacation'}}
INBOX = []
MIN, HOUR = 60_000, 3_600_000
TV_STATE = {'relinked': False, 'playing': {'this': True, 'b': True}}
TVS_SET = {'tvs': None}   # /__mock/set {tvs}: replaces the list until /__mock/reset


def _now():
    return int(time.time() * 1000)


def _health(status, count=0, first=None):
    return {'status': status, 'count': count, 'first': first}


def tvs_json():
    """The four TVs, as /api/tvs answers (seen times are relative to now)."""
    if TVS_SET['tvs'] is not None:
        return {'tvs': TVS_SET['tvs']}
    now, p = _now(), TV_STATE['playing']
    bedroom_ok = TV_STATE['relinked']
    ended = [g for g, st in THIS_SIGNINS.items() if st == 'expired']
    return {'tvs': [
        {'id': 'this', 'name': 'Living Room', 'online': True, 'onScreen': True, 'version': '2.0.171', 'seen': None, 'link': 'ok',
         'health': _health('attention', len(ended), 'Google Drive’s sign-in ended' if 'drive' in ended else 'A sign-in ended') if ended else _health('good'),
         'playing': {'title': 'Midnight Harbor', 'app': 'Woodruff Family', 'playing': p['this'], 'position': 2710000, 'duration': 6840000,
                     'can': ['playpause', 'back10', 'fwd30', 'next'], 'key': 'drive:m-harbor'}},
        {'id': 'b', 'name': 'Den', 'online': True, 'onScreen': False, 'version': '2.0.171', 'seen': now, 'link': 'ok',
         'health': _health('attention', 2, 'Google Drive’s sign-in ended'),
         'playing': {'title': 'Backyard Science · S2 E4', 'app': 'YouTube', 'playing': p['b'], 'position': 410000, 'duration': 1320000, 'can': ['playpause']}},
        {'id': 'c', 'name': 'Kitchen', 'online': False, 'onScreen': False, 'version': '2.0.171', 'seen': now - 2 * HOUR, 'link': 'ok',
         'health': _health('unknown'), 'playing': None},
        {'id': 'd', 'name': 'Bedroom', 'online': bedroom_ok, 'onScreen': False, 'version': '2.0.171', 'seen': now if bedroom_ok else now - 26 * HOUR,
         'link': 'ok' if bedroom_ok else 'refused', 'health': _health('good' if bedroom_ok else 'unknown'), 'playing': None},
    ]}


def _clock(ms):
    return time.strftime('%I:%M %p', time.localtime(ms / 1000)).lstrip('0')


def _date(ms):
    return time.strftime('%b ', time.localtime(ms / 1000)) + str(time.localtime(ms / 1000).tm_mday)


def _moment(ms):
    """As the TV writes a moment: today the time, then "yesterday", then the date."""
    a, b = time.localtime(ms / 1000), time.localtime()
    if (a.tm_year, a.tm_yday) == (b.tm_year, b.tm_yday):
        return _clock(ms)
    if (_now() - ms) < 2 * 24 * HOUR and a.tm_yday == b.tm_yday - 1:
        return 'yesterday'
    return _date(ms)


SIGNIN_WORDS = {'signedIn': ('Signed in', 'Connected'), 'offline': ('Offline', 'Offline'), 'unreachable': ('Can’t reach Google', 'Can’t reach Google'),
                'expired': ('Sign-in ended', 'Sign-in ended'), 'keysRejected': ('Keys not accepted', 'Keys not accepted'),
                'notSignedIn': ('Not signed in', 'Not connected'), 'notSetUp': ('Not set up', 'Not connected')}
CRASH_REPORT = ('Version 2.0.170 · {when} · thread main\njava.lang.IllegalStateException: The player was released\n'
                '\tat androidx.media3.exoplayer.ExoPlayerImpl.verifyApplicationThread(ExoPlayerImpl.java:2861)\n'
                '\tat com.woodruff.tvexplorer.player.PlayerActivity.onStop(PlayerActivity.kt:410)\n'
                '\tat android.app.Activity.performStop(Activity.java:8501)\n\tat android.app.ActivityThread.callActivityOnStop(ActivityThread.java:5208)')


def health_report(name, at, attention=(), states=None, doorbell=('watching', 'Watching', 'Doorbell presses pop up on this TV, even when the other TVs are off.'),
                  updates=('upToDate', 'Up to date', False), last_try=None, crash_at=None, closed=('Nothing unusual', False, None), android=31):
    """A TV's /api/health report, every word as the TV's own builder writes it."""
    states = states or {}
    sign_ins = {}
    for g in ('drive', 'photos', 'calendar', 'cameras'):
        st = states.get(g, 'signedIn')
        sign_ins[g] = {'state': st, 'text': SIGNIN_WORDS[st][0 if g == 'drive' else 1], 'attention': st in ('expired', 'keysRejected')}
        if st == 'expired':
            sign_ins[g]['since'] = at - 24 * HOUR
    sign_ins['doorbell'] = {'state': doorbell[0], 'text': doorbell[1], 'attention': doorbell[0] == 'failing', 'detail': doorbell[2]}
    items = [dict(zip(('id', 'words', 'detail', 'fix'), a[:4]), **({'grant': a[4]} if len(a) > 4 else {})) for a in attention]
    words = [i['words'] for i in items]
    frag = [w if w.split(' ')[0] in ('Google', 'Woodruff', 'Android') else w[0].lower() + w[1:] for w in words]
    sentence = 'Everything’s working.' if not frag else (frag[0] if len(frag) == 1 else ', '.join(frag[:-1]) + ', and ' + frag[-1])
    sentence = sentence[0].upper() + sentence[1:] + ('' if not frag else '.')
    crash = None
    if crash_at:
        crash = {'at': crash_at, 'text': ('Yesterday, ' + _clock(crash_at)) if _moment(crash_at) == 'yesterday' else
                 (_clock(crash_at) if _moment(crash_at) == _clock(crash_at) else _date(crash_at) + ', ' + _clock(crash_at)),
                 'attention': _now() - crash_at < 7 * 24 * HOUR, 'summary': 'IllegalStateException in the player',
                 'lines': 'Version 2.0.170 · ' + _date(crash_at) + ', ' + _clock(crash_at) + ' · thread main\njava.lang.IllegalStateException: The player was released\nat androidx.media3.exoplayer.ExoPlayerImpl.verifyApplicationThread(ExoPlayerImpl.java:2861)',
                 'report': CRASH_REPORT.format(when=_date(crash_at) + ', ' + _clock(crash_at))}
    return {
        'id': name.lower().replace(' ', '-'), 'name': name, 'at': at, 'android': android, 'version': '2.0.171',
        'status': 'attention' if items else 'good', 'sentence': sentence, 'attention': items,
        'installedBy': {'summary': 'Installed fresh Oct 12, 9:10 AM · updated Nov 1, 3:02 AM by Woodruff itself', 'by': 'Woodruff itself',
                        'firstInstall': 1760278200000, 'lastUpdate': 1761984120000},
        'updates': {'step': updates[0], 'text': updates[1], 'attention': updates[2], 'auto': True, 'testTv': False},
        'lastUpdateTry': last_try,
        'installPermission': {'allowed': True, 'text': 'Allowed', 'attention': False},
        'signIns': sign_ins,
        'lastCrash': crash,
        'closedByAndroid': {'text': closed[0], 'attention': closed[1], 'detail': closed[2]} if android >= 30 else None,
        'freeSpace': {'free': 13314398618, 'text': '12.4 GB free', 'attention': False},
        'keys': {'text': 'All saved', 'googleClientId': True, 'googleClientSecret': True, 'camerasProject': True, 'tmdb': True},
        'uptime': 2 * 24 * HOUR + 3 * HOUR,
    }


THROUGH = ('through', 'Through Living Room', 'This TV hears the doorbell from Living Room while that TV is on. Connect the cameras on this TV to hear it on its own.')


def tv_health(tv_id):
    """(status, body) for /api/tvs/health?id=…, as the hub answers."""
    now = _now()
    if tv_id == 'this':
        low = now - 11 * HOUR
        ended = [g for g in ('drive', 'photos', 'calendar', 'cameras') if THIS_SIGNINS.get(g) == 'expired']
        names = {'drive': 'Google Drive’s', 'photos': 'Google Photos’', 'calendar': 'Google Calendar’s', 'cameras': 'The cameras’'}
        where = {'drive': 'open Streaming and scan the code with your phone.', 'photos': 'Settings → Accounts → Google Photos.',
                 'calendar': 'Settings → Accounts → Google Calendar.', 'cameras': 'open the Command Center and scan the code with your phone.'}
        return 200, health_report('Living Room', now, attention=[
            ('signin.' + g, names[g] + ' sign-in ended', 'Google ended this sign-in. On Living Room: ' + where[g], 'signin', g) for g in ended
        ], states=dict(THIS_SIGNINS), closed=('Low memory · ' + _moment(low), False, _date(low) + ', ' + _clock(low)))
    if tv_id == 'b':
        r = health_report('Den', now - 3 * MIN, attention=[
            ('signin.drive', 'Google Drive’s sign-in ended', 'Google ended this sign-in. On Den: open Streaming and scan the code with your phone.', 'signin', 'drive'),
            ('update.failed', 'The last update didn’t install', 'The package installer said no.', 'update'),
        ], states={'drive': 'expired', 'calendar': 'notSignedIn', 'cameras': 'unreachable'}, doorbell=THROUGH,
            updates=('ready', '2.0.172 ready', False), last_try={'text': 'Didn’t install', 'reason': 'The package installer said no.'},
            crash_at=now - 12 * 24 * HOUR)
        r['via'] = 'Living Room'
        return 200, r
    if tv_id == 'c':
        last = health_report('Kitchen', now - 2 * HOUR, doorbell=THROUGH)
        return 409, {'status': 'off', 'message': 'Off or asleep. These are the last things it reported.', 'version': '2.0.171',
                     'seen': now - 2 * HOUR, 'last': last, 'lastAt': now - 2 * HOUR}
    if tv_id == 'd':
        if TV_STATE['relinked']:
            r = health_report('Bedroom', now, doorbell=THROUGH)
            r['via'] = 'Living Room'
            return 200, r
        last = health_report('Bedroom', now - 26 * HOUR, doorbell=THROUGH)
        return 409, {'status': 'refused', 'message': '', 'version': '2.0.171', 'seen': now - 26 * HOUR, 'last': last, 'lastAt': now - 26 * HOUR}
    return 400, 'Unknown TV'

# The phone's Smoothness Report (Settings › TVs): what a TV sends after a 3-minute recording, made by the
# app's own report code from realistic numbers. {recording} is where it stands: see diagnostics().
SMOOTHNESS = """Woodruff Family · smoothness report
Made Oct 29, 2:05 PM
App 2.0.143 (build 143) · up 38 min
Sony BRAVIA 4K VH2 (BRAVIA_VH2)
Android 12 (API 31)
CPU: 4 cores, 4 × 1.80 GHz · MediaTek MT5895
RAM: 2.8 GB, 1.1 GB free · low-RAM: no
Java heap: 61 MB used of 256 MB
Display: 3840×2160 @ 60 Hz (app draws 1920×1080)
 also: 3840×2160 @ 59.94, 50, 30, 25, 24, 23.98 Hz
       1920×1080 @ 60, 50 Hz
Animation scales: window 1×, transition 1×, animator 1×
Graphics: ARM · repaints only what changed
App code: compiled for speed

{recording}
Navigating: drawn within 1 s of a remote key.
Idle: the rest (video, animations alone).
Late: past its deadline (set by Android).
Slow: over 25 ms. Stutter: over 50 ms.
Late frames, average ms in each phase:
 wait   waiting to start (main thread busy)
 input  input batched into the frame
 anim   animation and composition
 layout measure and layout
 draw   recording the drawing
 sync   uploading pictures
 gfx    issuing commands, swapping buffers
 RT     queued behind the render thread
 GPU    the graphics chip's own time

== Home ==
Navigating: 2,140 frames
 late 28% · slow 18% · 45 stutters
 ms p50 13 · p90 33 · p99 85 · max 106
 late: wait 12.8 · input 0.0 · anim 9.6
  layout 1.6 · draw 3.2 · sync 1.1
  gfx 2.1 · RT 1.6 · GPU 8.0
Idle: 4,410 frames
 late 2% · slow 1% · no stutters
 ms p50 9 · p90 13 · p99 29 · max 44
 late: wait 2.9 · input 0.0 · anim 4.8
  layout 0.0 · draw 3.6 · sync 5.8
  gfx 4.8 · RT 7.0 · GPU 9.7

== Movies ==
Navigating: 1,630 frames
 late 27% · slow 16% · 24 stutters
 ms p50 13 · p90 31 · p99 67 · max 100
 late: wait 12.1 · input 0.0 · anim 9.1
  layout 1.5 · draw 3.0 · sync 1.0
  gfx 2.0 · RT 1.5 · GPU 7.6
Idle: 220 frames
 late 0% · slow 0% · no stutters
 ms p50 9 · p90 13 · p99 13 · max 29
 late: wait 2.9 · input 0.0 · anim 4.8
  layout 0.0 · draw 3.6 · sync 5.8
  gfx 4.8 · RT 7.0 · GPU 9.7

== Settings ==
Navigating: 610 frames
 late 16% · slow 10% · 12 stutters
 ms p50 12 · p90 26 · p99 82 · max 105
 late: wait 2.9 · input 0.0 · anim 10.3
  layout 1.7 · draw 3.4 · sync 1.1
  gfx 2.3 · RT 12.6 · GPU 8.6
Idle: 40 frames
 late 0% · slow 0% · no stutters
 ms p50 9 · p90 12 · p99 13 · max 13
 late: none

== Command_center ==
Navigating: 320 frames
 late 5% · slow 3% · 2 stutters
 ms p50 10 · p90 15 · p99 35 · max 67
 late: wait 2.7 · input 0.0 · anim 9.7
  layout 1.6 · draw 3.2 · sync 1.1
  gfx 2.1 · RT 11.8 · GPU 8.1
Idle: 5,100 frames
 late 2% · slow 1% · no stutters
 ms p50 9 · p90 13 · p99 32 · max 44
 late: wait 3.1 · input 0.0 · anim 5.1
  layout 0.0 · draw 3.8 · sync 6.1
  gfx 5.1 · RT 7.4 · GPU 10.2

== Remote keys ==
213 presses (and held repeats) handled
 ms p50 5 · p90 37 · p99 98 · max 107
 over 16 ms: 42 · over 50 ms: 7
 (A key's time shows as "wait" in the next frame.)

== Hot spots ==
Main thread busy over 12 ms, sampled every
5 ms: 168 samples ≈ 840 ms.
By Woodruff code (with the 2 calls above it):
1. 58 samples ≈ 290 ms
   AndroidParagraph.<init>:140
   ParagraphLayoutCache.layoutText:381
   LibraryRowsKt.PosterCard:412
2. 41 samples ≈ 205 ms
   ComposerImpl.startGroup (Composer.kt:1500)
   ComposerImpl.startRestartGroup (Composer.kt:2210)
   HomeScreenKt.CameraTile:733
3. 33 samples ≈ 165 ms
   BitmapFactory.decodeStreamInternal:790
   BitmapFactory.decodeStream:765
   BackdropKt.loadBackdrop:96
4. 22 samples ≈ 110 ms
   FocusOwnerImpl.moveFocus:302
   AndroidComposeView.dispatchKeyEvent:1210
   MainActivity.dispatchKeyEvent:122
5. 14 samples ≈ 70 ms
   (no Woodruff code on the stack)
By top of stack:
1. 58 samples ≈ 290 ms
   TextLayout.<init>:238
   AndroidParagraph.<init>:140
   ParagraphLayoutCache.layoutText:381
2. 41 samples ≈ 205 ms
   ComposerImpl.startGroup (Composer.kt:1500)
   ComposerImpl.startRestartGroup (Composer.kt:2210)
   HomeScreenKt.CameraTile:733
3. 33 samples ≈ 165 ms
   BitmapFactory.nativeDecodeStream (native)
   BitmapFactory.decodeStreamInternal:790
   BitmapFactory.decodeStream:765
4. 22 samples ≈ 110 ms
   FocusOwnerImpl.moveFocus:302
   AndroidComposeView.dispatchKeyEvent:1210
   MainActivity.dispatchKeyEvent:122
5. 14 samples ≈ 70 ms
   VMRuntime.trackExternalAllocation (native)
   Bitmap.nativeCreate (native)
   Bitmap.createBitmap:1113

== Long main-thread work (over 12 ms) ==
1. 41 times, 1,394 ms, longest 54 ms
   Choreographer$FrameHandler: Choreographer$FrameDisplayEventReceiver
2. 12 times, 420 ms, longest 57 ms
   Remote key (MainActivity.dispatchKeyEvent)
3. 5 times, 95 ms, longest 25 ms
   Handler: AndroidUiDispatcher$dispatchCallback$1
4. 3 times, 87 ms, longest 38 ms
   Handler: DispatchedContinuation[Dispatchers.Main, Continuation at HomeScreenKt$HomeScreen$2$1.invokeSuspend(HomeScreen.kt:214)]

== Garbage collection (during the recording) ==
14 collections, 212 ms
2 blocking, 61 ms
"""
RECORD_S = 180
DIAG = {'started': 0.0}


def diagnostics():
    """The report as it stands, and the seconds the mock recording still runs (0 when none is)."""
    left, now = 0, time.time()
    if DIAG['started']:
        ran = now - DIAG['started']
        left = max(0, math.ceil(RECORD_S - ran))  # rounded up, like the TV's countdown
    if left:
        r = int(ran)
        rec = 'Recording: running, %d:%02d left\nFrames below: since it started (%d:%02d).' % (left // 60, left % 60, r // 60, r % 60)
    else:
        ago = now - DIAG['started'] - RECORD_S if DIAG['started'] else 6 * 60 + 12
        rec = 'Recording: finished %s ago (3:00 long)\nFrames below: during that recording.' % (
            'under a minute' if ago < 60 else '%d min' % (ago // 60))
    return SMOOTHNESS.replace('{recording}', rec), left



# ---- Watch: the family's Movies and TV Shows -------------------------------------------------------------------------
WATCH = {'library': 'ready', 'started': True}
MIN_MS = 60_000


def _poster_url(tid, kind='poster', extra=''):
    return '/api/poster?id=%s&kind=%s%s&v=1' % (tid, kind, extra)


def _movie(tid, title, year, genres, rating, poster=True, backdrop=False, added=0, overview=None, runtime=None, age=None, cast=None):
    return {'id': tid, 'kind': 'movie', 'title': title, 'year': year, 'genres': genres, 'rating': rating, 'poster': poster,
            'backdrop': backdrop, 'added': added, 'overview': overview, 'runtime': runtime, 'ageRating': age, 'cast': cast or []}


_NOW0 = int(time.time() * 1000)
MOVIES = [
    _movie('m-ironwood', 'Ironwood', 2025, ['Drama'], 7.4, backdrop=True, added=_NOW0 - 2 * DAY, runtime=128, age='PG-13',
           overview='A forester and her estranged son spend one winter keeping an old mill turning, and learn what each of them gave up.',
           cast=['A. Rivers', 'B. Lane', 'C. Moss', 'D. Hale']),
    _movie('m-lastlight', 'The Last Light', 2023, ['Drama'], 7.1, backdrop=True, added=_NOW0 - 40 * DAY, runtime=101, age='PG',
           overview='The keeper of a lighthouse on its last season writes down everything the ships ever told him.', cast=['E. Stone', 'F. Ward']),
    _movie('m-harbor', 'Midnight Harbor', 2024, ['Thriller', 'Drama'], 7.8, backdrop=True, added=_NOW0 - 6 * DAY, runtime=114, age='PG-13',
           overview='A harbour town keeps a secret that the tide brings back every spring. When a ferry captain finds a letter addressed to her '
                    'mother, she starts asking questions the whole town has agreed never to answer, and the night shift at the harbour turns into '
                    'a search for what really happened on the last boat out.', cast=['G. Pierce', 'H. Ortiz', 'I. Chen', 'J. Bauer']),
    _movie('m-neon', 'Neon Alley', 2024, ['Crime'], 6.9, backdrop=True, added=_NOW0 - 12 * DAY, runtime=97, age='R', overview='Two cousins run a noodle bar that the whole block owes money to.'),
    _movie('m-orbit', 'Orbit', 2021, ['Sci-Fi'], 7.6, backdrop=True, added=_NOW0 - 90 * DAY, runtime=126, age='PG-13', overview='The last crew of a weather station above the clouds has one week to come home.'),
    _movie('m-paper', 'Paper Planes', 2022, ['Family'], 7.0, added=_NOW0 - 30 * DAY, runtime=96, age='G', overview='A girl and her grandfather build paper planes for the county fair.'),
    _movie('m-cabin', 'Summer at the Cabin', 2020, ['Comedy'], 6.6, poster=False, added=_NOW0 - 200 * DAY, runtime=92, age='PG', overview='Three families, one cabin, no signal.'),
]
_PLACES = ['Copper', 'North', 'Silver', 'Cedar', 'Willow', 'Granite', 'Harbor', 'Maple', 'Stone', 'Amber', 'Pine', 'River', 'Juniper', 'Coral',
           'Iron', 'Meadow', 'Raven', 'Linden', 'Ember', 'Frost']
_WEATHER = ['Valley', 'Star', 'Rain', 'Wind', 'Fog', 'Sky', 'Storm', 'Snow', 'Dawn', 'Tide', 'Thunder', 'Bay', 'Hollow', 'Ridge', 'Sun',
            'Field', 'Moon', 'Creek', 'Cloud']
_GENRES = ['Drama', 'Comedy', 'Thriller', 'Family', 'Sci-Fi', 'Crime', 'Adventure', 'Documentary']
for _i in range(57):
    _t = '%s %s' % (_PLACES[_i % len(_PLACES)], _WEATHER[(_i * 7) % len(_WEATHER)])
    MOVIES.append(_movie('m-g%02d' % _i, _t, 1990 + (_i * 13) % 35, [_GENRES[_i % len(_GENRES)]], round(5.2 + (_i * 37 % 34) / 10, 1),
                         added=_NOW0 - (50 + _i * 3) * DAY, runtime=85 + (_i * 11) % 60, overview='A story about %s.' % _t.lower()))

SHOW_EPISODES = {
    'Backyard Science': [
        ('Season 1', [('Tide Pools', 'The kids follow a question from the back garden to the edge of the sea, and find an answer they did not expect.'),
                      ('Ants', 'One ant hill, four magnifying glasses and a picnic nobody gets to eat.'),
                      ('Clouds', 'Why some clouds rain and others just sit there all afternoon.'),
                      ('Roots', 'What happens under the lawn when nobody is looking.'),
                      ('Echoes', 'Shouting into the old well turns into a lesson about sound.'),
                      ('Stars', 'A night on the roof with a borrowed telescope.')]),
        ('Season 2', [('Magnets', 'The fridge door becomes a laboratory.'), ('Ice', 'How long a block of ice lasts in the sun, measured every hour.'),
                      ('Wind', 'Kites, paper and the neighbour’s washing line.'),
                      ('The Volcano', 'A trip to the island turns into a lesson about what is under our feet, and the big eruption goes sideways.'),
                      ('Rainbows', 'Garden hoses at sunset, and what a prism does.'), ('Seeds', 'The last day of summer, and what to plant next.')]),
    ],
    'Detective Rowe': [('Season 1', [('The Quiet Street', None), ('Two Keys', None), ('The Ferry', None), ('Lost Property', None),
                                     ('The Allotment', None), ('Paper Trail', None), ('Low Tide', None), ('The Last Case', None)])],
    'Kitchen Rules': [('Season %d' % n, [('Week %d' % e, 'Four kitchens, one judge, and a dish nobody has made before.') for e in range(1, 5)]) for n in range(1, 5)],
    'The Lighthouse': [('Episodes', [('Arrival', 'A new keeper arrives with one suitcase and no idea how to cook.'), ('Fog', 'Three days without the horizon.'),
                                     ('The Supply Boat', 'Late again, and carrying the wrong letters.')])],
}
SHOWS = [
    dict(_movie('s-backyard', 'Backyard Science', 2023, ['Family', 'Documentary'], 8.1, backdrop=True, added=_NOW0 - 300 * DAY, age='TV-G',
                overview='Two curious kids and their grandad turn the back garden into a laboratory, one question at a time: why the sky goes orange, '
                         'where the rain goes, and what the ants are doing all day.', cast=['A. Rivers', 'B. Lane', 'C. Moss']), kind='show'),
    dict(_movie('s-rowe', 'Detective Rowe', 2019, ['Crime', 'Drama'], 7.3, poster=False, added=_NOW0 - 400 * DAY, age='TV-14',
                overview='A retired detective takes one last case in the town where she grew up.'), kind='show'),
    dict(_movie('s-kitchen', 'Kitchen Rules', 2021, ['Reality'], 6.8, added=_NOW0 - 1 * DAY, age='TV-PG', overview='Home cooks, one kitchen, and a judge who has seen everything.'),
         kind='show', caption='New · S4 E2'),
    dict(_movie('s-lighthouse', 'The Lighthouse', 2022, ['Drama'], 7.0, added=_NOW0 - 500 * DAY, age='TV-PG', overview='A year on a lighthouse, told one storm at a time.'), kind='show'),
]
# What's been watched, and where (the TV's Continue watching and ✓s).
PROGRESS = {}   # media key -> (position, duration, upNext)
WATCHED = set()


def _reset_watch():
    WATCH.clear(); WATCH.update({'library': 'ready', 'started': True})
    PROGRESS.clear(); WATCHED.clear()
    PROGRESS.update({
        'drive:m-harbor': (2_710_000, 6_840_000, False),                       # Midnight Harbor: 45:10 of 1:54:00
        'drive:ep-s-backyard-2-4': (731_000, 1_440_000, False),                # Backyard Science S2 E4: 12:11 of 24:00
        'drive:ep-s-kitchen-3-1': (0, 0, True),                                # Kitchen Rules: up next S3 E1
        'drive:m-orbit': (2_040_000, 7_560_000, False),                        # Orbit: 34 minutes in
    })
    WATCHED.update({'drive:m-paper'} | {'drive:ep-s-backyard-1-%d' % e for e in range(1, 7)} | {'drive:ep-s-backyard-2-%d' % e for e in range(1, 4)}
                   | {'drive:ep-s-kitchen-%d-%d' % (n, e) for n in (1, 2) for e in range(1, 5)})
    CONTINUE[:] = ['drive:m-harbor', 'drive:ep-s-backyard-2-4', 'drive:ep-s-kitchen-3-1', 'drive:m-orbit']


CONTINUE = []
_reset_watch()


def _all_titles():
    return MOVIES + SHOWS


def _is_show(t):
    return t['kind'] == 'show'


def _season_list(show):
    eps = SHOW_EPISODES[show['title']]
    return [{'id': 'inside' if name == 'Episodes' else 'f-%s-%d' % (show['id'], n), 'name': name} for n, (name, _) in enumerate(eps, 1)]


def _episode_key(show, season, number):
    return 'drive:ep-%s-%d-%d' % (show['id'], season, number)


def _play(key, title, position, label, duration):
    return {'key': key, 'title': title, 'position': position, 'label': label, 'duration': duration}


def _film_play(m):
    key = 'drive:' + m['id']
    pos, dur, _ = PROGRESS.get(key, (0, (m['runtime'] or 100) * MIN_MS, False))
    name = '%s (%d).mkv' % (m['title'], m['year'])
    label = ('Resume from ' + _dur(pos)) if pos > MIN_MS else 'Play'
    return _play(key, name, pos, label, dur), (_play(key, name, 0, 'Start Over', dur) if pos > MIN_MS else None)


def _dur(ms):
    s = ms // 1000
    h, m = s // 3600, (s % 3600) // 60
    return '%dh %02dm' % (h, m) if h else ('%dm' % m if m else '%ds' % s)


def _ep_file(show, season, number, name):
    code = 'S%02dE%02d' % (season, number)
    return '%s %s %s.mkv' % (show['title'], code, name) if name else '%s %s.mkv' % (show['title'], code)


def _show_play(show):
    """(play, restart, season index to open, the episode's name line)."""
    seasons = SHOW_EPISODES[show['title']]
    for key in CONTINUE:
        for n, (_, eps) in enumerate(seasons, 1):
            for e, (name, _) in enumerate(eps, 1):
                if _episode_key(show, n, e) == key:
                    pos, dur, nxt = PROGRESS[key]
                    code = 'S%d E%d' % (n, e)
                    play = _play(key, _ep_file(show, n, e, name), 0 if nxt else pos, ('Play ' if nxt else 'Resume ') + code, dur)
                    first = _play(_episode_key(show, 1, 1), _ep_file(show, 1, 1, seasons[0][1][0][0]), 0, 'Play from the Start', 0)
                    return play, first, n - 1, code + ' · ' + name
    first_name = seasons[0][1][0][0]
    return _play(_episode_key(show, 1, 1), _ep_file(show, 1, 1, first_name), 0, 'Play S1 E1', 0), None, 0, 'S1 E1 · ' + first_name


def _title(t, with_play=True):
    show = _is_show(t)
    play = None
    if with_play:
        play = (_show_play(t)[0] if any(k.startswith('drive:ep-%s-' % t['id']) for k in CONTINUE) else None) if show else _film_play(t)[0]
    return {'id': t['id'], 'kind': t['kind'], 'title': t['title'], 'year': t['year'],
            'poster': _poster_url(t['id']) if t['poster'] else None,
            'backdrop': _poster_url(t['id'], 'backdrop') if t['backdrop'] else None,
            'genres': t['genres'], 'rating': t['rating'], 'watched': ('drive:' + t['id']) in WATCHED,
            'caption': t.get('caption'), 'added': t['added'], 'play': play}


def _continue_item(key):
    pos, dur, nxt = PROGRESS[key]
    if key.startswith('drive:ep-'):
        sid, season, number = key[len('drive:ep-'):].rsplit('-', 2)
        show = next(s for s in SHOWS if s['id'] == sid)
        name = SHOW_EPISODES[show['title']][int(season) - 1][1][int(number) - 1][0]
        code = 'S%s E%s' % (season, number)
        play, restart, _, _ = _show_play(show)
        return {'key': key, 'playTitle': _ep_file(show, int(season), int(number), name), 'titleId': sid, 'kind': 'show', 'name': show['title'],
                'code': code, 'episode': name, 'position': pos, 'duration': dur, 'upNext': nxt,
                'poster': _poster_url(sid) if show['poster'] else None, 'drive': True, 'play': play, 'restart': restart, 'where': None}
    m = next(x for x in MOVIES if 'drive:' + x['id'] == key)
    play, restart = _film_play(m)
    return {'key': key, 'playTitle': '%s (%d).mkv' % (m['title'], m['year']), 'titleId': m['id'], 'kind': 'movie', 'name': m['title'], 'code': None,
            'episode': None, 'position': pos, 'duration': dur, 'upNext': nxt, 'poster': _poster_url(m['id']) if m['poster'] else None,
            'drive': True, 'play': play, 'restart': restart, 'where': None}


def _filled():
    return WATCH['library'] in ('ready', 'offline', 'unreachable', 'expired', 'keysRejected')


def watch_home():
    state = WATCH['library']
    if not _filled():
        return {'state': state, 'tv': 'Living Room', 'updated': None, 'featured': [], 'continue': [], 'recent': [],
                'movies': {'count': 0, 'items': []}, 'shows': {'count': 0, 'items': []}}
    featured = [t for t in _all_titles() if t['backdrop'] and t['rating'] >= 6.5]
    featured.sort(key=lambda t: (t['id'] != 'm-harbor', t['title']))
    recent = sorted(_all_titles(), key=lambda t: -t['added'])[:20]
    return {'state': state, 'tv': 'Living Room', 'updated': _now() - 5 * MIN,
            'featured': [_title(t) for t in featured[:5]],
            'continue': [_continue_item(k) for k in CONTINUE],
            'recent': [_title(t) for t in recent],
            'movies': {'count': len(MOVIES), 'items': [_title(t) for t in _az(MOVIES)[:20]]},
            'shows': {'count': len(SHOWS), 'items': [_title(t) for t in _az(SHOWS)[:20]]}}


def _sort_key(title):
    t = title.lower()
    for a in ('the ', 'a ', 'an '):
        if t.startswith(a):
            return t[len(a):]
    return t


def _az(items):
    return sorted(items, key=lambda t: _sort_key(t['title']))


def _matches(t, words):
    text = t['title'].lower()
    return all(w in text or (len(w) == 4 and w.isdigit() and str(t['year']) == w) for w in words)


def _rank(t, q):
    q = _sort_key(q.strip().lower())
    title = t['title'].lower()
    return 0 if _sort_key(title).startswith(q) else 1 if (' ' + title).find(' ' + q) >= 0 else 2


def library_page(query):
    q = lambda k, d='': query.get(k, [d])[0]
    items = SHOWS if q('place') == 'shows' else MOVIES
    words = [w for w in q('q').lower().split() if w]
    out = [t for t in items if (not words or _matches(t, words))]
    if q('genre'):
        out = [t for t in out if q('genre') in t['genres']]
    if q('unwatched') == '1':
        out = [t for t in out if ('drive:' + t['id']) not in WATCHED and not (_is_show(t) and any(k.startswith('drive:ep-%s-' % t['id']) for k in list(PROGRESS) + list(WATCHED)))]
    sort = q('sort', 'az')
    if sort == 'recent':
        out.sort(key=lambda t: -t['added'])
    elif sort == 'newest':
        out.sort(key=lambda t: (-(t['year'] or 0), _sort_key(t['title'])))
    elif sort == 'rated':
        out.sort(key=lambda t: (-(t['rating'] or -1), _sort_key(t['title'])))
    else:
        out = _az(out)
    offset, limit = int(q('offset', '0')), min(120, int(q('limit', '60')))
    genres = sorted({g for t in items for g in t['genres']})
    return {'state': WATCH['library'], 'count': len(out), 'offset': offset, 'items': [_title(t) for t in out[offset:offset + limit]],
            'genres': genres, 'rated': True, 'capped': None, 'updated': _now() - 5 * MIN}


def library_search(query):
    q = query.get('q', [''])[0]
    words = [w for w in q.lower().split() if w]

    def ranked(items):
        hits = [t for t in items if words and _matches(t, words)]
        return sorted(hits, key=lambda t: (_rank(t, q), -(t['rating'] or -1), _sort_key(t['title'])))
    movies, shows = ranked(MOVIES), ranked(SHOWS)
    first = 'shows' if shows and (not movies or (_rank(shows[0], q), -(shows[0]['rating'] or -1)) < (_rank(movies[0], q), -(movies[0]['rating'] or -1))) else 'movies'
    return {'state': WATCH['library'], 'q': q, 'first': first, 'movies': [_title(t) for t in movies[:100]], 'shows': [_title(t) for t in shows[:100]],
            'capped': {'movies': len(movies) > 100, 'shows': len(shows) > 100}}


def library_title(tid):
    t = next((x for x in _all_titles() if x['id'] == tid), None)
    if t is None:
        return 400, 'That title isn’t in Movies or TV Shows any more.'
    o = _title(t, with_play=False)
    o.update({'overview': t['overview'], 'runtime': None if _is_show(t) else t['runtime'], 'ageRating': t['ageRating'], 'cast': t['cast'][:4],
              'seasons': [], 'season': 0, 'films': None, 'play': None, 'restart': None})
    if _is_show(t):
        play, restart, season, name = _show_play(t)
        o.update({'seasons': _season_list(t), 'season': season, 'play': play, 'restart': restart, 'playName': name, 'playMinutes': 24})
    else:
        play, restart = _film_play(t)
        o.update({'play': play, 'restart': restart, 'playMinutes': t['runtime'], 'watchKey': 'drive:' + t['id']})
    return 200, o


def library_episodes(sid, season_id):
    show = next((x for x in SHOWS if x['id'] == sid), None)
    if show is None:
        return 400, 'That show isn’t in TV Shows any more.'
    seasons = SHOW_EPISODES[show['title']]
    ids = [s['id'] for s in _season_list(show)]
    if season_id not in ids:
        return 200, {'state': 'ready', 'episodes': []}
    n = ids.index(season_id) + 1
    out = []
    for e, (name, synopsis) in enumerate(seasons[n - 1][1], 1):
        key = _episode_key(show, n, e)
        pos, dur, nxt = PROGRESS.get(key, (0, 0, False))
        out.append({'key': key, 'playTitle': _ep_file(show, n, e, name), 'code': 'S%d E%d' % (n, e), 'number': e, 'special': False, 'name': name,
                    'overview': synopsis, 'still': _poster_url(key[len('drive:'):], 'still', '&in=' + season_id), 'runtime': 24 if show['title'] != 'Detective Rowe' else 52,
                    'position': 0 if nxt else pos, 'duration': dur, 'watched': key in WATCHED, 'upNext': nxt,
                    'new': show['id'] == 's-kitchen' and n == 4 and e == 2})
    return 200, {'state': 'ready', 'episodes': out}


def art_svg(tid, kind):
    """A sample picture: the TV gallery's sample posters' look (a gradient, a sun, a hill, the title in bold capitals)."""
    t = next((x for x in _all_titles() if x['id'] == tid), None)
    i = sum(map(ord, tid))
    a, b, c, d = PALETTES[i % len(PALETTES)]
    if kind == 'still':
        w, h, title, year = 300, 169, '', ''
    elif kind == 'backdrop':
        w, h = 1280, 720
        title, year = '', ''
    else:
        w, h = 342, 513
        title, year = (t['title'].upper(), str(t['year'] or '')) if t else ('', '')
    words = title.split()
    lines, cur = [], ''
    for wd in words:
        if len(cur) + len(wd) + 1 > 11 and cur:
            lines.append(cur); cur = wd
        else:
            cur = (cur + ' ' + wd).strip()
    if cur:
        lines.append(cur)
    text = ''.join('<text x="%d" y="%d" font-family="Helvetica, Arial, sans-serif" font-size="38" font-weight="800" fill="#fff" '
                   'text-anchor="middle" letter-spacing="1">%s</text>' % (w / 2, h * 0.62 + k * 44, ln.replace('&', '&amp;')) for k, ln in enumerate(lines[:3]))
    if year:
        text += '<text x="%d" y="%d" font-family="Helvetica, Arial, sans-serif" font-size="20" fill="#fff" fill-opacity=".8" text-anchor="middle">%s</text>' % (
            w / 2, h * 0.62 + len(lines[:3]) * 44 + 8, year)
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d">'
            '<defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient></defs>'
            '<rect width="%d" height="%d" fill="url(#g)"/><circle cx="%.0f" cy="%.0f" r="%.0f" fill="#fff" fill-opacity=".28"/>'
            '<path d="M0 %.0f Q %.0f %.0f %.0f %.0f T %d %.0f V %d H 0 Z" fill="%s" fill-opacity=".55"/>%s</svg>') % (
        w, h, w, h, c, d, w, h, w * (0.35 + (i % 30) / 100), h * 0.3, min(w, h) * 0.2,
        h * 0.72, w * 0.25, h * 0.62, w * 0.5, h * 0.74, w, h * 0.7, h, a, text)


KEYS = {'up', 'down', 'left', 'right', 'ok', 'back', 'home', 'playpause', 'mute', 'volup', 'voldown', 'netflix', 'youtube'}
KEYS.add('menu')   # holding OK on the phone's remote: the TV's menu for what's focused
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
        if path.startswith('/api/') and path not in ('/api/screen', '/api/tvs/screen', '/api/type', '/api/inbox', '/api/tvs', '/api/poster'):
            log('GET', self.path, b'')
        if path == '/':
            # Read on every request, so an edited page shows up without restarting the server.
            page = (open(os.path.join(WEB, 'index.html'), encoding='utf-8').read().replace('__TVX_KEY__', 'gallery')
                    .replace('__TVX_HOME_NAME__', 'Living Room').replace('__TVX_HOME_TAG__', 'gallery'))
            return self.send(page, 'text/html; charset=utf-8')
        ext = os.path.splitext(path)[1]
        if ext in TYPES and '/' not in path[1:]:
            return self.send(open(os.path.join(WEB, path[1:]), 'rb').read(), TYPES[ext])
        if path == '/api/screen':
            return self.screen()
        if path == '/api/tvs/screen':
            tv = query.get('id', [''])[0]
            if tv in ('c', 'd'):
                return self.send({'message': "%s isn't showing Woodruff Family, or is off." % ('Kitchen' if tv == 'c' else 'Bedroom')}, status=409)
            return self.screen()
        if path == '/api/poster':
            return self.send(art_svg(query.get('id', [''])[0], query.get('kind', ['poster'])[0]), 'image/svg+xml',
                             headers={'Cache-Control': 'private, max-age=604800'})
        if path.startswith('/api/watch/') or path.startswith('/api/library'):
            if WATCH['library'] == 'down':
                return self.send('', 'text/plain', 503)
        if path == '/api/watch/home':
            body = json.dumps(watch_home())
            tag = '"%x"' % (hash(body) & 0xffffffffffff)
            if self.headers.get('if-none-match') == tag:
                self.send_response(304); self.send_header('ETag', tag); self.end_headers(); return
            return self.send(body, headers={'ETag': tag, 'Cache-Control': 'no-cache'})
        if path == '/api/library':
            return self.send(library_page(query))
        if path == '/api/library/search':
            return self.send(library_search(query))
        if path == '/api/library/title':
            status, body = library_title(query.get('id', [''])[0])
            return self.send(body, 'application/json' if isinstance(body, dict) else 'text/plain', status)
        if path == '/api/library/episodes':
            status, body = library_episodes(query.get('show', [''])[0], query.get('season', [''])[0])
            return self.send(body, 'application/json' if isinstance(body, dict) else 'text/plain', status)
        if path == '/api/tvs/health':
            status, body = tv_health(query.get('id', [''])[0])
            return self.send(body, 'application/json' if isinstance(body, dict) else 'text/plain', status)
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
        if path == '/api/diagnostics':
            report, left = diagnostics()
            return self.send(report, 'text/plain; charset=utf-8', headers={'X-Recording-Left': str(left), 'Cache-Control': 'no-store'})
        if path == '/api/inbox':
            messages = INBOX[:]
            del INBOX[:]
            return self.send({'messages': messages})
        if path == '/__mock/google':
            return self.send('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">'
                             '<title>Google</title><body style="font:17px system-ui;padding:24px">Google sign-in (sample)</body>',
                             'text/html; charset=utf-8')
        if path == '/api/signin/status':
            return self.send(signin_status(query.get('grant', ['drive'])[0]))
        if path in ('/api/memories/thumb', '/api/memories/photo'):
            where = query.get('path', [''])[0]
            if where in ADDED:
                return self.send(ADDED[where][0], ADDED[where][1], headers={'Cache-Control': 'private, max-age=31536000'})
            i = next((n for n, m in enumerate(MEMORIES) if m['path'] == where), None)
            if i is None:
                return self.send("That isn't in Family Memories", 'text/plain', 400)
            big, tall = path.endswith('photo'), i % 3 == 2
            w, h = (1200, 1600) if big and tall else (1600, 1200) if big else (315, 420) if tall else (420, 315)
            return self.send(landscape(i, w, h), 'image/svg+xml', headers={'Cache-Control': 'private, max-age=31536000'})
        if path == '/__mock/memories':
            return self.send(MEM_LOG)
        api = {
            '/api/roots': ROOTS,
            '/api/type': FIELD,
            '/api/memories': memories_json(),
            '/api/phones': {'phones': PHONES},
            '/api/settings': settings_json(),
            '/api/tvs': tvs_json(),
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
        upload = path in ('/api/upload', '/api/memories/add')
        if not path.startswith('/__mock/'):
            log('POST', self.path, raw if not upload else b'x' * len(raw))
        try:
            body = json.loads(raw) if raw and not upload else {}
        except ValueError:
            body = {}
        if path == '/__mock/inbox':
            msg = {'title': body.get('title', 'Dinner\'s ready'), 'body': body.get('body', ''), 'from': body.get('from', "Mom's iPhone")}
            msg.update({k: body[k] for k in ('id', 'link') if k in body})
            INBOX.append(msg)
            return self.send({'ok': True})
        if path == '/__mock/crash':
            SETTINGS['crash'] = body.get('text', '')
            return self.send({'ok': True})
        if path == '/__mock/field':
            FIELD['field'] = body.get('field')
            return self.send({'ok': True})
        if path == '/__mock/reset':
            SETTINGS.clear(); SETTINGS.update(json.loads(json.dumps(SETTINGS_START)))
            SIGNIN.update({'mode': None, 'grant': 'drive', 'tv': 'Living Room'})
            THIS_SIGNINS.clear()
            TVS_SET['tvs'] = None
            TV_STATE['relinked'] = False
            TV_STATE['playing'] = {'this': True, 'b': True}
            _reset_watch()
            return self.send({'ok': True})
        if path == '/__mock/set':
            if 'library' in body:
                WATCH['library'] = body['library']
            if 'watch' in body:
                WATCH.update(body['watch'])
            for k, v in (body.get('settings') or {}).items():   # one level deep: {google: {state}} keeps the rest of google
                if isinstance(v, dict) and isinstance(SETTINGS.get(k), dict):
                    SETTINGS[k].update(v)
                else:
                    SETTINGS[k] = v
            if 'signin' in body:
                SIGNIN['mode'] = body['signin']
            if 'signinTv' in body:   # the TV's name in the sheet (a long one, for tests)
                SIGNIN['tv'] = body['signinTv']
            if 'thisSignIns' in body:
                THIS_SIGNINS.clear(); THIS_SIGNINS.update(body['thisSignIns'])
            if 'tvs' in body:
                TVS_SET['tvs'] = body['tvs']
            if 'phones' in body:
                PHONES[:] = body['phones']
            return self.send({'ok': True})
        if path in ('/api/key', '/api/tvs/key'):
            if body.get('key') not in KEYS:
                return self.send('Unknown key', 'text/plain', 400)
            if path == '/api/tvs/key' and body.get('id') == 'b' and body.get('key') == 'netflix':
                return self.send("Den: Netflix isn't installed on this TV", 'text/plain', 400)
            if body.get('key') == 'playpause':
                tv = 'this' if path == '/api/key' else body.get('id')
                if tv in TV_STATE['playing']:
                    TV_STATE['playing'][tv] = not TV_STATE['playing'][tv]
            return self.send({'ok': True})
        if path in ('/api/media', '/api/tvs/media'):
            tv = 'this' if path == '/api/media' else body.get('id')
            if tv not in TV_STATE['playing']:
                return self.send('Nothing is playing on that TV', 'text/plain', 400)
            if body.get('action') == 'playpause':
                TV_STATE['playing'][tv] = not TV_STATE['playing'][tv]
            return self.send({'ok': True, 'playing': TV_STATE['playing'][tv]})
        if path in ('/api/watch', '/api/play'):
            if WATCH['started']:
                return self.send({'ok': True, 'started': True})
            return self.send({'ok': True, 'started': False, 'message': 'It’s waiting on the TV: open Woodruff Family there.'})
        if path == '/api/tvs/watch':
            if not str(body.get('key', '')).startswith('drive:'):
                return self.send('Only Google Drive videos can be watched together', 'text/plain', 400)
            if body.get('id') == 'all':
                return self.send({'ok': True, 'started': 3, 'of': 4, 'failed': ['Kitchen']})
            name = {'b': 'Den', 'c': 'Kitchen', 'd': 'Bedroom'}.get(body.get('id'))
            if name is None:
                return self.send('That TV isn’t linked any more.', 'text/plain', 400)
            if body.get('id') in ('c', 'd'):
                return self.send('%s didn’t respond' % name, 'text/plain', 400)
            return self.send({'ok': True, 'started': WATCH['started'], 'tv': name})
        if path == '/api/library/watched':
            keys = body.get('keys') or [body.get('key')]
            for k in keys:
                if body.get('watched', True):
                    WATCHED.add(k); PROGRESS.pop(k, None)
                    if k in CONTINUE:
                        CONTINUE.remove(k)
                else:
                    WATCHED.discard(k)
            return self.send({'ok': True})
        if path == '/api/continue/remove':
            k = body.get('key')
            if k in CONTINUE:
                CONTINUE.remove(k)
            return self.send({'ok': True})
        if path == '/api/tvs/update/check':
            return self.send({'ok': True})
        if path == '/api/signin':
            grant = body.get('grant')
            if grant not in ('drive', 'photos', 'calendar', 'cameras'):
                return self.send('Unknown sign-in', 'text/plain', 400)
            mode, cur = SIGNIN['mode'], settings_json()
            if mode == 'down':
                return self.send('', 'text/plain', 503)
            if mode == 'keys' or cur['google']['state'] == 'notSetUp':
                return self.send({'need': 'keys', 'message': 'Add the Google Client ID and secret first'}, status=400)
            if mode == 'project' or (grant == 'cameras' and not cur['nest']['project']):
                return self.send({'need': 'project', 'message': 'Add the cameras project first'}, status=400)
            SIGNIN['n'] += 1
            SIGNIN['grant'] = grant
            state = THIS_SIGNINS.get(grant) or (cur['google']['state'] if grant == 'drive' else cur['nest']['state'] if grant == 'cameras' else 'signedIn')
            return self.send({'state': 'mock-%d' % SIGNIN['n'], 'authUrl': 'http://%s/__mock/google' % self.headers.get('Host', '127.0.0.1:8765'),
                              'expiresAt': _now() + 600_000, 'again': state == 'expired', 'tv': SIGNIN['tv']})
        if path == '/api/tvs/relink':
            if body.get('id') != 'd':
                return self.send('That TV isn’t linked any more.', 'text/plain', 400)
            if ''.join(ch for ch in str(body.get('code', '')) if ch.isdigit()) != '123456':
                return self.send('That code didn’t work. Check the code on the other TV.', 'text/plain', 400)
            TV_STATE['relinked'] = True
            return self.send({'ok': True, 'name': 'Bedroom', 'id': 'd'})
        if path == '/api/diagnostics/record':
            DIAG['started'] = time.time()
            return self.send({'ok': True, 'seconds': RECORD_S})
        if path == '/api/phone/hello':
            phone = {'id': body.get('id', 'p-blake'), 'name': body.get('name', "Blake's iPhone"),
                     'alerts': body.get('alerts', True), 'topic': 'woodruff-example'}
            return self.send({'phone': phone, 'phones': PHONES})
        if path == '/api/memories/add':
            name = query.get('name', [''])[0].rsplit('/', 1)[-1]
            ext = os.path.splitext(name)[1].lower()
            if ext not in ('.jpg', '.jpeg', '.png', '.heic', '.gif', '.webp', '.mov', '.mp4', '.m4v'):
                return self.send('Only photos and videos go in Family Memories', 'text/plain', 400)
            video = ext in ('.mov', '.mp4', '.m4v')
            taken = query.get('taken', [None])[0]
            item = {'path': (VIDEOS if video else PHOTOS) + '/' + name, 'name': name, 'video': video,
                    'taken': int(taken) if taken else int(time.time() * 1000), 'size': len(raw), 'v': int(time.time() * 1000)}
            MEMORIES[:] = [m for m in MEMORIES if m['path'] != item['path']]
            MEMORIES.append(item)
            MEMORIES.sort(key=lambda m: (-m['taken'], m['name']))
            ADDED[item['path']] = (raw, 'image/jpeg' if ext in ('.jpg', '.jpeg') else 'image/svg+xml' if video else 'application/octet-stream')
            if video:
                ADDED[item['path']] = (landscape(len(MEM_LOG), 420, 315), 'image/svg+xml')
            MEM_LOG.append({'name': name, 'taken': int(taken) if taken else None, 'bytes': len(raw), 'jpeg': jpeg_size(raw)})
            return self.send({'item': item})
        if path == '/api/memories/delete':
            MEMORIES[:] = [m for m in MEMORIES if m['path'] != body.get('path')]
            return self.send(memories_json())
        if path == '/api/announce':
            return self.send({'ok': True, 'phones': len(body.get('phones') or []), 'tvs': 3 if body.get('tv', True) else 0})
        if path == '/api/settings/weather':
            SETTINGS['weather'] = body.get('place', '')
        elif path == '/api/settings/nest':
            for k in ('project', 'doorbell', 'person', 'motion'):
                if k in body:
                    SETTINGS['nest'][k] = body[k]
        elif path == '/api/settings/google':
            changed = body.get('client_id', '') != SETTINGS['google']['clientId'] or bool(body.get('client_secret'))
            SETTINGS['google']['clientId'] = body.get('client_id', '')
            if changed:   # new keys: signed out, as on the TV
                SETTINGS['google']['state'] = 'notSignedIn'
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
            return self.send(settings_json())  # like the TV: settings changes answer with the full settings
        if path == '/api/upload':
            return self.send({'ok': True, 'name': query.get('name', [''])[0]})
        self.send({'ok': True})


ThreadingHTTPServer(('127.0.0.1', int(os.environ.get('MOCK_PORT', '8765'))), Handler).serve_forever()
